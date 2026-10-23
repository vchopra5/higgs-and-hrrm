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
    private static final AudioFormat FORMAT = new AudioFormat(24000, 16, 1, true, false);
    private final Consumer<String> status;
    private final Runnable ended;
    private final ArrayBlockingQueue<String> outgoing = new ArrayBlockingQueue<>(64);
    private record AudioChunk(byte[] bytes, long generation) {}
    private final ArrayBlockingQueue<AudioChunk> playback = new ArrayBlockingQueue<>(64);
    private volatile long playbackGeneration;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean microphoneStarted = new AtomicBoolean();
    private final StringBuilder incoming = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean ready;
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speaker;
    private boolean recording;
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
        URI endpoint = URI.create(target);
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

    synchronized void beginTalking() {
        if (!isReady()) return;
        recording = true;
        clearPlayback();
        enqueue("{\"type\":\"interrupt\"}");
        if (microphoneStarted.compareAndSet(false, true))
            Thread.ofVirtual().name("higgs-microphone").start(this::captureLoop);
    }

    synchronized void endTalking() {
        if (!recording) return;
        recording = false;
        enqueue("{\"type\":\"commit\"}");
    }

    private void captureLoop() {
        try {
            TargetDataLine line = AudioSystem.getTargetDataLine(FORMAT);
            microphone = line;
            line.open(FORMAT, 9600);
            if (closed.get()) { line.close(); return; }
            line.start();
            byte[] frame = new byte[1920]; // 40 ms
            while (!closed.get()) {
                int count = line.read(frame, 0, frame.length);
                synchronized (this) {
                    if (recording && count > 0) {
                        String audio = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(frame, count));
                        enqueue("{\"type\":\"audio\",\"audio\":\"" + audio + "\"}");
                    }
                }
            }
        } catch (Exception e) {
            if (!closed.get()) fail("Microphone unavailable. Check device and microphone permission.");
        } finally {
            TargetDataLine line = microphone;
            if (line != null) line.close();
        }
    }

    private void enqueue(String message) {
        if (!closed.get() && !outgoing.offer(message)) fail("Voice connection is too slow. Please reconnect.");
    }

    private void sendLoop() {
        try {
            while (!closed.get()) socket.sendText(outgoing.take(), true).join();
        } catch (Exception e) {
            if (!closed.get()) fail("Voice connection interrupted.");
        }
    }

    private void playLoop() {
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT);
            speaker = line;
            line.open(FORMAT, 9600);
            if (closed.get()) { line.close(); return; }
            line.start();
            while (!closed.get()) {
                AudioChunk chunk = playback.take();
                byte[] frame = chunk.bytes();
                for (int offset = 0; offset < frame.length && !closed.get() && chunk.generation() == playbackGeneration;) {
                    int count = Math.min(1920, frame.length - offset);
                    offset += line.write(frame, offset, count);
                }
            }
        } catch (Exception e) {
            if (!closed.get()) fail("Audio output unavailable. Check your sound device.");
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
        if (incoming.length() + text.length() > 2_000_000) { fail("Voice response exceeded the size limit."); return null; }
        incoming.append(text);
        if (last) {
            try {
                JsonObject event = JsonParser.parseString(incoming.toString()).getAsJsonObject();
                String type = event.get("type").getAsString();
                switch (type) {
                    case "audio" -> {
                        byte[] bytes = Base64.getDecoder().decode(event.get("audio").getAsString());
                        if ((bytes.length & 1) != 0) throw new IllegalArgumentException("Invalid PCM");
                        if (!playback.offer(new AudioChunk(bytes, playbackGeneration))) fail("Audio playback fell behind. Please reconnect.");
                    }
                    case "clear" -> clearPlayback();
                    case "status" -> {
                        String message = event.get("message").getAsString();
                        if ("Ready".equalsIgnoreCase(message)) {
                            ready = true;
                            status.accept("Ready. Hold B to talk; V to leave.");
                        } else status.accept(message);
                    }
                    case "error" -> fail(event.has("message") ? event.get("message").getAsString() : "Voice error");
                    case "end" -> close();
                    default -> { }
                }
            } catch (Exception e) { fail("Invalid response from voice backend."); }
            incoming.setLength(0);
        }
        ws.request(1);
        return null;
    }

    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        if (!closed.get()) { status.accept("Villager conversation ended."); close(); }
        return null;
    }
    @Override public void onError(WebSocket ws, Throwable error) { fail("Voice connection failed."); }
    private void fail(String message) {
        if (!closed.get()) { status.accept(message); close(); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sender.interrupt();
        player.interrupt();
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
