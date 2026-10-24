package dev.higgs;

/** Tracks a generation end separately from PCM draining into Minecraft's native audio buffers. */
final class PlaybackCompletion {
    private long id = -1, quietSince = -1;
    void end(long floorId) { id = floorId; quietSince = -1; }
    void clear() { id = -1; quietSince = -1; }
    long poll(boolean buffered, long nowMs) {
        if (id < 0) return -1;
        if (buffered) { quietSince = -1; return -1; }
        if (quietSince < 0) quietSince = nowMs;
        if (nowMs - quietSince < 500) return -1;
        long completed = id;
        clear();
        return completed;
    }
}
