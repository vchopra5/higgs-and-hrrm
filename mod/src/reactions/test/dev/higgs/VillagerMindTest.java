package dev.higgs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VillagerMindTest {
    public static void main(String[] args) {
        UUID iris = UUID.fromString("8f358353-937f-4f95-85a9-3221b7d9897b"), bram = UUID.fromString("16ec72fc-6e7e-45b8-9b98-f9076b399bd1");
        check(VillagerBonds.relation(iris, bram).equals(VillagerBonds.relation(bram, iris)), "bonds are mutual");
        check(VillagerBonds.relation(iris, iris).equals("self"), "self");
        Map<String, Integer> spread = new HashMap<>();
        for (int i = 0; i < 4000; i++) spread.merge(VillagerBonds.relation(new UUID(i * 7919L, i), new UUID(i * 104729L, -i)), 1, Integer::sum);
        check(spread.keySet().containsAll(List.of("sibling", "friend", "rival", "acquaintance")), "every bond occurs");
        check(spread.get("acquaintance") > spread.get("friend") && spread.get("friend") > spread.get("rival")
            && spread.get("rival") > spread.get("sibling"), "acquaintances are commonest, siblings rarest: " + spread);
        check(VillagerBonds.describe("sibling", "Iris").equals("your sibling Iris"), "phrase");
        check(VillagerMind.mood(List.of()).equals("calm"), "nothing happened");
        check(VillagerMind.mood(List.of("trade", "gift")).equals("cheerful"), "a gift beats a trade");
        check(VillagerMind.mood(List.of("gift", "hit")).equals("shaken and on edge"), "a hit outweighs a gift");
        check(VillagerMind.goal("farmer", iris, 10).equals(VillagerMind.goal("farmer", iris, 10)), "goal is stable within a day");
        check(!VillagerMind.goal("farmer", iris, 10).equals(VillagerMind.goal("farmer", iris, 11)), "and changes the next day");
        check(VillagerMind.goal("nitwit", iris, 3) != null, "jobless villagers still have something on their mind");
        System.out.println("Villager bonds and state-of-mind checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
