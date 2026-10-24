package dev.higgs;
public final class VoiceCaptionsTest {
    public static void main(String[] args) {
        VoiceCaptions captions = new VoiceCaptions();
        captions.update("villager", "a", "Hello ", false, 0);
        captions.update("villager", "a", "there", false, 0);
        check(captions.visible(0).getFirst().text().equals("Hello there"), "deltas accumulate");
        captions.update("villager", "a", "Hello there!", true, 0);
        check(captions.visible(0).getFirst().text().equals("Hello there!"), "final replaces, not duplicates");
        captions.update("you", "b", "Bread please", true, 0);
        check(captions.visible(8000).size() == 2, "both speakers remain visible");
        check(captions.visible(9500).getFirst().opacity(9500) == .5f, "fade halfway");
        check(captions.visible(11000).isEmpty(), "expired text removed");
        captions.update("villager", "c", "x".repeat(5000), false, 12000);
        check(captions.visible(12000).getFirst().text().length() == 1200, "bounded memory");
        captions.update("villager", "d", "New turn", false, 12001);
        check(captions.visible(12001).getFirst().text().equals("New turn"), "new turn resets text");
        System.out.println("Voice caption regression checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
