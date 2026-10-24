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
        System.out.println("Voice confirmation regression checks passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
