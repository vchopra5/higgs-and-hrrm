package dev.higgs;

import java.util.UUID;

record QuoteConfirmation(String id, long expires, VoiceConfirmation gate, int presentedTurn) {
    static QuoteConfirmation refresh(QuoteConfirmation previous, boolean sameTerms, int turn, long now) {
        if (previous != null && sameTerms && now < previous.expires()) {
            if (turn > previous.presentedTurn()) previous.gate().represent(previous.presentedTurn());
            if (previous.gate().stale(turn)) previous.gate().represent(turn);
            return new QuoteConfirmation(previous.id(), previous.expires(), previous.gate(), Math.max(turn, previous.presentedTurn()));
        }
        long expires = now + 120_000;
        return new QuoteConfirmation(UUID.randomUUID().toString(), expires, new VoiceConfirmation(turn, expires), turn);
    }
    boolean stale(int turn) { return gate.stale(turn) && turn > presentedTurn + 1; }
    VoiceConfirmation.Result advance(int turn, long now) {
        // Preserve a yes on the re-quote turn; later turns use that fresh presentation.
        if (turn > presentedTurn) gate.represent(presentedTurn);
        return gate.advance(turn, now);
    }
}
