package dev.higgs;

/**
 * Live microphone audio cannot arrive faster than real time. Some Linux mixers buffer while the line is
 * stopped and replay it in one burst after start; frames ahead of the wall clock are that stale backlog.
 */
final class RealtimeGate {
    private final long toleranceMicros;
    private long startedAt;
    private long acceptedMicros;

    RealtimeGate(long toleranceMs) { this.toleranceMicros = toleranceMs * 1000; }

    void start(long nowNanos) {
        startedAt = nowNanos;
        acceptedMicros = 0;
    }

    /** Accepts a frame only while accepted audio stays within the tolerance of elapsed wall time. */
    boolean accept(long frameMicros, long nowNanos) {
        long elapsedMicros = (nowNanos - startedAt) / 1000;
        if (acceptedMicros + frameMicros > elapsedMicros + toleranceMicros) return false;
        acceptedMicros += frameMicros;
        return true;
    }
}
