package dev.higgs;

/** Whole-item pricing at the player's current reputation rate. */
final class Negotiation {
    record Band(String name, int rate) {}
    record Bundle(int payment, int secondary, int quantity, int stockUses) {}
    static Band band(int standing) {
        int score = Math.max(-5, Math.min(5, standing));
        return new Band(score <= -2 ? "annoyed" : score >= 2 ? "friendly" : "neutral", 100 - 5 * score);
    }
    static Bundle quote(int cost, int secondary, int output, int remainingUses,
                        int maxPayment, int maxSecondary, int maxOutput, Band band,
                        Integer quantity, Integer payment) {
        if (cost < 1 || output < 1 || remainingUses < 1) return null;
        if ((quantity != null && (quantity < 1 || quantity > 64))
            || (payment != null && (payment < 1 || payment > 64))) throw new IllegalArgumentException("Counts must be 1–64");
        int limit = Math.min(64, Math.min(maxOutput, quantity != null ? quantity : payment != null ? 64 : output));
        int budget = Math.min(64, Math.min(maxPayment, payment != null ? payment : 64));
        for (int q = limit; q >= 1; q--) {
            int uses = (q - 1) / output + 1;
            long second = ((long) secondary * q + output - 1) / output;
            long pay = Math.max(1, Math.round((double) cost * q * band.rate / (100.0 * output)));
            if (uses > remainingUses || second > Math.min(64, maxSecondary) || pay > budget) continue;
            return new Bundle((int) pay, (int) second, q, uses);
        }
        return null;
    }
}
