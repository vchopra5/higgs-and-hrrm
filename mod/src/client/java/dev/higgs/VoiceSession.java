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
    final VoiceCaptions captions = new VoiceCaptions();
    private final Runnable ended;
    private record Outbound(String json, int audioBytes) {}
    private final ArrayBlockingQueue<Outbound> outgoing = new ArrayBlockingQueue<>(64);
    private record AudioChunk(byte[] bytes, long generation) { int byteCount() { return bytes.length; } }
    private final AudioPlaybackQueue<AudioChunk> playback = new AudioPlaybackQueue<>(24000 * 2 * 30, AudioChunk::byteCount);
    private volatile long playbackGeneration;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean microphoneStarted = new AtomicBoolean();
    private final StringBuilder incoming = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean ready;
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speaker;
    private volatile boolean muted = true;
    private volatile boolean speaking;
    private long microphoneGeneration;
    private volatile Thread capture;
    private long lastOverflowNotice;
    private volatile double micDbfs = -96;
    private volatile long lastMicFrame;
    private volatile long sentChunks, sentBytes, receivedChunks, receivedBytes;
    private volatile int playingBytes;
    private volatile String lastEvent = "connecting", lastError = "";
    private volatile String inputDevice = "opening", outputDevice = "opening";
    private volatile JsonObject backendDiagnostics = new JsonObject();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Thread sender;
    private final Thread player;

    VoiceSession(String url, String ticket, Consumer<String> status, Runnable ended) {
        this.status = status;
        this.ended = ended;
        sender = Thread.ofVirtual().name("higgs-audio-send").unstarted(this::sendLoop);
        player = Thread.ofVirtual().name("higgs-audio-play").unstarted(this::playLoop);
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
        player.start();
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
                if (!line.isRunning()) { line.flush(); line.start(); }
                long frameGeneration;
                synchronized (this) { frameGeneration = microphoneGeneration; }
                int count = line.read(frame, 0, frame.length);
                synchronized (this) {
                    if (!muted && !closed.get() && frameGeneration == microphoneGeneration && count > 0) {
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
        if (!closed.get() && !outgoing.offer(new Outbound(message, audioBytes))) fail("Voice connection is too slow. Please reconnect.");
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

    private void playLoop() {
        try {
            AudioDevices.Opened<SourceDataLine> opened = AudioDevices.output();
            SourceDataLine line = opened.line();
            speaker = line;
            outputDevice = opened.description();
            AudioFormat format = line.getFormat();
            PcmAudioConverter converter = new PcmAudioConverter(24000, 1, (int) format.getSampleRate(), format.getChannels());
            long generation = playbackGeneration;
            if (closed.get()) { line.close(); return; }
            line.start();
            while (!closed.get()) {
                AudioChunk chunk = playback.take();
                if (chunk.generation() != playbackGeneration) continue;
                if (generation != chunk.generation()) { converter.reset(); generation = chunk.generation(); }
                byte[] frame = converter.convert(chunk.bytes(), chunk.bytes().length);
                for (int offset = 0; offset < frame.length && !closed.get() && chunk.generation() == playbackGeneration;) {
                    int count = Math.min((int) format.getSampleRate() / 25 * format.getFrameSize(), frame.length - offset);
                    // Queue diagnostics are expressed in the 24 kHz mono wire format.
                    playingBytes = (int) ((long) (frame.length - offset) * 48000 / ((int) format.getSampleRate() * format.getFrameSize()));
                    int written = line.write(frame, offset, count);
                    if (written <= 0 && !closed.get() && chunk.generation() == playbackGeneration)
                        throw new IllegalStateException("Audio output stopped accepting frames");
                    offset += written;
                }
                playingBytes = 0;
            }
        } catch (Exception e) {
            if (!closed.get()) {
                System.getLogger("higgs-villagers").log(System.Logger.Level.WARNING, "Audio output initialization/playback failed", e);
                fail("Audio output unavailable. Check audio routing and -Dhiggs.audio.output; details in latest.log.");
            }
        } finally {
            SourceDataLine line = speaker;
            if (line != null) line.close();
        }
    }

    private void clearPlayback() {
        playbackGeneration++;
        playback.clear();
        SourceDataLine line = speaker;
        if (line != null && line.isOpen()) line.flush();
    }

    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
        if (closed.get()) return null;
        if (incoming.length() + text.length() > 2_000_000) { fail("Voice response exceeded the size limit."); return null; }
        incoming.append(text);
        if (last) {
            try {
                JsonObject event = JsonParser.parseString(incoming.toString()).getAsJsonObject();
                String type = event.get("type").getAsString();
                if (!"diagnostics".equals(type)) lastEvent = type;
                switch (type) {
                    case "transcript" -> captions.update(event.get("speaker").getAsString(), event.get("id").getAsString(),
                        event.get("text").getAsString(), event.get("replace").getAsBoolean(), System.currentTimeMillis());
                    case "diagnostics" -> backendDiagnostics = event;
                    case "audio" -> {
                        if (!muted) break; // Ignore packets already in flight when B was pressed.
                        byte[] bytes = Base64.getDecoder().decode(event.get("audio").getAsString());
                        receivedChunks++; receivedBytes += bytes.length;
                        if ((bytes.length & 1) != 0) throw new IllegalArgumentException("Invalid PCM");
                        if (bytes.length > 0 && !playback.offer(new AudioChunk(bytes, playbackGeneration))) {
                            // Keep the conversation alive if output arrives faster than the speaker can play.
                            if (System.currentTimeMillis() - lastOverflowNotice > 10_000) {
                                lastOverflowNotice = System.currentTimeMillis();
                                status.accept("Audio backlog exceeded 30 seconds; skipping older speech.");
                            }
                        }
                    }
                    case "clear" -> clearPlayback();
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

    String[] diagnosticLines() {
        JsonObject remote = backendDiagnostics;
        long age = lastMicFrame == 0 ? -1 : System.currentTimeMillis() - lastMicFrame;
        String latency = field(remote, "firstAudioLatencyMs", "—");
        String remoteError = field(remote, "lastError", "");
        return new String[] {
            "Higgs diagnostics [F8]  " + (closed.get() ? "ENDED" : ready ? "READY" : "CONNECTING"),
            String.format(java.util.Locale.ROOT, "Mic %.1f dBFS  frame %s  %s", microphoneDbfs(), age < 0 ? "none" : age + "ms ago", muted ? "MUTED" : isTransmitting() ? "LIVE" : "INACTIVE"),
            "Input " + inputDevice,
            "Output " + outputDevice,
            "Sent " + sentChunks + " chunks / " + sentBytes + " B  pending " + outgoing.size(),
            "Audio received " + receivedChunks + " / " + receivedBytes + " B  queued " + ((playback.queuedBytes() + playingBytes) / 48) + "ms",
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
        player.interrupt();
        Thread captureThread = capture;
        if (captureThread != null) captureThread.interrupt();
        // Device closure can block briefly; never perform it on the game thread.
        Thread.ofVirtual().name("higgs-audio-close").start(() -> {
            if (microphone != null) { microphone.stop(); microphone.close(); }
            if (speaker != null) { speaker.stop(); speaker.flush(); speaker.close(); }
            if (socket != null) socket.abort();
            http.shutdownNow();
        });
        outgoing.clear();
        playback.clear();
        ended.run();
    }
}
