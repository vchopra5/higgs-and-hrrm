package dev.higgs;

/**
 * One consumable approval in the input turn immediately after the quote was last presented.
 * Later turns are STALE: the conversation moved on, so the exact quote must be re-presented.
 */
final class VoiceConfirmation {
    enum Result { WAIT, CONFIRMED, STALE, EXPIRED, CONSUMED }
    private final long expiresAt;
    private int lastTurn;
    private int confirmations;
    VoiceConfirmation(int proposalTurn, long expiresAt) { this.lastTurn = proposalTurn; this.expiresAt = expiresAt; }
    Result advance(int turn, long now) {
        if (confirmations >= 1) return Result.CONSUMED;
        if (now >= expiresAt) return Result.EXPIRED;
        if (turn <= lastTurn) return Result.WAIT;
        if (turn > lastTurn + 1) return Result.STALE;
        lastTurn = turn;
        confirmations++;
        return Result.CONFIRMED;
    }
    /** Re-arm only after a known pre-purchase failure and a repeated quote.
     * The failed turn stays spent and the original expiry is unchanged.
     */
    void retryAfterFailure(int turn) {
        if (confirmations != 1 || turn != lastTurn)
            throw new IllegalStateException("No matching failed confirmation");
        confirmations = 0;
    }
    /** True when a yes in this turn would be stale, i.e. the player has not just heard the quote. */
    boolean stale(int turn) { return turn > lastTurn + 1; }
    /** The quote is read out again in this turn; only the next turn may accept it. */
    void represent(int turn) { if (confirmations == 0 && turn > lastTurn) lastTurn = turn; }
}
