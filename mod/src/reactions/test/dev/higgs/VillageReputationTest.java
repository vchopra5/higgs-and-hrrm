package dev.higgs;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

public final class VillageReputationTest {
    public static void main(String[] args) {
        check(VillageReputation.average(List.of()) == 0, "an empty village is neutral");
        check(VillageReputation.average(List.of(4, 2, 0, 0)) == 1.5, "strangers count as neutral");
        check(VillageReputation.band(1.5).equals("friendly") && VillageReputation.band(1.4).equals("neutral")
            && VillageReputation.band(-2.6).equals("annoyed") && VillageReputation.band(-4.6).equals("hostile"), "bands round the average");
        check(VillageReputation.describe(1.5).equals("Friendly (+1.5)") && VillageReputation.describe(0).equals("Neutral (+0.0)"), "description");
        // Gossip only from villagers with a clear opinion, one step at a time.
        check(VillageReputation.influence(1, 0) == 0 && VillageReputation.influence(-1, 0) == 0, "neutral speakers change nothing");
        check(VillageReputation.influence(3, 0) == 1 && VillageReputation.influence(-3, 0) == -1, "one step toward the speaker");
        check(VillageReputation.influence(2, 2) == 2 && VillageReputation.influence(2, 4) == 3, "never past the speaker's own view");
        check(VillageReputation.influence(-5, -4) == -4 && VillageReputation.influence(-5, -3) == -4, "gossip alone never makes a villager hostile");
        check(VillageReputation.influence(5, -5) == -4, "praise can lift a hostile villager");
        var random = new SplittableRandom(3);
        List<String> names = List.of("Oakhollow", "Ashbrook");
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 6; i++) used.add(VillageReputation.pickName(names, used, random));
        check(used.equals(Set.of("Oakhollow", "Ashbrook", "Oakhollow II", "Ashbrook II", "Oakhollow III", "Ashbrook III")), "names stay unique: " + used);
        check(VillageReputation.pickName(List.of(), used, random).equals("Village"), "fallback name");
        System.out.println("Village reputation and gossip influence checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
