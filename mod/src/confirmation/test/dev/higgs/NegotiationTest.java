package dev.higgs;

public final class NegotiationTest {
    public static void main(String[] args) {
        var neutral = Negotiation.band(0);
        var charmed = Negotiation.band(5);
        check(neutral.rate() == 100, "fresh standing is the normal price");
        check(charmed.rate() == 75 && Negotiation.band(-5).rate() == 125, "standing extremes");
        check(Negotiation.band(9).equals(charmed) && Negotiation.band(-9).equals(Negotiation.band(-5)), "standing clamped");
        for (int standing = -5; standing <= 5; standing++) {
            var band = Negotiation.band(standing);
            check(band.rate() == 100 - 5 * standing, "every reputation point changes price by 5%");
            check(band.name().equals(standing <= -2 ? "annoyed" : standing >= 2 ? "friendly" : "neutral"), "band name follows standing");
            expect(Negotiation.quote(20, 0, 1, 12, 64, 64, 64, band, null, null), 20 - standing, 1);
        }
        expect(quote(neutral, null, null), 4, 12);
        expect(quote(charmed, null, null), 3, 12);
        expect(quote(Negotiation.band(-5), null, null), 5, 12);
        expect(quote(neutral, 24, null), 8, 24);
        expect(quote(charmed, 15, null), 4, 15);
        expect(quote(neutral, null, 2), 2, 7);
        expect(quote(charmed, null, 4), 4, 17);
        expect(quote(neutral, 15, 4), 4, 13);
        expect(quote(neutral, 6, 4), 2, 6);
        expect(quote(neutral, 4, null), 1, 4);
        expect(quote(neutral, 5, null), 2, 5);
        expect(Negotiation.quote(1, 0, 2, 12, 64, 64, 64, neutral, 3, null), 2, 3);
        expect(Negotiation.quote(1, 0, 64, 12, 64, 64, 64, charmed, 1, null), 1, 1);
        check(Negotiation.quote(4, 0, 12, 0, 64, 64, 64, neutral, null, null) == null, "sold out");
        expect(Negotiation.quote(4, 0, 12, 1, 64, 64, 64, neutral, 24, null), 4, 12);
        expect(Negotiation.quote(4, 0, 12, 10, 64, 64, 16, neutral, null, 64), 5, 16);
        expect(Negotiation.quote(1, 0, 12, 10, 64, 64, 128, neutral, null, 64), 5, 64);
        expect(Negotiation.quote(4, 0, 1, 100, 16, 64, 64, neutral, 64, null), 16, 4);
        expect(Negotiation.quote(4, 0, 1, 100, 128, 64, 64, neutral, 64, null), 64, 16);
        check(Negotiation.quote(4, 0, 1, 10, 64, 64, 64, neutral, null, 1) == null, "unaffordable budget");
        var twoCosts = Negotiation.quote(4, 2, 12, 10, 64, 64, 64, neutral, 7, null);
        check(twoCosts.secondary() == 2 && twoCosts.stockUses() == 1, "secondary payment rounds up");
        var limitedSecond = Negotiation.quote(4, 2, 12, 10, 64, 1, 64, neutral, 24, null);
        check(limitedSecond.quantity() == 6 && limitedSecond.secondary() == 1, "secondary stack limits quantity");
        var fullSecond = Negotiation.quote(1, 2, 1, 100, 64, 128, 64, neutral, 64, null);
        check(fullSecond.quantity() == 32 && fullSecond.secondary() == 64, "secondary never exceeds 64");
        for (int invalid : new int[]{-1, 0, 65}) {
            boolean rejectedQuantity = false, rejectedPayment = false;
            try { quote(neutral, invalid, null); } catch (IllegalArgumentException e) { rejectedQuantity = true; }
            try { quote(neutral, null, invalid); } catch (IllegalArgumentException e) { rejectedPayment = true; }
            check(rejectedQuantity && rejectedPayment, "invalid counts rejected");
        }
        for (int standing = -5; standing <= 5; standing++) {
            var band = Negotiation.band(standing);
            for (int budget = 1; budget <= 64; budget++) {
                for (int cost = 1; cost <= 16; cost++) {
                    var result = Negotiation.quote(cost, 2, 7, 4, 64, 64, 64, band, null, budget);
                    if (result == null) {
                        check(price(cost, 1, band.rate(), 7) > budget, "no affordable item omitted");
                        continue;
                    }
                    check(result.payment() == price(cost, result.quantity(), band.rate(), 7), "exact rounded price");
                    check(result.payment() >= 1 && result.payment() <= budget, "whole-item minimum and budget ceiling");
                    check(result.quantity() >= 1 && result.quantity() <= 28 && result.stockUses() <= 4, "stock bounds");
                    check(result.stockUses() == (result.quantity() + 6) / 7, "stock accounting rounds up");
                    check(result.secondary() == (2 * result.quantity() + 6) / 7, "secondary scales with quantity");
                    check(result.quantity() == 28 || price(cost, result.quantity() + 1, band.rate(), 7) > budget,
                        "budget buys the most available items");
                }
            }
        }
        System.out.println("Negotiation price, quantity, stock and budget checks passed");
    }
    static int price(int cost, int quantity, int rate, int output) {
        return Math.max(1, (cost * quantity * rate + 50 * output) / (100 * output));
    }
    static Negotiation.Bundle quote(Negotiation.Band band, Integer q, Integer p) {
        return Negotiation.quote(4, 0, 12, 12, 64, 64, 64, band, q, p);
    }
    static void expect(Negotiation.Bundle b, int pay, int quantity) {
        check(b != null && b.payment() == pay && b.quantity() == quantity, "expected " + pay + " for " + quantity + ", got " + b);
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
