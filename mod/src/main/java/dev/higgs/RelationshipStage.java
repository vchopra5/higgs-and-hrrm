package dev.higgs;

import java.util.Map;

/**
 * How close a villager and a player have grown, from what the journal saw happen between them. Shared history earns
 * points (finishing a quest or keeping a promise counts more than a routine trade); hurtful acts cost points. Each
 * stage also needs enough goodwill, and a villager who dislikes the player never gets past acquaintance.
 */
enum RelationshipStage {
    STRANGER("stranger", 0, Integer.MIN_VALUE), ACQUAINTANCE("acquaintance", 0, Integer.MIN_VALUE),
    FRIEND("friend", 8, 1), CLOSE_FRIEND("close friend", 18, 2), CONFIDANT("confidant", 32, 3);

    final String label;
    final int points, standing;
    RelationshipStage(String label, int points, int standing) { this.label = label; this.points = points; this.standing = standing; }

    private static final Map<String, Integer> WEIGHTS = Map.ofEntries(
        Map.entry("first_met", 1), Map.entry("conversation", 2), Map.entry("trade", 1), Map.entry("gift", 2),
        Map.entry("quest_done", 3), Map.entry("errand_received", 2), Map.entry("errand_done", 2), Map.entry("outing", 2),
        Map.entry("guided", 1), Map.entry("introduced", 1), Map.entry("kindness", 1), Map.entry("promise_kept", 3),
        Map.entry("hit", -2), Map.entry("insult", -1), Map.entry("promise_broken", -2));

    static int points(Map<String, Integer> counts) {
        int total = 0;
        for (var entry : counts.entrySet()) total += WEIGHTS.getOrDefault(entry.getKey(), 0) * entry.getValue();
        return total;
    }

    /** counts: the pair's live episodes by type; standing: the villager's current opinion, -5 to 5. */
    static RelationshipStage of(Map<String, Integer> counts, int standing) {
        if (counts.values().stream().mapToInt(Integer::intValue).sum() == 0) return STRANGER;
        if (standing <= -2) return ACQUAINTANCE;
        int points = points(counts);
        RelationshipStage reached = ACQUAINTANCE;
        for (RelationshipStage stage : values()) if (points >= stage.points && standing >= stage.standing) reached = stage;
        return reached;
    }

    static RelationshipStage named(String label) {
        for (RelationshipStage stage : values()) if (stage.label.equals(label)) return stage;
        return STRANGER;
    }
}
