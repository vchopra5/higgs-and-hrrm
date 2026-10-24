package dev.higgs;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Orders overheard villager lines. The backend generates faster than speech plays, so a line's audio is held until the
 * previous line has finished, then plays after a short conversational pause. All calls run on the client thread.
 */
final class ChatterSequence {
    static final long GAP_MS = 350;
    /** Minecraft keeps ~400 ms of PCM in native buffers after the queue drains; stopping sooner clips the last word. */
    static final long TAIL_MS = 500;
    static final int MAX_LINE_BYTES = 48000 * 30;

    static final class Line {
        final int speaker;
        final String name;
        final ArrayDeque<byte[]> pending = new ArrayDeque<>();
        int bytes;
        boolean ended, captioned;
        String text = "";
        Line(int speaker, String name) { this.speaker = speaker; this.name = name; }
    }

    private final Map<Integer, Line> lines = new HashMap<>();
    private int current;
    private boolean finished, playing;
    private long nextAt, drainedAt = -1;

    void line(int index, int speaker, String name) {
        if (index >= current && index < current + 16) lines.putIfAbsent(index, new Line(speaker, name));
    }

    void audio(int index, byte[] pcm) {
        Line line = lines.get(index);
        if (line == null || line.ended || pcm.length == 0 || line.bytes + pcm.length > MAX_LINE_BYTES) return;
        line.pending.addLast(pcm);
        line.bytes += pcm.length;
    }

    void lineEnd(int index, String text) {
        Line line = lines.get(index);
        if (line == null) return;
        line.ended = true;
        line.text = text == null ? "" : text;
    }

    void end() { finished = true; }

    Line current() { return lines.get(current); }

    boolean playing() { return playing; }

    /** The current line has audio (or is known to be silent) and the pause after the last line is over. */
    boolean ready(long now) {
        Line line = current();
        return !playing && line != null && now >= nextAt && (!line.pending.isEmpty() || line.ended);
    }

    void begin() { playing = true; drainedAt = -1; }

    /** While playing: true once the line has ended, all its PCM went to the stream, and the tail has played out. */
    boolean lineOver(long now, boolean streamEmpty) {
        Line line = current();
        if (line == null) return true;
        if (!line.ended || !line.pending.isEmpty() || !streamEmpty) { drainedAt = -1; return false; }
        if (drainedAt < 0) drainedAt = now;
        return now - drainedAt >= TAIL_MS;
    }

    void advance(long now) {
        lines.remove(current);
        current++;
        playing = false;
        nextAt = now + GAP_MS;
    }

    boolean done() { return finished && lines.isEmpty() && !playing; }
}
