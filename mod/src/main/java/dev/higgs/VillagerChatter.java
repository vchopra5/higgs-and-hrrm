package dev.higgs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Now and then two idle villagers near a player chat with each other. The backend voices both sides; players within
 * earshot get a listen link and hear each line from the speaking villager. Only public memories reach the scene.
 */
final class VillagerChatter {
    interface Host {
        CompletableFuture<JsonElement> request(String method, String path, JsonObject body);
        void send(ServerPlayer player, JsonObject message);
        boolean canSend(ServerPlayer player);
        boolean talking(ServerPlayer player);
        boolean talking(Villager villager);
        TradeReputation opinion(ServerPlayer player, Villager villager);
        /** The listener heard the speaker's opinion of the player; its own standing moves to the given score. */
        void influenced(ServerPlayer player, Villager speaker, Villager listener, int standing);
        String villageName(Villager villager);
    }
    private record Scene(MinecraftServer server, String id, Villager a, Villager b, String url, long until, Set<UUID> invited) {}
    private static final double PAIR_DISTANCE_SQUARED = 6 * 6;
    private static final long SCENE_MS = 60_000;
    private static final long RECENT_HIT_MS = 10 * 60_000;

    private final Host host;
    private final VillagerPersonalityService personalities;
    private final VillagerMemoryService memory;
    private final String publicBackend;
    private final boolean enabled;
    private final int intervalTicks;
    private final long cooldownMs;
    private final double hearingSquared;
    private final double chance;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<UUID, Long> lastChat = new HashMap<>();
    private Scene active;
    private boolean starting;

    VillagerChatter(Map<String, String> config, Host host, VillagerPersonalityService personalities,
                    VillagerMemoryService memory, String publicBackend) {
        this.host = host;
        this.personalities = personalities;
        this.memory = memory;
        this.publicBackend = publicBackend;
        enabled = !"false".equalsIgnoreCase(config.getOrDefault("HIGGS_CHATTER_ENABLED", "true"));
        intervalTicks = 20 * Math.max(10, number(config, "HIGGS_CHATTER_INTERVAL_SECONDS", 60));
        cooldownMs = 1000L * Math.max(30, number(config, "HIGGS_CHATTER_COOLDOWN_SECONDS", 300));
        double radius = Math.max(4, Math.min(32, number(config, "HIGGS_CHATTER_RADIUS", 16)));
        hearingSquared = radius * radius;
        chance = Math.max(0, Math.min(100, number(config, "HIGGS_CHATTER_CHANCE_PERCENT", 50))) / 100.0;
    }

    private static int number(Map<String, String> config, String key, int fallback) {
        try { return Integer.parseInt(config.getOrDefault(key, "").trim()); } catch (NumberFormatException e) { return fallback; }
    }

    void tick(MinecraftServer server, int ticks) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (active != null) {
            if (now > active.until()) active = null; // Finished long ago; let any remaining playback end naturally.
            else if (!idle(active.a()) || !idle(active.b())) stop(true);
            else if (ticks % 20 == 0) invite(server, active);
            return;
        }
        if (starting || ticks % intervalTicks != 0 || random.nextDouble() >= chance) return;
        lastChat.values().removeIf(at -> now - at > cooldownMs);
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        Collections.shuffle(players);
        for (ServerPlayer player : players) {
            if (!host.canSend(player) || host.talking(player) || player.isSpectator()) continue;
            List<Villager> nearby = new ArrayList<>(player.level().getEntitiesOfClass(Villager.class,
                player.getBoundingBox().inflate(Math.sqrt(hearingSquared)),
                v -> idle(v) && !lastChat.containsKey(v.getUUID()) && player.distanceToSqr(v) <= hearingSquared));
            Collections.shuffle(nearby);
            for (int i = 0; i < nearby.size(); i++) {
                for (int j = i + 1; j < nearby.size(); j++) {
                    if (nearby.get(i).distanceToSqr(nearby.get(j)) <= PAIR_DISTANCE_SQUARED) {
                        start(server, player, nearby.get(i), nearby.get(j));
                        return;
                    }
                }
            }
        }
    }

    /** Idle and awake: no chatter once villagers wind down for the night. */
    private boolean idle(Villager villager) {
        return villager.isAlive() && !villager.isBaby() && !villager.isSleeping() && !villager.isTrading() && !host.talking(villager)
            && !HiggsVillagers.bedtime(villager);
    }

    private void start(MinecraftServer server, ServerPlayer player, Villager a, Villager b) {
        starting = true;
        personalities.prepare(a).thenCombine(personalities.prepare(b), (first, second) -> List.of(first, second))
            .whenComplete((prepared, failure) -> server.execute(() -> {
                if (failure != null || server.isStopped() || !idle(a) || !idle(b) || !player.isAlive()) { starting = false; return; }
                personalities.applyName(a, prepared.get(0));
                personalities.applyName(b, prepared.get(1));
                JsonObject body = scene(server, player, a, prepared.get(0).profile(), b, prepared.get(1).profile());
                host.request("POST", "/scenes", body).whenComplete((reply, error) -> server.execute(() -> {
                    starting = false;
                    if (error != null) {
                        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Villager chatter unavailable: {}", error.getMessage());
                        return;
                    }
                    JsonObject created = reply.getAsJsonObject();
                    String id = created.get("sceneId").getAsString();
                    if (!idle(a) || !idle(b)) { host.request("DELETE", "/scenes/" + id, null).exceptionally(ignored -> null); return; }
                    long now = System.currentTimeMillis();
                    lastChat.put(a.getUUID(), now);
                    lastChat.put(b.getUUID(), now);
                    String url = publicBackend.replaceFirst("^http", "ws") + "/chatter?scene=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                        + "&token=" + URLEncoder.encode(created.get("token").getAsString(), StandardCharsets.UTF_8);
                    active = new Scene(server, id, a, b, url, now + SCENE_MS, new HashSet<>());
                    org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Villager chatter scene={} topic={} between {} and {}",
                        id, body.get("topic").getAsString(), a.getName().getString(), b.getName().getString());
                    invite(server, active);
                    if (body.get("topic").getAsString().equals("player")) gossip(player, a, b);
                }));
            }));
    }

    private JsonObject scene(MinecraftServer server, ServerPlayer player, Villager a, VillagerPersonalityStore.Profile first,
                             Villager b, VillagerPersonalityStore.Profile second) {
        JsonObject speakerA = speaker(player, a, first), speakerB = speaker(player, b, second);
        JsonObject factsA = speakerA.getAsJsonObject("facts"), factsB = speakerB.getAsJsonObject("facts");
        boolean playerNews = factsA.has("whatThePlayerToldMe") || factsB.has("whatThePlayerToldMe")
            || !factsA.get("opinionOfPlayer").getAsString().equals("neutral") || !factsB.get("opinionOfPlayer").getAsString().equals("neutral");
        boolean rumours = factsA.has("rumoursIHeard") || factsB.has("rumoursIHeard");
        JsonObject shared = new JsonObject();
        shared.addProperty("playerName", player.getName().getString());
        shared.addProperty("timeOfDay", ChatterTopic.timeOfDay(a.level().getOverworldClockTime()));
        shared.addProperty("weather", a.level().isThundering() ? "thunderstorm" : a.level().isRaining() ? "rain" : "clear");
        String village = host.villageName(a);
        if (village != null) shared.addProperty("village", village);
        String topic = ChatterTopic.choose(random, playerNews, rumours);
        if (topic.equals("player")) {
            stance(factsA, host.opinion(player, a).score(), host.opinion(player, b).score());
            stance(factsB, host.opinion(player, b).score(), host.opinion(player, a).score());
        }
        JsonObject body = new JsonObject();
        body.addProperty("topic", topic);
        body.addProperty("lines", ChatterTopic.lines(random));
        body.add("shared", shared);
        JsonArray speakers = new JsonArray();
        speakers.add(speakerA);
        speakers.add(speakerB);
        body.add("speakers", speakers);
        return body;
    }

    /** A speaker with a clear opinion praises or badmouths the player, and the scene's speech should match. */
    private static void stance(JsonObject facts, int speaker, int listener) {
        int next = VillageReputation.influence(speaker, listener);
        if (next != listener) facts.addProperty("stanceOnPlayer", next > listener ? "praise" : "badmouth");
    }

    /** A villager's persona and what it may say about the player; shared with conversation companions. */
    JsonObject speaker(ServerPlayer player, Villager villager, VillagerPersonalityStore.Profile profile) {
        JsonObject speaker = new JsonObject();
        speaker.addProperty("entityId", villager.getId());
        speaker.addProperty("villagerId", villager.getUUID().toString());
        speaker.addProperty("name", villager.getName().getString());
        speaker.addProperty("profession", VillagerMemoryService.profession(villager));
        if (profile != null) {
            JsonObject personality = new JsonObject();
            personality.addProperty("catalogueVersion", profile.catalogueVersion());
            personality.addProperty("archetypeId", profile.archetypeId());
            personality.addProperty("styleId", profile.styleId());
            speaker.add("personality", personality);
        }
        TradeReputation opinion = host.opinion(player, villager);
        JsonObject facts = new JsonObject();
        facts.addProperty("opinionOfPlayer", Negotiation.band(opinion.score()).name());
        if (opinion.lastHit() != 0 && System.currentTimeMillis() - opinion.lastHit() < RECENT_HIT_MS)
            facts.addProperty("playerHitMeRecently", true);
        JsonObject context = memory.conversationContext(villager, player);
        // Private confidences never leave the conversation they were told in.
        JsonArray told = summaries(context.getAsJsonArray("memories"), false);
        if (!told.isEmpty()) facts.add("whatThePlayerToldMe", told);
        JsonArray heard = summaries(context.getAsJsonArray("heardRumours"), true);
        if (!heard.isEmpty()) facts.add("rumoursIHeard", heard);
        JsonArray wares = new JsonArray();
        for (var offer : villager.getOffers()) {
            if (wares.size() >= 3) break;
            wares.add(offer.getResult().getHoverName().getString());
        }
        if (!wares.isEmpty()) facts.add("myWares", wares);
        speaker.add("facts", facts);
        return speaker;
    }

    /** Both villagers hear each other's view of the player; computed from the opinions before either changes. */
    private void gossip(ServerPlayer player, Villager a, Villager b) {
        int scoreA = host.opinion(player, a).score(), scoreB = host.opinion(player, b).score();
        int nextB = VillageReputation.influence(scoreA, scoreB), nextA = VillageReputation.influence(scoreB, scoreA);
        if (nextB != scoreB) host.influenced(player, a, b, nextB);
        if (nextA != scoreA) host.influenced(player, b, a, nextA);
    }

    private static JsonArray summaries(JsonArray entries, boolean withSource) {
        JsonArray result = new JsonArray();
        if (entries == null) return result;
        for (JsonElement element : entries) {
            if (result.size() >= 2 || !element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("private") || entry.get("private").getAsBoolean() || !entry.has("summary")) continue;
            String summary = entry.get("summary").getAsString();
            if (withSource && entry.has("heardFrom") && !entry.get("heardFrom").isJsonNull())
                summary += " (heard from " + entry.get("heardFrom").getAsString() + ")";
            result.add(summary);
        }
        return result;
    }

    private void invite(MinecraftServer server, Scene scene) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (scene.invited().contains(player.getUUID()) || player.level() != scene.a().level()
                || !host.canSend(player) || host.talking(player)
                || Math.min(player.distanceToSqr(scene.a()), player.distanceToSqr(scene.b())) > hearingSquared) continue;
            scene.invited().add(player.getUUID());
            JsonObject message = new JsonObject();
            message.addProperty("type", "chatter");
            message.addProperty("sceneId", scene.id());
            message.addProperty("url", scene.url());
            host.send(player, message);
        }
    }

    /** A player started talking to one of the chatting villagers: the scene yields to the conversation. */
    void villagerBusy(Villager villager) {
        if (active != null && (active.a() == villager || active.b() == villager)) stop(true);
    }

    void stop(boolean notifyBackend) {
        Scene scene = active;
        active = null;
        if (scene == null) return;
        if (notifyBackend) host.request("DELETE", "/scenes/" + scene.id(), null).exceptionally(ignored -> null);
        // Clients may hold lines already generated; tell them to stop rather than finish the scene.
        JsonObject message = new JsonObject();
        message.addProperty("type", "chatter_end");
        message.addProperty("sceneId", scene.id());
        for (UUID id : scene.invited()) {
            ServerPlayer player = scene.server().getPlayerList().getPlayer(id);
            if (player != null && host.canSend(player)) host.send(player, message);
        }
    }

    void clear() {
        stop(false);
        lastChat.clear();
        starting = false;
    }
}
