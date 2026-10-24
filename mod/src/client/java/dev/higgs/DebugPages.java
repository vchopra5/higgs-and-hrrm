package dev.higgs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Formats backend debug events into HUD rows for the F6/F7 pages; free of Minecraft types so it is testable. */
final class DebugPages {
    private DebugPages() {}

    static List<String> conversation(JsonObject diagnostics) {
        List<String> lines = new ArrayList<>();
        JsonObject debug = object(diagnostics, "debug");
        if (debug == null) { lines.add("Waiting for backend diagnostics…"); return lines; }
        String id = text(debug, "sessionId", "?");
        lines.add("Session " + id.substring(0, Math.min(8, id.length())) + "  model " + text(debug, "model", "?")
            + "  mode " + text(debug, "mode", "?").toUpperCase(Locale.ROOT));
        lines.add("Voice " + text(diagnostics, "voice", "?") + (bool(diagnostics, "voiceFallback") ? " (fallback)" : "")
            + "  upstream " + (bool(diagnostics, "upstreamReady") ? "ready" : "not ready"));
        lines.add("Elapsed " + clock(number(debug, "elapsedMs")) + " / " + clock(number(debug, "limitMs"))
            + "  turn " + number(debug, "turnId") + "  state " + text(debug, "state", "?"));
        lines.add("Responses " + number(diagnostics, "responses") + "  first audio " + text(diagnostics, "firstAudioLatencyMs", "—")
            + "ms  VAD " + number(diagnostics, "speechStarts") + "/" + number(diagnostics, "speechStops"));
        lines.add("Last event " + text(diagnostics, "lastEvent", "?") + "  error " + text(diagnostics, "lastError", "none"));
        JsonObject suppressed = object(diagnostics, "suppressedAudio");
        if (suppressed != null) {
            StringBuilder counts = new StringBuilder();
            suppressed.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsLong() > 0)
                    counts.append(counts.isEmpty() ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue().getAsLong());
            });
            lines.add("Suppressed audio " + (counts.isEmpty() ? "none" : counts));
        }
        lines.add("");
        lines.add("Pending tools");
        List<JsonObject> pending = objects(debug, "pending");
        if (pending.isEmpty()) lines.add("  none");
        for (JsonObject tool : pending) {
            lines.add("  … " + text(tool, "name", "?") + " " + text(tool, "arguments", "") + "  " + seconds(number(tool, "ageMs"))
                + (bool(tool, "internal") ? "  [internal]" : ""));
        }
        lines.add("Recent tools (newest first)");
        List<JsonObject> tools = objects(debug, "tools");
        if (tools.isEmpty()) lines.add("  none");
        for (JsonObject tool : tools.reversed()) {
            lines.add("  " + text(tool, "name", "?") + " " + text(tool, "arguments", "") + " → " + text(tool, "result", "")
                + "  (" + number(tool, "durationMs") + "ms, turn " + number(tool, "turnId") + ", " + seconds(number(tool, "ageMs")) + " ago)"
                + (bool(tool, "internal") ? "  [internal]" : ""));
        }
        lines.add("Provider events (newest first)");
        List<JsonObject> events = objects(debug, "events");
        if (events.isEmpty()) lines.add("  none");
        for (JsonObject event : events.reversed()) {
            long count = number(event, "count");
            lines.add("  " + seconds(number(event, "ageMs")) + "  " + text(event, "type", "?") + (count > 1 ? " ×" + count : ""));
        }
        return lines;
    }

    private static final Set<String> HEADER_KEYS = Set.of("name", "profession", "level", "playerName", "autoGreeting");
    private static final List<String> ORDERED_KEYS = List.of("personality", "openingRumour", "heardRumours", "memories");

    static List<String> context(JsonObject message) {
        List<String> lines = new ArrayList<>();
        JsonObject context = object(message, "context");
        if (context == null) { lines.add("No context yet. Start a conversation to capture what Boson receives."); return lines; }
        lines.add("Prompt " + number(message, "instructionsChars") + " chars  ·  voice " + text(message, "voice", "?")
            + (bool(message, "voiceFallback") ? " (fallback)" : ""));
        lines.add("Villager " + text(context, "name", "?") + " (" + text(context, "profession", "?") + ", level "
            + text(context, "level", "?") + ")  player " + text(context, "playerName", "?")
            + "  auto greeting " + text(context, "autoGreeting", "?"));
        String character = text(message, "characterInstructions", "");
        if (!character.isBlank()) { lines.add(""); lines.add("Character prompt"); lines.add("  " + character.trim()); }
        for (String key : ORDERED_KEYS) {
            if (!context.has(key)) continue;
            lines.add("");
            render(lines, "", key, context.get(key));
        }
        if (context.has("offers") && context.get("offers").isJsonArray()) {
            lines.add("");
            lines.add("offers: [" + context.getAsJsonArray("offers").size() + "]");
            for (JsonElement element : context.getAsJsonArray("offers")) {
                if (element.isJsonObject()) lines.add("  " + offer(element.getAsJsonObject()));
            }
        }
        for (var entry : context.entrySet()) {
            String key = entry.getKey();
            if (HEADER_KEYS.contains(key) || ORDERED_KEYS.contains(key) || key.equals("offers")) continue;
            lines.add("");
            render(lines, "", key, entry.getValue());
        }
        return lines;
    }

    /** Generic JSON tree: objects nest, arrays list their entries, and flat objects collapse onto one row. */
    private static void render(List<String> lines, String indent, String key, JsonElement value) {
        if (value == null || value.isJsonNull()) lines.add(indent + key + ": —");
        else if (value.isJsonPrimitive()) lines.add(indent + key + ": " + value.getAsString());
        else if (value.isJsonArray()) {
            lines.add(indent + key + ": " + (value.getAsJsonArray().isEmpty() ? "(none)" : "[" + value.getAsJsonArray().size() + "]"));
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonObject()) item(lines, indent + "  ", element.getAsJsonObject());
                else if (element.isJsonPrimitive()) lines.add(indent + "  - " + element.getAsString());
                else lines.add(indent + "  - " + element);
            }
        } else {
            lines.add(indent + key + ":");
            for (var entry : value.getAsJsonObject().entrySet()) render(lines, indent + "  ", entry.getKey(), entry.getValue());
        }
    }

    private static void item(List<String> lines, String indent, JsonObject object) {
        StringBuilder flat = new StringBuilder();
        List<java.util.Map.Entry<String, JsonElement>> nested = new ArrayList<>();
        for (var entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                flat.append(flat.isEmpty() ? "" : ", ").append(entry.getKey()).append(": ").append(entry.getValue().getAsString());
            } else nested.add(entry);
        }
        lines.add(indent + "- " + flat);
        for (var entry : nested) render(lines, indent + "  ", entry.getKey(), entry.getValue());
    }

    private static String offer(JsonObject offer) {
        StringBuilder line = new StringBuilder("#" + text(offer, "offerIndex", "?") + "  ");
        if (offer.has("costA")) {
            line.append(stack(object(offer, "costA")));
            String secondary = stack(object(offer, "costB"));
            if (!secondary.isEmpty()) line.append(" + ").append(secondary);
            line.append(" → ");
        }
        line.append(stack(object(offer, "result")));
        if (bool(offer, "outOfStock")) line.append("  [out of stock]");
        if (bool(offer, "unavailable")) line.append("  [no bundle fits]");
        return line.toString();
    }

    private static String stack(JsonObject item) {
        if (item == null || number(item, "count") <= 0 || text(item, "item", "").equals("minecraft:air")) return "";
        return number(item, "count") + " " + text(item, "name", text(item, "item", "?"));
    }

    /** Wraps one row to a pixel width, indenting continuation rows under the original indent. */
    static List<String> wrap(String line, int width, ToIntFunction<String> measure) {
        List<String> out = new ArrayList<>();
        if (line.isEmpty() || measure.applyAsInt(line) <= width) { out.add(line); return out; }
        int leading = 0;
        while (leading < line.length() && line.charAt(leading) == ' ') leading++;
        String indent = " ".repeat(Math.min(leading + 2, 24));
        String rest = line;
        String prefix = "";
        while (!rest.isEmpty()) {
            String candidate = prefix + rest;
            if (measure.applyAsInt(candidate) <= width) { out.add(candidate); break; }
            // Longest prefix that fits, then back off to the last space when that keeps a useful amount of text.
            int low = prefix.length() + 1, high = candidate.length();
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (measure.applyAsInt(candidate.substring(0, middle)) <= width) low = middle;
                else high = middle - 1;
            }
            int end = low;
            int space = candidate.lastIndexOf(' ', end);
            if (space > prefix.length() + (end - prefix.length()) / 2) end = space;
            out.add(candidate.substring(0, end));
            rest = candidate.substring(end).stripLeading();
            prefix = indent;
        }
        return out;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static List<JsonObject> objects(JsonObject parent, String key) {
        List<JsonObject> result = new ArrayList<>();
        JsonElement value = parent.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) if (element.isJsonObject()) result.add(element.getAsJsonObject());
        }
        return result;
    }

    private static String text(JsonObject parent, String key, String fallback) {
        JsonElement value = parent.get(key);
        String text = value == null || value.isJsonNull() ? fallback : value.isJsonPrimitive() ? value.getAsString() : value.toString();
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }

    private static long number(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        try { return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private static boolean bool(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static String clock(long ms) { return String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1000 % 60); }
    static String seconds(long ms) { return String.format(Locale.ROOT, "%.1fs", ms / 1000.0); }
}
