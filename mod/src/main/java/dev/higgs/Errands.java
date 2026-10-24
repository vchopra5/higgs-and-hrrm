package dev.higgs;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Rules for errands between villagers and for villagers who shun the player; free of Minecraft types. */
final class Errands {
    /** Reputation for a completed errand: the villager who asked, and the one who received it. */
    static final int GIVER_REWARD = 2, TARGET_REWARD = 1;
    static final int MESSAGE_LIMIT = 160;

    // Grammar, greetings and errand boilerplate: none of it tells the recipient what is actually needed.
    private static final Set<String> FILLER = Set.of(
        "a", "about", "after", "all", "am", "an", "and", "any", "are", "as", "ask", "asks", "asked", "at", "be", "by", "can", "come",
        "could", "do", "for", "from", "get", "greeting", "greetings", "had", "has", "have", "he", "hello", "help", "her", "hey", "hi",
        "him", "his", "i", "if", "important", "in", "is", "it", "its", "job", "just", "little", "matter", "me", "moment", "my", "need",
        "needs", "of", "on", "or", "our", "over", "please", "quick", "quickly", "regards", "say", "says", "see", "send", "sends", "she",
        "small", "so", "some", "something", "soon", "speak", "task", "tell", "that", "the", "their", "them", "there", "they", "thing",
        "this", "time", "to", "talk", "up", "urgent", "us", "village", "visit", "wants", "was", "we", "when", "will", "with", "word",
        "would", "you", "your", "favour", "favor", "errand", "chat", "drop", "stop");

    private Errands() {}

    /**
     * A message the recipient could not act on: "Cedric says hello and needs help with a small task" leaves them asking
     * what the task is. Concrete messages name at least two real things (wolves, fence, storm, bread, sundown).
     */
    static boolean vague(String message, String giverName) {
        Set<String> names = Set.copyOf(Arrays.asList(giverName.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        return Arrays.stream(message.toLowerCase(Locale.ROOT).replaceAll("['’]s\\b", "").split("[^\\p{L}\\p{N}]+"))
            .filter(word -> word.length() > 1 && !FILLER.contains(word) && !names.contains(word)).distinct().count() < 2;
    }

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
