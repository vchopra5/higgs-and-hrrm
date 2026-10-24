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
    private final VillagerJournal.Limits journalLimits;
    private VillagerJournal journal;
    private int cursor;
    private volatile boolean stopping;
    private GossipPresentation gossipPresentation;

    interface GossipPresentation {
        boolean offer(MinecraftServer server, Villager source, Villager target, VillagerMemoryStore.Memory story,
                      java.util.function.BooleanSupplier valid,
                      java.util.function.Supplier<CompletableFuture<Boolean>> deliver);
    }

    void gossipPresentation(GossipPresentation presentation) { gossipPresentation = presentation; }
    private Bonds bonds = new Bonds() {
        public int standing(Villager villager, UUID player) { return 0; }
        public void reached(Villager villager, ServerPlayer player, RelationshipStage stage) {}
    };

    /** The game's side of a relationship: the villager's current opinion, and what happens when a new stage is reached. */
    public interface Bonds {
        int standing(Villager villager, UUID player);
        void reached(Villager villager, ServerPlayer player, RelationshipStage stage);
    }

    public void bonds(Bonds bonds) { this.bonds = bonds; }

    public VillagerMemoryService(Map<String, String> config, VillagerPersonalityService personalities) {
        this.personalities = personalities;
        radius = setting(config, "HIGGS_GOSSIP_RADIUS", 16, 1, 64);
        intervalTicks = 20 * setting(config, "HIGGS_GOSSIP_INTERVAL_SECONDS", 10, 1, 3600);
        gossip = new VillagerGossip(radius, 1000L * setting(config, "HIGGS_GOSSIP_COOLDOWN_SECONDS", 60, 1, 3600));
        limits = new VillagerMemoryStore.Limits(
            setting(config, "HIGGS_MEMORY_MAX_PER_VILLAGER", 64, 1, 256), 4096,
            3_600_000L * setting(config, "HIGGS_MEMORY_TTL_HOURS", 720, 1, 8760),
            setting(config, "HIGGS_GOSSIP_MAX_HOPS", 3, 0, 8));
        journalLimits = new VillagerJournal.Limits(48, 20_000,
            24L * 3_600_000L * setting(config, "HIGGS_MEMORY_EPISODE_DAYS", 30, 1, 365));
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
        try {
            journal = new VillagerJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/higgs-journal.json"), journalLimits);
        } catch (Exception failure) {
            LOG.error("Villager journal unavailable; existing save preserved. Check world/data/higgs-journal.json.");
        }
    }

    /** Observed events (trades, gifts, quests…); null while unavailable, so callers record best-effort. */
    public VillagerJournal journal() { return stopping ? null : journal; }

    /**
     * The villager's state of mind right now: mood from what happened to it in the last in-game day (with anyone),
     * today's goal from its trade, and the latest notable thing on its mind.
     */
    public JsonObject mind(Villager villager) {
        JsonObject mind = new JsonObject();
        long now = System.currentTimeMillis();
        VillagerJournal current = journal();
        var lately = current == null ? List.<VillagerJournal.Episode>of() : current.since(villager.getUUID(), now - VillagerMind.MOOD_MS);
        mind.addProperty("mood", VillagerMind.mood(lately.stream().map(VillagerJournal.Episode::type).toList()));
        mind.addProperty("goal", VillagerMind.goal(profession(villager), villager.getUUID(), gameDay(villager)));
        lately.stream().filter(e -> e.salience() >= 3).reduce((first, second) -> second)
            .ifPresent(latest -> mind.addProperty("onYourMind", latest.text()));
        return mind;
    }

    /** In-game day, for "yesterday" and "three days ago". */
    public static long gameDay(Villager villager) { return Math.floorDiv(villager.level().getOverworldClockTime(), 24000L); }

    /** Records what a villager saw happen with the player. Best-effort: no journal, no record. */
    public void observe(Villager villager, ServerPlayer player, String type, String text, int salience, long mergeWithinMs) {
        VillagerJournal current = journal();
        if (current == null) return;
        current.record(villager.getUUID(), player.getUUID(), type, text, gameDay(villager),
            System.currentTimeMillis(), salience, false, mergeWithinMs);
        progress(villager, player);
    }

    /**
     * How close the pair are now. A stage once reached is kept even after the episodes that earned it expire,
     * but a villager who has turned against the player falls back to acquaintance.
     */
    public RelationshipStage stage(Villager villager, ServerPlayer player) { return stage(villager, player.getUUID()); }

    /** By player ID, so the dashboard can show players who are offline. */
    public RelationshipStage stage(Villager villager, UUID player) {
        VillagerJournal current = journal();
        if (current == null) return RelationshipStage.STRANGER;
        int standing = bonds.standing(villager, player);
        var computed = RelationshipStage.of(current.counts(villager.getUUID(), player, System.currentTimeMillis()), standing);
        var summary = current.summary(villager.getUUID(), player);
        var stored = RelationshipStage.named(summary == null ? null : summary.stage());
        return standing <= -2 || stored.ordinal() <= computed.ordinal() ? computed : stored;
    }

    /** Records a milestone the first time the pair reaches a stage beyond any before it (friend and above). */
    public void progress(Villager villager, ServerPlayer player) {
        VillagerJournal current = journal();
        if (current == null) return;
        long now = System.currentTimeMillis();
        var computed = RelationshipStage.of(current.counts(villager.getUUID(), player.getUUID(), now), bonds.standing(villager, player.getUUID()));
        var summary = current.summary(villager.getUUID(), player.getUUID());
        if (computed.ordinal() <= RelationshipStage.named(summary == null ? null : summary.stage()).ordinal()) return;
        current.update(villager.getUUID(), player.getUUID(), record -> record.withStage(computed.label));
        if (computed.ordinal() < RelationshipStage.FRIEND.ordinal()) return;
        String name = player.getName().getString();
        current.record(villager.getUUID(), player.getUUID(), "milestone", switch (computed) {
            case CONFIDANT -> "Today " + name + " became someone I would trust with anything";
            case CLOSE_FRIEND -> "Today " + name + " and I became close friends";
            default -> "Today " + name + " and I became friends";
        }, gameDay(villager), now, 5, false, 0);
        bonds.reached(villager, player, computed);
    }

    /** Villagers within eight blocks who can see the player also remember the moment, faintly. */
    public void witnesses(Villager subject, ServerPlayer player, String text, long mergeWithinMs) {
        VillagerJournal current = journal();
        if (current == null) return;
        long now = System.currentTimeMillis();
        for (Villager witness : subject.level().getEntitiesOfClass(Villager.class, subject.getBoundingBox().inflate(8),
                v -> v != subject && v.isAlive() && !v.isBaby() && !v.isSleeping() && v.distanceToSqr(subject) <= 64 && v.hasLineOfSight(player))) {
            current.record(witness.getUUID(), player.getUUID(), "witnessed", text, gameDay(witness), now, 2, true, mergeWithinMs);
        }
    }

    private static String when(long today, long day) {
        long ago = today - day;
        return ago <= 0 ? "earlier today" : ago == 1 ? "yesterday" : ago + " days ago";
    }

    private static JsonArray episodeEntries(List<VillagerJournal.Episode> episodes, long today) {
        JsonArray result = new JsonArray();
        for (var episode : episodes) {
            JsonObject entry = new JsonObject();
            entry.addProperty("when", when(today, episode.gameDay()));
            entry.addProperty("what", episode.text());
            if (episode.witnessed()) entry.addProperty("seen", true);
            result.add(entry);
        }
        return result;
    }

    /**
     * Long-term relationship at the start of a conversation: the lasting summary, the most telling recent episodes,
     * and how much has passed between them. Episodes are things the villager saw happen, unlike player claims.
     */
    public JsonObject relationshipContext(Villager villager, ServerPlayer player) {
        JsonObject context = new JsonObject();
        VillagerJournal current = journal();
        if (current == null) return context;
        long now = System.currentTimeMillis(), today = gameDay(villager);
        var summary = current.summary(villager.getUUID(), player.getUUID());
        if (summary != null && !summary.text().isEmpty()) context.addProperty("summary", summary.text());
        context.addProperty("stage", stage(villager, player).label);
        if (summary != null && summary.nickname() != null) context.addProperty("nickname", summary.nickname());
        var all = current.all(villager.getUUID(), player.getUUID(), now);
        if (all.isEmpty()) return context;
        JsonArray promises = new JsonArray();
        for (var open : current.openPromises(villager.getUUID(), player.getUUID(), now)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", open.id().toString());
            entry.addProperty("promise", open.text());
            entry.addProperty("when", when(today, open.gameDay()));
            promises.add(entry);
        }
        if (!promises.isEmpty()) context.add("openPromises", promises);
        context.add("recent", episodeEntries(current.recent(villager.getUUID(), player.getUUID(), now, 6), today));
        JsonObject counts = new JsonObject();
        current.counts(villager.getUUID(), player.getUUID(), now).forEach(counts::addProperty);
        context.add("counts", counts);
        all.stream().filter(e -> e.type().equals("first_met")).findFirst()
            .ifPresent(first -> context.addProperty("firstMet", when(today, first.gameDay())));
        return context;
    }

    public void stop() {
        stopping = true;
        if (journal != null) {
            try { journal.close(); }
            catch (Exception failure) { LOG.error("Could not flush the villager journal on shutdown."); }
            finally { journal = null; }
        }
        conversation.clear();
        if (store == null) return;
        try { store.close(); }
        catch (Exception failure) { LOG.error("Could not flush villager memories on shutdown; check world save permissions."); }
        finally { store = null; }
    }

    /** Player claims and rumours this villager holds, newest first; empty while memory is unavailable. */
    public List<VillagerMemoryStore.Memory> memoriesOf(Villager villager) {
        return store == null ? List.of() : store.owned(villager.getUUID(), System.currentTimeMillis());
    }

    public JsonArray context(Villager villager, ServerPlayer player) {
        if (store == null) return new JsonArray();
        var memories = entries(store.workingContext(villager.getUUID(), player.getUUID(), System.currentTimeMillis()));
        // Leave room in the 128 KiB session request for rumours, character and trade data.
        // A count bound alone does not cover Gson's six-byte escapes for valid text.
        return MemoryContextBudget.limit(memories, MemoryContextBudget.SESSION_BYTES);
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

    public boolean handles(String name) {
        return name.equals("remember_conversation") || name.equals("recall_memories") || name.equals("_recall_turn");
    }

    public CompletableFuture<JsonObject> handle(ServerPlayer player, Villager villager, String name, JsonObject args) {
        if (store == null || stopping) return CompletableFuture.completedFuture(error("Memory storage is unavailable."));
        long now = System.currentTimeMillis();
        try {
            if (name.equals("recall_memories") || name.equals("_recall_turn")) {
                boolean prepared = name.equals("_recall_turn");
                if (!args.keySet().equals(prepared ? Set.of("query") : Set.of("query", "scope"))) throw new IllegalArgumentException();
                String query = string(args, "query", prepared ? 4000 : VillagerMemoryStore.MAX_QUERY, true);
                String scope = prepared ? "village" : string(args, "scope", 10, false);
                if (!scope.equals("player") && !scope.equals("village")) throw new IllegalArgumentException();
                JsonObject result = new JsonObject();
                result.add("memories", entries(store.recall(villager.getUUID(), player.getUUID(), query, scope.equals("village"), now)));
                VillagerJournal current = journal();
                if (current != null)
                    result.add("episodes", episodeEntries(current.search(villager.getUUID(), player.getUUID(), query, now, 6), gameDay(villager)));
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
            entry.addProperty("createdAt", memory.createdAt());
            entry.addProperty("learnedAt", memory.learnedAt());
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
        if (store == null || stopping || (ticks % intervalTicks != 0 && ticks % Math.min(intervalTicks, 40) != 0)) return;
        long now = System.currentTimeMillis();
        // Snapshot before any transfers: newly heard stories wait until a later pass.
        var snapshot = store.gossipSnapshot(now);
        if (ticks % intervalTicks != 0) {
            snapshot.replaceAll((owner, stories) -> stories.stream().filter(m -> m.interest().score() >= 75).toList());
            snapshot.values().removeIf(List::isEmpty);
        }
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
                // Bound the work before ranking: prefer a listener whose profession fits the news.
                if (gossipPresentation != null) {
                    neighbours = new ArrayList<>(neighbours.subList(0, Math.min(16, neighbours.size())));
                    neighbours.sort(Comparator.comparingLong((Villager target) -> {
                        var story = gossip.select(contact(source), contact(target), snapshot.get(id), store, now,
                            sourcePolicy, personalities.policy(target));
                        return story == null ? Long.MIN_VALUE : VillagerMemoryStore.priority(story, profession(target), null, now);
                    }).reversed());
                }
                int checked = 0;
                for (Villager target : neighbours) {
                    if (++checked > 16 || delivered.size() >= 64) break;
                    if (gossipPresentation != null) {
                        var story = gossip.select(contact(source), contact(target), snapshot.get(id), store, now,
                            sourcePolicy, personalities.policy(target));
                        if (story != null && gossipPresentation.offer(server, source, target, story,
                                () -> shareable(source, target, story), () -> deliver(server, source, target, story))) {
                            cursor = Math.floorMod(cursor + i + 1, owners.size());
                            personalities.flush();
                            return;
                        }
                        continue;
                    }
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

    record SharePlan(Villager source, Villager target, VillagerMemoryStore.Memory story) {}

    SharePlan planShare(Villager source, String memoryId, java.util.function.Predicate<Villager> available) {
        if (store == null || stopping || gossipPresentation == null) return null;
        var stories = store.gossipSnapshot(System.currentTimeMillis()).getOrDefault(source.getUUID(), List.of());
        var story = stories.stream().filter(m -> m.rootId().toString().equals(memoryId)).findFirst().orElse(null);
        if (story == null) return null;
        return source.level().getEntitiesOfClass(Villager.class, source.getBoundingBox().inflate(radius),
                v -> v != source && available.test(v) && shareable(source, v, story)).stream()
            .max(Comparator.comparingLong(v -> VillagerMemoryStore.priority(story, profession(v), null, System.currentTimeMillis())))
            .map(target -> new SharePlan(source, target, story)).orElse(null);
    }
    boolean startShare(MinecraftServer server, SharePlan plan) {
        return gossipPresentation != null && shareable(plan.source, plan.target, plan.story)
            && gossipPresentation.offer(server, plan.source, plan.target, plan.story,
                () -> shareable(plan.source, plan.target, plan.story), () -> deliver(server, plan.source, plan.target, plan.story));
    }

    private boolean shareable(Villager source, Villager target, VillagerMemoryStore.Memory story) {
        return !stopping && store != null && source.level() == target.level()
            && gossip.select(contact(source), contact(target), List.of(story), store, System.currentTimeMillis(),
                personalities.policy(source), personalities.policy(target)) != null;
    }

    /** Recheck the exact story on arrival; no replacement story may silently be delivered instead. */
    private CompletableFuture<Boolean> deliver(MinecraftServer server, Villager source, Villager target,
                                               VillagerMemoryStore.Memory story) {
        if (!shareable(source, target, story) || !gossip.spread(contact(source), contact(target), List.of(story), store,
                System.currentTimeMillis(), personalities.policy(source), personalities.policy(target)))
            return CompletableFuture.completedFuture(false);
        return store.flush().handle((ignored, failure) -> {
            if (failure != null) LOG.error("Could not save a gossip exchange", failure);
            return failure == null;
        });
    }
}
