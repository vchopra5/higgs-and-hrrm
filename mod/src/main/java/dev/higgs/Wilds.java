package dev.higgs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Places beyond the village that villagers know of by rumour: landscapes ("the nearest forest", "the sea") and
 * structures ("a desert temple", "buried treasure"). Turns the player's words into what to look for; the world search
 * lives in {@link Wilderness}. Pure, so the matching is testable without the game.
 */
final class Wilds {
    enum Kind { BIOME, BIOME_TAG, STRUCTURE, STRUCTURE_TAG, OTHER_VILLAGE }
    /** id is a vanilla biome, biome tag, structure or structure tag path, e.g. "is_forest" or "desert_pyramid". */
    record Place(String label, Kind kind, String id) {}
    private record Entry(String phrase, Place place) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static void add(Place place, String... phrases) { for (String phrase : phrases) ENTRIES.add(new Entry(phrase, place)); }
    static {
        add(new Place("a forest", Kind.BIOME_TAG, "is_forest"), "forest", "woods", "woodland", "wood");
        add(new Place("a birch forest", Kind.BIOME, "birch_forest"), "birch forest", "birch wood", "birch woods");
        add(new Place("a dark forest", Kind.BIOME, "dark_forest"), "dark forest", "roofed forest", "dark oak forest");
        add(new Place("a flower forest", Kind.BIOME, "flower_forest"), "flower forest", "flower field", "flower fields");
        add(new Place("a jungle", Kind.BIOME_TAG, "is_jungle"), "jungle", "rainforest");
        add(new Place("a taiga", Kind.BIOME_TAG, "is_taiga"), "taiga", "pine forest", "spruce forest", "boreal forest");
        add(new Place("the sea", Kind.BIOME_TAG, "is_ocean"), "ocean", "sea", "open water");
        add(new Place("a beach", Kind.BIOME_TAG, "is_beach"), "beach", "shore", "coast", "seaside");
        add(new Place("a river", Kind.BIOME_TAG, "is_river"), "river", "stream");
        add(new Place("the mountains", Kind.BIOME_TAG, "is_mountain"), "mountain", "mountains", "peak", "peaks");
        add(new Place("a desert", Kind.BIOME, "desert"), "desert", "dunes", "sand dunes");
        add(new Place("a swamp", Kind.BIOME, "swamp"), "swamp", "marsh", "bog", "wetlands");
        add(new Place("a mangrove swamp", Kind.BIOME, "mangrove_swamp"), "mangrove", "mangroves", "mangrove swamp");
        add(new Place("open plains", Kind.BIOME, "plains"), "plains", "grassland", "grasslands", "prairie");
        add(new Place("a meadow", Kind.BIOME, "meadow"), "meadow");
        add(new Place("snowy plains", Kind.BIOME, "snowy_plains"), "snowy plains", "tundra", "snowfields", "snow");
        add(new Place("the ice spikes", Kind.BIOME, "ice_spikes"), "ice spikes");
        add(new Place("the badlands", Kind.BIOME_TAG, "is_badlands"), "badlands", "mesa", "canyon", "canyons");
        add(new Place("a savanna", Kind.BIOME_TAG, "is_savanna"), "savanna", "savannah");
        add(new Place("a cherry grove", Kind.BIOME, "cherry_grove"), "cherry grove", "cherry blossom", "cherry blossoms", "cherry trees");
        add(new Place("a mushroom island", Kind.BIOME, "mushroom_fields"), "mushroom island", "mushroom fields", "mooshroom");
        add(new Place("a desert temple", Kind.STRUCTURE, "desert_pyramid"), "pyramid", "desert temple", "desert pyramid", "sand temple");
        add(new Place("a jungle temple", Kind.STRUCTURE, "jungle_pyramid"), "jungle temple", "jungle pyramid");
        add(new Place("an igloo", Kind.STRUCTURE, "igloo"), "igloo");
        add(new Place("a witch's hut", Kind.STRUCTURE, "swamp_hut"), "witch hut", "witch's hut", "witches hut", "swamp hut", "witch");
        add(new Place("a shipwreck", Kind.STRUCTURE_TAG, "shipwreck"), "shipwreck", "wreck", "sunken ship");
        add(new Place("an ocean monument", Kind.STRUCTURE, "monument"), "ocean monument", "monument", "guardians", "sea temple");
        add(new Place("a woodland mansion", Kind.STRUCTURE, "mansion"), "mansion", "woodland mansion");
        add(new Place("a pillager outpost", Kind.STRUCTURE, "pillager_outpost"), "outpost", "pillager outpost", "pillager tower", "pillagers");
        add(new Place("buried treasure", Kind.STRUCTURE, "buried_treasure"), "buried treasure", "hidden treasure", "treasure",
            "hidden chest", "treasure chest", "buried chest");
        add(new Place("a ruined portal", Kind.STRUCTURE_TAG, "ruined_portal"), "ruined portal", "portal", "nether portal", "old portal");
        add(new Place("ocean ruins", Kind.STRUCTURE_TAG, "ocean_ruin"), "ocean ruin", "ocean ruins", "sunken ruins", "underwater ruins");
        add(new Place("an abandoned mineshaft", Kind.STRUCTURE_TAG, "mineshaft"), "mineshaft", "mine shaft", "abandoned mine", "old mine");
        add(new Place("trail ruins", Kind.STRUCTURE, "trail_ruins"), "trail ruins", "ruins", "buried ruins");
        add(new Place("the trial chambers", Kind.STRUCTURE, "trial_chambers"), "trial chamber", "trial chambers");
        add(new Place("another village", Kind.OTHER_VILLAGE, "village"), "another village", "other village", "next village",
            "nearest village", "neighbouring village", "neighboring village", "different village", "other town", "another town");
        // Longest phrases first, so "desert temple" beats "desert" and "birch forest" beats "forest".
        ENTRIES.sort(Comparator.comparingInt((Entry e) -> e.phrase().length()).reversed());
    }

    private Wilds() {}

    /** What the player is asking about, or null when it is nothing beyond the village. */
    static Place interpret(String request) {
        String text = request == null ? "" : request.toLowerCase(Locale.ROOT).replace('’', '\'');
        for (Entry entry : ENTRIES) {
            if (Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(entry.phrase()) + "s?([^\\p{L}]|$)").matcher(text).find()) return entry.place();
        }
        return null;
    }

    /** A spoken distance: rounded the way someone passing on a rumour would. */
    /**
     * How far a villager will walk the player out to a place it knows of: the better it likes them, the farther. Even a
     * stranger walks them a short way; a confidant, or one with the highest opinion of them, goes anywhere it knows of.
     */
    static int escortBlocks(RelationshipStage stage, int standing) {
        if (stage == RelationshipStage.CONFIDANT || standing >= 5) return Integer.MAX_VALUE;
        return switch (stage) {
            case STRANGER -> 64;
            case ACQUAINTANCE -> 128;
            case FRIEND -> 320;
            default -> 640;
        };
    }

    static String distance(double blocks) {
        long rounded = blocks < 100 ? Math.max(10, Math.round(blocks / 10) * 10) : Math.round(blocks / 50) * 50;
        return "about " + rounded + " blocks";
    }
}
