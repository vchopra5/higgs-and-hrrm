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
        System.out.println("Wilds matching checks passed");
    }

    private static void check(String request, String id) {
        var place = Wilds.interpret(request);
        if (place == null || !place.id().equals(id)) throw new AssertionError(request + " -> " + place);
    }
}
