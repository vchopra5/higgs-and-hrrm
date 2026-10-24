package dev.higgs;

import java.util.List;

public final class VoiceCaptionsTest {
    public static void main(String[] args) {
        VoiceCaptions captions = new VoiceCaptions();
        check(captions.update("villager", "a", "Hello ", false).isEmpty(), "deltas wait for the final");
        captions.update("villager", "a", "there", false);
        check(captions.update("villager", "a", "Hello there!", true).equals(List.of(line("villager", "Hello there!"))),
            "final posts once, not the deltas too");
        check(captions.update("villager", "a", "Hello there!", true).isEmpty(), "repeated final is not reposted");
        check(captions.update("you", "b", "Bread please", true).equals(List.of(line("you", "Bread please"))), "user line posts");
        captions.update("villager", "c", "Bread costs ", false);
        check(captions.update("you", "d", "Wait", true).equals(List.of(line("villager", "Bread costs"), line("you", "Wait"))),
            "interrupted reply posts before the next line");
        check(captions.update("villager", "c", "Bread costs one emerald.", true).isEmpty(), "late final of posted reply skipped");
        captions.update("villager", "e", "x".repeat(5000), false);
        check(captions.flush().getFirst().text().length() == 4000, "bounded memory");
        check(captions.flush().isEmpty(), "flush empties");
        check(captions.update("villager", "f", "§cRed\u0007 text", true).getFirst().text().equals("Red  text"),
            "formatting codes and control characters removed");
        check(captions.update("you", "g", "   ", true).isEmpty(), "blank lines skipped");
        System.out.println("Voice caption regression checks passed");
    }
    private static VoiceCaptions.Line line(String speaker, String text) { return new VoiceCaptions.Line(speaker, text); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
