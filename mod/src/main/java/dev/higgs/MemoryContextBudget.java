package dev.higgs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;

/** Matches the Gson encoding used by the HTTP bridge, including HTML and Unicode escaping. */
final class MemoryContextBudget {
    static final int SESSION_BYTES = 48 * 1024;
    private static final Gson GSON = new Gson();

    private MemoryContextBudget() {}

    static JsonArray limit(JsonArray entries, int maximumBytes) {
        if (maximumBytes < 2) throw new IllegalArgumentException("A JSON array needs at least two bytes");
        JsonArray result = new JsonArray();
        int used = 2; // Array delimiters; each subsequent element also needs a comma.
        for (JsonElement entry : entries) {
            int bytes = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8).length + (result.isEmpty() ? 0 : 1);
            if (bytes > maximumBytes - used) break;
            result.add(entry);
            used += bytes;
        }
        return result;
    }
}
