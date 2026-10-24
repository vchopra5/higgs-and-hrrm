package dev.higgs;

/** Small deterministic lifecycle: movement and voice may fail; neither may deliver a story twice. */
final class GossipEncounter {
    enum Action { APPROACH, FACE, START_VOICE, DELIVER, REACT, FINISH, CANCEL }
    private enum Phase { APPROACH, VOICE, EXCHANGE, SAVING, REACTION, DONE }
    private Phase phase = Phase.APPROACH;
    private final long started;
    private long progressAt, arrivedAt, reactionUntil;
    private double best = Double.MAX_VALUE;

    GossipEncounter(long now) { started = progressAt = now; }
    static float speed(int interest) { return interest >= 80 ? 0.85f : 0.55f; }

    Action tick(long now, boolean available, boolean storyValid, double distanceSquared, boolean visible) {
        if (phase == Phase.DONE) return Action.FINISH;
        if (!available || ((phase == Phase.APPROACH || phase == Phase.VOICE || phase == Phase.EXCHANGE) && !storyValid)
                || now - started > 100_000) return cancel();
        if (phase != Phase.APPROACH && (distanceSquared > 9 || !visible)) return cancel();
        switch (phase) {
            case APPROACH -> {
                if (distanceSquared <= 4 && visible) {
                    phase = Phase.VOICE; arrivedAt = now; return Action.START_VOICE;
                }
                if (distanceSquared < best - 0.5) { best = distanceSquared; progressAt = now; }
                if (now - started > 20_000 || now - progressAt > 6_000) return cancel();
                return Action.APPROACH;
            }
            case VOICE -> {
                // Realtime failure still permits a short visual exchange, but never an endless waiting pair.
                if (now - arrivedAt > 65_000) return cancel();
                return Action.FACE;
            }
            case EXCHANGE -> {
                if (now - arrivedAt < 3_000) return Action.FACE;
                phase = Phase.SAVING; return Action.DELIVER;
            }
            case SAVING -> { return Action.FACE; }
            case REACTION -> {
                if (now >= reactionUntil) { phase = Phase.DONE; return Action.FINISH; }
                return Action.REACT;
            }
            default -> { return Action.FINISH; }
        }
    }
    void voiceDone() { if (phase == Phase.VOICE) phase = Phase.EXCHANGE; }
    void saved(boolean success, long now) {
        if (phase != Phase.SAVING) return;
        phase = success ? Phase.REACTION : Phase.DONE;
        reactionUntil = now + 1_500;
    }
    private Action cancel() { phase = Phase.DONE; return Action.CANCEL; }
}
