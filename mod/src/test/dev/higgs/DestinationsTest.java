package dev.higgs;

import java.util.List;

public final class DestinationsTest {
    public static void main(String[] args) {
        List<String> known = List.of("Iris", "Reed 2", "Alden");
        var iris = Destinations.interpret("Can you take me to Iris?", known);
        check(iris.size() == 1 && iris.getFirst().kind() == Destinations.Kind.VILLAGER && iris.getFirst().value().equals("Iris"), "by name");
        check(Destinations.interpret("where's reed", known).getFirst().value().equals("Reed 2"), "by call name, any case");
        var librarian = Destinations.interpret("Show me the librarian, please", known).getFirst();
        check(librarian.kind() == Destinations.Kind.PROFESSION && librarian.value().equals("librarian"), "by trade");
        var library = Destinations.interpret("Where is the library?", known).getFirst();
        check(library.kind() == Destinations.Kind.LANDMARK && library.value().equals("librarian") && library.label().equals("the library"),
            "a building is a landmark");
        var bell = Destinations.interpret("Take me to the bell", known).getFirst();
        check(bell.kind() == Destinations.Kind.LANDMARK && bell.value().equals("meeting") && bell.label().equals("the village bell"), "the bell");
        check(Destinations.interpret("I need a smith", known).stream().map(Destinations.Guess::value).toList()
            .containsAll(List.of("armorer", "weaponsmith", "toolsmith")), "any smith");
        check(Destinations.interpret("the priest", known).getFirst().value().equals("cleric"), "synonyms");
        check(Destinations.interpret("take me to the fishermen", known).getFirst().value().equals("fisherman"), "plurals");
        check(Destinations.interpret("take me somewhere nice", known).isEmpty(), "nothing recognisable");
        check(Destinations.interpret("Iris told me about the bell", known).getFirst().kind() == Destinations.Kind.VILLAGER, "names first");
        check(Destinations.interpret("alderman", known).isEmpty(), "whole words only");
        System.out.println("Destination matching checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
