package dev.higgs;

/** Rules for errands between villagers and for villagers who shun the player; free of Minecraft types. */
final class Errands {
    /** Reputation for a completed errand: the villager who asked, and the one who received it. */
    static final int GIVER_REWARD = 2, TARGET_REWARD = 1;
    static final int MESSAGE_LIMIT = 160;

    private Errands() {}

    /** Chance a villager refuses to talk and walks off: none while neutral or better, rising as dislike deepens. */
    static double shunChance(int standing) {
        if (standing >= -1) return 0;
        return switch (Math.max(Negotiation.HOSTILE, standing)) {
            case -2 -> 0.25;
            case -3 -> 0.40;
            case -4 -> 0.55;
            default -> 0.75;
        };
    }

    /** Minecraft directions: +x is east, -z is north. */
    static String compass(double dx, double dz) {
        if (dx * dx + dz * dz < 4) return "right here";
        String[] directions = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        return directions[(int) Math.floorMod(Math.round(degrees / 45), 8)];
    }

    static int reward(int standing, int gain) { return Math.min(5, standing + gain); }
}
