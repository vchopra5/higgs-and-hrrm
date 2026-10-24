package dev.higgs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The text of "Our story", a book a villager writes for a close friend: the relationship summary, the milestones with
 * their in-game days, and the most notable moments they shared, in order. Built from the journal alone.
 */
final class Keepsake {
    record Moment(String type, String text, long day, int salience, boolean witnessed) {}
    /** Roughly what fits on one written-book page. */
    static final int PAGE = 220, MOMENTS = 8;
    private static final Set<String> SKIPPED = Set.of("milestone", "keepsake", "chatter_heard", "witnessed", "overheard", "shunned");

    static List<String> pages(String author, String player, String summary, List<Moment> moments) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("Our story\n\nWritten by " + author + " for " + player + ".");
        if (summary != null && !summary.isBlank()) paragraphs.add(summary.trim());
        List<Moment> milestones = moments.stream().filter(m -> m.type().equals("milestone"))
            .sorted(Comparator.comparingLong(Moment::day)).toList();
        if (!milestones.isEmpty()) {
            paragraphs.add("Milestones");
            milestones.forEach(m -> paragraphs.add("Day " + m.day() + ": " + m.text() + "."));
        }
        List<Moment> notable = moments.stream().filter(m -> !m.witnessed() && !SKIPPED.contains(m.type()))
            .sorted(Comparator.comparingInt(Moment::salience).reversed().thenComparing(Comparator.comparingLong(Moment::day).reversed()))
            .limit(MOMENTS).sorted(Comparator.comparingLong(Moment::day)).toList();
        if (!notable.isEmpty()) {
            paragraphs.add("Moments we shared");
            notable.forEach(m -> paragraphs.add("Day " + m.day() + ": " + m.text() + "."));
        }
        paragraphs.add("May there be many more.\n\n— " + author);
        return paginate(paragraphs);
    }

    /** Whole paragraphs per page where they fit; a long one is split between words. */
    static List<String> paginate(List<String> paragraphs) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String paragraph : paragraphs) {
            for (String piece : split(paragraph.replace("..", "."))) {
                if (page.length() > 0 && page.length() + 2 + piece.length() > PAGE) { pages.add(page.toString()); page.setLength(0); }
                if (page.length() > 0) page.append("\n\n");
                page.append(piece);
            }
        }
        if (page.length() > 0) pages.add(page.toString());
        return pages;
    }

    private static List<String> split(String paragraph) {
        List<String> pieces = new ArrayList<>();
        String rest = paragraph;
        while (rest.length() > PAGE) {
            int cut = rest.lastIndexOf(' ', PAGE);
            if (cut <= 0) cut = PAGE;
            pieces.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) pieces.add(rest);
        return pieces;
    }
}
