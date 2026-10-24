package dev.higgs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What each villager has seen happen with each player: trades, gifts, quests, errands, hits and kindness, written
 * by the server as first-person lines. These are observed events, unlike player claims in VillagerMemoryStore.
 * Episodes fade (important ones last longer); the per-pair relationship summary never expires.
 * State is synchronized; only the writer thread touches the save file.
 */
public final class VillagerJournal implements AutoCloseable {
    public record Episode(UUID id, UUID villagerId, UUID playerId, String type, String text,
                          long gameDay, long at, int salience, boolean witnessed) {}
    public record Summary(UUID villagerId, UUID playerId, String text, long updatedAt) {}
    public record Limits(int perPair, int total, long lifetimeMs) {
        public Limits {
            if (perPair < 1 || total < perPair || lifetimeMs < 1) throw new IllegalArgumentException("Invalid journal limits");
        }
        public static Limits defaults() { return new Limits(48, 20_000, 30L * 24 * 60 * 60 * 1000); }
    }
    private record Snapshot(int schemaVersion, List<Episode> episodes, List<Summary> summaries) {}
    public static final int MAX_TEXT = 320, MAX_SUMMARY = 600;
    private static final Gson GSON = new Gson();
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private final Path file;
    private final Limits limits;
    private final Map<String, List<Episode>> episodes = new LinkedHashMap<>();
    private final Map<String, Summary> summaries = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "higgs-journal-save"); thread.setDaemon(true); return thread;
    });
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);
    private boolean closed;

    public VillagerJournal(Path file, Limits limits) throws IOException {
        this.file = file;
        this.limits = limits;
        if (!Files.exists(file)) return;
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Journal save exceeds size limit");
        Snapshot snapshot = GSON.fromJson(Files.readString(file), Snapshot.class);
        if (snapshot == null || snapshot.schemaVersion != 1 || snapshot.episodes == null) throw new IOException("Invalid journal save");
        for (Episode episode : snapshot.episodes) {
            if (valid(episode)) episodes.computeIfAbsent(key(episode.villagerId, episode.playerId), k -> new ArrayList<>()).add(episode);
        }
        if (snapshot.summaries != null) for (Summary summary : snapshot.summaries) {
            if (summary != null && summary.villagerId != null && summary.playerId != null && summary.text != null)
                summaries.put(key(summary.villagerId, summary.playerId), summary);
        }
    }

    private static boolean valid(Episode episode) {
        return episode != null && episode.id != null && episode.villagerId != null && episode.playerId != null
            && episode.type != null && episode.text != null && episode.salience >= 1 && episode.salience <= 5;
    }

    private static String key(UUID villager, UUID player) { return villager + ":" + player; }

    /** Important events outlast routine ones: salience 5 lasts four lifetimes, salience 4 two. */
    long lifetime(int salience) { return limits.lifetimeMs * (salience >= 5 ? 4 : salience >= 4 ? 2 : 1); }

    /**
     * Records an episode. With mergeWithinMs > 0, the latest episode of the same type for this pair within that window
     * is replaced instead (e.g. "hit me three times" rather than three entries).
     */
    public synchronized Episode record(UUID villager, UUID player, String type, String text, long gameDay, long now,
                                       int salience, boolean witnessed, long mergeWithinMs) {
        if (closed) return null;
        String clean = clean(text, MAX_TEXT);
        if (clean.isEmpty() || type == null || type.isBlank()) return null;
        int importance = Math.max(1, Math.min(5, salience));
        List<Episode> pair = episodes.computeIfAbsent(key(villager, player), k -> new ArrayList<>());
        prune(pair, now);
        UUID id = UUID.randomUUID();
        if (mergeWithinMs > 0) {
            for (int i = pair.size() - 1; i >= 0; i--) {
                Episode previous = pair.get(i);
                if (!previous.type.equals(type) || now - previous.at > mergeWithinMs) continue;
                pair.remove(i);
                id = previous.id;
                importance = Math.max(importance, previous.salience);
                break;
            }
        }
        Episode episode = new Episode(id, villager, player, type, clean, gameDay, now, importance, witnessed);
        pair.add(episode);
        while (pair.size() > limits.perPair) pair.remove(evictionCandidate(pair));
        enforceTotal(now);
        save();
        return episode;
    }

    /** The least important episode, oldest first among equals. */
    private static int evictionCandidate(List<Episode> pair) {
        int worst = 0;
        for (int i = 1; i < pair.size(); i++) {
            Episode a = pair.get(i), b = pair.get(worst);
            if (a.salience < b.salience || (a.salience == b.salience && a.at < b.at)) worst = i;
        }
        return worst;
    }

    private void enforceTotal(long now) {
        int total = episodes.values().stream().mapToInt(List::size).sum();
        while (total > limits.total) {
            List<Episode> oldestPair = null;
            int index = -1;
            for (List<Episode> pair : episodes.values()) {
                if (pair.isEmpty()) continue;
                int candidate = evictionCandidate(pair);
                if (oldestPair == null || pair.get(candidate).at < oldestPair.get(index).at) { oldestPair = pair; index = candidate; }
            }
            if (oldestPair == null) return;
            oldestPair.remove(index);
            total--;
        }
    }

    private void prune(List<Episode> pair, long now) { pair.removeIf(episode -> now - episode.at > lifetime(episode.salience)); }

    /** Up to limit episodes, favouring the most important of the recent ones, returned oldest first. */
    public synchronized List<Episode> recent(UUID villager, UUID player, long now, int limit) {
        List<Episode> pair = new ArrayList<>(episodes.getOrDefault(key(villager, player), List.of()));
        pair.removeIf(episode -> now - episode.at > lifetime(episode.salience));
        pair.sort(Comparator.comparingLong(Episode::at).reversed());
        List<Episode> window = new ArrayList<>(pair.subList(0, Math.min(pair.size(), limit * 2)));
        window.sort(Comparator.comparingInt(Episode::salience).reversed().thenComparing(Comparator.comparingLong(Episode::at).reversed()));
        List<Episode> chosen = new ArrayList<>(window.subList(0, Math.min(window.size(), limit)));
        chosen.sort(Comparator.comparingLong(Episode::at));
        return chosen;
    }

    /** A villager's episodes with anyone since a moment, oldest first: what its mood is made of. */
    public synchronized List<Episode> since(UUID villager, long from) {
        List<Episode> result = new ArrayList<>();
        for (List<Episode> pair : episodes.values()) {
            for (Episode episode : pair) if (episode.villagerId.equals(villager) && episode.at >= from) result.add(episode);
        }
        result.sort(Comparator.comparingLong(Episode::at));
        return result;
    }

    /** Live (unexpired) episodes for a pair, oldest first. */
    public synchronized List<Episode> all(UUID villager, UUID player, long now) {
        List<Episode> pair = new ArrayList<>(episodes.getOrDefault(key(villager, player), List.of()));
        pair.removeIf(episode -> now - episode.at > lifetime(episode.salience));
        return pair;
    }

    /** Episodes whose text shares words with the query, best first; lexical like the memory retriever. */
    public synchronized List<Episode> search(UUID villager, UUID player, String query, long now, int limit) {
        Set<String> terms = new HashSet<>(TextTerms.words(query));
        if (terms.isEmpty()) return List.of();
        List<Episode> pair = all(villager, player, now);
        Map<String, Integer> frequency = new HashMap<>();
        for (Episode episode : pair) for (String word : new HashSet<>(TextTerms.words(episode.text))) frequency.merge(word, 1, Integer::sum);
        record Scored(Episode episode, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Episode episode : pair) {
            double score = 0;
            for (String word : new HashSet<>(TextTerms.words(episode.text))) {
                if (terms.contains(word)) score += Math.log(1 + (pair.size() + 1.0) / frequency.get(word));
            }
            if (score > 0) scored.add(new Scored(episode, score + episode.salience * 0.01));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(limit).map(Scored::episode).toList();
    }

    public synchronized Map<String, Integer> counts(UUID villager, UUID player, long now) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Episode episode : all(villager, player, now)) counts.merge(episode.type, 1, Integer::sum);
        return counts;
    }

    public synchronized Summary summary(UUID villager, UUID player) { return summaries.get(key(villager, player)); }

    public synchronized void summarise(UUID villager, UUID player, String text, long now) {
        if (closed) return;
        String clean = clean(text, MAX_SUMMARY);
        if (clean.isEmpty()) return;
        summaries.put(key(villager, player), new Summary(villager, player, clean, now));
        save();
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        String result = text.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        return result.length() > max ? result.substring(0, max) : result;
    }

    private void save() {
        Snapshot snapshot = new Snapshot(1, episodes.values().stream().flatMap(List::stream).toList(), List.copyOf(summaries.values()));
        String json = GSON.toJson(snapshot);
        lastWrite = lastWrite.thenRunAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temporary, json);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not save the villager journal");
            }
        }, writer);
    }

    public CompletableFuture<Void> flush() {
        synchronized (this) { return lastWrite; }
    }

    @Override public void close() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = lastWrite;
        }
        pending.join();
        writer.shutdown();
    }
}
