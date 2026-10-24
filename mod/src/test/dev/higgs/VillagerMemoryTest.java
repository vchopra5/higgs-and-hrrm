package dev.higgs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import com.google.gson.JsonParser;

public final class VillagerMemoryTest {
    private static final UUID FARMER = UUID.randomUUID(), LIBRARIAN = UUID.randomUUID(), THIRD = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID(), OTHER_PLAYER = UUID.randomUUID();
    private static final long NOW = 1_000_000;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("higgs-memory-test-");
        try {
            persistenceAndPlayers(directory.resolve("persistence.json"));
            gossipAndProvenance(directory.resolve("gossip.json"));
            privacyAndFailedWrites(directory);
            boundsExpiryAndOrdering(directory.resolve("bounded.json"));
            changedSettingsAndGlobalBounds(directory.resolve("settings.json"));
            storyInterestAndNaturalOpenings(directory.resolve("interest.json"));
            legacyMigration(directory.resolve("legacy.json"));
            personalityGossip(directory.resolve("personality-gossip.json"));
            learnedTimeMigration(directory.resolve("version-two.json"));
            invalidSaves(directory);
            System.out.println("Villager memory and gossip regression checks passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static VillagerMemoryStore store(Path file) throws IOException {
        return new VillagerMemoryStore(file, VillagerMemoryStore.Limits.defaults());
    }

    private static void persistenceAndPlayers(Path file) throws Exception {
        UUID root;
        try (var store = store(file)) {
            var memory = store.remember(FARMER, "Mira", PLAYER, "Alex", "Found emeralds under the well", false, NOW);
            root = memory.rootId();
            require(store.recall(FARMER, PLAYER, "", false, NOW).isEmpty(), "unsaved memory must not be reported as saved");
            store.flush().join();
            require(store.recall(FARMER, PLAYER, "", false, NOW).size() == 1, "saved story can be recalled");
            require(store.recall(FARMER, OTHER_PLAYER, "", false, NOW).isEmpty(), "player-scoped recall isolates identities");
            require(store.recall(FARMER, OTHER_PLAYER, "", true, NOW).size() == 1, "public news can be heard by others");
            store.remember(FARMER, "Mira", OTHER_PLAYER, "Sam", "I prefer bread", true, NOW + 1);
            store.flush().join();
            require(store.recall(FARMER, PLAYER, "", true, NOW + 1).size() == 1, "village scope excludes another player's private memory");
        }
        try (var restarted = store(file)) {
            var recalled = restarted.recall(FARMER, PLAYER, "emeralds", false, NOW + 2);
            require(recalled.size() == 1 && recalled.getFirst().rootId().equals(root), "world restart preserves memory identity and content");
            require(restarted.recall(LIBRARIAN, PLAYER, "", true, NOW).isEmpty(), "unconnected villager knows nothing");
        }
    }

    private static VillagerGossip.Contact contact(UUID id, String dimension, double x, boolean eligible) {
        return new VillagerGossip.Contact(id, id.equals(FARMER) ? "Mira" : "Rowan", dimension, x, 64, 0, eligible);
    }

    private static void gossipAndProvenance(Path file) throws Exception {
        try (var store = store(file)) {
            var root = store.remember(FARMER, "Mira", PLAYER, "Alex", "Found emeralds under the well", false, NOW);
            store.flush().join();
            VillagerGossip gossip = new VillagerGossip(16, 60_000);
            var source = contact(FARMER, "overworld", 0, true);
            var target = contact(LIBRARIAN, "overworld", 5, true);
            var originalSnapshot = store.gossipSnapshot(NOW);
            require(!gossip.spread(source, contact(LIBRARIAN, "nether", 5, true), originalSnapshot.get(FARMER), store, NOW), "no cross-dimension gossip");
            require(!gossip.spread(source, contact(LIBRARIAN, "overworld", 17, true), originalSnapshot.get(FARMER), store, NOW), "no distant gossip");
            require(!gossip.spread(source, contact(LIBRARIAN, "overworld", 5, false), originalSnapshot.get(FARMER), store, NOW), "sleeping or unloaded target excluded");
            require(!gossip.spread(contact(FARMER, "overworld", 0, false), target, originalSnapshot.get(FARMER), store, NOW), "ineligible source excluded");
            require(gossip.spread(source, target, originalSnapshot.get(FARMER), store, NOW), "nearby villagers share a story");
            require(!originalSnapshot.containsKey(LIBRARIAN), "new recipients cannot cascade in the same snapshot");
            require(store.recall(LIBRARIAN, PLAYER, "", false, NOW).isEmpty(), "gossip waits for persistence before recall");
            store.flush().join();
            var heard = store.recall(LIBRARIAN, PLAYER, "", false, NOW).getFirst();
            require(heard.rootId().equals(root.rootId()) && heard.originId().equals(FARMER)
                && heard.sourceId().equals(FARMER) && heard.hops() == 1, "gossip keeps provenance");
            require(!store.receive(heard, LIBRARIAN, "Rowan", FARMER, NOW + 1), "A to B to A never duplicates a root");
            var third = contact(THIRD, "overworld", 10, true);
            require(gossip.spread(target, third, List.of(heard), store, NOW + 1), "second-hop gossip works on a later pass");
            store.flush().join();
            var twice = store.recall(THIRD, PLAYER, "", false, NOW + 1).getFirst();
            require(twice.sourceId().equals(LIBRARIAN) && twice.originId().equals(FARMER), "most recent source differs from original source");
            store.remember(FARMER, "Mira", PLAYER, "Alex", "I like bread", false, NOW + 2);
            store.flush().join();
            require(!gossip.spread(source, target, store.gossipSnapshot(NOW + 2).get(FARMER), store, NOW + 2), "pair cooldown blocks another story");
            require(gossip.spread(source, target, store.gossipSnapshot(NOW + 60_001).get(FARMER), store, NOW + 60_001), "cooldown later permits another story");
        }
    }

    private static void privacyAndFailedWrites(Path directory) throws Exception {
        try (var store = store(directory.resolve("private.json"))) {
            var secret = store.remember(FARMER, "Mira", PLAYER, "Alex", "A secret stash", true, NOW);
            store.flush().join();
            require(store.gossipSnapshot(NOW).isEmpty(), "private stories never enter gossip");
            require(!store.receive(secret, FARMER, "Mira", LIBRARIAN, NOW), "private stories rejected even if passed directly");
            var duplicate = store.remember(FARMER, "Mira", PLAYER, "Alex", "A SECRET STASH", false, NOW + 1);
            require(duplicate.rootId().equals(secret.rootId()) && duplicate.privateMemory(), "repeat summary cannot downgrade secrecy");
            var publicStory = store.remember(FARMER, "Mira", PLAYER, "Alex", "Keep this story", false, NOW);
            store.flush().join();
            store.remember(FARMER, "Mira", PLAYER, "Alex", "Keep this story", true, NOW + 1);
            require(!store.receive(publicStory, FARMER, "Mira", LIBRARIAN, NOW + 1), "stale public snapshot cannot leak newly private story");
        }
        Path unavailableParent = directory.resolve("not-a-directory");
        Files.writeString(unavailableParent, "preserve me");
        var failing = store(unavailableParent.resolve("memory.json"));
        failing.remember(FARMER, "Mira", PLAYER, "Alex", "Must not acknowledge this save", false, NOW);
        try { failing.flush().join(); throw new AssertionError("write should fail"); }
        catch (CompletionException expected) { /* failure must reach the tool caller */ }
        require(failing.recall(FARMER, PLAYER, "", false, NOW).isEmpty(), "failed save is not recalled as durable");
        require(failing.gossipSnapshot(NOW).isEmpty(), "failed save cannot spread");
        try { failing.close(); throw new AssertionError("shutdown must report failed persistence"); }
        catch (IOException expected) { }
        require(Files.readString(unavailableParent).equals("preserve me"), "failed write preserves existing data");
    }

    private static void boundsExpiryAndOrdering(Path file) throws Exception {
        var limits = new VillagerMemoryStore.Limits(2, 4, 1000, 1);
        try (var store = new VillagerMemoryStore(file, limits)) {
            for (int i = 0; i < 12; i++) {
                store.remember(FARMER, "Mira", PLAYER, "Alex", "Story " + i, false, NOW + i);
                store.flush();
            }
            store.flush().join();
            require(store.size() == 2, "per-villager storage bound");
            var root = store.gossipSnapshot(NOW + 20).get(FARMER).getFirst();
            require(store.receive(root, FARMER, "Mira", LIBRARIAN, NOW + 20), "one allowed hop");
            store.flush().join();
            var oneHop = store.recall(LIBRARIAN, PLAYER, "", false, NOW + 20).getFirst();
            require(!store.receive(oneHop, LIBRARIAN, "Rowan", THIRD, NOW + 20), "hop limit prevents further spread");
            require(!store.receive(root, FARMER, "Mira", THIRD, NOW + 2000), "expired root cannot spread");
        }
        try (var restarted = new VillagerMemoryStore(file, limits)) {
            var recent = restarted.recall(FARMER, PLAYER, "", false, NOW + 20);
            require(recent.size() == 2 && recent.getFirst().summary().equals("Story 11"), "queued snapshots save in order");
            require(restarted.recall(FARMER, PLAYER, "", false, NOW + 2000).isEmpty(), "expired memories disappear");
        }
    }

    private static void invalidSaves(Path directory) throws Exception {
        for (String invalid : List.of("broken json", "{\"schemaVersion\":999,\"memories\":[]}", "{\"schemaVersion\":1,\"memories\":[null]}")) {
            Path file = directory.resolve("corrupt.json");
            Files.writeString(file, invalid);
            try (var ignored = store(file)) { throw new AssertionError("corrupt save should be rejected"); }
            catch (IOException expected) { }
            require(Files.readString(file).equals(invalid), "corrupt original file must be preserved");
        }
    }

    private static void changedSettingsAndGlobalBounds(Path file) throws Exception {
        try (var store = new VillagerMemoryStore(file, new VillagerMemoryStore.Limits(2, 4, 10_000, 3))) {
            store.remember(FARMER, "Mira", PLAYER, "Alex", "Emeralds under the well", false, NOW);
            store.remember(FARMER, "Mira", PLAYER, "Alex", "I like bread", false, NOW + 1);
            store.flush().join();
            require(store.recall(FARMER, PLAYER, "well", false, NOW + 1).getFirst().summary().contains("well"), "query relevance outranks recency");
            require(store.recall(FARMER, PLAYER, "diamonds", false, NOW + 1).isEmpty(), "an unrelated query must not return stories as matching memories");
            require(store.recall(FARMER, PLAYER, "well", false, NOW + 1).size() == 1, "only matching stories are included for a specific query");
            require(store.recall(FARMER, PLAYER, "", false, NOW + 1).size() == 2, "an empty query still returns recent memories");
            for (var root : store.gossipSnapshot(NOW + 1).get(FARMER)) {
                require(store.receive(root, FARMER, "Mira", LIBRARIAN, NOW + 1), "prepare recalled rumours");
            }
            store.remember(THIRD, "Fenn", PLAYER, "Alex", "A third villager's story", false, NOW + 2);
            store.flush().join();
            require(store.size() == 4, "world-wide entry bound");
        }
        try (var reduced = new VillagerMemoryStore(file, new VillagerMemoryStore.Limits(1, 4, 10_000, 0))) {
            require(reduced.size() == 3, "lowering per-villager capacity trims instead of rejecting the save");
            require(reduced.recall(LIBRARIAN, PLAYER, "", false, NOW + 2).size() == 1, "lowering hop limit preserves already known rumours");
            require(reduced.gossipSnapshot(NOW + 2).isEmpty(), "zero-hop configuration disables spreading");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static VillagerMemoryStore.Interest interest(int score, String audience) {
        return new VillagerMemoryStore.Interest(score, "Assessment from the conversation's meaning", List.of(audience));
    }

    private static void storyInterestAndNaturalOpenings(Path file) throws Exception {
        UUID libraryRoot;
        try (var store = store(file)) {
            var library = store.remember(FARMER, "Mira", PLAYER, "Alex", "Discovered an ancient library", false, interest(80, "librarian"), NOW);
            libraryRoot = library.rootId();
            var farm = store.remember(FARMER, "Mira", PLAYER, "Alex", "A strange blight threatens the crops", false, interest(80, "farmer"), NOW + 1);
            var routine = store.remember(FARMER, "Mira", PLAYER, "Alex", "I prefer bread", false, interest(15, "farmer"), NOW + 2);
            store.remember(FARMER, "Mira", PLAYER, "Alex", "A private shocking story", true, interest(100, "all"), NOW + 3);
            store.flush().join();
            var snapshot = store.gossipSnapshot(NOW + 4);
            require(snapshot.get(FARMER).size() == 2, "only newsworthy public memories enter gossip");
            require(store.recall(FARMER, PLAYER, "bread", false, NOW + 4).getFirst().rootId().equals(routine.rootId()), "routine preferences remain retrievable memories");
            require(!store.receive(routine, FARMER, "Mira", LIBRARIAN, NOW + 4), "low-interest memories cannot bypass the gossip threshold");
            var source = contact(FARMER, "overworld", 0, true);
            var librarian = new VillagerGossip.Contact(LIBRARIAN, "Rowan", "overworld", 5, 64, 0, true, "librarian");
            var farmer = new VillagerGossip.Contact(THIRD, "Fenn", "overworld", 5, 64, 0, true, "farmer");
            var gossip = new VillagerGossip(16, 60_000);
            require(gossip.spread(source, librarian, snapshot.get(FARMER), store, NOW + 4), "a librarian hears the story relevant to their profession first");
            require(gossip.spread(source, farmer, snapshot.get(FARMER), store, NOW + 4), "a farmer hears a different relevant story first");
            store.flush().join();
            require(store.has(LIBRARIAN, library.rootId()) && !store.has(LIBRARIAN, farm.rootId()), "recipient relevance chooses library discovery");
            require(store.has(THIRD, farm.rootId()) && !store.has(THIRD, library.rootId()), "recipient relevance chooses crop danger");
            var ranked = store.heardRumours(LIBRARIAN, PLAYER, "librarian", NOW + 5);
            var conversation = new RumourConversation(600_000);
            var opening = conversation.choose(ranked, LIBRARIAN, PLAYER, NOW + 5).orElseThrow();
            require(opening.rootId().equals(library.rootId()), "a heard story becomes a proactive opening without a query");
            conversation.markOffered(LIBRARIAN, PLAYER, opening.rootId(), NOW + 5);
            require(conversation.choose(ranked, LIBRARIAN, PLAYER, NOW + 6).isEmpty(), "re-entering does not repeat the same opening");
            require(conversation.choose(ranked, LIBRARIAN, OTHER_PLAYER, NOW + 6).isPresent(), "topic novelty is specific to each player");
            require(conversation.choose(ranked, LIBRARIAN, PLAYER, NOW + 600_006).isPresent(), "old conversation cooldowns expire");
            require(store.heardRumours(FARMER, PLAYER, "farmer", NOW + 6).isEmpty(), "direct and private memories do not masquerade as heard rumours");
        }
        try (var restored = store(file)) {
            var remembered = restored.heardRumours(LIBRARIAN, PLAYER, "librarian", NOW + 10).getFirst();
            require(remembered.rootId().equals(libraryRoot) && remembered.interest().score() == 80
                && remembered.interest().audiences().equals(List.of("librarian")), "semantic interest and audience survive restart");
        }
    }

    private static void personalityGossip(Path file) throws Exception {
        var eager = new VillagerGossip.Policy(55, 5000, 100, List.of("people"));
        var sceptic = new VillagerGossip.Policy(70, 30000, 150, List.of("books"));
        var guarded = new VillagerGossip.Policy(65, 60000, 200, List.of("tools"));
        try (var store = store(file)) {
            var low = store.remember(FARMER, "Mira", PLAYER, "Alex", "A village gathering", false, interest(60, "all"), NOW);
            var book = store.remember(FARMER, "Mira", PLAYER, "Alex", "Found enchanted books", false, interest(80, "all"), NOW);
            store.remember(FARMER, "Mira", PLAYER, "Alex", "Found unusual tools", false, interest(80, "all"), NOW);
            var secret = store.remember(FARMER, "Mira", PLAYER, "Alex", "Private enchanted books", true, interest(100, "all"), NOW);
            store.flush().join();
            var source = contact(FARMER, "overworld", 0, true);
            var target = contact(LIBRARIAN, "overworld", 5, true);
            var third = contact(THIRD, "overworld", 10, true);
            var gossip = new VillagerGossip(16, 60000);
            var snapshot = store.gossipSnapshot(NOW).get(FARMER);
            require(!gossip.spread(source, target, snapshot, store, NOW + 4999, eager, sceptic), "eager character still waits for its delay");
            require(!gossip.spread(source, target, snapshot, store, NOW + 5000, guarded, sceptic), "guarded character waits longer for the same story");
            require(gossip.spread(source, target, snapshot, store, NOW + 5000, eager, sceptic), "eager character shares first");
            store.flush().join();
            require(store.has(LIBRARIAN, book.rootId()), "personal interests break ties independently of profession");
            var heard = store.recall(LIBRARIAN, PLAYER, "", false, NOW + 5000).getFirst();
            require(heard.learnedAt() == NOW + 5000 && heard.createdAt() == NOW, "each holder has its own learned time");
            require(!gossip.spread(target, third, List.of(heard), store, NOW + 30000, sceptic, guarded), "original story age cannot bypass a recipient's delay");
            require(gossip.spread(target, third, List.of(heard), store, NOW + 35000, sceptic, guarded), "later hop waits from the actual hearing time");
            require(!gossip.spread(source, target, List.of(low), store, NOW + 64000, eager, sceptic), "existing pair cooldown remains enforced");
            require(gossip.spread(source, target, List.of(low), store, NOW + 65000, eager, sceptic), "pair cooldown expires at its configured boundary");
            require(!gossip.spread(source, contact(UUID.randomUUID(), "overworld", 5, true), List.of(low), store, NOW + 120000, sceptic, eager), "sceptic declines lower-interest news even after waiting");
            require(!gossip.spread(source, contact(UUID.randomUUID(), "overworld", 5, true), List.of(secret), store, NOW + 120000, eager, eager), "eager traits never bypass privacy");
        }
    }

    private static void learnedTimeMigration(Path file) throws Exception {
        UUID root;
        try (var store = store(file)) {
            root = store.remember(FARMER, "Mira", PLAYER, "Alex", "An old but interesting story", false, NOW).rootId();
        }
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        saved.addProperty("schemaVersion", 2);
        saved.getAsJsonArray("memories").forEach(entry -> entry.getAsJsonObject().remove("learnedAt"));
        Files.writeString(file, saved.toString());
        try (var migrated = new VillagerMemoryStore(file, VillagerMemoryStore.Limits.defaults(), NOW + 100000)) {
            var memory = migrated.recall(FARMER, PLAYER, "", false, NOW + 100000).getFirst();
            require(memory.rootId().equals(root) && memory.learnedAt() == NOW + 100000 && memory.createdAt() == NOW,
                "version-two migration conservatively starts the delay at load time and retains provenance");
        }
        try (var restarted = store(file)) {
            require(restarted.recall(FARMER, PLAYER, "", false, NOW + 100000).getFirst().learnedAt() == NOW + 100000,
                "migrated learned time persists without resetting on later restarts");
        }
    }

    private static void legacyMigration(Path file) throws Exception {
        UUID root;
        try (var store = store(file)) {
            root = store.remember(FARMER, "Mira", PLAYER, "Alex", "An existing saved story", false, NOW).rootId();
        }
        var old = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        old.addProperty("schemaVersion", 1);
        old.getAsJsonArray("memories").forEach(entry -> entry.getAsJsonObject().remove("interest"));
        Files.writeString(file, old.toString());
        try (var migrated = store(file)) {
            var memory = migrated.recall(FARMER, PLAYER, "", false, NOW).getFirst();
            require(memory.rootId().equals(root) && memory.interest().score() == 60, "old saves keep identities with a neutral interest default");
            var reassessed = migrated.remember(FARMER, "Mira", PLAYER, "Alex", "An existing saved story", false, interest(15, "all"), NOW + 1);
            migrated.flush().join();
            require(reassessed.rootId().equals(root) && migrated.size() == 1 && migrated.gossipSnapshot(NOW + 1).isEmpty(),
                "a fresh semantic assessment can demote an old story without duplicating its root");
        }
        require(JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("schemaVersion").getAsInt() == 3, "legacy save upgrades to the new schema");
    }
}
