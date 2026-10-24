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
    private record Scene(MinecraftServer server, String id, List<Villager> members, String url, long until, Set<UUID> invited,
                         CompletableFuture<Boolean> completion) {
        boolean whisper() { return completion != null; }
    }
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
    private boolean polling;
    private long generation;
    private List<Villager> pendingWhisper = List.of();
    private CompletableFuture<Boolean> pendingCompletion;
    private java.util.function.Predicate<Villager> reserved = villager -> false;

    void reservations(java.util.function.Predicate<Villager> reserved) { this.reserved = reserved; }
    boolean availableForGossip() { return !starting && active == null; }

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
            if (now > active.until()) stop(true);
            else if (!active.members().stream().allMatch(v -> active.whisper() ? free(v) : idle(v))) stop(true);
            else if (ticks % 20 == 0) {
                invite(server, active);
                if (active.whisper()) poll(server, active);
            }
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
                        List<Villager> group = new ArrayList<>(List.of(nearby.get(i), nearby.get(j)));
                        // Sometimes a third neighbour standing with both joins in.
                        for (int k = j + 1; k < nearby.size() && random.nextBoolean(); k++) {
                            Villager third = nearby.get(k);
                            if (third.distanceToSqr(group.get(0)) <= PAIR_DISTANCE_SQUARED && third.distanceToSqr(group.get(1)) <= PAIR_DISTANCE_SQUARED) {
                                group.add(third);
                                break;
                            }
                        }
                        start(server, player, group);
                        return;
                    }
                }
            }
        }
    }

    /** Idle and awake: no chatter once villagers wind down for the night. */
    private boolean idle(Villager villager) {
        return free(villager) && !reserved.test(villager);
    }

    private boolean free(Villager villager) {
        return villager.isAlive() && !villager.isBaby() && !villager.isSleeping() && !villager.isTrading() && !host.talking(villager)
            && !HiggsVillagers.bedtime(villager) && !VillagerAlarm.chased(villager);
    }

    private void start(MinecraftServer server, ServerPlayer player, List<Villager> group) {
        starting = true;
        long version = ++generation;
        List<CompletableFuture<VillagerPersonalityService.Prepared>> preparing = group.stream().map(personalities::prepare).toList();
        CompletableFuture.allOf(preparing.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> server.execute(() -> {
            if (version != generation) return;
            if (failure != null || server.isStopped() || !group.stream().allMatch(this::idle) || !player.isAlive()) { starting = false; return; }
            List<VillagerPersonalityStore.Profile> profiles = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) {
                var prepared = preparing.get(i).join();
                personalities.applyName(group.get(i), prepared);
                profiles.add(prepared.profile());
            }
            JsonObject body = scene(player, group, profiles);
            host.request("POST", "/scenes", body).whenComplete((reply, error) -> server.execute(() -> {
                if (version == generation) starting = false;
                if (error != null) {
                    org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Villager chatter unavailable: {}", error.getMessage());
                    return;
                }
                JsonObject created = reply.getAsJsonObject();
                String id = created.get("sceneId").getAsString();
                if (version != generation || server.isStopped() || !group.stream().allMatch(this::idle)) {
                    host.request("DELETE", "/scenes/" + id, null).exceptionally(none -> null); return;
                }
                long now = System.currentTimeMillis();
                for (Villager member : group) lastChat.put(member.getUUID(), now);
                String url = publicBackend.replaceFirst("^http", "ws") + "/chatter?scene=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                    + "&token=" + URLEncoder.encode(created.get("token").getAsString(), StandardCharsets.UTF_8);
                active = new Scene(server, id, List.copyOf(group), url, now + SCENE_MS, new HashSet<>(), null);
                org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Villager chatter scene={} topic={} between {}",
                    id, body.get("topic").getAsString(), group.stream().map(v -> v.getName().getString()).toList());
                invite(server, active);
                if (body.get("topic").getAsString().equals("player")) gossip(player, group);
            }));
        }));
    }

    /** A directed two-line exchange of one specific public story, using the existing Realtime scenes. */
    CompletableFuture<Boolean> whisper(MinecraftServer server, Villager source, Villager target, VillagerMemoryStore.Memory story) {
        if (!enabled || !availableForGossip() || !free(source) || !free(target) || story.privateMemory())
            return CompletableFuture.completedFuture(false);
        ServerPlayer listener = server.getPlayerList().getPlayers().stream()
            .filter(p -> p.level() == source.level() && host.canSend(p) && !host.talking(p)
                && Math.min(p.distanceToSqr(source), p.distanceToSqr(target)) <= hearingSquared)
            .findFirst().orElse(null);
        if (listener == null) return CompletableFuture.completedFuture(false);
        starting = true;
        long version = ++generation;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        List<Villager> group = List.of(source, target);
        pendingWhisper = group; pendingCompletion = result;
        result.whenComplete((ok, error) -> {
            if (pendingCompletion == result) { pendingWhisper = List.of(); pendingCompletion = null; }
        });
        var preparing = group.stream().map(personalities::prepare).toList();
        CompletableFuture.allOf(preparing.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> server.execute(() -> {
            if (version != generation) { result.complete(false); return; }
            if (failure != null || server.isStopped() || !group.stream().allMatch(this::free)) {
                starting = false; result.complete(false); return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("topic", "rumour"); body.addProperty("delivery", "whisper"); body.addProperty("lines", 2);
            JsonObject shared = new JsonObject(), news = new JsonObject();
            news.addProperty("summary", story.summary());
            news.addProperty("toldByPlayer", story.playerName());
            news.addProperty("originVillager", story.originName());
            news.addProperty("heardFrom", source.getName().getString());
            shared.add("story", news); body.add("shared", shared);
            JsonArray speakers = new JsonArray();
            for (int i = 0; i < group.size(); i++) {
                var prepared = preparing.get(i).join();
                personalities.applyName(group.get(i), prepared);
                JsonObject speaker = speaker(listener, group.get(i), prepared.profile(), List.of(group.get(1 - i)));
                // Keep unrelated memories and opinions out of this exact-story exchange.
                JsonObject facts = new JsonObject();
                facts.addProperty("role", i == 0 ? "Tell your neighbour the supplied story, crediting its source"
                    : "React briefly to the story your neighbour just told you");
                speaker.add("facts", facts); speakers.add(speaker);
            }
            body.add("speakers", speakers);
            host.request("POST", "/scenes", body).whenComplete((reply, error) -> server.execute(() -> {
                if (version == generation) starting = false;
                if (error != null) { result.complete(false); return; }
                JsonObject created = reply.getAsJsonObject();
                String id = created.get("sceneId").getAsString();
                if (version != generation || server.isStopped() || !group.stream().allMatch(this::free)) {
                    host.request("DELETE", "/scenes/" + id, null).exceptionally(none -> null);
                    result.complete(false); return;
                }
                String url = publicBackend.replaceFirst("^http", "ws") + "/chatter?scene=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                    + "&token=" + URLEncoder.encode(created.get("token").getAsString(), StandardCharsets.UTF_8);
                active = new Scene(server, id, group, url, System.currentTimeMillis() + SCENE_MS, new HashSet<>(), result);
                for (Villager member : group) lastChat.put(member.getUUID(), System.currentTimeMillis());
                invite(server, active);
            }));
        }));
        return result;
    }

    private void poll(MinecraftServer server, Scene scene) {
        if (polling) return;
        polling = true;
        host.request("GET", "/scenes/" + scene.id(), null).whenComplete((reply, error) -> server.execute(() -> {
            polling = false;
            if (active != scene) return;
            if (error != null) { stop(true); return; }
            JsonObject status = reply.getAsJsonObject();
            if (!status.get("finished").getAsBoolean() || status.get("playbackRemainingMs").getAsLong() > 0) return;
            active = null;
            scene.completion().complete(status.get("lines").getAsInt() > 0);
        }));
    }

    private JsonObject scene(ServerPlayer player, List<Villager> group, List<VillagerPersonalityStore.Profile> profiles) {
        List<JsonObject> speakers = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            List<Villager> others = new ArrayList<>(group);
            others.remove(i);
            speakers.add(speaker(player, group.get(i), profiles.get(i), others));
        }
        boolean playerNews = speakers.stream().map(speaker -> speaker.getAsJsonObject("facts")).anyMatch(facts ->
            facts.has("whatThePlayerToldMe") || !facts.get("opinionOfPlayer").getAsString().equals("neutral"));
        boolean rumours = speakers.stream().anyMatch(speaker -> speaker.getAsJsonObject("facts").has("rumoursIHeard"));
        Villager first = group.getFirst();
        JsonObject shared = new JsonObject();
        shared.addProperty("playerName", player.getName().getString());
        shared.addProperty("timeOfDay", ChatterTopic.timeOfDay(first.level().getOverworldClockTime()));
        shared.addProperty("weather", first.level().isThundering() ? "thunderstorm" : first.level().isRaining() ? "rain" : "clear");
        String village = host.villageName(first);
        if (village != null) shared.addProperty("village", village);
        String topic = ChatterTopic.choose(random, playerNews, rumours);
        if (topic.equals("player")) {
            for (int i = 0; i < group.size(); i++) {
                int own = host.opinion(player, group.get(i)).score();
                for (Villager other : group) {
                    if (other == group.get(i)) continue;
                    int listener = host.opinion(player, other).score();
                    int next = VillageReputation.influence(own, listener);
                    if (next != listener) { speakers.get(i).getAsJsonObject("facts").addProperty("stanceOnPlayer", next > listener ? "praise" : "badmouth"); break; }
                }
            }
        }
        JsonObject body = new JsonObject();
        body.addProperty("topic", topic);
        body.addProperty("lines", ChatterTopic.lines(random) + (group.size() > 2 ? 1 : 0));
        body.addProperty("playerId", player.getUUID().toString());
        body.add("shared", shared);
        JsonArray list = new JsonArray();
        speakers.forEach(list::add);
        body.add("speakers", list);
        return body;
    }

    /** A speaker with a clear opinion praises or badmouths the player, and the scene's speech should match. */
    private static void stance(JsonObject facts, int speaker, int listener) {
        int next = VillageReputation.influence(speaker, listener);
        if (next != listener) facts.addProperty("stanceOnPlayer", next > listener ? "praise" : "badmouth");
    }

    /** A villager's persona and what it may say about the player; shared with conversation companions. */
    JsonObject speaker(ServerPlayer player, Villager villager, VillagerPersonalityStore.Profile profile, List<Villager> others) {
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
        // TinyTroupe-style persona relationships and state of mind.
        facts.add("mind", memory.mind(villager));
        JsonObject bonds = new JsonObject();
        for (Villager other : others) {
            String name = other.getName().getString();
            bonds.addProperty(name, VillagerBonds.describe(VillagerBonds.relation(villager.getUUID(), other.getUUID()), name));
        }
        if (!bonds.isEmpty()) facts.add("neighbours", bonds);
        speaker.add("facts", facts);
        return speaker;
    }

    /**
     * Everyone hears everyone's view of the player, computed from the opinions before any changes. Each listener is
     * swayed at most one step, by the speaker with the strongest clear opinion.
     */
    private void gossip(ServerPlayer player, List<Villager> group) {
        Map<Villager, Integer> before = new HashMap<>();
        for (Villager member : group) before.put(member, host.opinion(player, member).score());
        for (Villager listener : group) {
            Villager loudest = null;
            for (Villager speaker : group) {
                if (speaker == listener || VillageReputation.influence(before.get(speaker), before.get(listener)) == before.get(listener)) continue;
                if (loudest == null || Math.abs(before.get(speaker)) > Math.abs(before.get(loudest))) loudest = speaker;
            }
            if (loudest != null) host.influenced(player, loudest, listener, VillageReputation.influence(before.get(loudest), before.get(listener)));
        }
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
            if (scene.invited().contains(player.getUUID()) || player.level() != scene.members().getFirst().level()
                || !host.canSend(player) || host.talking(player)
                || scene.members().stream().mapToDouble(player::distanceToSqr).min().orElse(Double.MAX_VALUE)
                    > (scene.whisper() ? 25 : hearingSquared)) continue;
            scene.invited().add(player.getUUID());
            JsonObject message = new JsonObject();
            message.addProperty("type", "chatter");
            message.addProperty("sceneId", scene.id());
            message.addProperty("url", scene.url());
            message.addProperty("whisper", scene.whisper());
            host.send(player, message);
        }
    }

    /** A player started talking to one of the chatting villagers: the scene yields to the conversation. */
    void villagerBusy(Villager villager) {
        if (active != null && active.members().contains(villager)) stop(true);
        else if (pendingWhisper.contains(villager)) stop(true);
    }

    void cancelWhisper(Villager source, Villager target) {
        if (pendingWhisper.contains(source) || pendingWhisper.contains(target)
                || (active != null && active.whisper() && (active.members().contains(source) || active.members().contains(target))))
            stop(true);
    }

    void stop(boolean notifyBackend) {
        generation++;
        starting = false;
        if (pendingCompletion != null) pendingCompletion.complete(false);
        Scene scene = active;
        active = null;
        if (scene == null) return;
        if (scene.completion() != null) scene.completion().complete(false);
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
