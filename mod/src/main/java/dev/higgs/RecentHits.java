package dev.higgs;

import java.util.ArrayDeque;

/** Rolling history of attack attempts, including hits on invulnerable demo villagers. */
public final class RecentHits {
    public static final long WINDOW_MS = 30_000;
    private final ArrayDeque<Long> times = new ArrayDeque<>();
    public record Stats(int count, long spanMs, long sincePreviousMs) {}
    public long latest() { return times.isEmpty() ? Long.MIN_VALUE : times.getLast(); }
    public Stats record(long now) {
        while (!times.isEmpty() && now - times.getFirst() >= WINDOW_MS) times.removeFirst();
        long gap = times.isEmpty() ? -1 : now - times.getLast();
        times.addLast(now);
        return new Stats(times.size(), now - times.getFirst(), gap);
    }
}
