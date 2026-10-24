package dev.higgs;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Deterministic retrieval evaluation for observed episodes (trades, gifts, quests…), using a real persisted journal.
 * The frozen baseline is the first journal search: text words only, no word endings, no event types.
 * Synthetic and small: a development regression check, not a general estimate of performance.
 */
public final class VillagerJournalBenchmark {
    private static final UUID IRIS = new UUID(0, 1), BRAM = new UUID(0, 2), STEVE = new UUID(1, 1), ALEX = new UUID(1, 2);
    private static final long NOW = 1_000_000;
    private record Query(String id, String category, String text, Set<String> expected) {}
    private record Result(String id, String category, String query, Set<String> expected, List<String> baseline, List<String> revised) {}
    private record Metrics(int answerable, int unanswerable, double top1, double meanReciprocalRank, double recallAt3, double abstention) {}
    private record Report(String suite, String methodology, String limitations, Metrics baseline, Metrics revised,
                          Map<String, Boolean> invariants, List<Result> cases) {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("higgs-journal-benchmark-");
        Path file = directory.resolve("journal.json");
        Map<UUID, String> labels = new HashMap<>();
        List<Result> results = new ArrayList<>();
        Map<String, Boolean> invariants = new LinkedHashMap<>();
        var limits = VillagerJournal.Limits.defaults();
        try (var journal = new VillagerJournal(file, limits)) {
            populate(journal, labels);
            for (Query query : queries()) {
                List<String> baseline = baseline(journal.all(IRIS, STEVE, NOW), query.text).stream().map(e -> labels.get(e.id())).toList();
                List<String> revised = journal.search(IRIS, STEVE, query.text, NOW, 6).stream().map(e -> labels.get(e.id())).toList();
                results.add(new Result(query.id, query.category, query.text, query.expected, baseline, revised));
            }
            boolean otherPlayer = true, otherVillager = true;
            for (Query query : queries()) {
                for (var episode : journal.search(IRIS, STEVE, query.text, NOW, 48)) {
                    otherPlayer &= episode.playerId().equals(STEVE);
                    otherVillager &= episode.villagerId().equals(IRIS);
                }
            }
            invariants.put("otherPlayersEpisodesExcluded", otherPlayer);
            invariants.put("otherVillagersEpisodesExcluded", otherVillager);
        }
        try (var restored = new VillagerJournal(file, limits)) {
            boolean identical = true;
            for (int i = 0; i < queries().size(); i++) {
                identical &= restored.search(IRIS, STEVE, queries().get(i).text, NOW, 6).stream().map(e -> labels.get(e.id())).toList()
                    .equals(results.get(i).revised);
            }
            invariants.put("rankingsSurviveRestart", identical);
        }
        Report report = new Report("journal-retrieval-v1",
            "One villager's journal with 13 episodes of different types for one player, plus decoys for another player and another "
                + "villager. Questions are phrased as a player would ask. Top-1 counts the first result in the expected set; abstention "
                + "counts unanswerable questions that return nothing.",
            "Synthetic, author-written questions; lexical retrieval cannot answer paraphrases with no shared or type words.",
            metrics(results, false), metrics(results, true), invariants, results);
        Path out = Path.of(args.length > 0 ? args[0] : "journal-benchmark.json");
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println("Journal benchmark report: " + out.toAbsolutePath());
        System.out.printf(Locale.ROOT, "Journal retrieval benchmark (%d questions): top1 %.3f -> %.3f; MRR %.3f -> %.3f; recall@3 %.3f -> %.3f; abstention %.3f -> %.3f%n",
            results.size(), report.baseline.top1, report.revised.top1, report.baseline.meanReciprocalRank, report.revised.meanReciprocalRank,
            report.baseline.recallAt3, report.revised.recallAt3, report.baseline.abstention, report.revised.abstention);
        System.out.println("Journal invariants: " + invariants);
        for (var entry : invariants.entrySet()) if (!entry.getValue()) throw new AssertionError("Invariant failed: " + entry.getKey());
        // Regression floor, so a change that loses retrieval quality fails the build.
        if (report.revised.top1 < 0.8 || report.revised.abstention < 1.0) throw new AssertionError("Journal retrieval regressed");
    }

    private static void populate(VillagerJournal journal, Map<UUID, String> labels) {
        add(journal, labels, "trade", IRIS, STEVE, "trade", "Steve bought 2 × Iron Axe from me for 5 × Emerald", 3);
        add(journal, labels, "gift", IRIS, STEVE, "gift", "Steve gave me Diamond; a generous gift", 4);
        add(journal, labels, "refused", IRIS, STEVE, "gift_refused", "Steve offered me Rotten Flesh; I refused it", 3);
        add(journal, labels, "quest_set", IRIS, STEVE, "quest_set", "I asked Steve to bring me 1 × Ominous Banner before I would trade at their price", 4);
        add(journal, labels, "quest_done", IRIS, STEVE, "quest_done", "Steve brought me the 1 × Ominous Banner I asked for, as promised", 5);
        add(journal, labels, "errand", IRIS, STEVE, "errand_received", "Steve brought me word from Bram: \"the wolves are back\"", 4);
        add(journal, labels, "hit", IRIS, STEVE, "hit", "Steve hit me 3 times", 4);
        add(journal, labels, "kind", IRIS, STEVE, "kindness", "Steve said something kind to me", 2);
        add(journal, labels, "met", IRIS, STEVE, "first_met", "I met Steve for the first time", 5);
        add(journal, labels, "talk", IRIS, STEVE, "conversation", "Steve told me about a hidden cave behind the waterfall and asked about my books", 3);
        add(journal, labels, "promise", IRIS, STEVE, "promise", "Steve promised to deal with the wolves near the eastern fields tomorrow", 4);
        add(journal, labels, "gossip", IRIS, STEVE, "chatter_heard", "When we talked about Steve, Alden said \"they hit me and I don't trust them\"", 2);
        add(journal, labels, "saw", IRIS, STEVE, "witnessed", "I saw Steve give Bram a gift", 2);
        // Decoys: another player with Iris, and Steve with another villager.
        add(journal, labels, "alex_trade", IRIS, ALEX, "trade", "Alex bought 64 × Paper from me for 2 × Emerald", 3);
        add(journal, labels, "bram_gift", BRAM, STEVE, "gift", "Steve gave me Bread", 3);
    }

    private static void add(VillagerJournal journal, Map<UUID, String> labels, String label, UUID villager, UUID player,
                            String type, String text, int salience) {
        var episode = journal.record(villager, player, type, text, 10, NOW - 1000 + labels.size(), salience, type.equals("witnessed"), 0);
        labels.put(episode.id(), label);
    }

    private static List<Query> queries() {
        return List.of(
            new Query("buy", "trade", "What did I buy from you last time?", Set.of("trade")),
            new Query("axes", "trade", "How much were those axes?", Set.of("trade")),
            new Query("give", "gift", "Did I ever give you anything?", Set.of("gift", "saw")),
            new Query("refused", "gift", "Remember the rotten flesh I offered you?", Set.of("refused")),
            new Query("quest_done", "quest", "Did I finish your quest?", Set.of("quest_done", "quest_set")),
            new Query("quest_set", "quest", "What did you ask me to bring you?", Set.of("quest_set")),
            new Query("errand", "errand", "Who did I bring a message from?", Set.of("errand")),
            new Query("hit", "hit", "Did I hit you?", Set.of("hit")),
            new Query("met", "first", "When did we first meet?", Set.of("met")),
            new Query("cave", "conversation", "What did I tell you about the cave?", Set.of("talk")),
            new Query("promise", "promise", "What did I promise you?", Set.of("promise")),
            new Query("gossip", "gossip", "What have others said about me?", Set.of("gossip")),
            new Query("saw", "witnessed", "Did you see me with Bram?", Set.of("saw")),
            new Query("none_boat", "abstention", "Did we ever sail a boat together?", Set.of()),
            new Query("none_colour", "abstention", "What is your favourite colour?", Set.of()),
            new Query("none_nether", "abstention", "Have you been to the nether?", Set.of()));
    }

    /** Frozen first journal search: words of the text only, no endings or types. */
    private static List<VillagerJournal.Episode> baseline(List<VillagerJournal.Episode> pair, String query) {
        Set<String> terms = new HashSet<>(TextTerms.words(query));
        if (terms.isEmpty()) return List.of();
        Map<String, Integer> frequency = new HashMap<>();
        for (var episode : pair) for (String word : new HashSet<>(TextTerms.words(episode.text()))) frequency.merge(word, 1, Integer::sum);
        record Scored(VillagerJournal.Episode episode, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (var episode : pair) {
            double score = 0;
            for (String word : new HashSet<>(TextTerms.words(episode.text()))) {
                if (terms.contains(word)) score += Math.log(1 + (pair.size() + 1.0) / frequency.get(word));
            }
            if (score > 0) scored.add(new Scored(episode, score + episode.salience() * 0.01));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(6).map(Scored::episode).toList();
    }

    private static Metrics metrics(List<Result> results, boolean revised) {
        int answerable = 0, unanswerable = 0;
        double top1 = 0, reciprocal = 0, recall3 = 0, abstained = 0;
        for (Result result : results) {
            List<String> ranked = revised ? result.revised : result.baseline;
            if (result.expected.isEmpty()) {
                unanswerable++;
                if (ranked.isEmpty()) abstained++;
                continue;
            }
            answerable++;
            if (!ranked.isEmpty() && result.expected.contains(ranked.getFirst())) top1++;
            for (int i = 0; i < ranked.size(); i++) if (result.expected.contains(ranked.get(i))) { reciprocal += 1.0 / (i + 1); break; }
            if (ranked.stream().limit(3).anyMatch(result.expected::contains)) recall3++;
        }
        return new Metrics(answerable, unanswerable, top1 / answerable, reciprocal / answerable, recall3 / answerable,
            unanswerable == 0 ? 1 : abstained / unanswerable);
    }
}
