package dev.higgs;
public final class RecentHitsTest {
    public static void main(String[] args) {
        var hits = new RecentHits();
        check(hits.record(0).sincePreviousMs() == -1);
        check(hits.record(100).count() == 2);
        var rolling = hits.record(30000);
        check(rolling.count() == 2 && rolling.spanMs() == 29900 && rolling.sincePreviousMs() == 29900);
        var reset = hits.record(60000);
        check(reset.count() == 1 && reset.spanMs() == 0 && reset.sincePreviousMs() == -1);
        System.out.println("Rolling hit statistics checks passed");
    }
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
