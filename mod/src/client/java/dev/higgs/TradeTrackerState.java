package dev.higgs;

/** Only the latest server quote can display an acceptance prompt. */
final class TradeTrackerState {
    record Quote(String id, String payment, String received, long expires) {}
    Quote quote;
    String villager = "", status = "", previous = "", quest = "";
    long updated;
    boolean active;

    /** Quest updates show the deal a quest unlocks; like interest, they never invite a purchase yes. */
    static boolean informational(String status) {
        return status.equals("interest") || status.startsWith("quest") || status.startsWith("gift");
    }

    void update(String villager, String status, Quote next, long now) { update(villager, status, next, "", now); }

    void update(String villager, String status, Quote next, String quest, long now) {
        if (status.equals("cancelled") && !active) return;
        this.quest = quest;
        if (next != null && quote != null && !next.id().equals(quote.id())
            && (!next.payment().equals(quote.payment()) || !next.received().equals(quote.received())))
            previous = quote.payment() + " → " + quote.received();
        else if (next == null) previous = "";
        if (informational(status) || informational(this.status)) previous = "";
        this.villager = villager;
        this.status = status;
        if (next != null) quote = next;
        active = next != null && !informational(status);
        updated = now;
    }
    void expire(long now) {
        if (active && quote != null && now >= quote.expires()) {
            active = false; status = "expired"; previous = ""; updated = now;
        }
    }
    float opacity(long now) {
        expire(now);
        long hold = status.startsWith("quest") ? 20000 : status.equals("interest") ? 15000 : status.startsWith("gift") ? 8000 : 6000;
        return status.isEmpty() ? 0 : active ? 1 : Math.max(0, Math.min(1, (hold - (now - updated)) / 1500f));
    }
    void clear() { quote = null; villager = ""; status = ""; previous = ""; quest = ""; active = false; updated = 0; }
}
