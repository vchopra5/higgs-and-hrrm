package dev.higgs;

import java.util.*;

/** Pure proximity/cooldown policy; the game adapter supplies loaded, eligible entities. */
public final class VillagerGossip {
    public record Contact(UUID id, String name, String dimension, double x, double y, double z, boolean eligible, String profession) {
        public Contact(UUID id, String name, String dimension, double x, double y, double z, boolean eligible) {
            this(id, name, dimension, x, y, z, eligible, "none");
        }
    }
    public record Policy(int threshold, long delayMs, int cooldownPercent, List<String> interests) {
        public Policy {
            if (threshold < 55 || threshold > 100 || delayMs < 0 || cooldownPercent < 100 || cooldownPercent > 400)
                throw new IllegalArgumentException("Invalid personality gossip policy");
            interests = List.copyOf(interests);
        }
        public static Policy neutral() { return new Policy(55, 0, 100, List.of()); }
    }
    private record Pair(UUID first, UUID second) {
        static Pair of(UUID a, UUID b) { return a.compareTo(b) < 0 ? new Pair(a, b) : new Pair(b, a); }
    }
    private final double radiusSquared;
    private final long cooldownMs;
    private final Map<Pair, Long> cooldowns = new HashMap<>();

    public VillagerGossip(double radius, long cooldownMs) {
        if (!Double.isFinite(radius) || radius <= 0 || cooldownMs < 1) throw new IllegalArgumentException("Invalid gossip settings");
        this.radiusSquared = radius * radius;
        this.cooldownMs = cooldownMs;
    }

    public boolean spread(Contact source, Contact target, List<VillagerMemoryStore.Memory> snapshot,
                          VillagerMemoryStore store, long now) {
        return spread(source, target, snapshot, store, now, Policy.neutral(), Policy.neutral());
    }
    public boolean spread(Contact source, Contact target, List<VillagerMemoryStore.Memory> snapshot,
                          VillagerMemoryStore store, long now, Policy sourcePolicy, Policy targetPolicy) {
        if (sourcePolicy == null || targetPolicy == null) return false;
        if (!source.eligible || !target.eligible || source.id.equals(target.id)
            || !source.dimension.equals(target.dimension)) return false;
        double dx = source.x - target.x, dy = source.y - target.y, dz = source.z - target.z;
        if (dx * dx + dy * dy + dz * dz > radiusSquared) return false;
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        Pair pair = Pair.of(source.id, target.id);
        if (cooldowns.containsKey(pair) || cooldowns.size() >= 4096) return false;
        for (var memory : snapshot.stream()
            .filter(memory -> memory.interest().score() >= sourcePolicy.threshold && now - memory.learnedAt() >= sourcePolicy.delayMs)
            .sorted(order(target.profession, null, now, targetPolicy.interests)).toList()) {
            if (store.receive(memory, source.id, source.name, target.id, now)) {
                cooldowns.put(pair, now + cooldownMs * sourcePolicy.cooldownPercent / 100);
                return true;
            }
        }
        return false;
    }
    public static Comparator<VillagerMemoryStore.Memory> order(String profession, UUID player, long now, List<String> interests) {
        return Comparator.<VillagerMemoryStore.Memory>comparingLong(memory ->
            VillagerMemoryStore.priority(memory, profession, player, now) + interestBonus(memory, interests)).reversed()
            .thenComparing(VillagerMemoryStore.priorityOrder(profession, player, now));
    }
    private static int interestBonus(VillagerMemoryStore.Memory memory, List<String> interests) {
        Set<String> words = new HashSet<>(Arrays.asList(memory.summary().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        return interests.stream().anyMatch(words::contains) ? 6 : 0;
    }
}
