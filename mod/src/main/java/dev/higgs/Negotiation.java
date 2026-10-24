package dev.higgs;

/**
 * Whole-item pricing. Quotes open at the list rate; a friendly player can haggle down to the floor rate.
 * Goodwill never raises a price above the villager's normal one, and ill will has no room to haggle.
 */
final class Negotiation {
    /** rate and floor are percentages of the base price; hero is the Hero of the Village discount already applied. */
    record Band(String name, int rate, int floor, int hero) {}
    record Bundle(int payment, int secondary, int quantity, int stockUses, boolean discounted) {}
    static final int HOSTILE = -5;
    /** At rock-bottom reputation the villager refuses all trade and nearby golems defend it. */
    static boolean hostile(int standing) { return standing <= HOSTILE; }
    static Band band(int standing) { return band(standing, -1); }

    /**
     * heroAmplifier is the Hero of the Village effect amplifier, or -1 without it. Vanilla takes
     * 0.3 + 0.0625 × amplifier off every price; voice quotes apply the same cut to the normal price and the floor.
     */
    static Band band(int standing, int heroAmplifier) {
        int score = Math.max(-5, Math.min(5, standing));
        int floor = 100 - 5 * score;
        String name = hostile(score) ? "hostile" : score <= -2 ? "annoyed" : score >= 2 ? "friendly" : "neutral";
        int hero = heroAmplifier < 0 ? 0 : (int) Math.min(90, Math.round((0.3 + 0.0625 * heroAmplifier) * 100));
        return new Band(name, Math.max(1, Math.max(100, floor) * (100 - hero) / 100), Math.max(1, floor * (100 - hero) / 100), hero);
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
    /** The player's own terms, below the floor: what a completed quest unlocks. Null when stock or stacks cannot fit. */
    static Bundle exact(int cost, int secondary, int output, int remainingUses,
                        int maxPayment, int maxSecondary, int maxOutput, Integer quantity, int payment) {
        if (cost < 1 || output < 1 || remainingUses < 1 || payment < 1 || payment > Math.min(64, maxPayment)) return null;
        int q = quantity != null ? quantity : output;
        if (q < 1 || q > Math.min(64, maxOutput)) return null;
        int uses = (q - 1) / output + 1;
        long second = ((long) secondary * q + output - 1) / output;
        if (uses > remainingUses || second > Math.min(64, maxSecondary)) return null;
        return new Bundle(payment, (int) second, q, uses, true);
    }
    private static long price(int cost, int quantity, int rate, int output) {
        return Math.max(1, Math.round((double) cost * quantity * rate / (100.0 * output)));
    }
}
