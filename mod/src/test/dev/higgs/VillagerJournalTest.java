package dev.higgs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class VillagerJournalTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("journal-test");
        Path file = dir.resolve("higgs-journal.json");
        UUID iris = UUID.randomUUID(), bram = UUID.randomUUID(), steve = UUID.randomUUID(), alex = UUID.randomUUID();
        long day = 24L * 60 * 60 * 1000, now = 1_000_000_000L;
        var limits = new VillagerJournal.Limits(5, 100, 30 * day);
        var journal = new VillagerJournal(file, limits);
        journal.record(iris, steve, "trade", "Steve bought 2 × Iron Axe from me for 5 × Emerald", 10, now, 3, false, 0);
        journal.record(iris, steve, "gift", "Steve gave me a Diamond; I was very grateful", 11, now + 1000, 4, false, 0);
        journal.record(iris, steve, "hit", "Steve hit me", 12, now + 2000, 4, false, 60_000);
        journal.record(iris, steve, "hit", "Steve hit me 3 times", 12, now + 5000, 3, false, 60_000);
        check(journal.all(iris, steve, now + 5000).stream().filter(e -> e.type().equals("hit")).count() == 1, "hits within a minute merge");
        check(journal.all(iris, steve, now + 5000).stream().anyMatch(e -> e.text().equals("Steve hit me 3 times") && e.salience() == 4), "merge keeps the higher salience");
        check(journal.all(bram, steve, now).isEmpty() && journal.all(iris, alex, now).isEmpty(), "pairs are isolated");
        check(journal.search(iris, steve, "what did I buy, the axe?", now + 6000, 3).getFirst().type().equals("trade"), "search finds the trade");
        check(journal.search(iris, steve, "weather", now + 6000, 3).isEmpty(), "unrelated query finds nothing");
        // The cap evicts the least important, oldest first.
        for (int i = 0; i < 4; i++) journal.record(iris, steve, "kindness", "Steve was kind " + i, 13, now + 10_000 + i, 1, false, 0);
        var kept = journal.all(iris, steve, now + 20_000);
        check(kept.size() == 5, "per-pair cap");
        check(kept.stream().anyMatch(e -> e.type().equals("gift")) && kept.stream().anyMatch(e -> e.type().equals("trade")), "important episodes survive the cap");
        check(journal.counts(iris, steve, now + 20_000).get("kindness") == 2, "counts by type");
        var recent = journal.recent(iris, steve, now + 20_000, 2);
        check(recent.size() == 2 && recent.getFirst().at() <= recent.get(1).at(), "recent is chronological");
        // Expiry: routine fades after the lifetime, important events last longer; the summary never expires.
        journal.summarise(iris, steve, "Steve is a regular who once hit me but made up for it with a diamond.", now);
        long later = now + 31 * day;
        check(journal.all(iris, steve, later).stream().noneMatch(e -> e.salience() <= 3), "routine episodes fade after 30 days");
        check(journal.all(iris, steve, later).stream().anyMatch(e -> e.type().equals("gift")), "salience 4 lasts twice as long");
        check(journal.all(iris, steve, now + 61 * day).isEmpty(), "but not forever");
        check(journal.summary(iris, steve).text().startsWith("Steve is a regular"), "summary kept");
        journal.close();
        // Restart: everything round-trips.
        var reopened = new VillagerJournal(file, limits);
        check(reopened.all(iris, steve, now + 20_000).size() == 5, "episodes survive restart");
        check(reopened.summary(iris, steve) != null && reopened.summary(iris, steve).updatedAt() == now, "summary survives restart");
        check(reopened.record(iris, steve, "gift", "   ", 1, now, 3, false, 0) == null, "blank text is ignored");
        reopened.close();
        Files.writeString(file, "{\"schemaVersion\":9}");
        boolean rejected = false;
        try { new VillagerJournal(file, limits); } catch (java.io.IOException e) { rejected = true; }
        check(rejected, "unknown schema is rejected rather than overwritten");
        System.out.println("Villager journal checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
