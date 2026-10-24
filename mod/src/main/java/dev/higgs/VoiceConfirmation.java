package dev.higgs;

/** One distinct input turn after a quote, with a single consumable approval. */
final class VoiceConfirmation {
    enum Result { WAIT, CONFIRMED, EXPIRED, CONSUMED }
    private final long expiresAt;
    private int lastTurn;
    private int confirmations;
    VoiceConfirmation(int proposalTurn, long expiresAt) { this.lastTurn = proposalTurn; this.expiresAt = expiresAt; }
    Result advance(int turn, long now) {
        if (confirmations >= 1) return Result.CONSUMED;
        if (now >= expiresAt) return Result.EXPIRED;
        if (turn <= lastTurn) return Result.WAIT;
        lastTurn = turn;
        confirmations++;
        return Result.CONFIRMED;
    }
}
