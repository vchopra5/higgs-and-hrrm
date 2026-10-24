package dev.higgs;

public final class VoiceConfirmationTest {
    public static void main(String[] args) {
        VoiceConfirmation gate = new VoiceConfirmation(8, 1000);
        check(gate.advance(8, 100) == VoiceConfirmation.Result.WAIT, "cannot confirm while proposing");
        check(gate.advance(7, 100) == VoiceConfirmation.Result.WAIT, "stale turns cannot count");
        check(gate.advance(9, 100) == VoiceConfirmation.Result.CONFIRMED, "first separate yes authorizes once");
        check(gate.advance(9, 101) == VoiceConfirmation.Result.CONSUMED, "duplicate tools cannot buy twice");
        check(gate.advance(10, 200) == VoiceConfirmation.Result.CONSUMED, "later turns cannot reuse approval");
        VoiceConfirmation expired = new VoiceConfirmation(0, 1000);
        check(expired.advance(1, 1000) == VoiceConfirmation.Result.EXPIRED, "expiry boundary rejects yes");
        // An unchanged re-quote must keep the original gate, not replace it with one
        // created on the acceptance turn. Changed terms intentionally need a later yes.
        VoiceConfirmation originalQuote = new VoiceConfirmation(20, 5000);
        check(originalQuote.advance(21, 2000) == VoiceConfirmation.Result.CONFIRMED, "unchanged quote accepts first yes");
        VoiceConfirmation changedQuote = new VoiceConfirmation(21, 5000);
        check(changedQuote.advance(21, 2000) == VoiceConfirmation.Result.WAIT, "changed terms cannot inherit old consent");
        check(changedQuote.advance(22, 2001) == VoiceConfirmation.Result.CONFIRMED, "changed terms accept one fresh yes");
        // Quote at 6, price talk at 7–8 without a tool, yes at 9: the player may have agreed to another price.
        VoiceConfirmation drifted = new VoiceConfirmation(6, 5000);
        check(drifted.stale(9), "later turns are stale");
        check(drifted.advance(9, 100) == VoiceConfirmation.Result.STALE, "stale yes does not purchase");
        drifted.represent(9);
        check(drifted.advance(9, 101) == VoiceConfirmation.Result.WAIT, "re-presented quote cannot be accepted in the same turn");
        check(drifted.advance(10, 102) == VoiceConfirmation.Result.CONFIRMED, "yes right after the re-presented quote purchases");
        check(!new VoiceConfirmation(6, 5000).stale(7), "the turn right after the quote is not stale");
        System.out.println("Voice confirmation regression checks passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
