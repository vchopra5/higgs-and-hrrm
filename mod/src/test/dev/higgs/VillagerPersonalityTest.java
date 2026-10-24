package dev.higgs;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;

public final class VillagerPersonalityTest {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var catalogue = PersonalityCatalogue.bundled();
        int currentEdition = catalogue.current().version();
        Path directory = Files.createTempDirectory("higgs-personalities-");
        UUID villager = UUID.randomUUID(), player = UUID.randomUUID(), stranger = UUID.randomUUID();
        Path file = directory.resolve("profiles.json");
        VillagerPersonalityStore.Profile original;
        try {
            try (var store = new VillagerPersonalityStore(file, catalogue)) {
                original = store.assign(villager, null);
                require(!store.ready(original), "identity cannot be used before persistence");
                try { store.confirmVisit(villager, player, 1000); throw new AssertionError("unsaved identity accepted"); }
                catch (IllegalStateException expected) { }
                store.flush().join();
                require(store.ready(original), "saved identity is ready");
                require(store.assign(villager, null).equals(original), "reconnecting never rerolls identity");
                Set<String> names = new HashSet<>(List.of(original.name()));
                for (int i = 0; i < 100; i++) require(names.add(store.assign(UUID.randomUUID(), null).name()), "names are unique despite a small name pool");
                store.confirmVisit(villager, player, 1000);
                require(store.familiarity(villager, player) == 0, "unflushed visits cannot claim familiarity");
                store.flush().join();
                require(store.familiarity(villager, player) == 1, "successful conversation creates recognition");
                for (int i = 0; i < 10; i++) store.confirmVisit(villager, player, 2000);
                store.flush().join();
                require(store.familiarity(villager, player) == 1, "rapid reconnects cannot farm familiarity");
                for (int i = 1; i < 30; i++) store.confirmVisit(villager, player, 1000L + 300_000L * i);
                store.flush().join();
                require(store.familiarity(villager, player) == 20 && store.familiarity(villager, stranger) == 0, "familiarity is bounded and per player");
            }
            try (var restarted = new VillagerPersonalityStore(file, catalogue)) {
                require(restarted.assign(villager, null).equals(original), "world restart preserves every profile field");
                require(restarted.familiarity(villager, player) == 20, "visits survive restart");
                var mira = restarted.assign(villager, "mira");
                require(mira.archetypeId().equals("mira") && mira.styleId().equals("brisk"), "explicit cast uses authored traits");
                require(!restarted.ready(mira), "explicit reassignment also waits for disk");
                restarted.flush().join();
                require(restarted.assign(villager, null).equals(mira), "removing a cast tag keeps the saved identity");
                require(restarted.assign(villager, "mira").equals(mira), "retained tag never rerolls cast identity");
                require(!restarted.assign(UUID.randomUUID(), "mira").name().equals(mira.name()), "duplicate cast names resolve collisions");
            }
            // New catalogue editions must not reinterpret profiles saved against edition one.
            var json = JsonParser.parseString(Files.readString(Path.of("../shared/personality-catalogue.json"))).getAsJsonObject();
            var edition = json.getAsJsonArray("versions").get(0).deepCopy().getAsJsonObject();
            edition.addProperty("version", currentEdition + 1);
            edition.getAsJsonArray("archetypes").get(0).getAsJsonObject().addProperty("motive", "A new edition's motive");
            json.getAsJsonArray("versions").add(edition); json.addProperty("currentVersion", currentEdition + 1);
            var updated = new PersonalityCatalogue(new java.io.ByteArrayInputStream(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try (var store = new VillagerPersonalityStore(file, updated)) {
                require(store.assign(villager, null).catalogueVersion() == currentEdition, "catalogue upgrade retains existing edition");
                require(store.assign(UUID.randomUUID(), null).catalogueVersion() == currentEdition + 1, "new identities use the new edition");
            }
            Path legacyFile = directory.resolve("legacy-profile.json");
            var legacy = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var oldProfile = legacy.getAsJsonArray("profiles").asList().stream()
                .map(entry -> entry.getAsJsonObject()).filter(entry -> entry.get("villagerId").getAsString().equals(villager.toString())).findFirst().orElseThrow();
            oldProfile.addProperty("catalogueVersion", 1);
            var oldProfiles = new com.google.gson.JsonArray(); oldProfiles.add(oldProfile);
            legacy.add("profiles", oldProfiles);
            Files.writeString(legacyFile, legacy.toString());
            try (var saved = new VillagerPersonalityStore(legacyFile, catalogue)) {
                require(saved.assign(villager, null).catalogueVersion() == 1, "released edition-one identities stay unchanged");
                var rookId = UUID.randomUUID();
                var rook = saved.assign(rookId, "rook");
                require(rook.archetypeId().equals("rook") && rook.styleId().equals("clipped") && rook.catalogueVersion() == currentEdition,
                    "Rook can be explicitly assigned with the authored clipped style");
                saved.flush().join();
                require(saved.assign(rookId, null).equals(rook), "Rook remains stable after removing the assignment tag");
            }
            Path blocked = directory.resolve("blocked"); Files.writeString(blocked, "preserved");
            var failing = new VillagerPersonalityStore(blocked.resolve("profiles.json"), catalogue);
            var unsaved = failing.assign(UUID.randomUUID(), "bram");
            try { failing.flush().join(); throw new AssertionError("write should fail"); } catch (CompletionException expected) { }
            require(!failing.ready(unsaved), "failed identity save cannot become ready");
            try { failing.close(); throw new AssertionError("close must report failure"); } catch (IOException expected) { }
            require(Files.readString(blocked).equals("preserved"), "failed writes preserve existing files");
            for (String invalid : List.of("broken", "{\"schemaVersion\":99}", Files.readString(file).replace("\"archetypeId\":\"mira\"", "\"archetypeId\":\"unknown\""))) {
                Path corrupt = directory.resolve("corrupt.json"); Files.writeString(corrupt, invalid);
                try (var ignored = new VillagerPersonalityStore(corrupt, catalogue)) { throw new AssertionError("corrupt identity accepted"); }
                catch (IOException expected) { }
                require(Files.readString(corrupt).equals(invalid), "corrupt original is never overwritten");
            }
            System.out.println("Villager personality persistence and familiarity checks passed");
        } finally {
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
