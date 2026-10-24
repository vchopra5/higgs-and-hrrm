package dev.higgs;

/** Only the latest server quote can display an acceptance prompt. */
final class TradeTrackerState {
    record Quote(String id, String payment, String received, long expires) {}
    Quote quote;
    String villager = "", status = "", previous = "";
    long updated;
    boolean active;

    void update(String villager, String status, Quote next, long now) {
        if (status.equals("cancelled") && !active) return;
        if (next != null && quote != null && !next.id().equals(quote.id())
            && (!next.payment().equals(quote.payment()) || !next.received().equals(quote.received())))
            previous = quote.payment() + " → " + quote.received();
        else if (next == null) previous = "";
        if (status.equals("interest") || this.status.equals("interest")) previous = "";
        this.villager = villager;
        this.status = status;
        if (next != null) quote = next;
        active = next != null && !status.equals("interest");
        updated = now;
    }
    void expire(long now) {
        if (active && quote != null && now >= quote.expires()) {
            active = false; status = "expired"; previous = ""; updated = now;
        }
    }
    float opacity(long now) {
        expire(now);
        return status.isEmpty() ? 0 : active ? 1 : Math.max(0, Math.min(1, ((status.equals("interest") ? 15000 : 6000) - (now - updated)) / 1500f));
    }
    void clear() { quote = null; villager = ""; status = ""; previous = ""; active = false; updated = 0; }
}
