package dev.higgs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

public final class DebugPagesTest {
    public static void main(String[] args) {
        JsonObject diagnostics = json("""
            {"type":"diagnostics","voice":"chloe","voiceFallback":true,"upstreamReady":true,"responses":3,
             "firstAudioLatencyMs":640,"speechStarts":2,"speechStops":2,"lastEvent":"response.done","lastError":null,
             "suppressedAudio":{"speaking":4,"cancelled":0},
             "debug":{"sessionId":"0123456789abcdef","model":"higgs-realtime","mode":"ptt","state":"idle","turnId":2,
               "elapsedMs":83000,"limitMs":600000,
               "events":[{"type":"session.updated","count":1,"ageMs":9000},{"type":"response.output_audio.delta","count":42,"ageMs":1200}],
               "tools":[{"name":"get_trades","internal":false,"turnId":1,"arguments":"{}","result":"{\\"offers\\":[]}","durationMs":120,"ageMs":5000}],
               "pending":[{"name":"report_treatment","internal":true,"arguments":"{\\"treatment\\":\\"polite\\"}","ageMs":400}]}}""");
        List<String> conversation = DebugPages.conversation(diagnostics);
        check(conversation.contains("Session 01234567  model higgs-realtime  mode PTT"), "session header");
        check(conversation.contains("Voice chloe (fallback)  upstream ready"), "voice row");
        check(conversation.contains("Elapsed 1:23 / 10:00  turn 2  state idle"), "clock row");
        check(conversation.contains("Last event response.done  error none"), "null error shows none");
        check(conversation.contains("Suppressed audio speaking 4"), "only non-zero suppression");
        check(conversation.indexOf("  1.2s  response.output_audio.delta ×42") < conversation.indexOf("  9.0s  session.updated"), "newest event first");
        check(conversation.stream().anyMatch(line -> line.startsWith("  get_trades {} → {\"offers\":[]}  (120ms, turn 1")), "tool row");
        check(conversation.stream().anyMatch(line -> line.startsWith("  … report_treatment") && line.endsWith("[internal]")), "pending row");
        check(DebugPages.conversation(new JsonObject()).equals(List.of("Waiting for backend diagnostics…")), "no debug yet");

        JsonObject context = json("""
            {"type":"debug_context","voice":"oliver","voiceFallback":false,"instructionsChars":6100,"characterInstructions":"Be kind.",
             "context":{"name":"Iris","profession":"toolsmith","level":2,"playerName":"Steve","autoGreeting":true,
               "memories":[{"summary":"Found diamonds","interest":60,"tags":["mine"]}],"heardRumours":[],
               "openingRumour":{"summary":"A creeper","heardFrom":"Bob"},
               "offers":[{"offerIndex":0,"outOfStock":false,"costA":{"item":"minecraft:emerald","name":"Emerald","count":2},
                 "costB":{"item":"minecraft:air","name":"Air","count":0},"result":{"item":"minecraft:flint","name":"Flint","count":10}}],
               "extra":"kept"}}""");
        List<String> page = DebugPages.context(context);
        check(page.getFirst().equals("Prompt 6100 chars  ·  voice oliver"), "context header");
        check(page.contains("Villager Iris (toolsmith, level 2)  player Steve  auto greeting true"), "villager row");
        check(page.contains("  Be kind."), "character prompt");
        check(page.contains("memories: [1]") && page.contains("  - summary: Found diamonds, interest: 60") && page.contains("    tags: [1]"), "memory list");
        check(page.contains("heardRumours: (none)"), "empty list");
        check(page.contains("openingRumour:") && page.contains("  heardFrom: Bob"), "nested object");
        check(page.contains("  #0  2 Emerald → 10 Flint"), "compact offer skips air");
        check(page.contains("extra: kept"), "unknown keys still shown");
        check(DebugPages.context(null).size() == 1, "no context yet");

        List<String> state = DebugPages.state(json("""
            {"type":"debug_state","state":{"conversation":{"with":"Iris the fletcher","openQuote":"1 × Bow for 1 × Emerald (earned with a quest)"},
             "errand":"none","quests":[],"earnedDeals":["Iris: 1 × Bow for 1 × Emerald (earned, unused)"],
             "villagers":[{"name":"Iris","standing":"1 neutral","stage":"friend"}]}}"""));
        check(state.contains("conversation:") && state.contains("  openQuote: 1 × Bow for 1 × Emerald (earned with a quest)"), "live quote");
        check(state.contains("errand: none") && state.contains("quests: (none)"), "empty state");
        check(state.contains("earnedDeals: [1]") && state.contains("  - Iris: 1 × Bow for 1 × Emerald (earned, unused)"), "earned deals");
        check(state.stream().anyMatch(line -> line.contains("name: Iris") && line.contains("stage: friend")), "villager rows: " + state);
        check(DebugPages.state(null).equals(List.of("Asking the server…")), "waiting for the server");

        // One unit per character keeps wrapping deterministic.
        List<String> wrapped = DebugPages.wrap("  alpha beta gamma delta", 12, String::length);
        check(wrapped.equals(List.of("  alpha beta", "    gamma", "    delta")), "wraps at spaces with hanging indent: " + wrapped);
        check(DebugPages.wrap("x".repeat(30), 10, String::length).size() == 4, "long words are split");
        check(DebugPages.wrap("short", 10, String::length).equals(List.of("short")), "short rows unchanged");
        System.out.println("Debug page regression checks passed");
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
