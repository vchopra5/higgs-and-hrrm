package dev.higgs;

import java.util.random.RandomGenerator;

/** Picks what two villagers chat about. The nearby player is the favourite subject when there is news about them. */
final class ChatterTopic {
    private ChatterTopic() {}

    /** playerNews: an opinion or public story about the player; rumours: a public rumour either villager heard. */
    static String choose(RandomGenerator random, boolean playerNews, boolean rumours) {
        int player = playerNews ? 4 : 1, rumour = rumours ? 2 : 0, work = 2, village = 2;
        int roll = random.nextInt(player + rumour + work + village);
        if (roll < player) return "player";
        if ((roll -= player) < rumour) return "rumour";
        return roll - rumour < work ? "work" : "village";
    }

    /** Four to six lines: long enough to feel like a conversation, short enough to overhear in passing. */
    static int lines(RandomGenerator random) { return 4 + random.nextInt(3); }

    static String timeOfDay(long dayTime) {
        long time = Math.floorMod(dayTime, 24000L);
        return time < 1000 ? "morning" : time < 11000 ? "day" : time < 13000 ? "evening" : time < 23000 ? "night" : "dawn";
    }
}
