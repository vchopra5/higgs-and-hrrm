package dev.higgs;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Deterministic, synthetic retrieval evaluation using real persisted world stores.
 * Inspired by LongMemEval's extraction/update/abstention categories and LoCoMo's
 * multi-session evidence questions; this is not a run of either published dataset.
 * The frozen baseline below is the pre-BM25 production recall implementation.
 */
public final class VillagerMemoryBenchmark {
    private static final UUID FARMER = new UUID(0, 1), LIBRARIAN = new UUID(0, 2), STRANGER = new UUID(0, 3);
    private static final UUID PLAYER = new UUID(1, 1), OTHER_PLAYER = new UUID(1, 2);
    private static final long NOW = 1_000_000, QUERY_TIME = NOW + 1000;
    private static final Set<String> LEGACY_STOP_WORDS = Set.of("", "i", "me", "my", "you", "the", "a", "about", "what", "did", "news", "remember");
    private record Query(String id, String category, String text, UUID owner, UUID player,
                         boolean village, List<String> expected, boolean diagnostic) {}
    private record Result(String id, String category, String query, List<String> expected,
                          List<String> baseline, List<String> revised, boolean diagnostic) {}
    private record Metrics(int questions, int answerable, int unanswerable, double top1,
                           double meanReciprocalRank, double recallAt3, double recallAt8,
                           double precisionAt3, double abstentionAccuracy) {}
    private record Report(String suite, String methodology, String limitations, Metrics baseline, Metrics revised,
                          Map<String, Map<String, Metrics>> categories, Map<String, Boolean> invariants, List<Result> cases) {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("higgs-memory-benchmark-");
        try {
            Path file = directory.resolve("memories.json");
            Map<UUID, String> labels = new LinkedHashMap<>();
            List<VillagerMemoryStore.Memory> persisted = new ArrayList<>();
            List<Query> queries = queries();
            List<Result> results = new ArrayList<>();
            Map<String, Boolean> invariants = new LinkedHashMap<>();
            try (var store = new VillagerMemoryStore(file, VillagerMemoryStore.Limits.defaults(), NOW)) {
                populate(store, labels, persisted);
                for (Query query : queries) {
                    List<String> baseline = labels(legacyRecall(persisted, query, QUERY_TIME), labels);
                    List<String> revised = labels(store.recall(query.owner, query.player, query.text, query.village, QUERY_TIME), labels);
                    results.add(new Result(query.id, query.category, query.text, query.expected, baseline, revised, query.diagnostic));
                }
            }
            try (var restored = new VillagerMemoryStore(file, VillagerMemoryStore.Limits.defaults(), QUERY_TIME)) {
                boolean identical = true;
                for (int i = 0; i < queries.size(); i++) {
                    Query query = queries.get(i);
                    identical &= labels(restored.recall(query.owner, query.player, query.text, query.village, QUERY_TIME), labels)
                        .equals(results.get(i).revised);
                }
                invariants.put("allRankingsSurviveRestart", identical);
                var rumour = restored.recall(LIBRARIAN, PLAYER, "archive", false, QUERY_TIME).getFirst();
                invariants.put("rumourProvenanceSurvivesRestart", rumour.originId().equals(FARMER)
                    && rumour.sourceId().equals(FARMER) && rumour.hops() == 1 && rumour.createdAt() == NOW + 30
                    && rumour.learnedAt() == NOW + 100);
            }
            invariants.putAll(invariants(directory.resolve("invariants.json")));
            invariants.putAll(workingContextInvariants(directory.resolve("context.json")));
            invariants.putAll(contextBudgetInvariants());
            Metrics before = metrics(results, false), after = metrics(results, true);
            Map<String, Map<String, Metrics>> categories = new LinkedHashMap<>();
            for (String category : results.stream().map(Result::category).distinct().toList()) {
                var rows = results.stream().filter(result -> result.category.equals(category)).toList();
                categories.put(category, Map.of("baseline", metrics(rows, false), "revised", metrics(rows, true)));
            }
            Report report = new Report("higgs-memory-retrieval-v1",
                "Hand-authored synthetic queries over a fixed persisted world corpus. Baseline reproduces the previous token-overlap scorer and priority tie-break. Revised calls production recall. Both receive identical eligible durable memories, query scope and eight-result budget. All cases, including known limitations, enter aggregate metrics. No model calls, fixture-derived scoring parameters or answer-string rules.",
                "Retrieval evidence metrics only, not generated-answer or speech quality. English lexical search cannot resolve unseen synonyms, infer event ordering, or decide that a newer conflicting claim supersedes an older one. Small synthetic suite has development bias and is not an official LongMemEval or LoCoMo score.",
                before, after, categories, invariants, results);
            if (args.length > 0) {
                Path reportFile = Path.of(args[0]);
                Files.createDirectories(reportFile.toAbsolutePath().getParent());
                Files.writeString(reportFile, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
                System.out.println("Memory benchmark report: " + reportFile.toAbsolutePath());
            }
            System.out.printf(Locale.ROOT,
                "Memory retrieval benchmark (%d queries): top1 %.3f -> %.3f; MRR %.3f -> %.3f; recall@3 %.3f -> %.3f; abstention %.3f -> %.3f%n",
                results.size(), before.top1, after.top1, before.meanReciprocalRank, after.meanReciprocalRank,
                before.recallAt3, after.recallAt3, before.abstentionAccuracy, after.abstentionAccuracy);
            System.out.println("Safety/persistence invariants: " + invariants);
            for (Result result : results) {
                if (!success(result, true)) System.out.println("Unresolved " + result.id + ": expected " + result.expected + ", retrieved " + result.revised);
            }
            require(invariants.values().stream().allMatch(Boolean::booleanValue), "memory isolation, provenance or persistence regressed");
            require(after.top1 > before.top1, "revised ranking should improve top1 on this fixed suite");
            require(after.meanReciprocalRank > before.meanReciprocalRank, "revised ranking should improve MRR");
            require(after.recallAt3 >= before.recallAt3, "evidence recall must not regress overall");
            require(after.abstentionAccuracy >= before.abstentionAccuracy, "unanswerable-query abstention must not regress");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void populate(VillagerMemoryStore store, Map<UUID, String> labels,
                                 List<VillagerMemoryStore.Memory> persisted) {
        add(store, labels, persisted, "emeralds", "Found emeralds under the old well", 20, 0);
        add(store, labels, persisted, "basalt", "Basalt is hidden beside the northern quarry", 20, 1);
        add(store, labels, persisted, "cocoa", "Cocoa grows near the western river", 20, 2);
        add(store, labels, persisted, "lapis", "Lapis is hidden inside the chest", 20, 3);
        add(store, labels, persisted, "fox", "The fox sleeps beneath the oak", 20, 4);
        add(store, labels, persisted, "recipe", "Pumpkin pie requires sugar and eggs", 20, 5);
        add(store, labels, persisted, "rené", "René owns the café map", 20, 6);
        add(store, labels, persisted, "repair", "Repaired the bridge using spruce planks", 40, 7);
        add(store, labels, persisted, "storm", "The storm damaged the bridge and the village market", 80, 8);
        add(store, labels, persisted, "old_food", "Favorite food is bread", 30, 9);
        add(store, labels, persisted, "new_food", "Favorite food is carrots", 30, 10);
        add(store, labels, persisted, "old_camp", "Camp is beside the eastern forest", 30, 11);
        add(store, labels, persisted, "new_camp", "Camp is beside the western lake", 30, 12);
        add(store, labels, persisted, "delivery", "Delivered wheat to the bakery on Monday", 30, 13);
        add(store, labels, persisted, "payment", "Received six emerald coins from the bakery on Tuesday", 30, 14);
        add(store, labels, persisted, "promise", "Promised to return the borrowed lantern before Friday", 30, 15);
        add(store, labels, persisted, "mount", "The horse is named Juniper", 30, 16);
        add(store, labels, persisted, "farm", "Mushrooms grow in the cellar", 30, 17);
        add(store, labels, persisted, "weather", "The weather is bright and the village is peaceful", 100, 40);
        add(store, labels, persisted, "meeting", "We were at the market and we will be there again", 100, 41);
        add(store, labels, persisted, "watch", "The guard is where the road meets the village", 100, 42);
        add(store, labels, persisted, "instruments", "We have bells and drums for the village festival", 100, 43);
        add(store, labels, persisted, "market", "The market has apples and flowers for sale", 100, 44);
        add(store, labels, persisted, "quarry_path", "The quarry path is near the village and the market", 100, 45);
        add(store, labels, persisted, "quarry_cart", "The village uses the quarry cart for the market", 100, 46);
        add(store, labels, persisted, "quarry_rain", "Rain near the quarry made the village road muddy", 100, 47);
        add(store, labels, persisted, "quarry_gate", "The quarry gate is near the village bridge", 100, 48);
        add(store, labels, persisted, "quarry_shift", "The village quarry workers meet near the market", 100, 49);
        add(store, labels, persisted, "long_recipe", "Sugar and eggs were sold at the busy market while merchants displayed scarves, candles, books, polished tools and baskets beside the road during the autumn festival", 100, 50);
        var sam = store.remember(FARMER, "Mira", OTHER_PLAYER, "Sam", "Found a silver compass on the hill", false, interest(70), NOW + 20);
        label(sam, "sam_compass", labels, persisted);
        var privateOther = store.remember(FARMER, "Mira", OTHER_PLAYER, "Sam", "The vault password is moonflower", true, interest(100), NOW + 21);
        label(privateOther, "sam_private", labels, persisted);
        var privateOwn = store.remember(FARMER, "Mira", PLAYER, "Alex", "The cellar password is nightjar", true, interest(30), NOW + 22);
        label(privateOwn, "own_private", labels, persisted);
        var archive = store.remember(FARMER, "Mira", PLAYER, "Alex", "A ruined archive lies beneath the marsh", false, interest(70), NOW + 30);
        label(archive, "archive", labels, persisted);
        store.flush().join();
        require(store.receive(archive, FARMER, "Mira", LIBRARIAN, NOW + 100), "prepare real local gossip");
        store.flush().join();
        persisted.add(store.recall(LIBRARIAN, PLAYER, "archive", false, NOW + 100).getFirst());
    }

    private static void add(VillagerMemoryStore store, Map<UUID, String> labels,
                            List<VillagerMemoryStore.Memory> persisted, String id, String summary, int score, int time) {
        label(store.remember(FARMER, "Mira", PLAYER, "Alex", summary, false, interest(score), NOW + time), id, labels, persisted);
    }
    private static void label(VillagerMemoryStore.Memory memory, String id, Map<UUID, String> labels,
                              List<VillagerMemoryStore.Memory> persisted) {
        labels.put(memory.rootId(), id); persisted.add(memory);
    }
    private static VillagerMemoryStore.Interest interest(int score) {
        return new VillagerMemoryStore.Interest(score, "Fixed benchmark fixture", List.of("all"));
    }

    private static List<Query> queries() {
        List<Query> queries = new ArrayList<>();
        query(queries, "exact_emeralds", "exact", "emeralds", "emeralds");
        query(queries, "exact_basalt", "exact", "basalt", "basalt");
        query(queries, "exact_lapis", "exact", "lapis", "lapis");
        query(queries, "exact_fox", "exact", "fox", "fox");
        query(queries, "exact_promise", "exact", "borrowed lantern", "promise");
        query(queries, "exact_mount", "exact", "Juniper", "mount");
        query(queries, "exact_farm", "exact", "mushrooms", "farm");
        query(queries, "exact_cocoa", "exact", "western river", "cocoa");
        query(queries, "question_lapis", "conversational_distractors", "Where is my lapis?", "lapis");
        query(queries, "question_emeralds", "conversational_distractors", "Where were the emeralds?", "emeralds");
        query(queries, "question_fox", "conversational_distractors", "Do you remember where the fox sleeps?", "fox");
        query(queries, "question_cocoa", "conversational_distractors", "What do we have about cocoa?", "cocoa");
        query(queries, "question_mount", "conversational_distractors", "Who is Juniper?", "mount");
        query(queries, "question_promise", "conversational_distractors", "What was it that I promised?", "promise");
        query(queries, "rare_basalt", "ranking", "basalt near the village quarry", "basalt");
        query(queries, "short_recipe", "ranking", "sugar eggs", "recipe", "long_recipe");
        query(queries, "bridge_repair", "ranking", "bridge spruce", "repair");
        query(queries, "old_well", "ranking", "old well", "emeralds");
        query(queries, "unicode_width", "normalization", "ＥＭＥＲＡＬＤＳ", "emeralds");
        query(queries, "unicode_accent", "normalization", "cafe\u0301 map", "rené");
        query(queries, "unicode_name", "normalization", "Rene\u0301", "rené");
        query(queries, "punctuation", "normalization", "LAPIS?!", "lapis");
        query(queries, "no_diamonds", "abstention", "Where is the diamond vault?");
        query(queries, "no_crown", "abstention", "Who has a ruby crown?");
        query(queries, "no_dragon", "abstention", "Were there dragons?");
        query(queries, "no_portal", "abstention", "Which portal will we use?");
        query(queries, "no_unicorn", "abstention", "unicorn");
        query(queries, "no_granite", "abstention", "granite");
        query(queries, "latest_food", "updates", "favorite food", "new_food");
        query(queries, "latest_camp", "updates", "camp", "new_camp");
        query(queries, "bakery_evidence", "multiple_facts", "bakery", "delivery", "payment");
        query(queries, "bridge_evidence", "multiple_facts", "bridge storm repair spruce", "repair", "storm");
        queries.add(new Query("subject_name", "attribution", "What did Sam tell you?", FARMER, PLAYER, true, List.of("sam_compass"), false));
        queries.add(new Query("subject_possessive", "attribution", "Sam’s compass", FARMER, PLAYER, true, List.of("sam_compass"), false));
        queries.add(new Query("heard_source", "attribution", "What did Mira tell you?", LIBRARIAN, PLAYER, false, List.of("archive"), false));
        queries.add(new Query("heard_content", "attribution", "archive", LIBRARIAN, PLAYER, false, List.of("archive"), false));
        query(queries, "own_secret", "scope", "nightjar", "own_private");
        queries.add(new Query("other_secret", "scope", "moonflower", FARMER, PLAYER, true, List.of(), false));
        queries.add(new Query("other_player_scope", "scope", "compass", FARMER, PLAYER, false, List.of(), false));
        queries.add(new Query("unknown_villager", "scope", "emeralds", STRANGER, PLAYER, true, List.of(), false));
        queries.add(new Query("unseen_synonym", "known_limitations", "steed", FARMER, PLAYER, false, List.of("mount"), true));
        queries.add(new Query("unseen_paraphrase", "known_limitations", "subterranean fungus cultivation", FARMER, PLAYER, false, List.of("farm"), true));
        queries.add(new Query("historical_food", "known_limitations", "favorite food before it changed", FARMER, PLAYER, false, List.of("old_food"), true));
        queries.add(new Query("historical_camp", "known_limitations", "camp before the move", FARMER, PLAYER, false, List.of("old_camp"), true));
        return queries;
    }
    private static void query(List<Query> queries, String id, String category, String text, String... expected) {
        queries.add(new Query(id, category, text, FARMER, PLAYER, false, List.of(expected), false));
    }

    /** Literal old production algorithm, including its existing stop words and priority order. */
    private static List<VillagerMemoryStore.Memory> legacyRecall(List<VillagerMemoryStore.Memory> persisted, Query query, long now) {
        Set<String> terms = new HashSet<>(Arrays.asList(query.text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        terms.removeAll(LEGACY_STOP_WORDS);
        return persisted.stream().filter(memory -> memory.ownerId().equals(query.owner) && memory.expiresAt() > now
            && (memory.playerId().equals(query.player) || (query.village && !memory.privateMemory())))
            .filter(memory -> terms.isEmpty() || legacyRelevance(memory, terms) > 0)
            .sorted(Comparator.<VillagerMemoryStore.Memory>comparingLong(memory -> legacyRelevance(memory, terms)).reversed()
                .thenComparing(Comparator.<VillagerMemoryStore.Memory>comparingLong(memory -> legacyPriority(memory, query.player, now)).reversed())
                .thenComparing(Comparator.comparingLong(VillagerMemoryStore.Memory::createdAt).reversed()))
            .limit(8).toList();
    }
    private static long legacyPriority(VillagerMemoryStore.Memory memory, UUID player, long now) {
        long ageHours = Math.max(0, now - memory.createdAt()) / 3_600_000;
        int personal = memory.playerId().equals(player) ? 15 : 0;
        return memory.interest().score() + personal + Math.max(0, 12 - ageHours / 6) - memory.hops() * 4L;
    }
    private static long legacyRelevance(VillagerMemoryStore.Memory memory, Set<String> terms) {
        Set<String> words = new HashSet<>(Arrays.asList(memory.summary().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        return terms.stream().filter(words::contains).count();
    }
    private static List<String> labels(List<VillagerMemoryStore.Memory> memories, Map<UUID, String> labels) {
        return memories.stream().map(memory -> labels.get(memory.rootId())).toList();
    }

    private static Metrics metrics(List<Result> cases, boolean revised) {
        int positives = 0, negatives = 0, top1 = 0, abstentions = 0;
        double reciprocalRank = 0, recall3 = 0, recall8 = 0, precision3 = 0;
        for (Result result : cases) {
            List<String> actual = revised ? result.revised : result.baseline;
            if (result.expected.isEmpty()) { negatives++; if (actual.isEmpty()) abstentions++; continue; }
            positives++;
            if (!actual.isEmpty() && result.expected.contains(actual.getFirst())) top1++;
            for (int i = 0; i < actual.size(); i++) {
                if (result.expected.contains(actual.get(i))) { reciprocalRank += 1.0 / (i + 1); break; }
            }
            long hits3 = actual.stream().limit(3).filter(result.expected::contains).count();
            long hits8 = actual.stream().filter(result.expected::contains).count();
            recall3 += (double) hits3 / result.expected.size();
            recall8 += (double) hits8 / result.expected.size();
            precision3 += actual.isEmpty() ? 0 : (double) hits3 / Math.min(3, actual.size());
        }
        return new Metrics(cases.size(), positives, negatives,
            fraction(top1, positives), fraction(reciprocalRank, positives), fraction(recall3, positives),
            fraction(recall8, positives), fraction(precision3, positives), fraction(abstentions, negatives));
    }
    private static double fraction(double numerator, int denominator) { return denominator == 0 ? 0 : numerator / denominator; }
    private static boolean success(Result result, boolean revised) {
        List<String> actual = revised ? result.revised : result.baseline;
        return result.expected.isEmpty() ? actual.isEmpty()
            : !actual.isEmpty() && result.expected.contains(actual.getFirst())
                && actual.stream().limit(3).toList().containsAll(result.expected);
    }

    private static Map<String, Boolean> invariants(Path file) throws Exception {
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        var limits = new VillagerMemoryStore.Limits(64, 4096, 10_000, 3);
        try (var store = new VillagerMemoryStore(file, limits, NOW)) {
            var publicMemory = store.remember(FARMER, "Mira", PLAYER, "Alex", "Basalt beside quarry", false, interest(30), NOW);
            outcomes.put("uncommittedMemoryExcluded", store.recall(FARMER, PLAYER, "basalt", false, NOW).isEmpty());
            store.flush().join();
            store.remember(FARMER, "Mira", PLAYER, "Alex", "Quarry near market", false, interest(80), NOW + 1);
            store.flush().join();
            var before = store.recall(FARMER, PLAYER, "basalt quarry", true, NOW + 1);
            var secret = store.remember(FARMER, "Mira", OTHER_PLAYER, "Sam", "Basalt quarry basalt quarry secret", true, interest(100), NOW + 2);
            store.flush().join();
            outcomes.put("privateCorpusCannotChangeRanking", before.equals(store.recall(FARMER, PLAYER, "basalt quarry", true, NOW + 2)));
            outcomes.put("privateMemoryCannotSpread", !store.receive(secret, FARMER, "Mira", LIBRARIAN, NOW + 2));
            outcomes.put("otherPlayerExcluded", store.recall(FARMER, OTHER_PLAYER, "basalt", false, NOW + 2).stream()
                .noneMatch(memory -> memory.rootId().equals(publicMemory.rootId())));
            outcomes.put("unknownVillagerExcluded", store.recall(STRANGER, PLAYER, "basalt", true, NOW + 2).isEmpty());
            outcomes.put("expiryEnforced", store.recall(FARMER, PLAYER, "basalt", true, NOW + 20_000).isEmpty());
        }
        try (var otherWorld = new VillagerMemoryStore(file.resolveSibling("other-world.json"), limits, NOW)) {
            outcomes.put("worldIsolation", otherWorld.recall(FARMER, PLAYER, "basalt", true, NOW).isEmpty());
        }
        return outcomes;
    }

    private static Map<String, Boolean> workingContextInvariants(Path file) throws Exception {
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        try (var store = new VillagerMemoryStore(file, VillagerMemoryStore.Limits.defaults(), NOW)) {
            var important = store.remember(FARMER, "Mira", PLAYER, "Alex", "A flood threatens the northern farms", false, interest(100), NOW);
            for (int i = 0; i < 40; i++) {
                store.remember(FARMER, "Mira", PLAYER, "Alex", "Routine observation number " + i, false, interest(10), NOW + i + 1);
            }
            var recent = store.remember(FARMER, "Mira", PLAYER, "Alex", "My favorite flower is the daisy", true, interest(5), NOW + 50);
            var privateOther = store.remember(FARMER, "Mira", OTHER_PLAYER, "Sam", "Sam's secret itinerary", true, interest(100), NOW + 51);
            store.flush().join();
            var pending = store.remember(FARMER, "Mira", PLAYER, "Alex", "An uncommitted sighting", false, interest(100), NOW + 52);
            var context = store.workingContext(FARMER, PLAYER, NOW + 52);
            outcomes.put("workingContextBoundedAndUnique", context.size() == 32 && context.stream().map(VillagerMemoryStore.Memory::rootId).distinct().count() == 32);
            outcomes.put("workingContextRetainsRecentRoutineAndSalientFacts", context.contains(recent) && context.contains(important));
            outcomes.put("workingContextExcludesOtherPrivateAndUncommitted", !context.contains(privateOther) && !context.contains(pending));
            outcomes.put("workingContextUnknownVillagerEmpty", store.workingContext(STRANGER, PLAYER, NOW + 52).isEmpty());
        }
        return outcomes;
    }

    private static Map<String, Boolean> contextBudgetInvariants() {
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        Gson gson = new Gson();
        boolean withinBudget = true, keptPrefix = true, neverTruncatedFact = true;
        for (String character : List.of("<", "\u2028", "漢", "\\", "\"")) {
            JsonArray original = new JsonArray();
            for (int i = 0; i < 32; i++) {
                JsonObject memory = new JsonObject();
                memory.addProperty("storyId", String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", i));
                memory.addProperty("summary", character.repeat(320));
                memory.addProperty("whyInteresting", character.repeat(160));
                for (String name : List.of("toldByPlayer", "originVillager", "heardFrom")) memory.addProperty(name, character.repeat(80));
                memory.addProperty("kind", "heard_from_villager");
                memory.addProperty("private", false);
                memory.addProperty("createdAt", NOW);
                memory.addProperty("learnedAt", NOW + 1);
                memory.addProperty("interest", 100);
                original.add(memory);
            }
            var bounded = MemoryContextBudget.limit(original, MemoryContextBudget.SESSION_BYTES);
            withinBudget &= gson.toJson(bounded).getBytes(StandardCharsets.UTF_8).length <= MemoryContextBudget.SESSION_BYTES;
            neverTruncatedFact &= original.size() == 32 && !bounded.isEmpty();
            for (int i = 0; i < bounded.size(); i++) keptPrefix &= bounded.get(i).equals(original.get(i));
            if (bounded.size() < original.size()) {
                bounded.add(original.get(bounded.size()));
                neverTruncatedFact &= gson.toJson(bounded).getBytes(StandardCharsets.UTF_8).length > MemoryContextBudget.SESSION_BYTES;
            }
        }
        JsonArray small = new JsonArray();
        for (int i = 0; i < 32; i++) { JsonObject memory = new JsonObject(); memory.addProperty("summary", "Ordinary memory " + i); small.add(memory); }
        outcomes.put("contextFitsEscapedUtf8Budget", withinBudget);
        outcomes.put("contextBudgetPreservesOrderingAndWholeFacts", keptPrefix && neverTruncatedFact);
        outcomes.put("ordinaryContextRetainsAll32Memories", MemoryContextBudget.limit(small, MemoryContextBudget.SESSION_BYTES).equals(small));
        return outcomes;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
