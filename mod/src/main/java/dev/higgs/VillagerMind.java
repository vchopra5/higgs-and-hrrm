package dev.higgs;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A villager's passing state of mind (TinyTroupe's emotions, goals and attention): mood from what happened to it
 * recently, a goal for the day from its trade, and what is on its mind. Mood fades within an in-game day.
 */
final class VillagerMind {
    private VillagerMind() {}

    /** One in-game day: 20 real minutes. */
    static final long MOOD_MS = 20 * 60_000L;

    private static final Map<String, String> MOODS = Map.ofEntries(
        Map.entry("hit", "shaken and on edge"), Map.entry("insult", "irritated"), Map.entry("gift_refused", "a little put out"),
        Map.entry("shunned", "sulky"), Map.entry("witnessed", "uneasy"), Map.entry("gift", "cheerful"),
        Map.entry("quest_done", "pleased"), Map.entry("errand_received", "pleased"), Map.entry("errand_done", "pleased"),
        Map.entry("trade", "content"), Map.entry("kindness", "warm"), Map.entry("conversation", "chatty"));
    /** When several things happened, the strongest feeling wins; bad experiences outweigh good ones. */
    private static final List<String> PRIORITY = List.of("hit", "insult", "witnessed", "gift_refused", "shunned",
        "gift", "quest_done", "errand_received", "errand_done", "kindness", "trade", "conversation");

    private static final Map<String, List<String>> GOALS = Map.ofEntries(
        Map.entry("farmer", List.of("getting the harvest in before the rain", "short of bone meal for the late crops", "planning to try pumpkins this season")),
        Map.entry("librarian", List.of("cataloguing a stack of old books", "hoping to find a rare enchantment", "short of paper for new copies")),
        Map.entry("cleric", List.of("brewing remedies for the sick", "worried about strange lights at night", "low on redstone for the altar")),
        Map.entry("armorer", List.of("finishing a heavy chestplate", "running low on iron", "wanting to try a new helmet design")),
        Map.entry("weaponsmith", List.of("sharpening a batch of axes", "running low on coal for the forge", "proud of a new blade")),
        Map.entry("toolsmith", List.of("fixing the village's broken hoes", "short of iron for pickaxes", "testing a sturdier shovel")),
        Map.entry("fisherman", List.of("waiting for the fish to bite", "mending a torn net", "hoping to catch something rare")),
        Map.entry("shepherd", List.of("dyeing wool for a festival", "worried a sheep has wandered off", "short of shears")),
        Map.entry("butcher", List.of("smoking meat for winter", "short of good cuts", "planning a village feast")),
        Map.entry("cartographer", List.of("mapping the woods to the east", "curious about a ruin far away", "short of paper for maps")),
        Map.entry("fletcher", List.of("fletching arrows for the guards", "short of feathers", "testing a new bow")),
        Map.entry("leatherworker", List.of("tanning hides", "working on a fine saddle", "short of leather")),
        Map.entry("mason", List.of("repairing the village well", "polishing a stone carving", "short of clay")));
    private static final List<String> IDLE = List.of("enjoying a quiet day", "curious about the travellers passing through",
        "hoping for news from other villages");

    /** Strongest recent feeling, or calm. episodeTypes are this villager's episodes within MOOD_MS, any player. */
    static String mood(List<String> episodeTypes) {
        for (String type : PRIORITY) if (episodeTypes.contains(type)) return MOODS.get(type);
        return "calm";
    }

    /** Today's goal: changes each in-game day, differs between villagers of the same trade. */
    static String goal(String profession, UUID villager, long gameDay) {
        List<String> options = GOALS.getOrDefault(profession, IDLE);
        int seed = (int) Math.floorMod(villager.getLeastSignificantBits() + gameDay, (long) options.size());
        return options.get(seed);
    }
}
