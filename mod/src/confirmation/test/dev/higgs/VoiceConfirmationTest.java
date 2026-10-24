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
        QuoteConfirmation originalQuote = QuoteConfirmation.refresh(null, false, 20, 1000);
        QuoteConfirmation sameTurn = QuoteConfirmation.refresh(originalQuote, true, 20, 1001);
        check(sameTurn.advance(20, 1001) == VoiceConfirmation.Result.WAIT, "re-proposing cannot approve a new quote");
        QuoteConfirmation acceptedQuote = QuoteConfirmation.refresh(sameTurn, true, 21, 2000);
        check(acceptedQuote.id().equals(originalQuote.id()) && acceptedQuote.expires() == originalQuote.expires(),
            "identical terms retain the approval identity and deadline");
        check(acceptedQuote.advance(21, 2000) == VoiceConfirmation.Result.CONFIRMED, "re-proposing identical terms on the accept turn still purchases");
        check(acceptedQuote.advance(21, 2001) == VoiceConfirmation.Result.CONSUMED, "refreshed quote purchases only once");
        QuoteConfirmation changedQuote = QuoteConfirmation.refresh(originalQuote, false, 21, 2000);
        check(!changedQuote.id().equals(originalQuote.id()), "changed terms get a fresh approval identity");
        check(changedQuote.advance(21, 2000) == VoiceConfirmation.Result.WAIT, "changed terms cannot inherit old consent");
        check(changedQuote.advance(22, 2001) == VoiceConfirmation.Result.CONFIRMED, "changed terms accept one fresh yes");
        QuoteConfirmation repeated = QuoteConfirmation.refresh(null, false, 20, 1000);
        repeated = QuoteConfirmation.refresh(repeated, true, 21, 2000);
        check(!repeated.stale(22), "fresh presentation keeps the next turn current");
        check(repeated.advance(22, 3000) == VoiceConfirmation.Result.CONFIRMED, "yes after an identical re-presentation purchases");
        QuoteConfirmation repeatedTwice = QuoteConfirmation.refresh(null, false, 20, 1000);
        repeatedTwice = QuoteConfirmation.refresh(repeatedTwice, true, 21, 2000);
        repeatedTwice = QuoteConfirmation.refresh(repeatedTwice, true, 21, 2001);
        check(repeatedTwice.advance(21, 2002) == VoiceConfirmation.Result.CONFIRMED, "multiple identical tools on the accept turn retain consent");
        QuoteConfirmation movedOn = QuoteConfirmation.refresh(null, false, 20, 1000);
        movedOn = QuoteConfirmation.refresh(movedOn, true, 21, 2000);
        check(movedOn.advance(23, 3000) == VoiceConfirmation.Result.STALE, "turns after the latest presentation still become stale");
        QuoteConfirmation oldQuote = QuoteConfirmation.refresh(null, false, 30, 1000);
        QuoteConfirmation expiredQuote = QuoteConfirmation.refresh(oldQuote, true, 31, oldQuote.expires());
        check(!expiredQuote.id().equals(oldQuote.id()), "expired identical terms require a new approval");
        check(expiredQuote.advance(31, oldQuote.expires()) == VoiceConfirmation.Result.WAIT, "expired approval cannot be inherited at the deadline");
        QuoteConfirmation staleQuote = QuoteConfirmation.refresh(oldQuote, true, 33, 2000);
        check(staleQuote.advance(33, 2000) == VoiceConfirmation.Result.WAIT, "stale re-proposal must be heard again");
        check(staleQuote.advance(34, 2001) == VoiceConfirmation.Result.CONFIRMED, "re-presented identical terms accept the next yes");
        QuoteConfirmation afterReputationChange = QuoteConfirmation.refresh(null, true, 40, 3000);
        check(afterReputationChange.advance(40, 3000) == VoiceConfirmation.Result.WAIT, "cleared approval cannot carry consent even if rounding leaves identical prices");
        // Quote at 6, price talk at 7–8 without a tool, yes at 9: the player may have agreed to another price.
        VoiceConfirmation drifted = new VoiceConfirmation(6, 5000);
        check(drifted.stale(9), "later turns are stale");
        check(drifted.advance(9, 100) == VoiceConfirmation.Result.STALE, "stale yes does not purchase");
        drifted.represent(9);
        check(drifted.advance(9, 101) == VoiceConfirmation.Result.WAIT, "re-presented quote cannot be accepted in the same turn");
        check(drifted.advance(10, 102) == VoiceConfirmation.Result.CONFIRMED, "yes right after the re-presented quote purchases");
        check(!new VoiceConfirmation(6, 5000).stale(7), "the turn right after the quote is not stale");
        VoiceConfirmation retry = new VoiceConfirmation(30, 5000);
        check(retry.advance(31, 100) == VoiceConfirmation.Result.CONFIRMED, "first attempt authorized");
        retry.retryAfterFailure(31);
        check(retry.advance(31, 101) == VoiceConfirmation.Result.WAIT, "failed utterance cannot retry twice");
        check(retry.advance(32, 102) == VoiceConfirmation.Result.CONFIRMED, "second yes retries a known failure");
        retry.retryAfterFailure(32);
        check(retry.advance(33, 103) == VoiceConfirmation.Result.CONFIRMED, "repeated failure permits another fresh yes");
        check(retry.advance(34, 104) == VoiceConfirmation.Result.CONSUMED, "success stays consumed");
        VoiceConfirmation expiringRetry = new VoiceConfirmation(40, 5000);
        expiringRetry.advance(41, 4999);
        expiringRetry.retryAfterFailure(41);
        check(expiringRetry.advance(42, 5000) == VoiceConfirmation.Result.EXPIRED, "retry does not extend quote expiry");
        System.out.println("Voice confirmation regression checks passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
