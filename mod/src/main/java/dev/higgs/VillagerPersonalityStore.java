package dev.higgs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Stable world identities; entity profession and name tags are deliberately not assignment seeds. */
public final class VillagerPersonalityStore implements AutoCloseable {
    public record Profile(UUID villagerId, int catalogueVersion, String archetypeId, String styleId, String name, String assignment) {}
    public record Visit(UUID villagerId, UUID playerId, int count, long lastAt) {}
    private record Snapshot(int schemaVersion, UUID worldId, List<Profile> profiles, List<Visit> visits) {}
    private record Pair(UUID villager, UUID player) {}
    private static final Gson GSON = new Gson();
    private final Path file;
    private final PersonalityCatalogue catalogue;
    private final UUID worldId;
    private final Map<UUID, Profile> profiles = new LinkedHashMap<>();
    private final Map<UUID, Long> profileRevisions = new HashMap<>();
    private final Map<Pair, Visit> visits = new LinkedHashMap<>();
    private final Map<Pair, Visit> durableVisits = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "higgs-personality-save"); thread.setDaemon(true); return thread;
    });
    private long revision, persisted, submitted;
    private boolean closed;
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    public VillagerPersonalityStore(Path file, PersonalityCatalogue catalogue) throws IOException {
        this.file = file; this.catalogue = catalogue;
        if (!Files.exists(file)) { worldId = UUID.randomUUID(); return; }
        try {
            if (Files.size(file) > 4 * 1024 * 1024) throw new IllegalArgumentException();
            Snapshot saved = GSON.fromJson(Files.readString(file), Snapshot.class);
            if (saved == null || saved.schemaVersion != 1 || saved.worldId == null || saved.profiles == null
                || saved.profiles.size() > 4096 || saved.visits == null || saved.visits.size() > 8192) throw new IllegalArgumentException();
            worldId = saved.worldId;
            Set<String> names = new HashSet<>();
            for (Profile p : saved.profiles) {
                if (p == null || p.villagerId == null || !PersonalityCatalogue.text(p.name, 80)
                    || p.assignment == null || (!p.assignment.equals("auto") && !p.assignment.equals(p.archetypeId))
                    || profiles.putIfAbsent(p.villagerId, p) != null || !names.add(p.name)) throw new IllegalArgumentException();
                catalogue.archetype(p.catalogueVersion, p.archetypeId); catalogue.style(p.catalogueVersion, p.styleId);
            }
            for (Visit v : saved.visits) {
                if (v == null || !profiles.containsKey(v.villagerId) || v.playerId == null || v.count < 1 || v.count > 20
                    || v.lastAt < 0 || visits.putIfAbsent(new Pair(v.villagerId, v.playerId), v) != null) throw new IllegalArgumentException();
            }
            durableVisits.putAll(visits);
        } catch (RuntimeException failure) { throw new IOException("Invalid personality save; original preserved", failure); }
    }

    public synchronized Profile profile(UUID villager) { return profiles.get(villager); }
    public synchronized boolean ready(Profile p) {
        return p != null && p.equals(profiles.get(p.villagerId)) && profileRevisions.getOrDefault(p.villagerId, 0L) <= persisted;
    }
    public synchronized Profile assign(UUID villager, String explicit) {
        if (closed) throw new IllegalStateException("Personality store closed");
        Objects.requireNonNull(villager);
        Profile old = profiles.get(villager);
        if (old != null && (explicit == null || old.assignment.equals(explicit))) return old;
        if (old == null && profiles.size() >= 4096) throw new IllegalStateException("Personality capacity reached");
        var edition = catalogue.current();
        UUID seed = UUID.nameUUIDFromBytes((worldId + ":" + villager).getBytes(StandardCharsets.UTF_8));
        var archetype = explicit == null ? edition.archetypes().get(Math.floorMod(seed.getMostSignificantBits(), edition.archetypes().size()))
            : catalogue.archetype(edition.version(), explicit);
        String style = explicit == null ? edition.styles().get(Math.floorMod(seed.getLeastSignificantBits(), edition.styles().size())).id() : archetype.defaultStyle();
        String base = explicit == null ? edition.names().get(Math.floorMod(seed.hashCode(), edition.names().size())) : archetype.name();
        Set<String> used = new HashSet<>();
        profiles.values().stream().filter(p -> !p.villagerId.equals(villager)).forEach(p -> used.add(p.name));
        String name = base; int suffix = 2;
        while (used.contains(name)) name = base + " " + suffix++;
        Profile profile = new Profile(villager, edition.version(), archetype.id(), style, name, explicit == null ? "auto" : explicit);
        profiles.put(villager, profile); profileRevisions.put(villager, ++revision);
        return profile;
    }

    public synchronized int familiarity(UUID villager, UUID player) {
        Visit visit = durableVisits.get(new Pair(villager, player)); return visit == null ? 0 : visit.count;
    }
    public synchronized void confirmVisit(UUID villager, UUID player, long now) {
        if (closed || !ready(profiles.get(villager))) throw new IllegalStateException("Identity not saved");
        if (now < 0) throw new IllegalArgumentException("Invalid visit time");
        Pair key = new Pair(villager, Objects.requireNonNull(player));
        Visit old = visits.get(key);
        if (old != null && now - old.lastAt < 300_000) return;
        if (old == null && visits.size() >= 8192) {
            Pair oldest = visits.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().lastAt)).orElseThrow().getKey();
            visits.remove(oldest);
        }
        visits.put(key, new Visit(villager, player, Math.min(20, old == null ? 1 : old.count + 1), now)); revision++;
    }

    public synchronized CompletableFuture<Void> flush() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Personality store closed"));
        if (revision <= submitted && !lastWrite.isCompletedExceptionally()) return lastWrite;
        Snapshot snapshot = new Snapshot(1, worldId, List.copyOf(profiles.values()), List.copyOf(visits.values()));
        long saving = revision; submitted = revision;
        lastWrite = CompletableFuture.runAsync(() -> {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try {
                    Files.writeString(temp, GSON.toJson(snapshot));
                    try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temp); }
                synchronized (this) {
                    persisted = saving;
                    durableVisits.clear();
                    for (Visit visit : snapshot.visits) durableVisits.put(new Pair(visit.villagerId, visit.playerId), visit);
                }
            } catch (IOException failure) { throw new CompletionException(failure); }
        }, writer);
        return lastWrite;
    }

    @Override public void close() throws IOException {
        CompletableFuture<Void> saving;
        synchronized (this) { if (closed) return; saving = flush(); closed = true; }
        writer.shutdown();
        try { saving.get(15, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Personality save interrupted", failure); }
        catch (ExecutionException | TimeoutException failure) { throw new IOException("Personality save failed", failure); }
    }
}
