package dev.higgs;

public final class NegotiationTest {
    public static void main(String[] args) {
        var neutral = Negotiation.band(0);
        var friendly = Negotiation.band(2);
        var annoyed = Negotiation.band(-2);
        var charmed = Negotiation.band(5);
        check(neutral.min() == 95 && neutral.max() == 105, "fresh standing is the normal price");
        check(charmed.min() == 75 && Negotiation.band(-5).max() == 125, "standing extremes");
        check(Negotiation.band(9).equals(charmed) && Negotiation.band(-9).equals(Negotiation.band(-5)), "standing clamped");
        for (int standing = -5; standing < 5; standing++) {
            var now = Negotiation.band(standing);
            var complimented = Negotiation.band(standing + 1);
            check(complimented.min() == now.min() - 4 && complimented.max() == now.max() - 4, "every compliment is 4% cheaper");
            check(now.name().equals(standing <= -2 ? "annoyed" : standing >= 2 ? "friendly" : "neutral"), "band name follows standing");
        }
        expect(quote(neutral, null, 2, false), 2, 6);
        expect(quote(neutral, 24, null, false), 8, 24);
        expect(quote(charmed, 15, 4, false), 4, 15);
        var counter = quote(neutral, 15, 4, false);
        expect(counter, 5, 15);
        check(counter.counteroffer(), "underpriced offer must counter");
        check(quote(friendly, 15, 4, false).counteroffer(), "a little goodwill is not 80% off");
        expect(quote(charmed, null, 4, true), 4, 16);
        expect(Negotiation.quote(1, 0, 6, 12, 64, 64, 64, charmed, null, null, true), 1, 8);
        // 48 items normally cost 16; whole emeralds hide changes below one emerald on small bundles.
        expect(quote(neutral, 48, null, false), 16, 48);
        expect(quote(annoyed, 48, null, false), 18, 48);
        expect(quote(friendly, 48, null, false), 15, 48);
        check(Negotiation.quote(4, 0, 12, 0, 64, 64, 64, neutral, null, null, false) == null, "sold out");
        check(Negotiation.quote(4, 0, 12, 1, 64, 64, 64, neutral, 24, null, false).quantity() <= 12, "stock bounds");
        var twoCosts = Negotiation.quote(4, 2, 12, 10, 64, 64, 64, neutral, 6, 2, false);
        check(twoCosts.secondary() == 1, "secondary payment scales too");
        boolean rejected = false;
        try { quote(neutral, -1, null, false); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "invalid counts rejected");
        // Exhaustively check integer bounds, stock accounting, and budget ceilings across bands.
        for (var band : new Negotiation.Band[]{neutral, friendly, annoyed, charmed, Negotiation.band(-5)}) {
            for (int budget = 1; budget <= 64; budget++) {
                for (int cost = 1; cost <= 16; cost++) {
                    var result = Negotiation.quote(cost, 2, 7, 4, 64, 64, 64, band, null, budget, true);
                    if (result == null) continue;
                    long price = result.payment() * 7L * 100;
                    check(price >= (long) cost * result.quantity() * band.min(), "price floor");
                    check(price <= (long) cost * result.quantity() * band.max(), "price ceiling");
                    check(result.payment() <= budget, "never exceed budget");
                    check(result.quantity() <= 28 && result.stockUses() <= 4, "never exceed stock");
                    check(result.secondary() * 7 >= 2 * result.quantity(), "never omit secondary payment");
                }
            }
        }
        System.out.println("Negotiation price, quantity, stock and budget checks passed");
    }
    static Negotiation.Bundle quote(Negotiation.Band band, Integer q, Integer p, boolean discount) {
        return Negotiation.quote(4, 0, 12, 12, 64, 64, 64, band, q, p, discount);
    }
    static void expect(Negotiation.Bundle b, int pay, int quantity) {
        check(b != null && b.payment() == pay && b.quantity() == quantity, "expected " + pay + " for " + quantity + ", got " + b);
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
