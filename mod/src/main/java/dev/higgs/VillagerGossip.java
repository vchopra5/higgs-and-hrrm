package dev.higgs;

import java.util.*;

/** Pure proximity/cooldown policy; the game adapter supplies loaded, eligible entities. */
public final class VillagerGossip {
    public record Contact(UUID id, String name, String dimension, double x, double y, double z, boolean eligible, String profession) {
        public Contact(UUID id, String name, String dimension, double x, double y, double z, boolean eligible) {
            this(id, name, dimension, x, y, z, eligible, "none");
        }
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
        if (!source.eligible || !target.eligible || source.id.equals(target.id)
            || !source.dimension.equals(target.dimension)) return false;
        double dx = source.x - target.x, dy = source.y - target.y, dz = source.z - target.z;
        if (dx * dx + dy * dy + dz * dz > radiusSquared) return false;
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        Pair pair = Pair.of(source.id, target.id);
        if (cooldowns.containsKey(pair) || cooldowns.size() >= 4096) return false;
        for (var memory : snapshot.stream().sorted(VillagerMemoryStore.priorityOrder(target.profession, null, now)).toList()) {
            if (store.receive(memory, source.id, source.name, target.id, now)) {
                cooldowns.put(pair, now + cooldownMs);
                return true;
            }
        }
        return false;
    }
}
