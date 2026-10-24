package dev.higgs;

public final class TradeTrackerStateTest {
    public static void main(String[] args) {
        var state = new TradeTrackerState();
        state.update("Farmer", "awaiting_confirmation", new TradeTrackerState.Quote("a", "4 emeralds", "12 bread", 1000), 0);
        check(state.active && state.opacity(999) == 1, "quote stays actionable until expiry");
        state.opacity(1000);
        check(!state.active && state.status.equals("expired"), "expired quote must not invite yes");
        state.update("Farmer", "awaiting_confirmation", new TradeTrackerState.Quote("b", "4 emeralds", "15 bread", 3000), 1500);
        check(state.previous.contains("12 bread") && state.quote.received().equals("15 bread"), "new quote replaces active deal");
        state.update("Farmer", "purchased", null, 2000);
        check(!state.active && state.quote.received().equals("15 bread"), "receipt retains purchased terms without inviting another acceptance");
        check(state.opacity(8000) == 0 && state.status.equals("purchased"), "success fades without becoming expired");
        state.clear();
        check(state.quote == null && state.previous.isEmpty() && state.opacity(0) == 0, "switching villagers clears all prior terms");
        state.update("Librarian", "unavailable", null, 10);
        check(!state.active && state.quote == null, "failed initial quote has no invented deal");
        state.update("Farmer", "interest", new TradeTrackerState.Quote("preview", "1 emerald", "6 bread", 20000), 1000);
        check(!state.active && state.opacity(2000) == 1, "interest must never invite purchase confirmation");
        state.update("Farmer", "awaiting_confirmation", new TradeTrackerState.Quote("real", "1 emerald", "7 bread", 30000), 3000);
        check(state.active && state.previous.isEmpty(), "interest promotes to exact quote without implying prior consent");
        state.update("Farmer", "cancelled", null, 4000);
        check(!state.active && state.status.equals("cancelled") && state.opacity(4000) == 1, "active trade shows cancellation");
        state.update("Farmer", "cancelled", null, 5000);
        check(state.updated == 4000, "repeated hits do not restart cancellation fade");
        check(state.opacity(10000) == 0, "cancelled trade fades away");
        state.clear();
        state.update("Farmer", "cancelled", null, 11000);
        check(state.opacity(11000) == 0, "hit outside a trade never opens tracker");
        System.out.println("Trade tracker lifecycle checks passed");
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
