package dev.higgs;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

public final class ChatterTopicTest {
    public static void main(String[] args) {
        var random = new SplittableRandom(7);
        Map<String, Integer> quiet = count(random, false, false), newsy = count(random, true, true);
        check(!quiet.containsKey("rumour"), "no rumour topic without a rumour");
        check(quiet.get("player") > 0 && quiet.get("work") > quiet.get("player"), "a player with no news is a rare subject");
        check(newsy.get("player") > newsy.get("work") && newsy.get("rumour") > 0, "news about the player makes them the favourite subject");
        for (int i = 0; i < 1000; i++) {
            int lines = ChatterTopic.lines(random);
            check(lines >= 4 && lines <= 6, "four to six lines");
        }
        check(ChatterTopic.timeOfDay(500).equals("morning") && ChatterTopic.timeOfDay(6000).equals("day")
            && ChatterTopic.timeOfDay(12000).equals("evening") && ChatterTopic.timeOfDay(18000).equals("night")
            && ChatterTopic.timeOfDay(23500).equals("dawn") && ChatterTopic.timeOfDay(24000 * 3 + 6000).equals("day")
            && ChatterTopic.timeOfDay(-1000).equals("dawn"), "time of day wraps across days");
        System.out.println("Villager chatter topic checks passed");
    }
    private static Map<String, Integer> count(SplittableRandom random, boolean news, boolean rumours) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 9000; i++) counts.merge(ChatterTopic.choose(random, news, rumours), 1, Integer::sum);
        return counts;
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
