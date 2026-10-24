package dev.higgs;

public final class CompanionPresenceTest {
    public static void main(String[] args) {
        check(CompanionPresence.withinRange(64, true), "join at eight blocks");
        check(!CompanionPresence.withinRange(64.01, true), "no join beyond eight blocks");
        for (double distance : new double[] {8.01, 9, 8.5, 10}) {
            check(CompanionPresence.withinRange(distance * distance, false), "stay across join boundary");
            check(!CompanionPresence.withinRange(distance * distance, true), "departed villager cannot rejoin yet");
        }
        check(!CompanionPresence.withinRange(100.01, false), "leave beyond ten blocks");
        check(CompanionPresence.withinRange(49, true), "return within range");
        check(!CompanionPresence.withinRange(Double.NaN, false), "invalid position is unavailable");
        System.out.println("Companion proximity boundary checks passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
