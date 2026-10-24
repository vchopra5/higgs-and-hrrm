package dev.higgs;

import java.util.*;

/** Conversation-topic cooldowns, scoped to the listener, player and original story. */
public final class RumourConversation {
    private record Topic(UUID villager, UUID player, UUID root) {}
    private final Map<Topic, Long> offered = new HashMap<>();
    private final long repeatAfterMs;

    public RumourConversation(long repeatAfterMs) {
        if (repeatAfterMs < 1) throw new IllegalArgumentException("Invalid conversation cooldown");
        this.repeatAfterMs = repeatAfterMs;
    }

    public Optional<VillagerMemoryStore.Memory> choose(List<VillagerMemoryStore.Memory> ranked,
                                                       UUID villager, UUID player, long now) {
        offered.entrySet().removeIf(entry -> entry.getValue() <= now);
        return ranked.stream().filter(m -> !m.privateMemory() && m.hops() > 0
            && m.interest().score() >= VillagerMemoryStore.GOSSIP_THRESHOLD && m.ownerId().equals(villager)
            && m.expiresAt() > now && !offered.containsKey(new Topic(villager, player, m.rootId()))).findFirst();
    }

    public void markOffered(UUID villager, UUID player, UUID root, long now) {
        offered.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (offered.size() >= 4096) {
            var oldest = offered.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow();
            offered.remove(oldest.getKey());
        }
        offered.put(new Topic(villager, player, root), now + repeatAfterMs);
    }

    public void clear() { offered.clear(); }
}
