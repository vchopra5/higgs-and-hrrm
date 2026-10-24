package dev.higgs;

public final class WildsTest {
    public static void main(String[] args) {
        check("Where's the nearest forest?", "is_forest");
        check("Take me to the woods", "is_forest");
        check("Is there a birch forest around here?", "birch_forest");
        check("Do you know of any pyramids?", "desert_pyramid");
        check("where is the desert temple", "desert_pyramid");
        check("I'm looking for the desert", "desert");
        check("which way is the sea", "is_ocean");
        check("Have you heard of any hidden chests?", "buried_treasure");
        check("Any buried treasure nearby?", "buried_treasure");
        check("take me to the jungle temple", "jungle_pyramid");
        check("is there another village near here", "village");
        check("Where's the nearest ocean monument?", "monument");
        check("show me the mountains", "is_mountain");
        check("I want to find a witch's hut", "swamp_hut");
        for (String village : new String[] {"take me to the librarian", "where's the bell?", "show me the church", "the temple please", "the farm"})
            if (Wilds.interpret(village) != null) throw new AssertionError("village place matched as wild: " + village);
        if (!Wilds.distance(37).equals("about 40 blocks") || !Wilds.distance(412).equals("about 400 blocks") || !Wilds.distance(3).equals("about 10 blocks"))
            throw new AssertionError("distance rounding");
        if (!Wilds.interpret("Do you know of any pyramids?").label().equals("a desert temple")) throw new AssertionError("label");
        int[] reach = {Wilds.escortBlocks(RelationshipStage.STRANGER, 0), Wilds.escortBlocks(RelationshipStage.ACQUAINTANCE, 0),
            Wilds.escortBlocks(RelationshipStage.FRIEND, 1), Wilds.escortBlocks(RelationshipStage.CLOSE_FRIEND, 2)};
        for (int i = 0; i < reach.length; i++) if (reach[i] <= 0 || i > 0 && reach[i] <= reach[i - 1]) throw new AssertionError("closer friends walk farther");
        if (Wilds.escortBlocks(RelationshipStage.CONFIDANT, 3) != Integer.MAX_VALUE || Wilds.escortBlocks(RelationshipStage.ACQUAINTANCE, 5) != Integer.MAX_VALUE)
            throw new AssertionError("the highest regard has no limit");
        // Playtest: the forest was ~346 blocks out. Faye (confidant) and Lena (friend, opinion 5) both refused under a flat cap.
        if (Wilds.escortBlocks(RelationshipStage.CONFIDANT, 3) < 346 || Wilds.escortBlocks(RelationshipStage.FRIEND, 5) < 346)
            throw new AssertionError("a villager who likes you most walks you to the forest");
        System.out.println("Wilds matching checks passed");
    }

    private static void check(String request, String id) {
        var place = Wilds.interpret(request);
        if (place == null || !place.id().equals(id)) throw new AssertionError(request + " -> " + place);
    }
}
