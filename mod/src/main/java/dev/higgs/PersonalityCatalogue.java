package dev.higgs;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The same versioned catalogue is bundled into Fabric and read by the Node bridge. */
public final class PersonalityCatalogue {
    public record Archetype(String id, String name, String defaultStyle, String voiceKey,
                            String motive, String contradiction, String socialStyle, List<String> interests,
                            long gossipDelayMs, int gossipThreshold, int cooldownPercent) {}
    public record Style(String id, String delivery) {}
    public record Edition(int version, List<Archetype> archetypes, List<Style> styles, List<String> names) {}
    private record Document(int schemaVersion, int currentVersion, List<Edition> versions) {}
    private final Document document;
    private final Map<Integer, Edition> editions = new HashMap<>();

    public static PersonalityCatalogue bundled() throws IOException {
        try (InputStream input = PersonalityCatalogue.class.getResourceAsStream("/assets/higgs_villagers/personalities.json")) {
            if (input == null) throw new IOException("Personality catalogue missing");
            return new PersonalityCatalogue(input);
        }
    }

    public PersonalityCatalogue(InputStream input) throws IOException {
        try {
            byte[] bytes = input.readNBytes(131_073);
            if (bytes.length > 131_072) throw new IllegalArgumentException();
            document = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class);
            if (document == null || document.schemaVersion != 1 || document.versions == null || document.versions.isEmpty()) throw new IllegalArgumentException();
            for (Edition edition : document.versions) {
                if (edition.version < 1 || editions.putIfAbsent(edition.version, edition) != null
                    || edition.archetypes == null || edition.archetypes.isEmpty() || edition.styles == null
                    || edition.styles.isEmpty() || edition.names == null || edition.names.isEmpty()) throw new IllegalArgumentException();
                Set<String> styles = new HashSet<>(), archetypes = new HashSet<>(), names = new HashSet<>();
                for (Style style : edition.styles) {
                    if (!id(style.id) || !styles.add(style.id) || !text(style.delivery, 400)) throw new IllegalArgumentException();
                }
                for (Archetype a : edition.archetypes) {
                    if (!id(a.id) || !archetypes.add(a.id) || !text(a.name, 40) || !styles.contains(a.defaultStyle)
                        || !Set.of("warm", "thoughtful", "steady").contains(a.voiceKey)
                        || !text(a.motive, 240) || !text(a.contradiction, 240) || !text(a.socialStyle, 600)
                        || a.interests == null || a.interests.isEmpty() || a.interests.size() > 8
                        || a.interests.stream().anyMatch(term -> !text(term, 32)) || a.gossipDelayMs < 0
                        || a.gossipDelayMs > 300_000 || a.gossipThreshold < 55 || a.gossipThreshold > 100
                        || a.cooldownPercent < 100 || a.cooldownPercent > 400) throw new IllegalArgumentException();
                }
                for (String name : edition.names) if (!text(name, 40) || !names.add(name)) throw new IllegalArgumentException();
            }
            if (!editions.containsKey(document.currentVersion)) throw new IllegalArgumentException();
        } catch (RuntimeException failure) { throw new IOException("Invalid personality catalogue", failure); }
    }

    static boolean text(String text, int max) {
        return text != null && !text.isBlank() && text.length() <= max && text.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean id(String text) { return text != null && text.matches("[a-z][a-z0-9_]{0,31}"); }
    public Edition current() { return edition(document.currentVersion); }
    public Edition edition(int version) {
        Edition result = editions.get(version);
        if (result == null) throw new IllegalArgumentException("Unknown catalogue version");
        return result;
    }
    public Archetype archetype(int version, String id) {
        return edition(version).archetypes.stream().filter(a -> a.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown archetype"));
    }
    public Style style(int version, String id) {
        return edition(version).styles.stream().filter(s -> s.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown style"));
    }
}
