package dev.higgs;

public final class TradeTrackerStateTest {
    public static void main(String[] args) {
        var state = new TradeTrackerState();
        state.update("Farmer", "awaiting_confirmation", new TradeTrackerState.Quote("a", "4 emeralds", "12 bread", 1000), 0);
        check(state.active && state.opacity(999) == 1, "quote stays actionable until expiry");
        state.opacity(1000);
        check(!state.active && state.status.equals("expired"), "expired quote must not invite yes");
        state.update("Farmer", "counteroffer", new TradeTrackerState.Quote("b", "4 emeralds", "15 bread", 3000), 1500);
        check(state.previous.contains("12 bread") && state.quote.received().equals("15 bread"), "counteroffer replaces active deal");
        state.update("Farmer", "purchased", null, 2000);
        check(!state.active && state.quote.received().equals("15 bread"), "receipt retains purchased terms without inviting another acceptance");
        check(state.opacity(8000) == 0 && state.status.equals("purchased"), "success fades without becoming expired");
        state.clear();
        check(state.quote == null && state.previous.isEmpty() && state.opacity(0) == 0, "switching villagers clears all prior terms");
        state.update("Librarian", "unavailable", null, 10);
        check(!state.active && state.quote == null, "failed initial quote has no invented deal");
        System.out.println("Trade tracker lifecycle checks passed");
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
