package dev.higgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns "take me to the librarian" or "where's the bell?" into ranked guesses: a villager by name, villagers of a trade,
 * or a landmark (the meeting bell, a trade's workstation). Pure; the world lookup lives in the server.
 */
final class Destinations {
    enum Kind { VILLAGER, PROFESSION, LANDMARK }
    /** value: a villager name, a profession id, or a landmark id ("meeting" or a profession whose workstation is meant). */
    record Guess(Kind kind, String value, String label) {}

    private static final Map<String, List<String>> PROFESSIONS = Map.ofEntries(
        Map.entry("librarian", List.of("librarian")), Map.entry("farmer", List.of("farmer")),
        Map.entry("cleric", List.of("cleric", "priest", "healer")), Map.entry("armorer", List.of("armorer", "armourer")),
        Map.entry("weaponsmith", List.of("weaponsmith")), Map.entry("toolsmith", List.of("toolsmith")),
        Map.entry("fisherman", List.of("fisherman", "fishermen", "fisher")), Map.entry("shepherd", List.of("shepherd")),
        Map.entry("butcher", List.of("butcher")), Map.entry("cartographer", List.of("cartographer", "mapmaker")),
        Map.entry("fletcher", List.of("fletcher", "bowyer")), Map.entry("leatherworker", List.of("leatherworker", "tanner")),
        Map.entry("mason", List.of("mason", "stonemason")));
    private static final List<String> SMITHS = List.of("armorer", "weaponsmith", "toolsmith");
    /** Landmarks: the meeting bell, or a trade's workplace by its block or its building. */
    private static final Map<String, List<String>> LANDMARKS = Map.ofEntries(
        Map.entry("meeting", List.of("bell", "meeting", "square", "village centre", "village center", "town centre", "town center", "plaza")),
        Map.entry("librarian", List.of("library", "lectern")), Map.entry("farmer", List.of("composter", "farm", "fields", "garden")),
        Map.entry("cleric", List.of("church", "temple", "brewing stand", "chapel")), Map.entry("armorer", List.of("blast furnace", "armoury", "armory")),
        Map.entry("weaponsmith", List.of("grindstone")), Map.entry("toolsmith", List.of("smithing table")),
        Map.entry("fisherman", List.of("barrel", "docks", "dock", "fishing hut")), Map.entry("shepherd", List.of("loom")),
        Map.entry("butcher", List.of("smoker", "butcher shop", "butchery")), Map.entry("cartographer", List.of("cartography table", "map room")),
        Map.entry("fletcher", List.of("fletching table")), Map.entry("leatherworker", List.of("cauldron", "tannery")),
        Map.entry("mason", List.of("stonecutter", "quarry")));

    private Destinations() {}

    private static boolean says(String text, String phrase) {
        return Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(phrase) + "s?([^\\p{L}]|$)").matcher(text).find();
    }

    /** Guesses, best first. names are the villagers the guide knows of (it is not one of them). */
    static List<Guess> interpret(String request, List<String> names) {
        String text = request == null ? "" : request.toLowerCase(Locale.ROOT).replace('’', '\'');
        List<Guess> guesses = new ArrayList<>();
        for (String name : names) {
            String call = name.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (call.length() > 1 && says(text, call)) guesses.add(new Guess(Kind.VILLAGER, name, name));
        }
        // Buildings and blocks before trades: "the library" is a place even though a librarian works there.
        for (var entry : LANDMARKS.entrySet()) {
            for (String phrase : entry.getValue()) {
                if (says(text, phrase)) {
                    guesses.add(new Guess(Kind.LANDMARK, entry.getKey(), entry.getKey().equals("meeting") ? "the village bell" : "the " + phrase));
                    break;
                }
            }
        }
        for (var entry : PROFESSIONS.entrySet()) {
            for (String word : entry.getValue()) {
                if (says(text, word)) { guesses.add(new Guess(Kind.PROFESSION, entry.getKey(), "the " + entry.getKey())); break; }
            }
        }
        if (says(text, "smith") || says(text, "blacksmith")) for (String smith : SMITHS) guesses.add(new Guess(Kind.PROFESSION, smith, "the " + smith));
        return guesses;
    }
}
