package dev.higgs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sound.sampled.*;

/** PCM16 little-endian mono at 24 kHz. All device and network IO runs off the game thread. */
final class VoiceSession implements AutoCloseable, WebSocket.Listener {
    private final Consumer<String> status;
    private final VoiceCaptions captions = new VoiceCaptions();
    private final Consumer<VoiceCaptions.Line> chat;
    private final Runnable ended;
    private record Outbound(String json, int audioBytes) {}
    private final ArrayBlockingQueue<Outbound> outgoing = new ArrayBlockingQueue<>(64);
    private final net.minecraft.world.entity.Entity villager;
    private volatile SpatialVoice playback;
    /** The joining neighbour, if any. Its lines wait for the main villager to finish so the two never overlap. */
    private volatile net.minecraft.world.entity.Entity companion;
    private SpatialVoice companionPlayback;
    private final java.util.ArrayDeque<byte[]> companionPending = new java.util.ArrayDeque<>();
    private int companionPendingBytes;
    private long mainQuietSince = -1;
    private volatile long playbackGeneration;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean microphoneStarted = new AtomicBoolean();
    private final StringBuilder incoming = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean ready;
    private volatile TargetDataLine microphone;
    private volatile boolean muted = true;
    private volatile boolean speaking;
    private long microphoneGeneration;
    private volatile Thread capture;
    private long lastOverflowNotice;
    private volatile double micDbfs = -96;
    private volatile long lastMicFrame;
    private volatile long sentChunks, sentBytes, droppedChunks, staleChunks, receivedChunks, receivedBytes;
    private final RealtimeGate realtime = new RealtimeGate(200);
    private volatile String lastEvent = "connecting", lastError = "";
    private volatile String inputDevice = "opening", outputDevice = "Minecraft positional audio / Friendly Creatures";
    private volatile JsonObject backendDiagnostics = new JsonObject();
    private volatile JsonObject debugContext;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Thread sender;

    VoiceSession(String url, String ticket, net.minecraft.world.entity.Entity villager, Consumer<String> status, Consumer<VoiceCaptions.Line> chat, Runnable ended) {
        this(url, ticket, villager, null, status, chat, ended);
    }

    VoiceSession(String url, String ticket, net.minecraft.world.entity.Entity villager, net.minecraft.world.entity.Entity companion,
                 Consumer<String> status, Consumer<VoiceCaptions.Line> chat, Runnable ended) {
        this.villager = villager;
        this.companion = companion;
        this.status = status;
        this.chat = chat;
        this.ended = ended;
        sender = Thread.ofVirtual().name("higgs-audio-send").unstarted(this::sendLoop);
        URI provided = URI.create(url);
        boolean hasTicket = provided.getRawQuery() != null && java.util.Arrays.stream(provided.getRawQuery().split("&"))
            .anyMatch(parameter -> parameter.startsWith("ticket="));
        String target = hasTicket ? url : url + (url.contains("?") ? "&" : "?") + "ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8);
        URI endpoint = URI.create(target + (target.contains("?") ? "&" : "?") + "mode=ptt");
        if (!"ws".equals(endpoint.getScheme()) && !"wss".equals(endpoint.getScheme()))
            throw new IllegalArgumentException("Invalid voice endpoint");
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(endpoint, this)
            .whenComplete((ws, error) -> {
                if (error != null) fail("Voice connection failed; check the backend.");
                else if (closed.get()) ws.abort();
            });
    }

    @Override public void onOpen(WebSocket ws) {
        socket = ws;
        if (closed.get()) { ws.abort(); return; }
        sender.start();
        status.accept("Connected; waiting for Higgs…");
        ws.request(1);
    }

    boolean isReady() { return ready && !closed.get(); }

    boolean isTransmitting() {
        TargetDataLine line = microphone;
        return !muted && isReady() && line != null && line.isActive();
    }

    boolean isMuted() { return muted; }
    boolean isSpeaking() { return speaking && !muted; }

    synchronized void setTalking(boolean held) {
        if (!isReady() || held == !muted) return;
        muted = !held;
        microphoneGeneration++;
        speaking = held;
        if (held) clearPlayback();
        // Serialized with captured frames: release commits only the preceding turn.
        enqueue(held ? "{\"type\":\"interrupt\"}" : "{\"type\":\"commit\"}");
        notifyAll();
        status.accept(held ? "Speaking… release B to send." : "Sent. Hold B to speak again; ");
    }

    private void startMicrophone() {
        if (microphoneStarted.compareAndSet(false, true)) {
            capture = Thread.ofVirtual().name("higgs-microphone").unstarted(this::captureLoop);
            capture.start();
        }
    }

    private void captureLoop() {
        try {
            AudioDevices.Opened<TargetDataLine> opened = AudioDevices.input();
            TargetDataLine line = opened.line();
            microphone = line;
            inputDevice = opened.description();
            AudioFormat format = line.getFormat();
            PcmAudioConverter converter = new PcmAudioConverter((int) format.getSampleRate(), format.getChannels(), 24000, 1);
            if (closed.get()) { line.close(); return; }
            byte[] frame = new byte[(int) format.getSampleRate() / 25 * format.getFrameSize()]; // 40 ms
            while (!closed.get()) {
                if (muted) {
                    line.stop();
                    line.flush();
                    converter.reset();
                    synchronized (this) {
                        while (muted && !closed.get()) wait();
                    }
                    continue;
                }
                if (!line.isRunning()) {
                    line.flush();
                    line.start();
                    realtime.start(System.nanoTime());
                }
                long frameGeneration;
                synchronized (this) { frameGeneration = microphoneGeneration; }
                int count = line.read(frame, 0, frame.length);
                synchronized (this) {
                    if (count > 0 && !realtime.accept(count * 1_000_000L / ((long) format.getSampleRate() * format.getFrameSize()), System.nanoTime())) {
                        staleChunks++; // Backlog replayed faster than real time; never send it.
                    } else if (!muted && !closed.get() && frameGeneration == microphoneGeneration && count > 0) {
                        byte[] pcm = converter.convert(frame, count);
                        if (pcm.length == 0) continue;
                        micDbfs = AudioLevels.dbfs(pcm, pcm.length);
                        lastMicFrame = System.currentTimeMillis();
                        String audio = Base64.getEncoder().encodeToString(pcm);
                        enqueue("{\"type\":\"audio\",\"audio\":\"" + audio + "\"}", pcm.length);
                    } else converter.reset();
                }
            }
        } catch (Exception e) {
            if (!closed.get()) {
                System.getLogger("higgs-villagers").log(System.Logger.Level.WARNING, "Microphone initialization/capture failed", e);
                fail("Microphone unavailable. Check audio routing, microphone permission and -Dhiggs.audio.input; details in latest.log.");
            }
        } finally {
            TargetDataLine line = microphone;
            if (line != null) line.close();
        }
    }

    private void enqueue(String message) { enqueue(message, 0); }
    private void enqueue(String message, int audioBytes) {
        if (closed.get() || outgoing.offer(new Outbound(message, audioBytes))) return;
        if (audioBytes == 0) { fail("Voice connection is too slow. Please reconnect."); return; }
        // A dropped microphone frame is recoverable; keep the conversation alive.
        droppedChunks++;
        if (System.currentTimeMillis() - lastOverflowNotice > 10_000) {
            lastOverflowNotice = System.currentTimeMillis();
            status.accept("Voice upload is behind; dropping microphone audio.");
        }
    }

    private void sendLoop() {
        try {
            while (!closed.get()) {
                Outbound packet = outgoing.take();
                socket.sendText(packet.json(), true).join();
                if (packet.audioBytes() > 0) { sentChunks++; sentBytes += packet.audioBytes(); }
            }
        } catch (Exception e) {
            if (!closed.get()) fail("Voice connection interrupted.");
        }
    }

    private void clearPlayback() {
        playbackGeneration++;
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            SpatialVoice previous = playback;
            playback = null;
            if (previous != null) previous.cancel();
            companionPending.clear();
            companionPendingBytes = 0;
            if (companionPlayback != null) companionPlayback.cancel();
            companionPlayback = null;
        });
    }

    private void playCompanion(byte[] bytes) {
        var speaker = companion;
        long generation = playbackGeneration;
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (closed.get() || !muted || companion == null || companion != speaker || generation != playbackGeneration
                || companionPendingBytes + bytes.length > 48000 * 30) return;
            companionPending.addLast(bytes);
            companionPendingBytes += bytes.length;
            pumpCompanion();
        });
    }

    /** A neighbour joined or (null) left; discard departed speech. Client thread. */
    void setCompanion(net.minecraft.world.entity.Entity entity) {
        companion = entity;
        ConversationOutline.setCompanion(entity == null ? -1 : entity.getId());
    }

    void dismissCompanion(int entityId) {
        if (companion != null && companion.getId() != entityId) return;
        setCompanion(null);
        companionPending.clear();
        companionPendingBytes = 0;
        if (companionPlayback != null) companionPlayback.cancel();
        companionPlayback = null;
    }

    /** Client tick: start held companion audio once the main villager has been quiet for a moment. */
    void tick() { if (!closed.get()) pumpCompanion(); }

    private void pumpCompanion() {
        long now = System.currentTimeMillis();
        if (playback != null && !playback.closed() && playback.queuedBytes() > 0) { mainQuietSince = -1; return; }
        if (mainQuietSince < 0) mainQuietSince = now;
        if (companionPending.isEmpty()) return;
        boolean speaking = companionPlayback != null && !companionPlayback.closed() && companionPlayback.queuedBytes() > 0;
        // Native buffers still hold ~400 ms of the main villager after its queue empties.
        if (!speaking && now - mainQuietSince < 450) return;
        if (companionPlayback == null || companionPlayback.closed()) {
            if (companionPlayback != null) companionPlayback.cancel();
            companionPlayback = new SpatialVoice(companion);
            if (net.minecraft.client.Minecraft.getInstance().getSoundManager().play(companionPlayback)
                == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) {
                companionPlayback.cancel();
                companionPlayback = null;
                companionPending.clear();
                companionPendingBytes = 0;
                return;
            }
        }
        while (!companionPending.isEmpty()) companionPlayback.offer(companionPending.removeFirst());
        companionPendingBytes = 0;
    }

    private void play(byte[] bytes) {
        long generation = playbackGeneration;
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (closed.get() || !muted || generation != playbackGeneration) return;
            if (playback == null || playback.closed()) {
                if (playback != null) playback.cancel();
                playback = new SpatialVoice(villager);
                playback.offer(bytes);
                if (net.minecraft.client.Minecraft.getInstance().getSoundManager().play(playback)
                    == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) playback.cancel();
            } else if (!playback.offer(bytes) && System.currentTimeMillis() - lastOverflowNotice > 10_000) {
                lastOverflowNotice = System.currentTimeMillis();
                status.accept("Audio backlog exceeded 30 seconds; skipping older speech.");
            }
        });
    }

    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
        if (closed.get()) return null;
        if (incoming.length() + text.length() > 2_000_000) { fail("Voice response exceeded the size limit."); return null; }
        incoming.append(text);
        if (last) {
            try {
                JsonObject event = JsonParser.parseString(incoming.toString()).getAsJsonObject();
                String type = event.get("type").getAsString();
                if (!"diagnostics".equals(type) && !"debug_context".equals(type)) lastEvent = type;
                switch (type) {
                    case "transcript" -> {
                        String speaker = event.get("speaker").getAsString();
                        // Companion lines arrive final only; the caption assembler handles player and main villager.
                        if (speaker.equals("companion")) {
                            String line = event.get("text").getAsString().replaceAll("§.", "").replaceAll("[\\p{Cntrl}]", " ").strip();
                            if (companion != null && (!event.has("entityId") || companion.getId() == event.get("entityId").getAsInt()) && !line.isEmpty()) chat.accept(new VoiceCaptions.Line("companion", line));
                        } else captions.update(speaker, event.get("id").getAsString(),
                            event.get("text").getAsString(), event.get("replace").getAsBoolean()).forEach(chat);
                    }
                    case "diagnostics" -> backendDiagnostics = event;
                    case "debug_context" -> debugContext = event;
                    case "audio" -> {
                        if (!muted) break; // Ignore packets already in flight when B was pressed.
                        byte[] bytes = Base64.getDecoder().decode(event.get("audio").getAsString());
                        receivedChunks++; receivedBytes += bytes.length;
                        if ((bytes.length & 1) != 0) throw new IllegalArgumentException("Invalid PCM");
                        if (bytes.length > 0 && event.has("speaker") && "companion".equals(event.get("speaker").getAsString())) {
                            if (companion != null && (!event.has("entityId") || companion.getId() == event.get("entityId").getAsInt())) playCompanion(bytes);
                        }
                        else if (bytes.length > 0) play(bytes);
                    }
                    case "clear" -> clearPlayback();
                    case "companion_left" -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        if (event.has("discardAudio") && event.get("discardAudio").getAsBoolean()) dismissCompanion(event.get("entityId").getAsInt());
                        else if (companion != null && companion.getId() == event.get("entityId").getAsInt()) setCompanion(null);
                    });
                    case "speech" -> speaking = event.get("active").getAsBoolean();
                    case "status" -> {
                        String message = event.get("message").getAsString();
                        if ("Ready".equalsIgnoreCase(message)) {
                            ready = true;
                            startMicrophone();
                            status.accept("Ready. Hold B to speak; release to send. ");
                        } else status.accept(message);
                    }
                    case "error" -> {
                        String message = event.has("message") ? event.get("message").getAsString() : "Voice error";
                        lastError = message;
                        if (event.has("fatal") && event.get("fatal").getAsBoolean()) fail(message);
                        else status.accept(message);
                    }
                    case "end" -> {
                        status.accept(event.has("message") ? event.get("message").getAsString() : "Voice session ended by backend.");
                        close();
                    }
                    default -> { }
                }
            } catch (Exception e) { fail("Invalid response from voice backend."); }
            incoming.setLength(0);
        }
        ws.request(1);
        return null;
    }

    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        if (!closed.get()) { status.accept("Voice connection closed (" + code + ")" + (reason.isBlank() ? "." : ": " + reason)); close(); }
        return null;
    }
    @Override public void onError(WebSocket ws, Throwable error) { fail("Voice connection failed."); }
    private void fail(String message) {
        if (!closed.get()) { lastError = message; status.accept(message); close(); }
    }
    double microphoneDbfs() {
        return muted || System.currentTimeMillis() - lastMicFrame > 1000 ? -96 : micDbfs;
    }

    JsonObject backendDiagnostics() { return backendDiagnostics; }
    JsonObject debugContext() { return debugContext; }

    String[] diagnosticLines() {
        JsonObject remote = backendDiagnostics;
        long age = lastMicFrame == 0 ? -1 : System.currentTimeMillis() - lastMicFrame;
        String latency = field(remote, "firstAudioLatencyMs", "—");
        String remoteError = field(remote, "lastError", "");
        return new String[] {
            "Socket " + (closed.get() ? "ENDED" : ready ? "READY" : "CONNECTING"),
            String.format(java.util.Locale.ROOT, "Mic %.1f dBFS  frame %s  %s", microphoneDbfs(), age < 0 ? "none" : age + "ms ago", muted ? "MUTED" : isTransmitting() ? "LIVE" : "INACTIVE"),
            "Input " + inputDevice,
            "Output " + outputDevice,
            "Sent " + sentChunks + " chunks / " + sentBytes + " B  pending " + outgoing.size() + "  dropped " + droppedChunks + "  stale " + staleChunks,
            "Audio received " + receivedChunks + " / " + receivedBytes + " B  queued " + ((playback == null ? 0 : playback.queuedBytes()) / 48) + "ms",
            "Voice " + field(remote, "voice", "?") + (field(remote, "voiceFallback", "false").equals("true") ? " (fallback)" : ""),
            "Backend in/out " + field(remote, "inputBytes", "0") + "/" + field(remote, "outputBytes", "0") + " B  ready " + field(remote, "upstreamReady", "?"),
            "VAD (off in PTT) " + field(remote, "speechStarts", "0") + "/" + field(remote, "speechStops", "0") + "  replies " + field(remote, "responses", "0") + "  first audio " + latency + "ms",
            "Suppressed " + field(remote, "suppressedAudio", "none"),
            "Tools " + field(remote, "pendingTools", "0") + "  event " + field(remote, "lastEvent", lastEvent),
            "Error " + (remoteError.isBlank() ? lastError.isBlank() ? "none" : lastError : remoteError)
        };
    }

    private static String field(JsonObject object, String key, String fallback) {
        var value = object.get(key);
        String text = value == null || value.isJsonNull() ? fallback : value.isJsonPrimitive() ? value.getAsString() : value.toString();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sender.interrupt();
        Thread captureThread = capture;
        if (captureThread != null) captureThread.interrupt();
        // Device closure can block briefly; never perform it on the game thread.
        Thread.ofVirtual().name("higgs-audio-close").start(() -> {
            if (microphone != null) { microphone.stop(); microphone.close(); }
            if (socket != null) socket.abort();
            http.shutdownNow();
        });
        outgoing.clear();
        clearPlayback();
        captions.flush().forEach(chat);
        ended.run();
    }
}
