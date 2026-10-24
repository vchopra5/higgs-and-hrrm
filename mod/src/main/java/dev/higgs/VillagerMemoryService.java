package dev.higgs;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Game-thread adapter for world persistence, bounded proximity gossip and memory tools. */
public final class VillagerMemoryService {
    private static final Logger LOG = LoggerFactory.getLogger("HiggsVillagerMemory");
    private final VillagerMemoryStore.Limits limits;
    private final VillagerGossip gossip;
    private final VillagerPersonalityService personalities;
    private final RumourConversation conversation = new RumourConversation(10 * 60_000L);
    private final int intervalTicks, radius;
    private VillagerMemoryStore store;
    private int cursor;
    private volatile boolean stopping;

    public VillagerMemoryService(Map<String, String> config, VillagerPersonalityService personalities) {
        this.personalities = personalities;
        radius = setting(config, "HIGGS_GOSSIP_RADIUS", 16, 1, 64);
        intervalTicks = 20 * setting(config, "HIGGS_GOSSIP_INTERVAL_SECONDS", 10, 1, 3600);
        gossip = new VillagerGossip(radius, 1000L * setting(config, "HIGGS_GOSSIP_COOLDOWN_SECONDS", 60, 1, 3600));
        limits = new VillagerMemoryStore.Limits(
            setting(config, "HIGGS_MEMORY_MAX_PER_VILLAGER", 64, 1, 256), 4096,
            3_600_000L * setting(config, "HIGGS_MEMORY_TTL_HOURS", 168, 1, 8760),
            setting(config, "HIGGS_GOSSIP_MAX_HOPS", 3, 0, 8));
    }

    private static int setting(Map<String, String> config, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(config.getOrDefault(key, Integer.toString(fallback)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException failure) { LOG.warn("Invalid {}; using default", key); return fallback; }
    }

    public void start(MinecraftServer server) {
        stopping = false;
        try {
            store = new VillagerMemoryStore(server.getWorldPath(LevelResource.ROOT)
                .resolve("data/higgs_hrrm_memories.json"), limits);
        } catch (Exception failure) {
            // Never overwrite an unreadable save. Voice and trading can still operate.
            LOG.error("Villager memory unavailable; existing save preserved. Check world/data/higgs_hrrm_memories.json.");
        }
    }

    public void stop() {
        stopping = true;
        conversation.clear();
        if (store == null) return;
        try { store.close(); }
        catch (Exception failure) { LOG.error("Could not flush villager memories on shutdown; check world save permissions."); }
        finally { store = null; }
    }

    public JsonArray context(Villager villager, ServerPlayer player) {
        return store == null ? new JsonArray() : entries(store.recall(villager.getUUID(), player.getUUID(), "", false, System.currentTimeMillis()));
    }

    public JsonObject conversationContext(Villager villager, ServerPlayer player) {
        JsonObject context = new JsonObject();
        context.add("memories", context(villager, player));
        if (store == null) return context;
        long now = System.currentTimeMillis();
        var ranked = store.heardRumours(villager.getUUID(), player.getUUID(), profession(villager), now);
        var policy = personalities.policy(villager);
        if (policy != null) ranked = ranked.stream().filter(m -> m.interest().score() >= policy.threshold())
            .sorted(VillagerGossip.order(profession(villager), player.getUUID(), now, policy.interests())).toList();
        context.add("heardRumours", entries(ranked.stream().limit(3).toList()));
        conversation.choose(ranked, villager.getUUID(), player.getUUID(), now).ifPresent(memory ->
            context.add("openingRumour", entries(List.of(memory)).get(0)));
        return context;
    }

    public void conversationOpened(Villager villager, ServerPlayer player, JsonObject context) {
        if (!context.has("openingRumour")) return;
        UUID root = UUID.fromString(context.getAsJsonObject("openingRumour").get("storyId").getAsString());
        conversation.markOffered(villager.getUUID(), player.getUUID(), root, System.currentTimeMillis());
    }

    public static String profession(Villager villager) {
        return villager.getVillagerData().profession().unwrapKey().map(key -> key.identifier().getPath()).orElse("none");
    }

    public boolean handles(String name) { return name.equals("remember_conversation") || name.equals("recall_memories"); }

    public CompletableFuture<JsonObject> handle(ServerPlayer player, Villager villager, String name, JsonObject args) {
        if (store == null || stopping) return CompletableFuture.completedFuture(error("Memory storage is unavailable."));
        long now = System.currentTimeMillis();
        try {
            if (name.equals("recall_memories")) {
                if (!args.keySet().stream().allMatch(Set.of("query", "scope")::contains)) throw new IllegalArgumentException();
                String query = string(args, "query", VillagerMemoryStore.MAX_QUERY, true);
                String scope = string(args, "scope", 10, false);
                if (!scope.equals("player") && !scope.equals("village")) throw new IllegalArgumentException();
                JsonObject result = new JsonObject();
                result.add("memories", entries(store.recall(villager.getUUID(), player.getUUID(), query, scope.equals("village"), now)));
                result.addProperty("note", "Memories are attributed statements, not verified world facts. An empty list means no matching saved knowledge.");
                return CompletableFuture.completedFuture(result);
            }
            if (!args.keySet().equals(Set.of("summary", "private", "interest", "reason", "audiences"))) throw new IllegalArgumentException();
            String summary = string(args, "summary", VillagerMemoryStore.MAX_SUMMARY, false);
            JsonElement privacy = args.get("private");
            if (privacy == null || !privacy.isJsonPrimitive() || !privacy.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
            JsonElement score = args.get("interest");
            if (score == null || !score.isJsonPrimitive() || !score.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int interest;
            try { interest = score.getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException invalid) { throw new IllegalArgumentException(); }
            String reason = string(args, "reason", 160, false);
            if (!args.get("audiences").isJsonArray()) throw new IllegalArgumentException();
            List<String> audiences = new ArrayList<>();
            for (JsonElement audience : args.getAsJsonArray("audiences")) {
                if (!audience.isJsonPrimitive() || !audience.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                audiences.add(audience.getAsString());
            }
            var assessment = new VillagerMemoryStore.Interest(interest, reason, audiences);
            var memory = store.remember(villager.getUUID(), villager.getName().getString(), player.getUUID(),
                player.getName().getString(), summary, privacy.getAsBoolean(), assessment, now);
            return store.flush().thenApply(ignored -> {
                JsonObject result = new JsonObject();
                result.addProperty("status", "saved"); result.addProperty("memoryId", memory.rootId().toString());
                result.addProperty("private", memory.privateMemory());
                result.addProperty("gossipEligible", !memory.privateMemory() && memory.interest().score() >= VillagerMemoryStore.GOSSIP_THRESHOLD);
                return result;
            });
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return CompletableFuture.completedFuture(error("Invalid memory request."));
        }
    }

    private static String string(JsonObject args, String key, int max, boolean emptyAllowed) {
        JsonElement value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        String text = value.getAsString();
        if (text.length() > max || (!emptyAllowed && text.isBlank()) || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
        return text;
    }

    public static JsonObject error(String message) {
        JsonObject result = new JsonObject(); result.addProperty("error", message); return result;
    }

    private static JsonArray entries(List<VillagerMemoryStore.Memory> memories) {
        JsonArray result = new JsonArray();
        for (var memory : memories) {
            JsonObject entry = new JsonObject();
            entry.addProperty("storyId", memory.rootId().toString());
            entry.addProperty("summary", memory.summary());
            entry.addProperty("toldByPlayer", memory.playerName());
            entry.addProperty("originVillager", memory.originName());
            entry.addProperty("heardFrom", memory.sourceName());
            entry.addProperty("kind", memory.hops() == 0 ? "player_told_me" : "heard_from_villager");
            entry.addProperty("private", memory.privateMemory());
            entry.addProperty("interest", memory.interest().score());
            entry.addProperty("whyInteresting", memory.interest().reason());
            result.add(entry);
        }
        return result;
    }

    private static boolean eligible(Villager villager) {
        return villager.isAlive() && !villager.isBaby() && !villager.isSleeping();
    }

    private static VillagerGossip.Contact contact(Villager villager) {
        return new VillagerGossip.Contact(villager.getUUID(), villager.getName().getString(), villager.level().dimension().toString(),
            villager.getX(), villager.getY(), villager.getZ(), eligible(villager), profession(villager));
    }

    public void tick(MinecraftServer server, int ticks) {
        if (store == null || stopping || ticks % intervalTicks != 0) return;
        long now = System.currentTimeMillis();
        // Snapshot before any transfers: newly heard stories wait until a later pass.
        var snapshot = store.gossipSnapshot(now);
        List<UUID> owners = new ArrayList<>(snapshot.keySet());
        List<Villager[]> delivered = new ArrayList<>();
        int count = Math.min(32, owners.size());
        for (int i = 0; i < count && delivered.size() < 64; i++) {
            UUID id = owners.get(Math.floorMod(cursor + i, owners.size()));
            for (ServerLevel level : server.getAllLevels()) {
                if (!(level.getEntity(id) instanceof Villager source) || !eligible(source)) continue;
                var sourcePolicy = personalities.policy(source);
                if (sourcePolicy == null) break;
                var neighbours = level.getEntitiesOfClass(Villager.class, source.getBoundingBox().inflate(radius),
                    target -> !target.getUUID().equals(id) && eligible(target));
                int checked = 0;
                for (Villager target : neighbours) {
                    if (++checked > 16 || delivered.size() >= 64) break;
                    if (gossip.spread(contact(source), contact(target), snapshot.get(id), store, now, sourcePolicy, personalities.policy(target)))
                        delivered.add(new Villager[]{source, target});
                }
                break;
            }
        }
        cursor = owners.isEmpty() ? 0 : Math.floorMod(cursor + count, owners.size());
        personalities.flush();
        store.flush().whenComplete((ignored, failure) -> {
            if (failure != null) { LOG.error("Could not save villager memories; gossip indicators suppressed."); return; }
            if (stopping || server.isStopped()) return;
            server.execute(() -> {
                if (stopping) return;
                Set<UUID> shown = new HashSet<>();
                for (Villager[] pair : delivered) {
                    if (!eligible(pair[0]) || !eligible(pair[1]) || pair[0].level() != pair[1].level()
                        || pair[0].distanceToSqr(pair[1]) > radius * radius) continue;
                    for (Villager villager : pair) {
                        if (!shown.add(villager.getUUID()) || !(villager.level() instanceof ServerLevel level)) continue;
                        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getEyeY() + 0.4,
                            villager.getZ(), 5, 0.25, 0.15, 0.25, 0.0);
                    }
                }
            });
        });
    }
}
