package dev.higgs;

import java.util.UUID;

/**
 * How two villagers relate (TinyTroupe-style persona relationships). Derived from both identities, so it is the same
 * from either side, never changes, and needs no storage.
 */
final class VillagerBonds {
    private VillagerBonds() {}

    static String relation(UUID a, UUID b) {
        if (a.equals(b)) return "self";
        // Order-independent mix of both identities.
        long mixed = (a.getMostSignificantBits() ^ a.getLeastSignificantBits()) ^ (b.getMostSignificantBits() ^ b.getLeastSignificantBits());
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        int roll = (int) Math.floorMod(mixed, 100L);
        return roll < 8 ? "sibling" : roll < 32 ? "friend" : roll < 44 ? "rival" : "acquaintance";
    }

    /** A short phrase for prompts: "your sister Iris" reads better than a label. */
    static String describe(String relation, String name) {
        return switch (relation) {
            case "sibling" -> "your sibling " + name;
            case "friend" -> "your good friend " + name;
            case "rival" -> name + ", your rival (you needle each other, though you would never wish them harm)";
            default -> name + ", a neighbour you know in passing";
        };
    }
}
