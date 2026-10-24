package dev.higgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Each villager's taste in gifts. Preferences derive from the villager's UUID and profession, so they never change
 * and need no storage: two loved items (one from their trade, one treat) and two they refuse.
 */
final class GiftCatalogue {
    enum Verdict { LOVED, PRECIOUS, LIKED, JUNK, DISLIKED }
    record Preferences(List<String> loves, List<String> dislikes) {}

    private static final Map<String, List<String>> THEMED = Map.ofEntries(
        Map.entry("farmer", List.of("minecraft:cake", "minecraft:golden_carrot")),
        Map.entry("librarian", List.of("minecraft:book", "minecraft:enchanted_book")),
        Map.entry("cleric", List.of("minecraft:glowstone_dust", "minecraft:amethyst_shard")),
        Map.entry("armorer", List.of("minecraft:iron_ingot", "minecraft:diamond")),
        Map.entry("weaponsmith", List.of("minecraft:iron_ingot", "minecraft:diamond")),
        Map.entry("toolsmith", List.of("minecraft:iron_ingot", "minecraft:diamond")),
        Map.entry("fisherman", List.of("minecraft:cooked_cod", "minecraft:tropical_fish")),
        Map.entry("shepherd", List.of("minecraft:white_wool", "minecraft:shears")),
        Map.entry("butcher", List.of("minecraft:cooked_porkchop", "minecraft:cooked_beef")),
        Map.entry("cartographer", List.of("minecraft:compass", "minecraft:map")),
        Map.entry("fletcher", List.of("minecraft:feather", "minecraft:arrow")),
        Map.entry("leatherworker", List.of("minecraft:leather", "minecraft:rabbit_hide")),
        Map.entry("mason", List.of("minecraft:quartz", "minecraft:amethyst_shard")));
    private static final List<String> TREATS = List.of("minecraft:cookie", "minecraft:cake", "minecraft:honey_bottle",
        "minecraft:sweet_berries", "minecraft:pumpkin_pie", "minecraft:poppy", "minecraft:dandelion",
        "minecraft:cornflower", "minecraft:glow_berries", "minecraft:golden_apple");
    private static final List<String> DISLIKE_POOL = List.of("minecraft:rotten_flesh", "minecraft:poisonous_potato",
        "minecraft:spider_eye", "minecraft:pufferfish", "minecraft:beetroot", "minecraft:dead_bush", "minecraft:bone",
        "minecraft:slime_ball", "minecraft:gunpowder", "minecraft:kelp", "minecraft:ink_sac", "minecraft:pumpkin");
    /** Valuable to anyone: a generous gift even when it is not a favourite. */
    private static final Set<String> PRECIOUS = Set.of("minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot",
        "minecraft:netherite_ingot", "minecraft:enchanted_book", "minecraft:golden_apple", "minecraft:enchanted_golden_apple",
        "minecraft:amethyst_shard", "minecraft:totem_of_undying", "minecraft:nether_star");
    /** Pleasant for anyone: food, flowers, useful materials. */
    private static final Set<String> VALUED = Set.of("minecraft:iron_ingot", "minecraft:cake", "minecraft:bread", "minecraft:apple", "minecraft:cookie", "minecraft:pumpkin_pie",
        "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken", "minecraft:cooked_mutton",
        "minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:baked_potato", "minecraft:honey_bottle",
        "minecraft:sweet_berries", "minecraft:glow_berries", "minecraft:poppy", "minecraft:dandelion",
        "minecraft:cornflower", "minecraft:book", "minecraft:golden_carrot");

    private GiftCatalogue() {}

    static Preferences preferences(UUID villager, String profession) {
        SplittableRandom random = new SplittableRandom(villager.getMostSignificantBits() ^ villager.getLeastSignificantBits());
        List<String> themed = THEMED.getOrDefault(profession, TREATS);
        List<String> loves = new ArrayList<>();
        loves.add(themed.get(random.nextInt(themed.size())));
        while (loves.size() < 2) {
            String treat = TREATS.get(random.nextInt(TREATS.size()));
            if (!loves.contains(treat)) loves.add(treat);
        }
        List<String> dislikes = new ArrayList<>();
        while (dislikes.size() < 2) {
            String item = DISLIKE_POOL.get(random.nextInt(DISLIKE_POOL.size()));
            if (!dislikes.contains(item) && !loves.contains(item)) dislikes.add(item);
        }
        return new Preferences(List.copyOf(loves), List.copyOf(dislikes));
    }

    static Verdict judge(Preferences preferences, String item) {
        if (preferences.dislikes().contains(item)) return Verdict.DISLIKED;
        if (preferences.loves().contains(item)) return Verdict.LOVED;
        return PRECIOUS.contains(item) ? Verdict.PRECIOUS : VALUED.contains(item) ? Verdict.LIKED : Verdict.JUNK;
    }

    /** Reputation a gift earns, before the per-villager cooldown. */
    static int gain(Verdict verdict) {
        return switch (verdict) { case LOVED -> 3; case PRECIOUS -> 2; case LIKED -> 1; default -> 0; };
    }

    /**
     * New standing after a gift. Gains wait out a cooldown; offering an item already refused costs a point. Neither
     * pushes a villager into hostility, which only the player's direct actions cause.
     */
    static int standingAfter(int standing, Verdict verdict, boolean refusedBefore, boolean onCooldown) {
        int next = verdict == Verdict.DISLIKED ? (refusedBefore ? standing - 1 : standing)
            : onCooldown ? standing : standing + gain(verdict);
        if (next >= standing) return Math.min(5, next);
        return Math.max(Math.min(standing, Negotiation.HOSTILE + 1), next);
    }
}
