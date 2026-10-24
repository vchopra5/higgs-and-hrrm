package dev.higgs;

public final class NegotiationTest {
    public static void main(String[] args) {
        var neutral = Negotiation.band(0);
        var charmed = Negotiation.band(5);
        check(neutral.rate() == 100 && neutral.floor() == 100, "fresh standing is the normal price with no haggling room");
        check(charmed.rate() == 100 && charmed.floor() == 75, "goodwill opens at the normal price but haggles to 75%");
        check(Negotiation.band(-5).rate() == 125 && Negotiation.band(-5).floor() == 125, "ill will marks up with no room");
        check(Negotiation.band(9).equals(charmed) && Negotiation.band(-9).equals(Negotiation.band(-5)), "standing clamped");
        for (int standing = -5; standing <= 5; standing++) {
            var band = Negotiation.band(standing);
            check(band.floor() == 100 - 5 * standing, "every reputation point moves the floor by 5%");
            check(band.rate() == Math.max(100, band.floor()), "goodwill never raises the opening price");
            check(band.name().equals(standing <= -5 ? "hostile" : standing <= -2 ? "annoyed" : standing >= 2 ? "friendly" : "neutral"), "band name follows standing");
            check(Negotiation.hostile(standing) == (standing == -5), "only rock bottom is hostile");
            expect(Negotiation.quote(20, 0, 1, 12, 64, 64, 64, band, null, null, false), Math.max(20, 20 - standing), 1);
            expect(Negotiation.quote(20, 0, 1, 12, 64, 64, 64, band, null, null, true), 20 - standing, 1);
        }
        expect(quote(neutral, null, null), 4, 12);
        expect(quote(charmed, null, null), 4, 12);
        expect(Negotiation.quote(4, 0, 12, 12, 64, 64, 64, charmed, null, null, true), 3, 12);
        // The reported case: 32 sticks for an emerald opens at 32 and accepts 27 from a well-liked player.
        var liked = Negotiation.band(3);
        expect(Negotiation.quote(32, 0, 1, 12, 64, 64, 64, liked, null, null, false), 32, 1);
        var haggled = Negotiation.quote(32, 0, 1, 12, 64, 64, 64, liked, 1, 27, false);
        check(haggled != null && haggled.payment() == 27 && haggled.quantity() == 1 && haggled.discounted(), "named price at the floor is accepted");
        expect(Negotiation.quote(32, 0, 1, 12, 64, 64, 64, liked, 1, 30, false), 30, 1);
        expect(Negotiation.quote(32, 0, 1, 12, 64, 64, 64, liked, 1, 40, false), 32, 1);
        check(Negotiation.quote(32, 0, 1, 12, 64, 64, 64, liked, 1, 26, false) == null, "below the floor is refused");
        check(Negotiation.quote(32, 0, 1, 12, 64, 64, 64, neutral, 1, 27, false) == null, "neutral players cannot haggle");
        check(!Negotiation.quote(32, 0, 1, 12, 64, 64, 64, liked, null, null, false).discounted(), "opening quote is not discounted");
        expect(quote(Negotiation.band(-5), null, null), 5, 12);
        expect(quote(neutral, 24, null), 8, 24);
        expect(quote(charmed, 15, null), 5, 15);
        expect(quote(neutral, null, 2), 2, 7);
        expect(quote(charmed, null, 4), 4, 17);
        expect(quote(neutral, 15, 4), 4, 13);
        expect(quote(neutral, 6, 4), 2, 6);
        expect(quote(neutral, 4, null), 1, 4);
        expect(quote(neutral, 5, null), 2, 5);
        expect(Negotiation.quote(1, 0, 2, 12, 64, 64, 64, neutral, 3, null, false), 2, 3);
        expect(Negotiation.quote(1, 0, 64, 12, 64, 64, 64, charmed, 1, null, false), 1, 1);
        check(Negotiation.quote(4, 0, 12, 0, 64, 64, 64, neutral, null, null, false) == null, "sold out");
        expect(Negotiation.quote(4, 0, 12, 1, 64, 64, 64, neutral, 24, null, false), 4, 12);
        expect(Negotiation.quote(4, 0, 12, 10, 64, 64, 16, neutral, null, 64, false), 5, 16);
        expect(Negotiation.quote(1, 0, 12, 10, 64, 64, 128, neutral, null, 64, false), 5, 64);
        expect(Negotiation.quote(4, 0, 1, 100, 16, 64, 64, neutral, 64, null, false), 16, 4);
        expect(Negotiation.quote(4, 0, 1, 100, 128, 64, 64, neutral, 64, null, false), 64, 16);
        check(Negotiation.quote(4, 0, 1, 10, 64, 64, 64, neutral, null, 1, false) == null, "unaffordable budget");
        var twoCosts = Negotiation.quote(4, 2, 12, 10, 64, 64, 64, neutral, 7, null, false);
        check(twoCosts.secondary() == 2 && twoCosts.stockUses() == 1, "secondary payment rounds up");
        var limitedSecond = Negotiation.quote(4, 2, 12, 10, 64, 1, 64, neutral, 24, null, false);
        check(limitedSecond.quantity() == 6 && limitedSecond.secondary() == 1, "secondary stack limits quantity");
        var fullSecond = Negotiation.quote(1, 2, 1, 100, 64, 128, 64, neutral, 64, null, false);
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
                    var result = Negotiation.quote(cost, 2, 7, 4, 64, 64, 64, band, null, budget, false);
                    if (result == null) {
                        check(price(cost, 1, band.floor(), 7) > budget, "no affordable item omitted");
                        continue;
                    }
                    int list = price(cost, result.quantity(), band.rate(), 7);
                    check(result.payment() == Math.min(budget, list), "pays the offer, never more than list");
                    check(result.payment() >= price(cost, result.quantity(), band.floor(), 7), "never below the floor");
                    check(result.discounted() == (result.payment() < list), "discount flag matches the price");
                    check(result.payment() >= 1 && result.payment() <= budget, "whole-item minimum and budget ceiling");
                    check(result.quantity() >= 1 && result.quantity() <= 28 && result.stockUses() <= 4, "stock bounds");
                    check(result.stockUses() == (result.quantity() + 6) / 7, "stock accounting rounds up");
                    check(result.secondary() == (2 * result.quantity() + 6) / 7, "secondary scales with quantity");
                    check(result.quantity() == 28 || price(cost, result.quantity() + 1, band.floor(), 7) > budget,
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
        return Negotiation.quote(4, 0, 12, 12, 64, 64, 64, band, q, p, false);
    }
    static void expect(Negotiation.Bundle b, int pay, int quantity) {
        check(b != null && b.payment() == pay && b.quantity() == quantity, "expected " + pay + " for " + quantity + ", got " + b);
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
