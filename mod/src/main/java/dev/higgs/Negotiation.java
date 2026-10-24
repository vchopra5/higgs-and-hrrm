package dev.higgs;

/** Integer-only pricing. Every returned bundle stays inside the band for the player's standing. */
final class Negotiation {
    record Band(String name, int min, int max) {}
    record Bundle(int payment, int secondary, int quantity, int stockUses, boolean counteroffer) {}
    /**
     * One saved standing per player and villager, -5 to 5, starting at 0. Each point moves the price 4%:
     * +5 centres on 80%, 0 on 100% and -5 on 120%. A discount request may go 5% below the centre.
     */
    static Band band(int standing) {
        int score = Math.max(-5, Math.min(5, standing));
        int centre = 100 - 4 * score;
        return new Band(score <= -2 ? "annoyed" : score >= 2 ? "friendly" : "neutral", centre - 5, centre + 5);
    }
    static Bundle quote(int cost, int secondary, int output, int remainingUses,
                        int maxPayment, int maxSecondary, int maxOutput, Band band,
                        Integer quantity, Integer payment, boolean discount) {
        if (cost < 1 || output < 1 || remainingUses < 1) return null;
        if ((quantity != null && (quantity < 1 || quantity > 64))
            || (payment != null && (payment < 1 || payment > 64))) throw new IllegalArgumentException("Counts must be 1–64");
        int targetRate = discount ? band.min : (band.min + band.max) / 2;
        Integer budget = payment != null ? payment : discount && quantity == null ? cost : null;
        int desiredQuantity = quantity != null ? quantity : budget != null
            ? Math.max(1, budget * output * 100 / (cost * targetRate)) : output;
        int desiredPayment = budget != null ? budget : Math.max(1, ceil(cost * desiredQuantity * targetRate, output * 100));
        Bundle best = null;
        long bestScore = Long.MAX_VALUE;
        for (int q = 1; q <= Math.min(64, maxOutput); q++) {
            int uses = ceil(q, output), second = ceil(secondary * q, output);
            if (uses > remainingUses || second > maxSecondary) continue;
            for (int pay = 1; pay <= Math.min(64, maxPayment); pay++) {
                long ratio = (long) pay * output * 100;
                if (ratio < (long) cost * q * band.min || ratio > (long) cost * q * band.max) continue;
                // A budget-only request must never spend more than that budget.
                if (quantity == null && budget != null && pay > budget) continue;
                long score = quantity == null && budget != null
                    ? Math.abs(pay - desiredPayment) * 10000L + Math.abs(q - desiredQuantity) * 100L + q
                    : Math.abs(q - desiredQuantity) * 10000L + Math.abs(pay - desiredPayment) * 100L + pay;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Bundle(pay, second, q, uses, pay != desiredPayment || q != desiredQuantity);
                }
            }
        }
        return best;
    }
    private static int ceil(int n, int d) { return (n + d - 1) / d; }
}
