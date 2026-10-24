package dev.higgs;
import com.google.gson.Gson;
public final class TradeReputationTest {
    public static void main(String[] args) {
        var neutral = TradeReputation.neutral();
        var hurt = neutral.hit(1000);
        check(hurt.score() == -2 && Negotiation.band(hurt.score(), 0, 0).name().equals("annoyed"), "hit survives reconnect and worsens trade band");
        check(hurt.hit(1200).equals(hurt), "rapid duplicate hits are capped");
        check(hurt.hit(1800).score() == -4, "repeated hits hurt reputation");
        check(hurt.hit(1800).hit(2600).score() == -5, "negative cap");
        check(hurt.treatment("polite", 2000).equals(hurt), "instant compliment cannot erase a hit");
        var liked = neutral.treatment("polite", 1000);
        check(liked.score() == 1 && Negotiation.band(liked.score(), 1, 0).name().equals("friendly"), "compliment improves pricing");
        check(liked.treatment("polite", 2000).equals(liked), "compliment farming cooldown");
        check(liked.treatment("polite", 61000).score() == 2, "later compliment persists");
        var gson = new Gson();
        check(gson.fromJson(gson.toJson(hurt), TradeReputation.class).equals(hurt), "save roundtrip");
        var legacy = gson.fromJson("{\"score\":2,\"updated\":1000}", TradeReputation.class);
        check(legacy.score() == 2 && legacy.lastHit() == 0, "existing reputation files remain readable");
        System.out.println("Trade reputation checks passed");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
