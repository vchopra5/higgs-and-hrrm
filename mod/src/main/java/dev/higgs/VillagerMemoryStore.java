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
                         int hops, long revision) {}
    private record Snapshot(int schemaVersion, List<Memory> memories) {}

    private static final Gson GSON = new Gson();
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
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
        this.file = file;
        this.limits = limits;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Memory save exceeds size limit");
            Snapshot snapshot = GSON.fromJson(Files.readString(file), Snapshot.class);
            if (snapshot == null || snapshot.schemaVersion != 1 || snapshot.memories == null
                || snapshot.memories.size() > limits.total) throw new IOException("Invalid memory save");
            for (Memory memory : snapshot.memories) {
                validate(memory);
                var owner = memories.computeIfAbsent(memory.ownerId, ignored -> new LinkedHashMap<>());
                if (owner.putIfAbsent(memory.rootId, memory) != null) throw new IOException("Duplicate memory entry");
                revision = Math.max(revision, memory.revision);
            }
            persistedRevision = submittedRevision = revision;
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
            || m.createdAt < 0 || m.expiresAt <= m.createdAt || m.revision < 1
            || !validText(m.summary, MAX_SUMMARY) || !validText(m.playerName, 80)
            || !validText(m.originName, 80) || !validText(m.sourceName, 80)
            || (m.privateMemory && m.hops != 0)) throw new IOException("Invalid memory entry");
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
            && value.codePoints().noneMatch(Character::isISOControl);
    }

    public synchronized Memory remember(UUID owner, String ownerName, UUID player, String playerName,
                                        String summary, boolean privateMemory, long now) {
        if (closed) throw new IllegalStateException("Memory store is closed");
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
                    if (previous.privateMemory || !privateMemory) return previous;
                    Memory updated = new Memory(previous.rootId, owner, player, playerName, owner,
                        ownerName, owner, ownerName, previous.summary, true, previous.createdAt,
                        previous.expiresAt, 0, ++revision);
                    entries.put(updated.rootId, updated);
                    return updated;
                }
            }
        }
        Memory memory = new Memory(UUID.randomUUID(), owner, player, playerName, owner, ownerName,
            owner, ownerName, summary.strip(), privateMemory, now, now + limits.lifetimeMs, 0, ++revision);
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
                .filter(m -> m.revision <= persistedRevision && !m.privateMemory && m.hops < limits.maxHops)
                .sorted(Comparator.comparingLong(Memory::createdAt).reversed()).toList();
            if (!shareable.isEmpty()) snapshot.put(owner, shareable);
        });
        return snapshot;
    }

    public synchronized boolean receive(Memory memory, UUID source, String sourceName, UUID recipient, long now) {
        if (closed || memory.privateMemory || memory.hops >= limits.maxHops || memory.expiresAt <= now
            || memory.revision > persistedRevision || !memory.ownerId.equals(source)
            || !has(source, memory.rootId) || has(recipient, memory.rootId) || source.equals(recipient)) return false;
        Memory current = memories.get(source).get(memory.rootId);
        if (current.privateMemory || !current.equals(memory)) return false;
        insert(new Memory(memory.rootId, recipient, memory.playerId, memory.playerName,
            memory.originId, memory.originName, source, label(sourceName), memory.summary,
            false, memory.createdAt, memory.expiresAt, memory.hops + 1, ++revision));
        return true;
    }

    public synchronized List<Memory> recall(UUID owner, UUID player, String query, boolean village, long now) {
        prune(now);
        var entries = memories.get(owner);
        if (entries == null) return List.of();
        Set<String> terms = new HashSet<>(Arrays.asList(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        terms.removeAll(Set.of("", "i", "me", "my", "you", "the", "a", "about", "what", "did", "news", "remember"));
        return entries.values().stream()
            .filter(m -> m.revision <= persistedRevision && (m.playerId.equals(player) || (village && !m.privateMemory)))
            .sorted(Comparator.<Memory>comparingLong(m -> relevance(m, terms)).reversed()
                .thenComparing(Comparator.comparingLong(Memory::createdAt).reversed()))
            .limit(8).toList();
    }

    private static long relevance(Memory memory, Set<String> terms) {
        Set<String> words = new HashSet<>(Arrays.asList(memory.summary.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        return terms.stream().filter(words::contains).count();
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
                    String json = GSON.toJson(new Snapshot(1, snapshot));
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
