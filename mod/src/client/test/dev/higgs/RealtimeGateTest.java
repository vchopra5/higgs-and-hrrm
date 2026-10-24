package dev.higgs;

public final class RealtimeGateTest {
    public static void main(String[] args) {
        long ms = 1_000_000L;
        RealtimeGate gate = new RealtimeGate(200);
        gate.start(0);
        // A 6 s backlog delivered within 60 ms: only the tolerance passes, the rest is dropped.
        int accepted = 0;
        for (int frame = 0; frame < 150; frame++) if (gate.accept(40_000, frame * 400_000L)) accepted++;
        check(accepted == 6, "stale burst is cut to the tolerance: " + accepted);
        // Live frames afterwards arrive at real time and all pass once the clock catches up.
        long now = 60 * ms;
        int live = 0;
        for (int frame = 0; frame < 100; frame++) {
            now += 40 * ms;
            if (gate.accept(40_000, now)) live++;
        }
        check(live >= 98, "real-time audio is kept: " + live);
        // A stall (e.g. GC pause) followed by a catch-up read is still real audio and passes.
        gate.start(0);
        check(gate.accept(40_000, 40 * ms), "first frame");
        check(gate.accept(1_000_000, 1100 * ms), "catch-up after a stall");
        // Restarting resets the clock for the next press.
        gate.start(5000 * ms);
        check(gate.accept(40_000, 5040 * ms), "restart resets");
        System.out.println("Realtime microphone gate checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
