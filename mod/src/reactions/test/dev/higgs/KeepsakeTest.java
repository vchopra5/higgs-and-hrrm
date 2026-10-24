package dev.higgs;

import java.util.ArrayList;
import java.util.List;

public final class KeepsakeTest {
    public static void main(String[] args) {
        List<Keepsake.Moment> moments = new ArrayList<>();
        moments.add(new Keepsake.Moment("first_met", "I met Steve for the first time", 2, 5, false));
        moments.add(new Keepsake.Moment("milestone", "Today Steve and I became friends", 9, 5, false));
        moments.add(new Keepsake.Moment("witnessed", "I saw Steve hit Bram", 10, 2, true));
        for (int i = 0; i < 12; i++) moments.add(new Keepsake.Moment("trade", "Steve bought bread from me (" + i + ")", 3 + i, i == 4 ? 4 : 1, false));
        moments.add(new Keepsake.Moment("quest_done", "Steve brought me the pillager banner", 12, 5, false));
        List<String> pages = Keepsake.pages("Iris", "Steve", "Steve is my dearest customer. ".repeat(15), moments);
        String all = String.join("\n", pages);
        check(pages.getFirst().startsWith("Our story\n\nWritten by Iris for Steve."), "title page");
        check(pages.stream().allMatch(page -> page.length() <= Keepsake.PAGE), "every page fits");
        check(all.contains("Day 9: Today Steve and I became friends."), "milestones with days");
        check(!all.contains("hit Bram"), "witnessed moments are left out");
        check(all.indexOf("Day 2: I met Steve") < all.indexOf("Day 12: Steve brought me"), "moments in order");
        check(all.contains("bread from me (4)") && !all.contains("bread from me (0)"), "only the most notable moments");
        long shared = all.lines().filter(line -> line.startsWith("Day ") && !line.contains("became")).count();
        check(shared == Keepsake.MOMENTS, "at most eight moments");
        check(pages.getLast().endsWith("— Iris"), "signed");
        check(Keepsake.pages("Iris", "Steve", "", List.of()).size() == 1, "a short story fits one page");
        System.out.println("Keepsake checks passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
