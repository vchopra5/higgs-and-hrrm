package dev.higgs;

/**
 * Whole-item pricing. Quotes open at the list rate; a friendly player can haggle down to the floor rate.
 * Goodwill never raises a price above the villager's normal one, and ill will has no room to haggle.
 */
final class Negotiation {
    record Band(String name, int rate, int floor) {}
    record Bundle(int payment, int secondary, int quantity, int stockUses, boolean discounted) {}
    static final int HOSTILE = -5;
    /** At rock-bottom reputation the villager refuses all trade and nearby golems defend it. */
    static boolean hostile(int standing) { return standing <= HOSTILE; }
    static Band band(int standing) {
        int score = Math.max(-5, Math.min(5, standing));
        int floor = 100 - 5 * score;
        String name = hostile(score) ? "hostile" : score <= -2 ? "annoyed" : score >= 2 ? "friendly" : "neutral";
        return new Band(name, Math.max(100, floor), floor);
    }
    /**
     * Payment is the most the player will pay: the villager accepts any amount at or above its floor, never charging
     * more than list. Discount asks for the villager's best price. Returns the largest whole-item bundle that fits.
     */
    static Bundle quote(int cost, int secondary, int output, int remainingUses,
                        int maxPayment, int maxSecondary, int maxOutput, Band band,
                        Integer quantity, Integer payment, boolean discount) {
        if (cost < 1 || output < 1 || remainingUses < 1) return null;
        if ((quantity != null && (quantity < 1 || quantity > 64))
            || (payment != null && (payment < 1 || payment > 64))) throw new IllegalArgumentException("Counts must be 1–64");
        int limit = Math.min(64, Math.min(maxOutput, quantity != null ? quantity : payment != null ? 64 : output));
        int budget = Math.min(64, Math.min(maxPayment, payment != null ? payment : 64));
        for (int q = limit; q >= 1; q--) {
            int uses = (q - 1) / output + 1;
            long second = ((long) secondary * q + output - 1) / output;
            long list = price(cost, q, band.rate, output), floor = price(cost, q, band.floor, output);
            long pay = discount ? floor : payment != null ? Math.min(payment, list) : list;
            if (uses > remainingUses || second > Math.min(64, maxSecondary) || pay > budget || pay < floor) continue;
            return new Bundle((int) pay, (int) second, q, uses, pay < list);
        }
        return null;
    }
    private static long price(int cost, int quantity, int rate, int output) {
        return Math.max(1, Math.round((double) cost * quantity * rate / (100.0 * output)));
    }
}
