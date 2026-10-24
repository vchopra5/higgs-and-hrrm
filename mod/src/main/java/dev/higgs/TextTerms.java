package dev.higgs;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared lexical analysis: memory retrieval and checking that a player passed on a message. */
final class TextTerms {
    // Query grammar is not evidence. Keep content words (including negation and time words),
    // and apply the same analysis to queries and documents. This is deliberately lexical:
    // synonyms and inferred facts need a separate semantic retrieval stage.
    static final Set<String> STOP_WORDS = Set.of(
        "a", "about", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by", "can", "could",
        "did", "do", "does", "for", "from", "had", "has", "have", "he", "her", "his", "how", "i",
        "if", "in", "is", "it", "its", "me", "my", "news", "of", "on", "or", "our", "please",
        "recall", "remember", "said", "say", "says", "she", "should", "so", "some", "tell", "than",
        "that", "the", "their", "them", "there", "these", "they", "this", "those", "to", "us", "was",
        "we", "were", "what", "when", "where", "which", "who", "why", "will", "with", "would", "you", "your");

    private TextTerms() {}

    static List<String> words(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("(?<=\\p{L})['’]s\\b", "");
        return Arrays.stream(normalized.split("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter(word -> !word.isEmpty() && !STOP_WORDS.contains(word)).toList();
    }

    /** "wolves" and "wolf", "returned" and "return" compare equal enough for a spoken paraphrase. */
    static String stem(String word) {
        if (word.length() > 4 && word.endsWith("ves")) return word.substring(0, word.length() - 3) + "f";
        if (word.length() > 4 && word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
        if (word.length() > 4 && word.endsWith("ing")) return word.substring(0, word.length() - 3);
        // "seed" and "need" are not past tenses.
        if (word.length() > 3 && word.endsWith("ed") && !word.endsWith("eed")) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) return word.substring(0, word.length() - 1);
        return word;
    }

    private static Set<String> stems(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String word : words(text)) result.add(stem(word));
        return result;
    }

    /** Content words two phrases share: "wheat seeds" and "Wheat Seeds" share two; "wheat seeds" and "Apple" none. */
    static int overlap(String a, String b) {
        Set<String> left = stems(a);
        left.retainAll(stems(b));
        return left.size();
    }

    /**
     * Whether what the player said carries the message they were asked to pass on: at least half of its content
     * words, or the sender's name plus one of them. Paraphrase is fine; an unrelated sentence is not. The sender's
     * name never counts as a content word, so "where is Cedric?" does not carry "Cedric says hello...".
     */
    static boolean conveys(String said, String message, String sender) {
        Set<String> expected = stems(message), heard = stems(said);
        expected.removeAll(stems(sender));
        if (expected.isEmpty()) return !heard.isEmpty();
        long matched = expected.stream().filter(heard::contains).count();
        boolean namesSender = !stems(sender).isEmpty() && heard.containsAll(stems(sender.split("\\s+")[0]));
        return matched * 2 >= expected.size() || (namesSender && matched >= 1);
    }
}
