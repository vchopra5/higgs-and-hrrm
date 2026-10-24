package dev.higgs;

public final class MerchantTurnTest {
    public static void main(String[] args) {
        MerchantTurn original = new MerchantTurn(10);
        check(original.permits(10, 0), "initial merchant can browse");
        original.address(10, 1);
        QuoteConfirmation old = QuoteConfirmation.refresh(null, false, 1, 1000);
        original.address(20, 2);
        check(!original.permits(10, 2), "yes to companion cannot confirm original quote");
        check(!original.permits(10, 1), "delayed original tool cannot execute");
        check(!original.permits(20, 2), "companion cannot use original merchant session");
        check(!original.address(10, 1), "late recipient update rejected");
        check(original.addressed(20, 2), "handoff must match recipient and turn");
        MerchantTurn next = new MerchantTurn(20);
        QuoteConfirmation fresh = QuoteConfirmation.refresh(null, false, 0, 2000);
        check(!fresh.id().equals(old.id()), "new merchant gets a new quote ID");
        check(next.permits(20, 0) && !next.permits(10, 0), "new merchant only");
        check(fresh.advance(0, 2001) == VoiceConfirmation.Result.WAIT, "handoff request is not consent");
        next.address(20, 1);
        check(next.permits(20, 1), "fresh confirmation addressed to new merchant");
        check(fresh.advance(1, 2002) == VoiceConfirmation.Result.CONFIRMED, "new quote can be accepted next turn");
        next.address(10, 2);
        check(!next.permits(20, 2), "switching back revokes new merchant too");
        System.out.println("Merchant ownership and confirmation checks passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
