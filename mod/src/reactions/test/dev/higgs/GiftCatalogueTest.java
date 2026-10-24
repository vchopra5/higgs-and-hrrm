package dev.higgs;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class GiftCatalogueTest {
    public static void main(String[] args) {
        UUID iris = UUID.fromString("8f358353-937f-4f95-85a9-3221b7d9897b");
        var prefs = GiftCatalogue.preferences(iris, "librarian");
        check(prefs.equals(GiftCatalogue.preferences(iris, "librarian")), "preferences are stable: remembered without storage");
        check(prefs.loves().size() == 2 && prefs.dislikes().size() == 2, "two loves, two dislikes");
        check(Set.of("minecraft:book", "minecraft:enchanted_book").contains(prefs.loves().getFirst()), "first love fits the trade");
        check(prefs.loves().stream().noneMatch(prefs.dislikes()::contains), "never loves and dislikes the same item");
        Set<String> variety = new HashSet<>();
        for (int i = 0; i < 50; i++) variety.add(GiftCatalogue.preferences(new UUID(i, i * 31L), "farmer").dislikes().getFirst());
        check(variety.size() > 4, "different villagers have different dislikes");
        check(GiftCatalogue.judge(prefs, prefs.loves().get(1)) == GiftCatalogue.Verdict.LOVED, "loved");
        check(GiftCatalogue.judge(prefs, prefs.dislikes().getFirst()) == GiftCatalogue.Verdict.DISLIKED, "disliked");
        String precious = prefs.loves().contains("minecraft:diamond") || prefs.dislikes().contains("minecraft:diamond") ? "minecraft:emerald" : "minecraft:diamond";
        check(GiftCatalogue.judge(prefs, precious) == GiftCatalogue.Verdict.PRECIOUS, "valuables are precious");
        String food = prefs.loves().contains("minecraft:bread") ? "minecraft:apple" : "minecraft:bread";
        check(GiftCatalogue.judge(prefs, food) == GiftCatalogue.Verdict.LIKED, "food is liked");
        check(GiftCatalogue.judge(prefs, "minecraft:dirt") == GiftCatalogue.Verdict.JUNK, "junk");
        check(GiftCatalogue.gain(GiftCatalogue.Verdict.LOVED) == 3 && GiftCatalogue.gain(GiftCatalogue.Verdict.PRECIOUS) == 2
            && GiftCatalogue.gain(GiftCatalogue.Verdict.LIKED) == 1
            && GiftCatalogue.gain(GiftCatalogue.Verdict.JUNK) == 0 && GiftCatalogue.gain(GiftCatalogue.Verdict.DISLIKED) == 0, "gains");
        var loved = GiftCatalogue.Verdict.LOVED;
        var disliked = GiftCatalogue.Verdict.DISLIKED;
        check(GiftCatalogue.standingAfter(0, loved, false, false) == 3, "a loved gift is a big step");
        check(GiftCatalogue.standingAfter(0, GiftCatalogue.Verdict.PRECIOUS, false, false) == 2, "a diamond is a generous gift");
        check(GiftCatalogue.standingAfter(4, loved, false, false) == 5, "capped at five");
        check(GiftCatalogue.standingAfter(1, loved, false, true) == 1, "gifts on cooldown earn nothing");
        check(GiftCatalogue.standingAfter(1, disliked, false, false) == 1, "the first unwanted gift is only refused");
        check(GiftCatalogue.standingAfter(1, disliked, true, false) == 0, "repeating it costs a point");
        check(GiftCatalogue.standingAfter(-4, disliked, true, false) == -4, "gifts never make a villager hostile");
        check(GiftCatalogue.standingAfter(-5, disliked, true, false) == -5, "already hostile stays hostile");
        check(GiftCatalogue.standingAfter(-5, loved, false, false) == -2, "a kind gift helps make amends");
        System.out.println("Gift preference and reputation checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
