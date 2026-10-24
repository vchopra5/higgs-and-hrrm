package dev.higgs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch quests a villager may set instead of accepting a lowball offer. Asking for a bigger discount earns a harder
 * errand; themed items come first, then general ones. The model picks one option; the server checks delivery.
 */
final class QuestCatalogue {
    /** A registry item id, or {@link #OMINOUS_BANNER} for the raid captain's banner (a component-tagged white banner). */
    record Item(String key, int count, String name) {}
    static final String OMINOUS_BANNER = "ominous_banner";
    /** Offers below this share of the normal price are refused outright: no errand is worth that. */
    static final int MIN_OFFER_PERCENT = 40;
    static final int MAX_OPTIONS = 4;

    private static final Map<Integer, List<Item>> GENERAL = Map.of(
        1, List.of(new Item("minecraft:wheat", 16, "Wheat"), new Item("minecraft:coal", 8, "Coal"),
            new Item("minecraft:bread", 6, "Bread"), new Item("minecraft:leather", 4, "Leather")),
        2, List.of(new Item("minecraft:iron_ingot", 6, "Iron Ingot"), new Item("minecraft:gold_ingot", 4, "Gold Ingot"),
            new Item("minecraft:honey_bottle", 3, "Honey Bottle"), new Item("minecraft:ender_pearl", 1, "Ender Pearl")),
        3, List.of(new Item(OMINOUS_BANNER, 1, "Ominous Banner"), new Item("minecraft:diamond", 2, "Diamond"),
            new Item("minecraft:blaze_rod", 3, "Blaze Rod"), new Item("minecraft:ender_pearl", 4, "Ender Pearl")));

    private static final Map<String, Map<Integer, List<Item>>> THEMED = Map.ofEntries(
        Map.entry("weaponsmith", tiers(List.of(new Item("minecraft:coal", 12, "Coal"), new Item("minecraft:flint", 8, "Flint")),
            List.of(new Item("minecraft:iron_ingot", 8, "Iron Ingot")),
            List.of(new Item(OMINOUS_BANNER, 1, "Ominous Banner"), new Item("minecraft:diamond", 3, "Diamond")))),
        Map.entry("armorer", tiers(List.of(new Item("minecraft:coal", 12, "Coal")),
            List.of(new Item("minecraft:iron_ingot", 10, "Iron Ingot")),
            List.of(new Item(OMINOUS_BANNER, 1, "Ominous Banner"), new Item("minecraft:diamond", 3, "Diamond")))),
        Map.entry("toolsmith", tiers(List.of(new Item("minecraft:coal", 12, "Coal"), new Item("minecraft:stick", 32, "Stick")),
            List.of(new Item("minecraft:iron_ingot", 8, "Iron Ingot")),
            List.of(new Item("minecraft:diamond", 2, "Diamond")))),
        Map.entry("librarian", tiers(List.of(new Item("minecraft:paper", 12, "Paper"), new Item("minecraft:ink_sac", 4, "Ink Sac")),
            List.of(new Item("minecraft:book", 6, "Book"), new Item("minecraft:feather", 8, "Feather")),
            List.of(new Item("minecraft:ender_pearl", 4, "Ender Pearl"), new Item("minecraft:amethyst_shard", 8, "Amethyst Shard")))),
        Map.entry("cleric", tiers(List.of(new Item("minecraft:rotten_flesh", 16, "Rotten Flesh")),
            List.of(new Item("minecraft:gold_ingot", 4, "Gold Ingot"), new Item("minecraft:redstone", 16, "Redstone Dust")),
            List.of(new Item("minecraft:rabbit_foot", 2, "Rabbit's Foot"), new Item("minecraft:blaze_rod", 3, "Blaze Rod")))),
        Map.entry("farmer", tiers(List.of(new Item("minecraft:wheat", 20, "Wheat"), new Item("minecraft:carrot", 16, "Carrot")),
            List.of(new Item("minecraft:pumpkin", 6, "Pumpkin"), new Item("minecraft:melon_slice", 16, "Melon Slice")),
            List.of(new Item("minecraft:golden_carrot", 8, "Golden Carrot")))),
        Map.entry("fisherman", tiers(List.of(new Item("minecraft:cod", 8, "Raw Cod")),
            List.of(new Item("minecraft:salmon", 8, "Raw Salmon"), new Item("minecraft:string", 12, "String")),
            List.of(new Item("minecraft:tropical_fish", 3, "Tropical Fish")))),
        Map.entry("shepherd", tiers(List.of(new Item("minecraft:white_wool", 12, "White Wool")),
            List.of(new Item("minecraft:string", 16, "String")),
            List.of())),
        Map.entry("butcher", tiers(List.of(new Item("minecraft:porkchop", 8, "Raw Porkchop"), new Item("minecraft:chicken", 8, "Raw Chicken")),
            List.of(new Item("minecraft:rabbit", 4, "Raw Rabbit")),
            List.of())),
        Map.entry("cartographer", tiers(List.of(new Item("minecraft:paper", 16, "Paper")),
            List.of(new Item("minecraft:compass", 1, "Compass")),
            List.of(new Item(OMINOUS_BANNER, 1, "Ominous Banner")))),
        Map.entry("fletcher", tiers(List.of(new Item("minecraft:stick", 32, "Stick"), new Item("minecraft:flint", 10, "Flint")),
            List.of(new Item("minecraft:feather", 16, "Feather"), new Item("minecraft:string", 12, "String")),
            List.of())),
        Map.entry("leatherworker", tiers(List.of(new Item("minecraft:leather", 8, "Leather")),
            List.of(new Item("minecraft:rabbit_hide", 8, "Rabbit Hide")),
            List.of())),
        Map.entry("mason", tiers(List.of(new Item("minecraft:clay_ball", 16, "Clay Ball")),
            List.of(new Item("minecraft:quartz", 16, "Nether Quartz")),
            List.of())));

    private QuestCatalogue() {}

    private static Map<Integer, List<Item>> tiers(List<Item> easy, List<Item> medium, List<Item> hard) {
        return Map.of(1, easy, 2, medium, 3, hard);
    }

    /** 1 for a small ask (85%+ of the normal price), 2 for a fair one (65%+), 3 for a steep one. */
    static int tier(int offerPercent) { return offerPercent >= 85 ? 1 : offerPercent >= 65 ? 2 : 3; }

    /** Errands for an offer at offerPercent of the normal price; empty when no quest fits. */
    static List<Item> options(String profession, int offerPercent) {
        if (offerPercent < MIN_OFFER_PERCENT || offerPercent >= 100) return List.of();
        int tier = tier(offerPercent);
        Map<String, Item> unique = new LinkedHashMap<>();
        List<Item> candidates = new ArrayList<>(THEMED.getOrDefault(profession, Map.of()).getOrDefault(tier, List.of()));
        candidates.addAll(GENERAL.get(tier));
        for (Item item : candidates) unique.putIfAbsent(item.key(), item);
        return List.copyOf(unique.values()).subList(0, Math.min(MAX_OPTIONS, unique.size()));
    }
}
