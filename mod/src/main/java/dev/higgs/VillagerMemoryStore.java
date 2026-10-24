package dev.higgs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** World-scoped memories. State is synchronized; only the writer touches the save file. */
public final class VillagerMemoryStore implements AutoCloseable {
    public static final int MAX_SUMMARY = 320;
    public static final int MAX_QUERY = 160;
    public static final int GOSSIP_THRESHOLD = 55;
    public static final Set<String> AUDIENCES = Set.of("all", "farmer", "librarian", "cleric", "armorer",
        "weaponsmith", "toolsmith", "cartographer", "fisherman", "shepherd", "butcher", "leatherworker", "mason", "fletcher");
    public record Interest(int score, String reason, List<String> audiences) {
        public Interest {
            if (score < 0 || score > 100 || !validText(reason, 160) || audiences == null || audiences.isEmpty()
                || audiences.size() > 4 || !AUDIENCES.containsAll(audiences)) throw new IllegalArgumentException("Invalid story interest");
            audiences = List.copyOf(new LinkedHashSet<>(audiences));
        }
        static Interest legacy() { return new Interest(60, "Previously saved story; interest was not assessed.", List.of("all")); }
    }
    public record Limits(int perVillager, int total, long lifetimeMs, int maxHops) {
        public Limits {
            if (perVillager < 1 || total < perVillager || lifetimeMs < 1 || maxHops < 0 || maxHops > 8)
                throw new IllegalArgumentException("Invalid memory limits");
        }
        public static Limits defaults() { return new Limits(64, 4096, 7L * 24 * 60 * 60 * 1000, 3); }
    }
    public record Memory(UUID rootId, UUID ownerId, UUID playerId, String playerName,
                         UUID originId, String originName, UUID sourceId, String sourceName,
                         String summary, boolean privateMemory, long createdAt, long expiresAt,
                         int hops, long revision, Interest interest, long learnedAt) {}
    private record Snapshot(int schemaVersion, List<Memory> memories) {}

    private static final Gson GSON = new Gson();
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static final double BM25_K1 = 1.2, BM25_B = 0.75;
    private record SearchDocument(Memory memory, Map<String, Integer> frequencies, int length) {}
    private record SearchResult(Memory memory, double score) {}
    private final Path file;
    private final Limits limits;
    private final Map<UUID, LinkedHashMap<UUID, Memory>> memories = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "higgs-memory-save"); thread.setDaemon(true); return thread;
    });
    private long revision, persistedRevision, submittedRevision;
    private boolean closed;
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    public VillagerMemoryStore(Path file, Limits limits) throws IOException {
        this(file, limits, System.currentTimeMillis());
    }
    VillagerMemoryStore(Path file, Limits limits, long loadedAt) throws IOException {
        this.file = file;
        this.limits = limits;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Memory save exceeds size limit");
            Snapshot snapshot = GSON.fromJson(Files.readString(file), Snapshot.class);
            if (snapshot == null || (snapshot.schemaVersion < 1 || snapshot.schemaVersion > 3) || snapshot.memories == null
                || snapshot.memories.size() > limits.total) throw new IOException("Invalid memory save");
            for (Memory memory : snapshot.memories) {
                if (snapshot.schemaVersion == 1 && memory != null) memory = withInterest(memory, Interest.legacy());
                if (snapshot.schemaVersion < 3 && memory != null) memory = new Memory(memory.rootId, memory.ownerId,
                    memory.playerId, memory.playerName, memory.originId, memory.originName, memory.sourceId, memory.sourceName,
                    memory.summary, memory.privateMemory, memory.createdAt, memory.expiresAt, memory.hops, memory.revision,
                    memory.interest, Math.max(memory.createdAt, loadedAt));
                validate(memory);
                var owner = memories.computeIfAbsent(memory.ownerId, ignored -> new LinkedHashMap<>());
                if (owner.putIfAbsent(memory.rootId, memory) != null) throw new IOException("Duplicate memory entry");
                revision = Math.max(revision, memory.revision);
            }
            persistedRevision = submittedRevision = revision;
            if (snapshot.schemaVersion < 3) revision++;
            for (var owner : memories.values()) {
                while (owner.size() > limits.perVillager) {
                    owner.remove(owner.keySet().iterator().next()); revision++;
                }
            }
        } catch (RuntimeException failure) {
            throw new IOException("Could not read memory save; original file preserved", failure);
        }
    }

    private void validate(Memory m) throws IOException {
        if (m == null || m.rootId == null || m.ownerId == null || m.playerId == null
            || m.originId == null || m.sourceId == null || m.hops < 0 || m.hops > 8
            || m.createdAt < 0 || m.learnedAt < m.createdAt || m.expiresAt <= m.createdAt || m.revision < 1
            || !validText(m.summary, MAX_SUMMARY) || !validText(m.playerName, 80)
            || !validText(m.originName, 80) || !validText(m.sourceName, 80)
            || m.interest == null || (m.privateMemory && m.hops != 0)) throw new IOException("Invalid memory entry");
    }

    private static Memory withInterest(Memory m, Interest interest) {
        return new Memory(m.rootId, m.ownerId, m.playerId, m.playerName, m.originId, m.originName,
            m.sourceId, m.sourceName, m.summary, m.privateMemory, m.createdAt, m.expiresAt, m.hops, m.revision, interest, m.learnedAt);
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
            && value.codePoints().noneMatch(Character::isISOControl);
    }

    public synchronized Memory remember(UUID owner, String ownerName, UUID player, String playerName,
                                        String summary, boolean privateMemory, long now) {
        return remember(owner, ownerName, player, playerName, summary, privateMemory, Interest.legacy(), now);
    }

    public synchronized Memory remember(UUID owner, String ownerName, UUID player, String playerName,
                                        String summary, boolean privateMemory, Interest interest, long now) {
        if (closed) throw new IllegalStateException("Memory store is closed");
        Objects.requireNonNull(interest);
        if (!validText(summary, MAX_SUMMARY)) throw new IllegalArgumentException("Invalid memory summary");
        Objects.requireNonNull(owner); Objects.requireNonNull(player);
        ownerName = label(ownerName); playerName = label(playerName);
        prune(now);
        var entries = memories.get(owner);
        if (entries != null) {
            for (Memory previous : List.copyOf(entries.values())) {
                if (previous.playerId.equals(player) && previous.hops == 0
                    && previous.summary.equalsIgnoreCase(summary.strip())) {
                    // A duplicate cannot accidentally make a private memory public.
                    boolean keepPrivate = previous.privateMemory || privateMemory;
                    if (previous.privateMemory == keepPrivate && previous.interest.equals(interest)) return previous;
                    Memory updated = new Memory(previous.rootId, owner, player, playerName, owner,
                        ownerName, owner, ownerName, previous.summary, keepPrivate, previous.createdAt,
                        previous.expiresAt, 0, ++revision, interest, previous.learnedAt);
                    entries.put(updated.rootId, updated);
                    return updated;
                }
            }
        }
        Memory memory = new Memory(UUID.randomUUID(), owner, player, playerName, owner, ownerName,
            owner, ownerName, summary.strip(), privateMemory, now, now + limits.lifetimeMs, 0, ++revision, interest, now);
        insert(memory);
        return memory;
    }

    private static String label(String value) {
        String clean = value == null ? "Unknown" : value.replaceAll("\\p{Cntrl}", "").strip();
        return clean.isEmpty() ? "Unknown" : clean.substring(0, Math.min(clean.length(), 80));
    }

    private void insert(Memory memory) {
        var owner = memories.computeIfAbsent(memory.ownerId, ignored -> new LinkedHashMap<>());
        while (owner.size() >= limits.perVillager) owner.remove(owner.keySet().iterator().next());
        owner.put(memory.rootId, memory);
        while (size() > limits.total) {
            var oldestOwner = memories.entrySet().iterator().next();
            var oldest = oldestOwner.getValue();
            oldest.remove(oldest.keySet().iterator().next());
            if (oldest.isEmpty()) memories.remove(oldestOwner.getKey());
        }
    }

    public synchronized int size() { return memories.values().stream().mapToInt(Map::size).sum(); }

    public synchronized void prune(long now) {
        boolean changed = false;
        for (var entries : memories.values()) changed |= entries.values().removeIf(m -> m.expiresAt <= now);
        memories.values().removeIf(Map::isEmpty);
        if (changed) revision++;
    }

    public synchronized boolean has(UUID owner, UUID root) {
        return memories.containsKey(owner) && memories.get(owner).containsKey(root);
    }

    public synchronized Map<UUID, List<Memory>> gossipSnapshot(long now) {
        prune(now);
        Map<UUID, List<Memory>> snapshot = new LinkedHashMap<>();
        memories.forEach((owner, entries) -> {
            List<Memory> shareable = entries.values().stream()
                .filter(m -> m.revision <= persistedRevision && !m.privateMemory && m.hops < limits.maxHops
                    && m.interest.score >= GOSSIP_THRESHOLD)
                .sorted(priorityOrder("all", null, now)).toList();
            if (!shareable.isEmpty()) snapshot.put(owner, shareable);
        });
        return snapshot;
    }

    public synchronized boolean receive(Memory memory, UUID source, String sourceName, UUID recipient, long now) {
        if (closed || memory.privateMemory || memory.interest.score < GOSSIP_THRESHOLD || memory.hops >= limits.maxHops || memory.expiresAt <= now
            || memory.revision > persistedRevision || !memory.ownerId.equals(source)
            || !has(source, memory.rootId) || has(recipient, memory.rootId) || source.equals(recipient)) return false;
        Memory current = memories.get(source).get(memory.rootId);
        if (current.privateMemory || !current.equals(memory)) return false;
        insert(new Memory(memory.rootId, recipient, memory.playerId, memory.playerName,
            memory.originId, memory.originName, source, label(sourceName), memory.summary,
            false, memory.createdAt, memory.expiresAt, memory.hops + 1, ++revision, memory.interest, Math.max(now, memory.createdAt)));
        return true;
    }

    public synchronized List<Memory> recall(UUID owner, UUID player, String query, boolean village, long now) {
        prune(now);
        var entries = memories.get(owner);
        if (entries == null) return List.of();
        // Scope before computing corpus statistics: another player's private knowledge must
        // influence neither the returned facts nor their ordering.
        List<Memory> candidates = entries.values().stream()
            .filter(m -> m.revision <= persistedRevision && (m.playerId.equals(player) || (village && !m.privateMemory)))
            .toList();
        Set<String> terms = new LinkedHashSet<>(searchWords(query));
        if (terms.isEmpty()) return candidates.stream().sorted(priorityOrder("all", player, now)).limit(8).toList();
        if (candidates.isEmpty()) return List.of();
        List<SearchDocument> documents = candidates.stream().map(VillagerMemoryStore::searchDocument).toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (SearchDocument document : documents) {
            for (String term : document.frequencies.keySet()) documentFrequency.merge(term, 1, Integer::sum);
        }
        double averageLength = Math.max(1, documents.stream().mapToInt(SearchDocument::length).average().orElse(1));
        return documents.stream()
            .map(document -> new SearchResult(document.memory,
                retrievalScore(document, terms, documentFrequency, documents.size(), averageLength)))
            .filter(result -> result.score > 0)
            .sorted(Comparator.comparingDouble(SearchResult::score).reversed()
                .thenComparing(SearchResult::memory, priorityOrder("all", player, now)))
            .limit(8).map(SearchResult::memory).toList();
    }

    /** Bounded session context: recent personal details and salient local knowledge both get space. */
    public synchronized List<Memory> workingContext(UUID owner, UUID player, long now) {
        prune(now);
        var entries = memories.get(owner);
        if (entries == null) return List.of();
        List<Memory> visible = entries.values().stream()
            .filter(m -> m.revision <= persistedRevision && (m.playerId.equals(player) || !m.privateMemory)).toList();
        Map<UUID, Memory> selected = new LinkedHashMap<>();
        visible.stream().filter(m -> m.playerId.equals(player))
            .sorted(Comparator.comparingLong(Memory::createdAt).reversed())
            .limit(16).forEach(m -> selected.put(m.rootId, m));
        visible.stream().sorted(priorityOrder("all", player, now))
            .filter(m -> !selected.containsKey(m.rootId))
            .limit(32 - selected.size()).forEach(m -> selected.put(m.rootId, m));
        return List.copyOf(selected.values());
    }

    /** Only stories this villager actually heard can become an unsolicited rumour opening. */
    public synchronized List<Memory> heardRumours(UUID owner, UUID player, String profession, long now) {
        prune(now);
        var entries = memories.get(owner);
        if (entries == null) return List.of();
        return entries.values().stream().filter(m -> m.revision <= persistedRevision && !m.privateMemory
            && m.hops > 0 && m.interest.score >= GOSSIP_THRESHOLD)
            .sorted(priorityOrder(profession, player, now)).toList();
    }

    public static Comparator<Memory> priorityOrder(String profession, UUID player, long now) {
        return Comparator.<Memory>comparingLong(m -> priority(m, profession, player, now)).reversed()
            .thenComparing(Comparator.comparingLong(Memory::createdAt).reversed());
    }

    static long priority(Memory memory, String profession, UUID player, long now) {
        long ageHours = Math.max(0, now - memory.createdAt) / 3_600_000;
        int audience = memory.interest.audiences.contains(profession) && !profession.equals("all") ? 18 : 0;
        int personal = memory.playerId.equals(player) ? 15 : 0;
        return memory.interest.score + audience + personal + Math.max(0, 12 - ageHours / 6) - memory.hops * 4L;
    }

    private static List<String> searchWords(String text) { return TextTerms.words(text); }

    private static SearchDocument searchDocument(Memory memory) {
        List<String> words = new ArrayList<>(searchWords(memory.summary));
        // These names are existing attributed metadata, not model-generated enrichment.
        // Deduplicate identical source/origin names before indexing them.
        for (String name : new LinkedHashSet<>(List.of(memory.playerName, memory.originName, memory.sourceName))) {
            words.addAll(searchWords(name));
        }
        Map<String, Integer> frequencies = new HashMap<>();
        for (String word : words) frequencies.merge(word, 1, Integer::sum);
        return new SearchDocument(memory, frequencies, words.size());
    }

    private static double retrievalScore(SearchDocument document, Set<String> terms,
                                         Map<String, Integer> documentFrequency, int count, double averageLength) {
        double score = 0;
        for (String term : terms) {
            int frequency = document.frequencies.getOrDefault(term, 0);
            if (frequency == 0) continue;
            int containing = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log1p((count - containing + 0.5) / (containing + 0.5));
            double norm = BM25_K1 * (1 - BM25_B + BM25_B * document.length / averageLength);
            score += idf * frequency * (BM25_K1 + 1) / (frequency + norm);
        }
        return score;
    }

    public synchronized CompletableFuture<Void> flush() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Memory store is closed"));
        if (revision <= submittedRevision && !lastWrite.isCompletedExceptionally()) return lastWrite;
        List<Memory> snapshot = memories.values().stream().flatMap(m -> m.values().stream()).toList();
        long savingRevision = revision;
        submittedRevision = revision;
        lastWrite = CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                try {
                    String json = GSON.toJson(new Snapshot(3, snapshot));
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_FILE_BYTES) throw new IOException("Memory save exceeds size limit");
                    Files.write(temp, bytes);
                    try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temp); }
                synchronized (this) { persistedRevision = savingRevision; }
            } catch (IOException failure) { throw new CompletionException(failure); }
        }, writer);
        return lastWrite;
    }

    @Override public void close() throws IOException {
        CompletableFuture<Void> saving;
        synchronized (this) { if (closed) return; saving = flush(); closed = true; }
        writer.shutdown();
        try { saving.get(15, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Memory save interrupted", failure); }
        catch (ExecutionException | TimeoutException failure) { throw new IOException("Memory save failed", failure); }
    }
}
