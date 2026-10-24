package dev.higgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Village-wide opinion and the gossip that moves it; free of Minecraft types so it can be tested. */
final class VillageReputation {
    private VillageReputation() {}

    /** Mean standing of the villagers in a village. Villagers who never met the player count as neutral (0). */
    static double average(List<Integer> standings) {
        return standings.isEmpty() ? 0 : standings.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    static String band(double average) { return Negotiation.band((int) Math.round(average)).name(); }

    static String describe(double average) {
        String band = band(average);
        return Character.toUpperCase(band.charAt(0)) + band.substring(1) + String.format(Locale.ROOT, " (%+.1f)", average);
    }

    /**
     * One villager talking about the player to another. A speaker with a clear opinion (not neutral) pulls the
     * listener one point toward it, never past the speaker's own view and never into hostility: only the player's own
     * actions make a villager hostile. Returns the listener's new standing.
     */
    static int influence(int speaker, int listener) {
        if (Negotiation.band(speaker).name().equals("neutral")) return listener;
        int next = speaker > listener ? listener + 1 : speaker < listener ? listener - 1 : listener;
        return Math.max(Negotiation.HOSTILE + 1, Math.min(5, next));
    }

    /** A random unused name; once all are taken, names repeat with a numeral ("Oakhollow II"). */
    static String pickName(List<String> names, Set<String> used, RandomGenerator random) {
        if (names.isEmpty()) return "Village";
        for (int round = 1; ; round++) {
            List<String> free = new ArrayList<>();
            for (String name : names) {
                String candidate = round == 1 ? name : name + " " + roman(round);
                if (!used.contains(candidate)) free.add(candidate);
            }
            if (!free.isEmpty()) return free.get(random.nextInt(free.size()));
        }
    }

    private static String roman(int number) {
        String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return number < numerals.length ? numerals[number] : Integer.toString(number);
    }
}
