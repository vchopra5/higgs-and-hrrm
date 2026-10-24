package dev.higgs;

import java.util.Map;

public final class RelationshipStageTest {
    public static void main(String[] args) {
        check(RelationshipStage.of(Map.of(), 5) == RelationshipStage.STRANGER, "no history, no relationship");
        check(RelationshipStage.of(Map.of("first_met", 1), 0) == RelationshipStage.ACQUAINTANCE, "meeting makes an acquaintance");
        var regular = Map.of("first_met", 1, "conversation", 2, "trade", 3);
        check(RelationshipStage.points(regular) == 8, "points");
        check(RelationshipStage.of(regular, 1) == RelationshipStage.FRIEND, "a regular with goodwill is a friend");
        check(RelationshipStage.of(regular, 0) == RelationshipStage.ACQUAINTANCE, "friendship needs goodwill too");
        var devoted = Map.of("first_met", 1, "conversation", 6, "quest_done", 2, "promise_kept", 2, "gift", 3, "outing", 2);
        check(RelationshipStage.of(devoted, 3) == RelationshipStage.CONFIDANT, "a long, warm history makes a confidant");
        check(RelationshipStage.of(devoted, 2) == RelationshipStage.CLOSE_FRIEND, "capped by standing");
        check(RelationshipStage.of(devoted, -2) == RelationshipStage.ACQUAINTANCE, "dislike caps it at acquaintance");
        check(RelationshipStage.points(Map.of("hit", 2, "promise_broken", 1)) == -6, "hurtful acts cost points");
        check(RelationshipStage.named("close friend") == RelationshipStage.CLOSE_FRIEND && RelationshipStage.named("x") == RelationshipStage.STRANGER, "labels round-trip");
        check(RelationshipStage.CONFIDANT.compareTo(RelationshipStage.FRIEND) > 0, "stages are ordered");
        System.out.println("Relationship stage checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
