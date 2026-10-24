package dev.higgs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** Listen-only link to a villager chatter scene. Network callbacks hop to the client thread; tick() drives playback. */
final class AmbientChatter implements WebSocket.Listener {
    /** Matches SpatialVoice's attenuation range: beyond it nothing is audible, so no caption either. */
    private static final double CAPTION_RANGE_SQUARED = 16 * 16;
    final String sceneId;
    private final ChatterSequence sequence = new ChatterSequence();
    private final StringBuilder incoming = new StringBuilder();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private SpatialVoice stream;
    private int linesPlayed;

    AmbientChatter(String sceneId, String url) {
        this.sceneId = sceneId;
        URI endpoint = URI.create(url);
        if (!"ws".equals(endpoint.getScheme()) && !"wss".equals(endpoint.getScheme())) throw new IllegalArgumentException("Invalid chatter endpoint");
        http.newWebSocketBuilder().buildAsync(endpoint, this).whenComplete((ws, error) -> {
            if (error != null) Minecraft.getInstance().execute(this::stop);
            else if (closed) ws.abort();
        });
    }

    @Override public void onOpen(WebSocket ws) {
        socket = ws;
        if (closed) { ws.abort(); return; }
        ws.request(1);
    }

    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
        if (closed) return null;
        if (incoming.length() + text.length() > 2_000_000) { Minecraft.getInstance().execute(this::stop); return null; }
        incoming.append(text);
        if (last) {
            String raw = incoming.toString();
            incoming.setLength(0);
            Minecraft.getInstance().execute(() -> handle(raw));
        }
        ws.request(1);
        return null;
    }

    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        // A finished scene closes the socket while lines may still be queued; let them play out.
        Minecraft.getInstance().execute(sequence::end);
        return null;
    }

    @Override public void onError(WebSocket ws, Throwable error) { Minecraft.getInstance().execute(sequence::end); }

    private void handle(String raw) {
        if (closed) return;
        try {
            JsonObject event = JsonParser.parseString(raw).getAsJsonObject();
            int index = event.has("index") ? event.get("index").getAsInt() : -1;
            switch (event.get("type").getAsString()) {
                case "line" -> sequence.line(index, event.get("speaker").getAsInt(), event.get("name").getAsString());
                case "audio" -> {
                    byte[] pcm = Base64.getDecoder().decode(event.get("audio").getAsString());
                    if ((pcm.length & 1) == 0) sequence.audio(index, pcm);
                }
                case "line_end" -> sequence.lineEnd(index, event.get("text").getAsString());
                case "end" -> {
                    // Deleted scenes were interrupted on purpose; anything else may finish what was generated.
                    if ("closed".equals(event.has("reason") ? event.get("reason").getAsString() : "")) stop();
                    else sequence.end();
                }
                default -> { }
            }
        } catch (RuntimeException ignored) { }
    }

    void tick(Minecraft client) {
        if (closed || client.level == null || client.player == null) return;
        long now = System.currentTimeMillis();
        ChatterSequence.Line line = sequence.current();
        if (sequence.playing()) {
            if (line != null && stream != null) {
                while (!line.pending.isEmpty()) stream.offer(line.pending.removeFirst());
                caption(client, line);
            }
            if (stream == null || stream.closed() || sequence.lineOver(now, stream.queuedBytes() == 0)) {
                if (stream != null) stream.cancel();
                stream = null;
                if (line != null) caption(client, line);
                sequence.advance(now);
            }
        } else if (sequence.ready(now)) {
            Entity speaker = client.level.getEntity(line.speaker);
            sequence.begin();
            linesPlayed++;
            if (speaker == null || line.pending.isEmpty()) {
                // Out of tracking range or silent: drop the audio and move on once the line is complete.
                line.pending.clear();
                if (!line.ended) return;
                sequence.advance(now);
                return;
            }
            stream = new SpatialVoice(speaker);
            while (!line.pending.isEmpty()) stream.offer(line.pending.removeFirst());
            if (client.getSoundManager().play(stream) == SoundEngine.PlayResult.NOT_STARTED) {
                stream.cancel();
                stream = null;
            }
        }
        if (sequence.done()) stop();
    }

    /** Overheard lines are grey and italic, distinct from your own conversation. */
    private void caption(Minecraft client, ChatterSequence.Line line) {
        if (line.captioned || !line.ended || line.text.isBlank()) return;
        line.captioned = true;
        Entity speaker = client.level.getEntity(line.speaker);
        if (speaker == null || client.player.distanceToSqr(speaker) > CAPTION_RANGE_SQUARED) return;
        client.gui.getChat().addClientSystemMessage(Component.literal("<" + line.name + "> " + line.text)
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    String status() { return closed ? "ended" : sequence.playing() ? "playing line " + linesPlayed : "waiting"; }

    boolean closed() { return closed; }

    void stop() {
        if (closed) return;
        closed = true;
        if (stream != null) stream.cancel();
        stream = null;
        WebSocket ws = socket;
        if (ws != null) ws.abort();
        http.shutdownNow();
    }
}
