package dev.higgs;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.registries.BuiltInRegistries;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class HiggsVillagers implements ModInitializer {
    private static final Gson GSON = new Gson();
    // The Node bridge serves HTTP/1.1; an h2c upgrade would enter its WebSocket handler.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build();
    // Accessed only on Minecraft's server thread, including HTTP completion callbacks.
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastStart = new HashMap<>();
    private String backend, token, publicBackend;
    private VillagerMemoryService memory;
    private VillagerPersonalityService personalities;
    private VillagerChatter chatter;
    private VillagerAlarm alarm;
    private VillageMoments moments;
    private VillagerOuting outings;
    private VillagerGossipVisit gossipVisit;
    private final VillagerGestures gestures = new VillagerGestures();
    private VillageDashboard dashboard;
    private final Map<String, Long> bedtimeUntil = new HashMap<>();
    private static final long BEDTIME_MS = 90_000;
    /** The clock is authoritative: REST can be stale after a time command, especially with NoAI. */
    static boolean bedtime(Villager villager) {
        long time = Math.floorMod(villager.level().getOverworldClockTime(), 24000L);
        return time >= 12500 && time < 23500;
    }
    private VillageTracker villages;
    private boolean companions = true;
    private int ticks;
    private final Map<String, RecentHits> hits = new HashMap<>();
    private void hit(MinecraftServer server, ServerPlayer player, Villager villager) {
        Session candidate = new Session(player, villager);
        if (!valid(candidate) || player.distanceToSqr(villager) > 25) return;
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(entry -> now - entry.getValue().latest() >= RecentHits.WINDOW_MS);
        String key = relationshipKey(candidate);
        RecentHits history = hits.computeIfAbsent(key, ignored -> new RecentHits());
        // At most one attack per server tick; retain rapid attacks in the statistics.
        if (history.latest() != Long.MIN_VALUE && now - history.latest() < 50) return;
        RecentHits.Stats stats = history.record(now);
        String hitter = player.getName().getString();
        memory.observe(villager, player, "hit", hitter + (stats.count() > 1 ? " hit me " + stats.count() + " times" : " hit me"), 4, RecentHits.WINDOW_MS);
        memory.witnesses(villager, player, "I saw " + hitter + " hit " + villager.getName().getString(), RecentHits.WINDOW_MS);
        // Invulnerable demo villagers skip vanilla damage feedback. Reproduce only
        // its visual animation; health, invulnerability, and position are unchanged.
        if (villager.isInvulnerable() && villager.hurtTime == 0) {
            villager.animateHurt(0);
            var level = player.level();
            level.getChunkSource().sendToTrackingPlayers(villager,
                new net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(villager));
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                villager.getX(), villager.getY() + villager.getBbHeight() * 0.65, villager.getZ(),
                6, 0.2, 0.25, 0.2, 0.08);
        }
        moodParticles(player, villager, false);
        // Physical treatment is authoritative and persists even without a voice connection.
        TradeReputation previousOpinion = relationships.getOrDefault(key, TradeReputation.neutral());
        TradeReputation hurtOpinion = previousOpinion.hit(now);
        if (!hurtOpinion.equals(previousOpinion)) {
            relationships.put(key, hurtOpinion);
            saveTradeReputations();
        }
        if (Negotiation.hostile(hurtOpinion.score())) rallyGolems(player, villager);
        Session s = sessions.get(player.getUUID());
        if (s == null || s.villager != villager) {
            if (now - lastStart.getOrDefault(player.getUUID(), 0L) < 1500 || token.isBlank()) return;
            JsonObject starting = new JsonObject(); starting.addProperty("type", "reaction_start");
            starting.addProperty("entityId", villager.getId()); send(player, starting);
            JsonObject request = new JsonObject(); request.addProperty("type", "start");
            request.addProperty("entityId", villager.getId()); receive(server, player, GSON.toJson(request), false);
            s = sessions.get(player.getUUID());
        }
        if (s == null || s.villager != villager) return;
        boolean changed = hurtOpinion.score() != previousOpinion.score();
        boolean wasTrading = changed && s.proposal != null && s.proposal.expires() > now;
        if (changed) {
            s.proposal = null;
            tradeStatus(s, outcome("cancelled", "Reputation changed; ask again for the new price."));
        }
        JsonObject event = new JsonObject(); event.addProperty("type", "hit");
        event.addProperty("windowMs", RecentHits.WINDOW_MS);
        event.addProperty("count", stats.count());
        event.addProperty("spanMs", stats.spanMs());
        event.addProperty("sincePreviousMs", stats.sincePreviousMs());
        event.addProperty("tradeCancelled", wasTrading || (s.hitEvent != null && s.hitEvent.get("tradeCancelled").getAsBoolean()));
        s.hitEvent = event;
        s.hitAt = now;
    }
    /** Nearby golems defend a villager who has turned hostile, exactly as if the player had attacked them. */
    private static void rallyGolems(ServerPlayer player, Villager villager) {
        if (player.isCreative() || player.isSpectator()) return;
        // canAttack keeps vanilla's rule that player-built golems never attack players.
        for (IronGolem golem : villager.level().getEntitiesOfClass(IronGolem.class, villager.getBoundingBox().inflate(16),
                golem -> golem.isAlive() && golem.canAttack(player))) {
            golem.setLastHurtByMob(player);
            golem.setTarget(player);
            golem.setPersistentAngerTarget(EntityReference.<LivingEntity>of(player));
            golem.startPersistentAngerTimer();
        }
    }
    private static void face(Villager villager, ServerPlayer player) {
        double dx = player.getX() - villager.getX();
        double dz = player.getZ() - villager.getZ();
        if (dx * dx + dz * dz < 0.001) return;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float turn = net.minecraft.util.Mth.approachDegrees(villager.getYRot(), yaw, 12);
        villager.setYRot(turn);
        villager.setYBodyRot(turn);
        villager.setYHeadRot(turn);
        villager.getLookControl().setLookAt(player, 30, 30);
        villager.getNavigation().stop();
    }
    /** A neighbour wandering within eight blocks of the player mid-conversation may join in. */
    private void maybeJoin(MinecraftServer server, Session s) {
        if (s.id == null || s.companion != null || s.departingCompanion != null || s.companionJoining || java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= 0.2) return;
        Villager joining = companionFor(s);
        if (joining == null) return;
        s.companionJoining = true;
        personalities.prepare(joining).whenComplete((prepared, failure) -> {
            if (server.isStopped()) return;
            server.execute(() -> {
                if (failure != null || sessions.get(s.player.getUUID()) != s || s.companion != null || !companionAvailable(s, joining, true)) { s.companionJoining = false; return; }
                personalities.applyName(joining, prepared);
                request("POST", "/sessions/" + s.id + "/companion", chatter.speaker(s.player, joining, prepared.profile(), List.of(s.villager)))
                    .whenComplete((reply, error) -> server.execute(() -> {
                        s.companionJoining = false;
                        if (error != null || sessions.get(s.player.getUUID()) != s || s.companion != null) return;
                        s.companion = joining;
                        if (!companionAvailable(s, joining, true)) { departCompanion(s); return; }
                        s.companionProfile = prepared.profile();
                        chatter.villagerBusy(joining);
                        JsonObject message = new JsonObject();
                        message.addProperty("type", "companion_join");
                        message.addProperty("sessionId", s.id);
                        message.addProperty("entityId", joining.getId());
                        message.addProperty("name", joining.getName().getString());
                        send(s.player, message);
                        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("{} joined {}'s conversation with {}",
                            joining.getName().getString(), s.player.getName().getString(), s.villager.getName().getString());
                    }));
            });
        });
    }
    /** The companion was asked to leave and agreed: it walks off and will not rejoin this player for a while. */
    private void companionLeft(Session s) {
        Villager leaving = s.companion;
        if (leaving == null) return;
        if (outings.walking(leaving)) outings.stop(leaving, null); // A guide that stayed on now heads home.
        s.companion = null;
        s.companionProfile = null;
        companionCooldown.put(s.player.getUUID() + ":" + leaving.getUUID(), System.currentTimeMillis() + COMPANION_COOLDOWN_MS);
        stepAway(s.player, leaving);
        s.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(leaving.getName().getString() + " leaves you to it")
            .withStyle(net.minecraft.ChatFormatting.GRAY), true);
    }
    /** The nearest idle neighbour within eight blocks of the player may join; never one already talking or hostile to the player. */
    private final Map<String, Long> companionCooldown = new HashMap<>();
    private static final long COMPANION_COOLDOWN_MS = 2 * 60_000;
    private boolean companionAvailable(Session s, Villager v, boolean joining) {
        return v.isAlive() && !v.isRemoved() && !v.isBaby() && !v.isSleeping() && !v.isTrading()
            && v.level() == s.player.level() && CompanionPresence.withinRange(v.distanceToSqr(s.player), joining)
            && sessions.values().stream().noneMatch(other -> other != s && (other.villager == v || other.companion == v));
    }
    private void departCompanion(Session s) {
        Villager leaving = s.companion;
        if (leaving == null || s.id == null) return;
        s.departingCompanion = leaving.getId();
        sendCompanionDeparture(s);
        s.companion = null;
        s.companionProfile = null;
        companionCooldown.put(s.player.getUUID() + ":" + leaving.getUUID(), System.currentTimeMillis() + 5000);
        JsonObject message = new JsonObject();
        message.addProperty("type", "companion_departed");
        message.addProperty("sessionId", s.id);
        message.addProperty("entityId", leaving.getId());
        send(s.player, message);
        s.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(leaving.getName().getString() + " moved out of earshot.")
            .withStyle(net.minecraft.ChatFormatting.GRAY), true);
    }
    private void sendCompanionDeparture(Session s) {
        if (s.departingCompanion == null || s.departureSending) return;
        s.departureSending = true;
        JsonObject event = new JsonObject();
        event.addProperty("type", "companion_departed");
        event.addProperty("entityId", s.departingCompanion);
        request("POST", "/sessions/" + s.id + "/events", event).whenComplete((reply, failure) ->
            s.player.level().getServer().execute(() -> {
                s.departureSending = false;
                if (failure == null) s.departingCompanion = null;
            }));
    }
    private Villager companionFor(Session s) {
        if (!companions || s.bedtime || bedtime(s.villager)) return null;
        long now = System.currentTimeMillis();
        companionCooldown.values().removeIf(until -> until < now);
        return s.villager.level().getEntitiesOfClass(Villager.class, s.player.getBoundingBox().inflate(8),
                v -> v != s.villager && companionAvailable(s, v, true)
                    && !companionCooldown.containsKey(s.player.getUUID() + ":" + v.getUUID())
                    && !Negotiation.hostile(standing(new Session(s.player, v)))
                    && sessions.values().stream().noneMatch(other -> other.villager == v || other.companion == v))
            .stream().min(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(s.villager))).orElse(null);
    }
    /** Opinion is per player, so only that player sees the hearts or angry clouds. */
    private static void moodParticles(ServerPlayer player, Villager villager, boolean happy) {
        player.level().sendParticles(player,
            happy ? net.minecraft.core.particles.ParticleTypes.HEART : net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER,
            false, false, villager.getX(), villager.getY() + villager.getBbHeight() + 0.25, villager.getZ(),
            happy ? 5 : 3, 0.3, 0.15, 0.3, 0);
    }
    private final Map<String, TradeReputation> relationships = new HashMap<>();
    private Path relationshipFile;
    private String relationshipKey(Session s) { return s.player.getUUID() + ":" + s.villager.getUUID(); }
    private int standing(Session s) { return relationships.getOrDefault(relationshipKey(s), TradeReputation.neutral()).score(); }
    private Negotiation.Band band(Session s) { return Negotiation.band(standing(s), heroLevel(s.player)); }
    /** Hero of the Village amplifier, or -1 without the effect. */
    private static int heroLevel(ServerPlayer player) {
        var effect = player.getEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE);
        return effect == null ? -1 : effect.getAmplifier();
    }
    /** Villagers without a trade (no profession, nitwits) or with nothing in stock have nothing to sell. */
    private static boolean canTrade(Villager villager) {
        String profession = VillagerMemoryService.profession(villager);
        return !profession.equals("none") && !profession.equals("nitwit")
            && villager.getOffers().stream().anyMatch(offer -> !offer.isOutOfStock());
    }
    /** What the villager knows about its own trade before saying a word: whether it can trade, and what is in stock. */
    private JsonObject tradeContext(Session s) {
        String profession = VillagerMemoryService.profession(s.villager);
        JsonObject trade = new JsonObject();
        JsonArray wares = new JsonArray(), soldOut = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (var offer : s.villager.getOffers()) {
            String name = offer.getResult().getHoverName().getString();
            if (seen.add(name + offer.isOutOfStock())) (offer.isOutOfStock() ? soldOut : wares).add(name);
        }
        boolean jobless = profession.equals("none") || profession.equals("nitwit");
        trade.addProperty("canTrade", canTrade(s.villager));
        trade.addProperty("reason", profession.equals("nitwit") ? "nitwit" : jobless ? "no_profession"
            : s.villager.getOffers().isEmpty() ? "no_offers" : wares.isEmpty() ? "sold_out" : "trading");
        trade.add("wares", wares);
        trade.add("soldOut", soldOut);
        int hero = heroLevel(s.player);
        if (hero >= 0) trade.addProperty("heroOfTheVillage", hero + 1);
        return trade;
    }
    private JsonObject noTrade(Session s) {
        String profession = VillagerMemoryService.profession(s.villager);
        return outcome("no_trade", profession.equals("none") || profession.equals("nitwit")
            ? "You have no trade, so you have nothing to sell. Say so plainly; never invent wares or prices."
            : "Everything you sell is out of stock right now. Say so and suggest they come back after you restock.");
    }
    private void saveTradeReputations() {
        if (relationshipFile == null) return;
        try {
            Files.createDirectories(relationshipFile.getParent());
            Path temporary = relationshipFile.resolveSibling("higgs-relationships.json.tmp");
            Files.writeString(temporary, GSON.toJson(relationships));
            Files.move(temporary, relationshipFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not save villager relationships");
        }
    }
    private JsonObject treatment(Session s, String tone, int turn) {
        if (!Set.of("polite", "neutral", "rude").contains(tone)) return outcome("invalid", "Unknown treatment");
        if (turn <= 0 || turn <= s.treatmentTurn) return outcome("unchanged", "Treatment already assessed for this turn");
        // A neutral verdict must not use up the turn: the keyword fallback may still catch an insult the model missed.
        if (!tone.equals("neutral")) {
            s.treatmentTurn = turn;
            moodParticles(s.player, s.villager, tone.equals("polite"));
        }
        String key = relationshipKey(s);
        TradeReputation previous = relationships.getOrDefault(key, TradeReputation.neutral());
        long now = System.currentTimeMillis();
        TradeReputation next = previous.treatment(tone, now);
        if (!next.equals(previous)) {
            relationships.put(key, next);
            saveTradeReputations();
        }
        boolean changed = next.score() != previous.score();
        if (changed) {
            s.proposal = null;
            tradeStatus(s, outcome("cancelled", "Reputation changed; ask again for the new price."));
        }
        Negotiation.Band limits = band(s);
        if (!tone.equals("neutral")) memory.observe(s.villager, s.player, tone.equals("polite") ? "kindness" : "insult",
            s.player.getName().getString() + (tone.equals("polite") ? " said something kind to me" : " insulted me"),
            tone.equals("polite") ? 2 : 3, 10 * 60_000L);
        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Treatment turn={} tone={} relationship={} band={}",
            turn, tone, next.score(), limits.name());
        if (tone.equals("rude") && Negotiation.hostile(next.score())) rallyGolems(s.player, s.villager);
        JsonObject result = outcome("assessed", Negotiation.hostile(next.score())
            ? "You are now hostile towards this player: refuse every trade and speak coldly until they make amends."
            : changed
            ? "Reputation changed. Any earlier price is outdated: call propose_trade again before saying a price."
            : "Reputation unchanged. Call get_trades or propose_trade for the current price before saying it.");
        result.addProperty("relationship", limits.name());
        result.addProperty("pricePercent", limits.rate());
        return result;
    }
    private static final class Session {
        final ServerPlayer player;
        final Villager villager;
        String id;
        VillagerPersonalityStore.Profile profile;
        boolean polling;
        Proposal proposal;
        int pollFailures;
        int treatmentTurn;
        JsonObject hitEvent;
        long hitAt;
        int lastPurchaseTurn = -1;
        JsonObject lastReceipt;
        JsonObject openingContext;
        QuestOffer questOffer;
        /** A neighbour who joined the conversation; it speaks but never trades. */
        Villager companion;
        VillagerPersonalityStore.Profile companionProfile;
        boolean companionJoining;
        Integer departingCompanion;
        boolean departureSending;
        /** Night: the villager says it is off to bed, and the conversation ends there. */
        boolean bedtime;
        /** The neighbour who just brought the player here, when a guide handed the conversation over. */
        String introducedBy;
        final Map<String, JsonObject> purchases = new HashMap<>();
        final Map<String, JsonObject> toolResults = new HashMap<>();
        final Set<String> pendingTools = new HashSet<>();
        Session(ServerPlayer player, Villager villager) { this.player = player; this.villager = villager; }
    }
    private record Proposal(QuoteConfirmation approval, int index, ItemStack costA,
                            ItemStack costB, ItemStack result, ItemStack baseA, ItemStack baseB, ItemStack baseResult, int stockUses,
                            String questId) {
        String id() { return approval.id(); }
        long expires() { return approval.expires(); }
        VoiceConfirmation confirmation() { return approval.gate(); }
    }
    /** Quests the model may set after a counteroffer, for the player's own terms on one offer. */
    private record QuestOffer(int index, Negotiation.Bundle deal, List<QuestCatalogue.Item> options) {}
    /**
     * An errand standing between the player and their haggled price. The base offer is fingerprinted so a
     * restocked or changed trade cannot be bought at a stale price. Persisted per player and villager.
     */
    private record Quest(String id, int offerIndex, String baseA, int baseACount, String baseB, int baseBCount,
                         String result, int resultCount, String resultName, int payment, int secondary, int quantity,
                         int stockUses, JsonObject deal, String item, int count, String itemName, boolean delivered) {
        Quest deliver() {
            return new Quest(id, offerIndex, baseA, baseACount, baseB, baseBCount, result, resultCount, resultName, payment,
                secondary, quantity, stockUses, deal, item, count, itemName, true);
        }
        String ask() { return count + " × " + itemName; }
    }
    /** What a player has given a villager, and what it refused; the villager remembers both. */
    /**
     * What the gift rules need: items refused (a repeat costs reputation) and the last rewarded gift (cooldown). The history
     * of gifts is in the journal. Older saves also carried "received"; Gson ignores it.
     */
    private record GiftLedger(List<String> refused, long lastReward) {}
    private static final long GIFT_COOLDOWN_MS = 5 * 60_000;
    private final Map<String, GiftLedger> gifts = new HashMap<>();
    private final Map<UUID, Long> lastGiftClick = new HashMap<>();
    private Path giftFile;
    private void saveGifts() {
        if (giftFile == null) return;
        try {
            Files.createDirectories(giftFile.getParent());
            Path temporary = giftFile.resolveSibling("higgs-gifts.json.tmp");
            Files.writeString(temporary, GSON.toJson(gifts));
            Files.move(temporary, giftFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not save villager gifts");
        }
    }
    /** Carry a message or a package from one villager to another in the same village. One per player. */
    private record Errand(String id, String kind, String giverId, String giverName, String targetId, String targetName,
                          String targetProfession, String message, String item, String itemName, long created) {
        String describe() {
            return kind.equals("message") ? "tell " + targetName + ": \"" + message + "\"" : "bring " + targetName + " the " + itemName;
        }
    }
    private static final long ERRAND_MS = 2 * 60 * 60_000L;
    private final Map<String, Errand> errands = new HashMap<>();
    private final Map<String, List<String>> errandHistory = new HashMap<>();
    private Path errandFile;
    private void saveErrands() {
        if (errandFile == null) return;
        try {
            Files.createDirectories(errandFile.getParent());
            JsonObject saved = new JsonObject();
            saved.add("open", GSON.toJsonTree(errands));
            saved.add("history", GSON.toJsonTree(errandHistory));
            Path temporary = errandFile.resolveSibling("higgs-errands.json.tmp");
            Files.writeString(temporary, GSON.toJson(saved));
            Files.move(temporary, errandFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not save villager errands");
        }
    }
    private Errand errand(ServerPlayer player) {
        Errand errand = errands.get(player.getUUID().toString());
        if (errand != null && System.currentTimeMillis() - errand.created() > ERRAND_MS) {
            errands.remove(player.getUUID().toString());
            saveErrands();
            return null;
        }
        return errand;
    }
    private final Map<String, Quest> quests = new HashMap<>();
    private Path questFile;
    private void saveQuests() {
        if (questFile == null) return;
        try {
            Files.createDirectories(questFile.getParent());
            Path temporary = questFile.resolveSibling("higgs-quests.json.tmp");
            Files.writeString(temporary, GSON.toJson(quests));
            Files.move(temporary, questFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not save villager quests");
        }
    }
    @Override public void onInitialize() {
        Map<String, String> config = config();
        backend = config.getOrDefault("HIGGS_BACKEND_URL", "http://127.0.0.1:8787").replaceAll("/+$", "");
        publicBackend = config.getOrDefault("HIGGS_PUBLIC_BACKEND_URL", backend).replaceAll("/+$", "");
        token = config.getOrDefault("HIGGS_BRIDGE_TOKEN", "");
        personalities = new VillagerPersonalityService();
        memory = new VillagerMemoryService(config, personalities);
        companions = !"false".equalsIgnoreCase(config.getOrDefault("HIGGS_COMPANION_ENABLED", "true"));
        villages = new VillageTracker(config, (player, villager) ->
            relationships.getOrDefault(player.getUUID() + ":" + villager.getUUID(), TradeReputation.neutral()).score());
        ServerLifecycleEvents.SERVER_STARTED.register(villages::start);
        chatter = new VillagerChatter(config, new VillagerChatter.Host() {
            public CompletableFuture<JsonElement> request(String method, String path, JsonObject body) { return HiggsVillagers.this.request(method, path, body); }
            public void send(ServerPlayer player, JsonObject message) { HiggsVillagers.this.send(player, message); }
            public boolean canSend(ServerPlayer player) { return ServerPlayNetworking.canSend(player, ControlPayload.TYPE); }
            public boolean talking(ServerPlayer player) { return sessions.containsKey(player.getUUID()); }
            public boolean talking(Villager villager) {
                return sessions.values().stream().anyMatch(s -> s.villager == villager || s.companion == villager);
            }
            public TradeReputation opinion(ServerPlayer player, Villager villager) {
                return relationships.getOrDefault(player.getUUID() + ":" + villager.getUUID(), TradeReputation.neutral());
            }
            public void influenced(ServerPlayer player, Villager speaker, Villager listener, int standing) {
                String key = player.getUUID() + ":" + listener.getUUID();
                TradeReputation previous = relationships.getOrDefault(key, TradeReputation.neutral());
                memory.observe(listener, player, "chatter_heard", speaker.getName().getString()
                    + (standing > previous.score() ? " spoke well of " : " badmouthed ") + player.getName().getString() + " to me", 2, 0);
                relationships.put(key, new TradeReputation(standing, System.currentTimeMillis(), previous.lastHit()));
                saveTradeReputations();
                boolean praise = standing > previous.score();
                moodParticles(player, listener, praise);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(speaker.getName().getString()
                    + (praise ? " spoke well of you to " : " badmouthed you to ") + listener.getName().getString())
                    .withStyle(praise ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GOLD), true);
                org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Chatter gossip: {} {} {} to {}; standing {} -> {}",
                    speaker.getName().getString(), praise ? "praised" : "badmouthed", player.getName().getString(),
                    listener.getName().getString(), previous.score(), standing);
            }
            public String villageName(Villager villager) {
                VillageTracker.Village village = villages.villageOf(villager);
                return village == null ? null : village.name();
            }
        }, personalities, memory, publicBackend);
        alarm = new VillagerAlarm(new VillagerAlarm.Host() {
            public CompletableFuture<JsonElement> request(String method, String path, JsonObject body) { return HiggsVillagers.this.request(method, path, body); }
            public void send(ServerPlayer player, JsonObject message) { HiggsVillagers.this.send(player, message); }
            public boolean canSend(ServerPlayer player) { return ServerPlayNetworking.canSend(player, ControlPayload.TYPE); }
        }, personalities, publicBackend);
        gossipVisit = new VillagerGossipVisit(chatter, villager ->
            sessions.values().stream().anyMatch(s -> s.villager == villager || s.companion == villager)
                || (outings != null && outings.walking(villager)), !token.isBlank(), (villager, busy) -> {
            JsonObject message = new JsonObject();
            message.addProperty("type", "gossip_activity");
            message.addProperty("villagerId", villager.getUUID().toString());
            message.addProperty("busy", busy);
            for (ServerPlayer player : villager.level().getServer().getPlayerList().getPlayers())
                if (player.level() == villager.level() && player.distanceToSqr(villager) <= 32 * 32
                        && ServerPlayNetworking.canSend(player, ControlPayload.TYPE)) send(player, message);
        });
        memory.gossipPresentation(gossipVisit);
        chatter.reservations(gossipVisit::busy);
        moments = new VillageMoments(alarm, villager -> gossipVisit.busy(villager)
                || sessions.values().stream().anyMatch(s -> s.villager == villager || s.companion == villager),
            (player, villager) -> relationships.getOrDefault(player.getUUID() + ":" + villager.getUUID(), TradeReputation.neutral()).score());
        outings = new VillagerOuting(new VillagerOuting.Host() {
            public void event(ServerPlayer player, JsonObject event) {
                Session s = sessions.get(player.getUUID());
                if (s != null && s.id != null) request("POST", "/sessions/" + s.id + "/events", event).exceptionally(ex -> null);
            }
            public int standing(ServerPlayer player, Villager villager) {
                return relationships.getOrDefault(player.getUUID() + ":" + villager.getUUID(), TradeReputation.neutral()).score();
            }
            public VillageTracker.Village village(Villager villager) { return villages.villageOf(villager); }
            public int villageRadius() { return villages.radius(); }
            public void observe(Villager villager, ServerPlayer player, String type, String text, int salience) {
                memory.observe(villager, player, type, text, salience, 0);
            }
            public void arrived(ServerPlayer player, Villager guide, Villager target) { handoff(player.level().getServer(), player, guide, target); }
        });
        memory.bonds(new VillagerMemoryService.Bonds() {
            public int standing(Villager villager, UUID player) {
                return relationships.getOrDefault(player + ":" + villager.getUUID(), TradeReputation.neutral()).score();
            }
            public void reached(Villager villager, ServerPlayer player, RelationshipStage stage) { milestone(villager, player, stage); }
        });
        dashboard = new VillageDashboard(new VillageDashboard.Host() {
            public CompletableFuture<JsonElement> request(String method, String path, JsonObject body) { return HiggsVillagers.this.request(method, path, body); }
            public int standing(UUID player, UUID villager) {
                return relationships.getOrDefault(player + ":" + villager, TradeReputation.neutral()).score();
            }
            public void setStanding(UUID player, UUID villager, int value) {
                TradeReputation previous = relationships.getOrDefault(player + ":" + villager, TradeReputation.neutral());
                relationships.put(player + ":" + villager, new TradeReputation(value, System.currentTimeMillis(), previous.lastHit()));
                saveTradeReputations();
                for (Session s : sessions.values())
                    if (s.player.getUUID().equals(player) && s.villager.getUUID().equals(villager)) s.proposal = null; // Old prices are stale.
            }
            public Set<UUID> playersWithStanding(UUID villager) {
                Set<UUID> players = new java.util.LinkedHashSet<>();
                String suffix = ":" + villager;
                for (String key : relationships.keySet())
                    if (key.endsWith(suffix)) players.add(UUID.fromString(key.substring(0, key.length() - suffix.length())));
                return players;
            }
            public JsonObject quest(UUID player, UUID villager) {
                Quest quest = quests.get(player + ":" + villager);
                if (quest == null) return null;
                JsonObject open = new JsonObject();
                open.addProperty("wants", quest.ask());
                open.addProperty("deal", terms(quest.deal()));
                open.addProperty("delivered", quest.delivered());
                return open;
            }
            public boolean clearQuest(UUID player, UUID villager) {
                if (quests.remove(player + ":" + villager) == null) return false;
                saveQuests();
                return true;
            }
            public boolean deliverQuest(UUID player, UUID villager) {
                Quest quest = quests.get(player + ":" + villager);
                if (quest == null || quest.delivered()) return false;
                quests.put(player + ":" + villager, quest.deliver());
                saveQuests();
                return true;
            }
            public boolean clearErrand(UUID player) {
                if (errands.remove(player.toString()) == null) return false;
                saveErrands();
                return true;
            }
            public JsonObject playerState(MinecraftServer server, ServerPlayer player) { return debugState(server, player).getAsJsonObject("state"); }
            public String talkingWith(Villager villager) {
                return sessions.values().stream().filter(s -> s.villager == villager || s.companion == villager)
                    .map(s -> s.player.getName().getString()).findFirst().orElse(null);
            }
        }, memory, villages, personalities);
        // Ringing a bell: vanilla still rings it; a few villagers come over to see what is the matter.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp && !token.isBlank()
                && world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.BellBlock)
                moments.bellRung(sp, hitResult.getBlockPos().immutable());
            return net.minecraft.world.InteractionResult.PASS;
        });
        ServerLifecycleEvents.SERVER_STARTED.register(personalities::start);
        ServerLifecycleEvents.SERVER_STARTED.register(memory::start);
        PayloadTypeRegistry.serverboundPlay().register(ControlPayload.TYPE, ControlPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ControlPayload.TYPE, ControlPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ControlPayload.TYPE, (payload, ctx) ->
            ctx.server().execute(() -> receive(ctx.server(), ctx.player(), payload.json())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            end(handler.player, false); lastStart.remove(handler.player.getUUID());
        });
        // A hostile villager also refuses the vanilla trading screen.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp) || !(entity instanceof Villager villager)) return net.minecraft.world.InteractionResult.PASS;
            if (sp.isShiftKeyDown() && !sp.isSpectator() && !villager.isBaby() && !sp.getItemInHand(hand).isEmpty()) {
                gift(sp, villager, hand);
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            if (!Negotiation.hostile(standing(new Session(sp, villager)))) return net.minecraft.world.InteractionResult.PASS;
            villager.playSound(SoundEvents.VILLAGER_NO);
            moodParticles(sp, villager, false);
            return net.minecraft.world.InteractionResult.FAIL;
        });
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer sp && entity instanceof Villager villager)
                hit(sp.level().getServer(), sp, villager);
            return net.minecraft.world.InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            memory.tick(server, ++ticks);
            gossipVisit.tick(ticks);
            if (!token.isBlank()) chatter.tick(server, ticks);
            if (!token.isBlank()) alarm.tick(server, ticks);
            if (!token.isBlank()) moments.tick(server, ticks);
            if (!token.isBlank()) dashboard.tick(server, ticks);
            villages.tick(server, ticks);
            // Rotate explicitly: the demo villagers have NoAI, so look controls alone do nothing.
            Map<UUID, Session> facing = new HashMap<>();
            for (Session s : sessions.values()) {
                if (!valid(s, 144)) continue;
                facing.merge(s.villager.getUUID(), s, (a, b) ->
                    a.player.distanceToSqr(a.villager) <= b.player.distanceToSqr(b.villager) ? a : b);
            }
            outings.tick(ticks);
            // A villager walking with the player steers itself; only standing partners turn to face you.
            for (Session s : facing.values()) if (!outings.walking(s.villager)) face(s.villager, s.player);
            if (ticks % 40 == 0) for (Session s : List.copyOf(sessions.values())) if (valid(s)) maybeJoin(server, s);
            for (Session s : sessions.values()) {
                if (s.companion != null && s.companion.isAlive() && s.companion.distanceToSqr(s.player) <= 144 && !outings.walking(s.companion))
                    face(s.companion, s.player);
            }
            gestures.tick(ticks);
            if (ticks % 20 == 0) {
                for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                    if (!ServerPlayNetworking.canSend(viewer, ControlPayload.TYPE)) continue;
                    for (Villager villager : viewer.level().getEntitiesOfClass(Villager.class,
                            viewer.getBoundingBox().inflate(16), v -> v.isAlive() && !v.isBaby())) {
                        Session active = sessions.get(viewer.getUUID());
                        Session opinion = active != null && active.villager == villager ? active : new Session(viewer, villager);
                        JsonObject expression = new JsonObject();
                        expression.addProperty("type", "villager_expression");
                        expression.addProperty("villagerId", villager.getUUID().toString());
                        expression.addProperty("expression", band(opinion).name());
                        expression.addProperty("standing", standing(opinion));
                        send(viewer, expression);
                    }
                }
            }
            if (ticks % 100 == 0 && !token.isBlank()) collectDigests(server);
            if (ticks % 10 != 0) return;
            for (Session s : List.copyOf(sessions.values())) {
                if (s.companion != null && !companionAvailable(s, s.companion, false)) departCompanion(s);
                sendCompanionDeparture(s);
                if (s.bedtime && !bedtime(s.villager)) {
                    bedtimeUntil.remove(s.player.getUUID() + ":" + s.villager.getUUID());
                    end(s.player, true, "Morning has arrived. You can talk to this villager again.");
                }
                else if (!valid(s, 144)) end(s.player, true, "Conversation ended: stay within 12 blocks of an awake villager.");
                else {
                    if (s.proposal != null && System.currentTimeMillis() >= s.proposal.expires()) {
                        s.proposal = null;
                        tradeStatus(s, outcome("expired", "Offer expired. Ask for a fresh quote."));
                    }
                    if (s.id != null && s.hitEvent != null) {
                        JsonObject event = s.hitEvent; s.hitEvent = null;
                        if (System.currentTimeMillis() - s.hitAt < 10_000)
                            request("POST", "/sessions/" + s.id + "/events", event).exceptionally(ex -> {
                                org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not deliver villager hit reaction");
                                return null;
                            });
                    }
                    if (s.id != null && !s.polling) poll(server, s);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            errandFile = server.getWorldPath(LevelResource.ROOT).resolve("data/higgs-errands.json");
            errands.clear();
            errandHistory.clear();
            if (Files.exists(errandFile)) try {
                JsonObject saved = JsonParser.parseString(Files.readString(errandFile)).getAsJsonObject();
                for (var entry : saved.getAsJsonObject("open").entrySet()) errands.put(entry.getKey(), GSON.fromJson(entry.getValue(), Errand.class));
                for (var entry : saved.getAsJsonObject("history").entrySet()) {
                    List<String> done = new ArrayList<>();
                    entry.getValue().getAsJsonArray().forEach(item -> done.add(item.getAsString()));
                    errandHistory.put(entry.getKey(), done);
                }
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not load villager errands"); }
            giftFile = server.getWorldPath(LevelResource.ROOT).resolve("data/higgs-gifts.json");
            gifts.clear();
            if (Files.exists(giftFile)) try {
                JsonObject saved = JsonParser.parseString(Files.readString(giftFile)).getAsJsonObject();
                for (var entry : saved.entrySet()) gifts.put(entry.getKey(), GSON.fromJson(entry.getValue(), GiftLedger.class));
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not load villager gifts"); }
            questFile = server.getWorldPath(LevelResource.ROOT).resolve("data/higgs-quests.json");
            quests.clear();
            if (Files.exists(questFile)) try {
                JsonObject saved = JsonParser.parseString(Files.readString(questFile)).getAsJsonObject();
                for (var entry : saved.entrySet()) quests.put(entry.getKey(), GSON.fromJson(entry.getValue(), Quest.class));
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not load villager quests"); }
            relationshipFile = server.getWorldPath(LevelResource.ROOT).resolve("data/higgs-relationships.json");
            relationships.clear();
            if (Files.exists(relationshipFile)) try {
                JsonObject saved = JsonParser.parseString(Files.readString(relationshipFile)).getAsJsonObject();
                for (var entry : saved.entrySet()) {
                    TradeReputation value = GSON.fromJson(entry.getValue(), TradeReputation.class);
                    relationships.put(entry.getKey(), new TradeReputation(Math.max(-5, Math.min(5, value.score())), value.updated(), value.lastHit()));
                }
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not load villager relationships"); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Session s : List.copyOf(sessions.values())) end(s.player, false);
            lastStart.clear();
            gossipVisit.clear();
            gestures.clear();
            memory.stop();
            personalities.stop();
            chatter.clear();
            alarm.clear();
            moments.clear();
            outings.clear();
            wilderness.clear();
            dashboard.clear();
            hits.clear();
        });
    }
    private void receive(MinecraftServer server, ServerPlayer player, String json) {
        receive(server, player, json, true);
    }
    private void receive(MinecraftServer server, ServerPlayer player, String json, boolean autoGreeting) {
        try {
            JsonObject request = JsonParser.parseString(json).getAsJsonObject();
            String type = request.get("type").getAsString();
            if (type.equals("end")) { end(player, true); return; }
            if (type.equals("debug_state")) { send(player, debugState(server, player)); return; }
            if (!type.equals("start")) return;
            long now = System.currentTimeMillis();
            if (now - lastStart.getOrDefault(player.getUUID(), 0L) < 1500) {
                error(player, "Please wait a moment before starting another conversation."); return;
            }
            lastStart.put(player.getUUID(), now);
            if (!(player.level().getEntity(request.get("entityId").getAsInt()) instanceof Villager villager)) {
                error(player, "Aim at a nearby villager."); return;
            }
            Session s = new Session(player, villager);
            if (!valid(s)) { error(player, "That villager is unavailable or too far away."); return; }
            if (token.isBlank()) { error(player, "Set HIGGS_BRIDGE_TOKEN in the server environment or project .env."); return; }
            // Player-initiated only: a hit still gets its reaction from a villager who would rather not talk.
            // Night: one goodnight line, then a polite refusal for a while (no paid session per retry).
            if (autoGreeting && bedtime(villager)) {
                String key = player.getUUID() + ":" + villager.getUUID();
                if (now < bedtimeUntil.getOrDefault(key, 0L)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(villager.getName().getString() + " is heading to bed")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), true);
                    error(player, villager.getName().getString() + " is heading to bed.");
                    return;
                }
                bedtimeUntil.values().removeIf(until -> until < now);
                bedtimeUntil.put(key, now + BEDTIME_MS);
                s.bedtime = true;
            }
            if (autoGreeting && shuns(player, villager, now)) {
                error(player, villager.getName().getString() + " turns away and won't talk to you.");
                return;
            }
            end(player, false);
            sessions.put(player.getUUID(), s);
            chatter.villagerBusy(villager);
            personalities.prepare(villager).whenComplete((prepared, failure) -> {
                if (server.isStopped()) return;
                server.execute(() -> {
                    if (sessions.get(player.getUUID()) != s) return;
                    if (!valid(s)) { end(player, true); return; }
                    if (failure != null) { end(player, true); error(player, "Could not save villager identity. Check world save permissions or cast tags."); return; }
                    personalities.applyName(villager, prepared);
                    s.profile = prepared.profile();
                    Villager companion = companionFor(s);
                    if (companion == null) { startVoice(server, s, autoGreeting); return; }
                    personalities.prepare(companion).whenComplete((joined, joinFailure) -> {
                        if (server.isStopped()) return;
                        server.execute(() -> {
                            if (sessions.get(player.getUUID()) != s) return;
                            // A failed or now-busy companion just means a two-way conversation.
                            if (joinFailure == null && companionAvailable(s, companion, true)) {
                                personalities.applyName(companion, joined);
                                s.companion = companion;
                                s.companionProfile = joined.profile();
                                chatter.villagerBusy(companion);
                            }
                            startVoice(server, s, autoGreeting);
                        });
                    });
                });
            });
        } catch (RuntimeException ex) { error(player, "Invalid villager request."); }
    }
    private boolean collectingDigests;
    /**
     * Finished conversations, consolidated by the backend into a first-person digest, an updated relationship summary
     * and promises. They become journal episodes; companions remember being there.
     */
    private void collectDigests(MinecraftServer server) {
        if (collectingDigests) return;
        collectingDigests = true;
        request("GET", "/digests", null).whenComplete((reply, failure) -> server.execute(() -> {
            collectingDigests = false;
            VillagerJournal journal = memory.journal();
            if (failure != null || journal == null || reply == null || !reply.isJsonArray()) return;
            long now = System.currentTimeMillis(), day = Math.floorDiv(server.overworld().getOverworldClockTime(), 24000L);
            for (JsonElement element : reply.getAsJsonArray()) {
                try {
                    JsonObject digest = element.getAsJsonObject();
                    if (digest.has("kind") && digest.get("kind").getAsString().equals("scene")) {
                        // Villagers chatting about the player: each remembers what the others said.
                        UUID subject = UUID.fromString(digest.get("playerId").getAsString());
                        String playerName = digest.get("playerName").getAsString();
                        for (JsonElement participant : digest.getAsJsonArray("participants")) {
                            String self = participant.getAsJsonObject().get("villagerId").getAsString();
                            List<String> heard = new ArrayList<>();
                            for (JsonElement line : digest.getAsJsonArray("lines")) {
                                JsonObject said = line.getAsJsonObject();
                                if (said.has("villagerId") && said.get("villagerId").getAsString().equals(self)) continue;
                                heard.add(said.get("name").getAsString() + " said \"" + said.get("text").getAsString() + "\"");
                            }
                            if (heard.isEmpty()) continue;
                            journal.record(UUID.fromString(self), subject, "chatter_heard", "When we talked about " + playerName + ", "
                                + String.join("; ", heard.subList(Math.max(0, heard.size() - 2), heard.size())), day, now, 2, false, 0);
                        }
                        continue;
                    }
                    UUID villager = UUID.fromString(digest.get("villagerId").getAsString());
                    UUID player = UUID.fromString(digest.get("playerId").getAsString());
                    String text = digest.get("digest").getAsString();
                    journal.record(villager, player, "conversation", text, day, now, 3, false, 0);
                    journal.summarise(villager, player, digest.get("summary").getAsString(), now);
                    // Kept or broken promises point back at the promise; open ones only, so a repeat digest changes nothing.
                    var open = journal.openPromises(villager, player, now);
                    for (String kind : List.of("fulfilled", "broken")) {
                        if (!digest.has(kind)) continue;
                        for (JsonElement id : digest.getAsJsonArray(kind)) {
                            var promise = open.stream().filter(e -> e.id().toString().equals(id.getAsString())).findFirst().orElse(null);
                            if (promise == null) continue;
                            boolean kept = kind.equals("fulfilled");
                            journal.record(villager, player, kept ? "promise_kept" : "promise_broken",
                                (kept ? "A promise was kept: " : "A promise was broken: ") + promise.text(), day, now, 4, false, 0, promise.id());
                            String key = player + ":" + villager;
                            TradeReputation previous = relationships.getOrDefault(key, TradeReputation.neutral());
                            relationships.put(key, new TradeReputation(Math.max(-5, Math.min(5, previous.score() + (kept ? 1 : -1))), now, previous.lastHit()));
                            saveTradeReputations();
                        }
                    }
                    for (JsonElement promise : digest.getAsJsonArray("promises"))
                        journal.record(villager, player, "promise", promise.getAsString(), day, now, 4, false, 0);
                    String names = digest.get("playerName").getAsString() + " talked with " + digest.get("villagerName").getAsString();
                    for (JsonElement companion : digest.getAsJsonArray("companions"))
                        journal.record(UUID.fromString(companion.getAsString()), player, "overheard",
                            "I was there when " + names + ": " + text, day, now, 2, true, 0);
                    // The conversation itself may have brought them closer.
                    ServerPlayer online = server.getPlayerList().getPlayer(player);
                    if (online != null && online.level().getEntity(villager) instanceof Villager entity) memory.progress(entity, online);
                } catch (RuntimeException malformed) {
                    org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Skipped a malformed conversation digest");
                }
            }
        }));
    }
    private boolean handingOff;
    private static final Map<String, net.minecraft.resources.ResourceKey<net.minecraft.world.entity.ai.village.poi.PoiType>> WORKSTATIONS = Map.ofEntries(
        Map.entry("meeting", net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING),
        Map.entry("librarian", net.minecraft.world.entity.ai.village.poi.PoiTypes.LIBRARIAN),
        Map.entry("farmer", net.minecraft.world.entity.ai.village.poi.PoiTypes.FARMER),
        Map.entry("cleric", net.minecraft.world.entity.ai.village.poi.PoiTypes.CLERIC),
        Map.entry("armorer", net.minecraft.world.entity.ai.village.poi.PoiTypes.ARMORER),
        Map.entry("weaponsmith", net.minecraft.world.entity.ai.village.poi.PoiTypes.WEAPONSMITH),
        Map.entry("toolsmith", net.minecraft.world.entity.ai.village.poi.PoiTypes.TOOLSMITH),
        Map.entry("fisherman", net.minecraft.world.entity.ai.village.poi.PoiTypes.FISHERMAN),
        Map.entry("shepherd", net.minecraft.world.entity.ai.village.poi.PoiTypes.SHEPHERD),
        Map.entry("butcher", net.minecraft.world.entity.ai.village.poi.PoiTypes.BUTCHER),
        Map.entry("cartographer", net.minecraft.world.entity.ai.village.poi.PoiTypes.CARTOGRAPHER),
        Map.entry("fletcher", net.minecraft.world.entity.ai.village.poi.PoiTypes.FLETCHER),
        Map.entry("leatherworker", net.minecraft.world.entity.ai.village.poi.PoiTypes.LEATHERWORKER),
        Map.entry("mason", net.minecraft.world.entity.ai.village.poi.PoiTypes.MASON));

    /** "Take me to the librarian": find who or what is meant within the guide's village, then lead the way. */
    /** Grown villagers living in this villager's village (or nearby, outside one), nearest first. */
    private List<Villager> neighbours(Villager self) {
        VillageTracker.Village village = villages.villageOf(self);
        var centre = village == null ? self.position() : new net.minecraft.world.phys.Vec3(village.x(), village.y(), village.z());
        double range = village == null ? 48 : villages.radius();
        return self.level().getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(centre, centre).inflate(range, 32, range),
                v -> v != self && v.isAlive() && !v.isBaby() && v.position().distanceToSqr(centre) <= range * range)
            .stream().sorted(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(self))).toList();
    }
    /** Who actually lives here, so the villager never invents a neighbour or a trade the village lacks. */
    private static JsonArray residents(List<Villager> neighbours) {
        JsonArray list = new JsonArray();
        for (Villager v : neighbours.subList(0, Math.min(16, neighbours.size()))) {
            String job = VillagerMemoryService.profession(v);
            list.add(v.getName().getString() + (job.equals("none") ? " (no trade yet)" : job.equals("nitwit") ? " (no trade)" : " the " + job));
        }
        return list;
    }
    private JsonObject guide(Session s, String destination) {
        Villager guide = s.villager;
        VillageTracker.Village village = villages.villageOf(guide);
        var centre = village == null ? guide.position() : new net.minecraft.world.phys.Vec3(village.x(), village.y(), village.z());
        double range = village == null ? 48 : villages.radius();
        List<Villager> neighbours = neighbours(guide);
        List<String> names = neighbours.stream().filter(Villager::hasCustomName).map(v -> v.getName().getString()).toList();
        // Somewhere beyond the village ("the nearest forest", "a pyramid"), unless they named a neighbour.
        Wilds.Place wild = Wilds.interpret(destination);
        if (wild != null && Destinations.interpret(destination, names).stream().noneMatch(g -> g.kind() == Destinations.Kind.VILLAGER))
            return rumour(s, wild, village);
        Villager targetVillager = null;
        net.minecraft.core.BlockPos targetPos = null;
        String label = null;
        for (Destinations.Guess guess : Destinations.interpret(destination, names)) {
            if (guess.kind() == Destinations.Kind.LANDMARK) {
                var key = WORKSTATIONS.get(guess.value());
                if (key == null || !(guide.level() instanceof net.minecraft.server.level.ServerLevel level)) continue;
                var found = level.getPoiManager().findClosest(holder -> holder.is(key), guide.blockPosition(), (int) range + 16,
                    net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY);
                if (found.isPresent() && net.minecraft.world.phys.Vec3.atCenterOf(found.get()).distanceToSqr(centre) <= range * range) {
                    targetPos = found.get();
                    label = guess.label();
                    break;
                }
            } else {
                Villager match = neighbours.stream().filter(v -> guess.kind() == Destinations.Kind.VILLAGER
                        ? v.getName().getString().equals(guess.value()) : VillagerMemoryService.profession(v).equals(guess.value()))
                    .min(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(guide))).orElse(null);
                if (match != null) {
                    targetVillager = match;
                    label = match.getName().getString() + " the " + VillagerMemoryService.profession(match);
                    break;
                }
            }
        }
        if (label == null) {
            JsonObject missing = outcome("not_found", "You do not know of any " + destination
                + (village == null ? " around here" : " in " + village.name()) + ". Say so kindly. If you suggest someone instead, "
                + "choose only from residents: never mention a neighbour or trade not listed there.");
            missing.add("residents", residents(neighbours));
            return missing;
        }
        String refusal = outings.guide(s.player, guide, targetVillager, targetPos, label);
        if (refusal != null) return outcome("refused", refusal);
        var where = targetVillager != null ? targetVillager.position() : net.minecraft.world.phys.Vec3.atCenterOf(targetPos);
        double dx = where.x - guide.getX(), dz = where.z - guide.getZ();
        String bearing = "about " + Math.round(Math.hypot(dx, dz)) + " blocks " + Errands.compass(dx, dz);
        JsonObject result = outcome("guiding", "Tell the player to follow you: you are leading them to " + label + ", " + bearing
            + ". Keep chatting as you walk; you will be told when you arrive.");
        result.addProperty("destination", label);
        result.addProperty("direction", bearing);
        return result;
    }

    private final Wilderness wilderness = new Wilderness();
    /** Farthest a villager will walk a friend out of the village. */
    private static final int ESCORT_BLOCKS = 320;
    /**
     * A place beyond the village, known by rumour. The villager says where to look; one that counts the player as a
     * friend walks them there when it is near enough, then heads home.
     */
    private JsonObject rumour(Session s, Wilds.Place place, VillageTracker.Village village) {
        Villager guide = s.villager;
        if (!(guide.level() instanceof net.minecraft.server.level.ServerLevel level)) return outcome("not_found", "You know of no such place.");
        net.minecraft.core.BlockPos origin = village == null ? guide.blockPosition()
            : net.minecraft.core.BlockPos.containing(village.x(), village.y(), village.z());
        String home = village == null ? "here" : village.name();
        var found = wilderness.locate(level, place, origin, origin, villages.radius());
        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Rumour {} asked of {}: {}", place.id(), guide.getName().getString(),
            found.map(at -> at.toShortString()).orElse("none nearby"));
        if (found.isEmpty()) return outcome("not_found", "You have never heard of " + place.label() + " anywhere near " + home
            + ". Say so in one short sentence; never invent one or guess a direction.");
        var at = found.get();
        double dx = at.getX() - guide.getX(), dz = at.getZ() - guide.getZ(), far = Math.hypot(dx, dz);
        if (far < 24) return outcome("here", "You are right by " + place.label() + " already. Say so and point it out.");
        String direction = Wilds.distance(far) + " " + Errands.compass(dx, dz);
        boolean friend = memory.stage(guide, s.player).ordinal() >= RelationshipStage.FRIEND.ordinal();
        String why = !friend ? "If they ask you to take them, you will not: you have your work in the village."
            : far > ESCORT_BLOCKS ? "It is too far for you to walk there with them; say so if they ask." : null;
        if (why == null) {
            String refusal = outings.expedition(s.player, guide, at, place.label());
            if (refusal == null) {
                JsonObject result = outcome("guiding", "You are taking them out to " + place.label() + ", " + direction + " of here. "
                    + "Tell them to follow you: it is a fair walk, you will keep chatting on the way, and once you get there you will head back to " + home + ".");
                result.addProperty("destination", place.label());
                result.addProperty("direction", direction);
                return result;
            }
            why = "You cannot take them yourself right now: " + refusal;
        }
        s.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Rumour from " + guide.getName().getString() + ": "
            + place.label() + ", " + direction + " of " + home).withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
        memory.observe(guide, s.player, "rumour_told", "I told " + s.player.getName().getString() + " where to find " + place.label()
            + " (" + direction + ")", 2, 10 * 60_000L);
        JsonObject result = outcome("directions", "You have heard tell of " + place.label() + ", " + direction + " of here. "
            + "Tell them where to look in your own words, as something you heard from travellers or traders: the direction and a rough distance, "
            + "nothing more precise. " + why);
        result.addProperty("destination", place.label());
        result.addProperty("direction", direction);
        return result;
    }

    /** At the villager the player asked for: the guide introduces them and the conversation moves to the new villager. */
    private void handoff(MinecraftServer server, ServerPlayer player, Villager guide, Villager target) {
        Session previous = sessions.get(player.getUUID());
        if (previous == null || previous.villager != guide || !target.isAlive()) { outings.release(player); return; }
        outings.stay(guide);
        handingOff = true;
        try { end(player, false); } finally { handingOff = false; }
        JsonObject switching = new JsonObject();
        switching.addProperty("type", "handoff");
        switching.addProperty("entityId", target.getId());
        send(player, switching);
        Session s = new Session(player, target);
        s.introducedBy = guide.getName().getString();
        sessions.put(player.getUUID(), s);
        lastStart.put(player.getUUID(), System.currentTimeMillis());
        chatter.villagerBusy(target);
        personalities.prepare(target).thenCombine(personalities.prepare(guide), List::of).whenComplete((prepared, failure) -> server.execute(() -> {
            if (sessions.get(player.getUUID()) != s) return;
            if (failure != null || !valid(s, 144)) { end(player, true); return; }
            personalities.applyName(target, prepared.get(0));
            s.profile = prepared.get(0).profile();
            s.companion = guide; // The guide stays with you as a companion and can chime in or leave.
            s.companionProfile = prepared.get(1).profile();
            memory.observe(target, player, "introduced", guide.getName().getString() + " brought " + player.getName().getString() + " to meet me", 3, 0);
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("{} guided {} to {}", guide.getName().getString(),
                player.getName().getString(), target.getName().getString());
            startVoice(server, s, true);
        }));
    }
    /** A villager by UUID, if it is loaded in any dimension. */
    private static Villager loaded(MinecraftServer server, UUID id) {
        for (var level : server.getAllLevels()) if (level.getEntity(id) instanceof Villager villager) return villager;
        return null;
    }
    private static String terms(JsonObject deal) {
        String extra = stackText(deal.getAsJsonObject("costB"));
        return stackText(deal.getAsJsonObject("receive")) + " for " + stackText(deal.getAsJsonObject("costA")) + (extra.isEmpty() ? "" : " + " + extra);
    }
    /**
     * Everything the server is holding for this player, for the F9 debug page: the live conversation and its quote,
     * the errand they carry, quests and earned deals, walks, and how each villager they know regards them.
     */
    private JsonObject debugState(MinecraftServer server, ServerPlayer player) {
        JsonObject state = new JsonObject();
        long now = System.currentTimeMillis();
        String self = player.getUUID().toString();
        Session s = sessions.get(player.getUUID());
        JsonObject conversation = new JsonObject();
        if (s == null) conversation.addProperty("with", "nobody");
        else {
            conversation.addProperty("with", s.villager.getName().getString() + " the " + VillagerMemoryService.profession(s.villager));
            if (s.companion != null) conversation.addProperty("companion", s.companion.getName().getString());
            Proposal p = s.proposal;
            conversation.addProperty("openQuote", p == null ? "none" : p.result().getCount() + " × " + p.result().getHoverName().getString()
                + " for " + p.costA().getCount() + " × " + p.costA().getHoverName().getString()
                + (p.costB().isEmpty() ? "" : " + " + p.costB().getCount() + " × " + p.costB().getHoverName().getString())
                + (p.questId() != null ? " (earned with a quest)" : ""));
            if (s.questOffer != null) conversation.addProperty("questOptionsOffered", s.questOffer.options().size());
        }
        String walk = outings.describe(player);
        if (walk != null) conversation.addProperty("walk", walk);
        state.add("conversation", conversation);
        Errand errand = errand(player);
        if (errand == null) state.addProperty("errand", "none");
        else {
            JsonObject job = new JsonObject();
            job.addProperty("from", errand.giverName());
            job.addProperty("to", errand.targetName() + " the " + errand.targetProfession());
            job.addProperty("task", errand.describe());
            job.addProperty("age", (now - errand.created()) / 60_000 + " min of " + ERRAND_MS / 60_000);
            state.add("errand", job);
        }
        JsonArray questList = new JsonArray(), deals = new JsonArray();
        for (var entry : quests.entrySet()) {
            if (!entry.getKey().startsWith(self + ":")) continue;
            Quest quest = entry.getValue();
            Villager giver = loaded(server, UUID.fromString(entry.getKey().substring(self.length() + 1)));
            String who = giver == null ? "a villager not loaded" : giver.getName().getString();
            if (quest.delivered()) deals.add(who + ": " + terms(quest.deal()) + " (earned, unused)");
            else questList.add(who + " wants " + quest.ask() + ", then " + terms(quest.deal()));
        }
        state.add("quests", questList);
        state.add("earnedDeals", deals);
        // Villagers this player has a standing with, loaded and nearest first.
        List<Villager> known = new ArrayList<>();
        for (String key : relationships.keySet()) {
            if (!key.startsWith(self + ":")) continue;
            Villager villager = loaded(server, UUID.fromString(key.substring(self.length() + 1)));
            if (villager != null) known.add(villager);
        }
        known.sort(java.util.Comparator.comparingDouble(v -> v.level() == player.level() ? v.distanceToSqr(player) : Double.MAX_VALUE));
        JsonArray people = new JsonArray();
        VillagerJournal journal = memory.journal();
        for (Villager villager : known.subList(0, Math.min(12, known.size()))) {
            JsonObject row = new JsonObject();
            int standing = standing(new Session(player, villager));
            row.addProperty("name", villager.getName().getString());
            row.addProperty("standing", standing + " " + Negotiation.band(standing).name());
            row.addProperty("stage", memory.stage(villager, player).label);
            if (journal != null) {
                var summary = journal.summary(villager.getUUID(), player.getUUID());
                if (summary != null && summary.nickname() != null) row.addProperty("nickname", summary.nickname());
                if (summary != null && summary.keepsake()) row.addProperty("keepsake", "given");
                int promises = journal.openPromises(villager.getUUID(), player.getUUID(), now).size();
                if (promises > 0) row.addProperty("openPromises", promises);
            }
            List<String> done = errandHistory.get(self + ":" + villager.getUUID());
            if (done != null && !done.isEmpty()) row.addProperty("errandsDone", done.size());
            people.add(row);
        }
        state.add("villagers", people);
        JsonObject message = new JsonObject();
        message.addProperty("type", "debug_state");
        message.add("state", state);
        return message;
    }
    private record RecentConversation(Set<UUID> villagers, String previous, long until) {}
    private static final long CONTINUE_MS = 2 * 60_000;
    private final Map<UUID, RecentConversation> recentConversations = new HashMap<>();
    /** The villager was speaking or listening in this player's conversation moments ago. */
    private RecentConversation continuing(ServerPlayer player, Villager villager) {
        RecentConversation recent = recentConversations.get(player.getUUID());
        if (recent == null || System.currentTimeMillis() > recent.until()) { recentConversations.remove(player.getUUID()); return null; }
        return recent.villagers().contains(villager.getUUID()) ? recent : null;
    }
    private void startVoice(MinecraftServer server, Session s, boolean autoGreeting) {
        RecentConversation continued = continuing(s.player, s.villager);
        if (continued != null) autoGreeting = false; // Already greeted: no hello again after switching.
        if (s.bedtime) autoGreeting = true; // The goodnight line is the whole conversation.
        try {
            ServerPlayer player = s.player;
            Villager villager = s.villager;
            JsonObject body = new JsonObject();
            body.addProperty("playerId", player.getUUID().toString());
            body.addProperty("villagerId", villager.getUUID().toString());
            JsonObject context = new JsonObject();
            context.addProperty("name", villager.getName().getString());
            context.addProperty("profession", VillagerMemoryService.profession(villager));
            context.addProperty("level", villager.getVillagerData().level());
            context.addProperty("playerName", player.getName().getString());
            context.addProperty("relationship", band(s).name());
            JsonObject village = villages.context(player, villager);
            if (village == null) village = new JsonObject();
            village.add("residents", residents(neighbours(villager)));
            context.add("village", village);
            context.add("gifts", giftContext(player, villager));
            context.add("relationship", memory.relationshipContext(villager, player));
            context.add("mind", memory.mind(villager));
            VillagerJournal journal = memory.journal();
            if (journal != null && journal.all(villager.getUUID(), player.getUUID(), System.currentTimeMillis()).stream().noneMatch(e -> !e.witnessed()))
                memory.observe(villager, player, "first_met", "I met " + player.getName().getString() + " for the first time", 5, 0);
            if (autoGreeting && !s.bedtime) {
                String present = dailyGift(player, villager);
                if (present != null) context.addProperty("dailyGift", present);
            }
            if (s.companion != null) {
                JsonObject neighbour = new JsonObject();
                neighbour.addProperty("name", s.companion.getName().getString());
                neighbour.addProperty("profession", VillagerMemoryService.profession(s.companion));
                context.add("companion", neighbour);
                body.add("companion", chatter.speaker(player, s.companion, s.companionProfile, List.of(s.villager)));
            }
            JsonObject errands = errandContext(player, villager);
            if (!errands.isEmpty()) context.add("errands", errands);
            memory.conversationContext(villager, player).entrySet().forEach(entry -> context.add(entry.getKey(), entry.getValue()));
            if (s.profile != null) context.add("personality", personalities.context(s.profile, player));
            context.addProperty("autoGreeting", autoGreeting);
            if (s.bedtime) context.addProperty("bedtime", true);
            if (s.introducedBy != null) context.addProperty("introducedBy", s.introducedBy);
            if (continued != null) {
                JsonObject carry = new JsonObject();
                carry.addProperty("previous", continued.previous());
                context.add("continuing", carry);
            }
            JsonObject trade = tradeContext(s);
            context.add("trade", trade);
            if (trade.get("canTrade").getAsBoolean()) context.add("offers", offers(s));
            Quest quest = quests.get(relationshipKey(s));
            if (quest != null) {
                JsonObject open = new JsonObject();
                open.addProperty("wants", quest.ask());
                open.add("deal", quest.deal());
                open.addProperty("delivered", quest.delivered());
                if (quest.delivered()) open.addProperty("owed", "They earned this deal and have not used it yet: it is theirs until they buy it.");
                context.add("quest", open);
            }
            body.add("context", context);
            s.openingContext = autoGreeting && context.has("openingRumour") ? context : null;
            request("POST", "/sessions", body).whenComplete((reply, failure) -> server.execute(() -> {
                if (failure != null) {
                    if (sessions.get(player.getUUID()) == s) { sessions.remove(player.getUUID()); error(player, "Voice bridge unavailable. Check backend configuration and logs."); }
                    return;
                }
                try {
                    JsonObject r = reply.getAsJsonObject(); s.id = r.get("sessionId").getAsString();
                    if (sessions.get(player.getUUID()) != s || !valid(s)) { delete(s.id); return; }
                    if (s.companion != null && !companionAvailable(s, s.companion, true)) departCompanion(s);
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "session"); response.addProperty("sessionId", s.id);
                    String ticket = r.get("ticket").getAsString(); response.addProperty("ticket", ticket);
                    response.addProperty("url", publicBackend.replaceFirst("^http", "ws") + "/audio?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8));
                    if (s.companion != null) {
                        response.addProperty("companionEntityId", s.companion.getId());
                        response.addProperty("companionName", s.companion.getName().getString());
                    }
                    send(player, response);
                    Quest open = quests.get(relationshipKey(s));
                    if (open != null) questStatus(s, open, open.delivered() ? "quest_delivered" : "quest", "");
                } catch (RuntimeException ex) { end(player, true); error(player, "Invalid voice bridge response."); }
            }));
        } catch (RuntimeException failure) {
            end(s.player, true);
            error(s.player, "Could not prepare the villager conversation. Check server logs and configuration.");
        }
    }
    private boolean valid(Session s) {
        return valid(s, 64);
    }
    private boolean valid(Session s, double maximumDistanceSquared) {
        return s.player.isAlive() && !s.player.isSpectator() && s.villager.isAlive() && !s.villager.isBaby() && !s.villager.isSleeping()
            && s.player.level() == s.villager.level() && s.player.distanceToSqr(s.villager) <= maximumDistanceSquared;
    }
    private VillagerMemoryService.SharePlan sharePlan(Session s, String memoryId) {
        if (!gossipVisit.readyForDeparture(s.villager) || s.proposal != null || s.villager.isTrading() || bedtime(s.villager) || outings.walking(s.villager)
                || VillagerAlarm.chased(s.villager)) return null;
        return memory.planShare(s.villager, memoryId, gossipVisit::readyForListener);
    }

    private void poll(MinecraftServer server, Session s) {
        s.polling = true;
        request("GET", "/sessions/" + s.id + "/tools", null).whenComplete((reply, failure) -> server.execute(() -> {
            s.polling = false;
            if (sessions.get(s.player.getUUID()) != s) return;
            if (failure != null) {
                // A single transient HTTP failure must not tear down a healthy audio session.
                if (++s.pollFailures >= 3) end(s.player, true, "Voice bridge unreachable after three attempts. Look at a villager to reconnect.");
                return;
            }
            s.pollFailures = 0;
            if (!valid(s, 144)) { end(s.player, true, "Conversation ended: villager is no longer nearby or available."); return; }
            try {
                for (JsonElement element : reply.getAsJsonArray()) {
                    JsonObject call = element.getAsJsonObject();
                    String callId = call.get("callId").getAsString();
                    JsonObject result = s.toolResults.get(callId);
                    if (result == null) {
                        if (s.pendingTools.contains(callId)) continue;
                        if (s.toolResults.size() + s.pendingTools.size() >= 256) { end(s.player, true); return; }
                        result = new JsonObject();
                        String name = call.get("name").getAsString();
                        if (name.equals("_share_news_depart") && call.has("internal") && call.get("internal").getAsBoolean()) {
                            var plan = sharePlan(s, call.getAsJsonObject("arguments").get("memoryId").getAsString());
                            if (plan != null) {
                                end(s.player, true, s.villager.getName().getString() + " is going to share the news.");
                                memory.startShare(server, plan);
                                return;
                            }
                            result = outcome("unavailable", "The neighbour is no longer available.");
                            s.toolResults.put(callId, result); submitToolResult(s, callId, result); continue;
                        }
                        if (name.equals("_conversation_opened") && call.has("internal") && call.get("internal").getAsBoolean()) {
                            if (s.openingContext != null) {
                                memory.conversationOpened(s.villager, s.player, s.openingContext);
                                s.openingContext = null;
                            }
                            result.addProperty("status", "offered");
                            s.toolResults.put(callId, result);
                            submitToolResult(s, callId, result);
                            continue;
                        }
                        if (name.equals("_companion_left") && call.has("internal") && call.get("internal").getAsBoolean()) {
                            companionLeft(s);
                            result.addProperty("status", "left");
                            s.toolResults.put(callId, result);
                            submitToolResult(s, callId, result);
                            continue;
                        }
                        if (name.equals("_personality_ready") && call.has("internal") && call.get("internal").getAsBoolean()) {
                            s.pendingTools.add(callId);
                            personalities.confirmVisit(s.profile, s.player).whenComplete((saved, saveFailure) -> {
                                if (server.isStopped()) return;
                                server.execute(() -> {
                                    s.pendingTools.remove(callId);
                                    if (sessions.get(s.player.getUUID()) != s) return;
                                    JsonObject response = saveFailure == null ? saved : VillagerMemoryService.error("Could not save familiarity.");
                                    s.toolResults.put(callId, response);
                                    submitToolResult(s, callId, response);
                                });
                            });
                            continue;
                        }
                        if (name.equals("offer_errand")) {
                            s.pendingTools.add(callId);
                            offerErrand(server, s, call.getAsJsonObject("arguments")).whenComplete((offered, errandFailure) -> server.execute(() -> {
                                s.pendingTools.remove(callId);
                                if (sessions.get(s.player.getUUID()) != s) return;
                                JsonObject response = errandFailure == null ? offered : outcome("unavailable", "The errand could not be set up. Apologise.");
                                org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Tool=offer_errand status={}", response.get("status").getAsString());
                                s.toolResults.put(callId, response);
                                submitToolResult(s, callId, response);
                            }));
                            continue;
                        }
                        if (memory.handles(name)) {
                            s.pendingTools.add(callId);
                            JsonObject arguments;
                            try {
                                JsonElement args = call.get("arguments");
                                arguments = args.isJsonPrimitive() ? JsonParser.parseString(args.getAsString()).getAsJsonObject() : args.getAsJsonObject();
                            } catch (RuntimeException invalid) {
                                arguments = new JsonObject();
                            }
                            memory.handle(s.player, s.villager, name, arguments).whenComplete((saved, saveFailure) -> {
                                if (server.isStopped()) return;
                                server.execute(() -> {
                                    s.pendingTools.remove(callId);
                                    if (sessions.get(s.player.getUUID()) != s) return;
                                    JsonObject response = saveFailure == null ? saved
                                        : VillagerMemoryService.error("Could not save memory. Please try again; it may be lost on restart.");
                                    s.toolResults.put(callId, response);
                                    submitToolResult(s, callId, response);
                                });
                            });
                            continue;
                        }
                        if (name.equals("share_news")) {
                            var plan = sharePlan(s, call.getAsJsonObject("arguments").get("memoryId").getAsString());
                            result = plan == null ? outcome("unavailable", "Stay with the player: no available neighbour can hear that public story yet.")
                                : outcome("ready", "You may go tell " + plan.target().getName().getString() + ". Give a brief farewell now; you will leave after speaking. Do not claim it has been delivered.");
                        } else if (name.equals("express_emotion")) {
                            boolean shown = gestures.show(s.villager, call.getAsJsonObject("arguments").get("emotion").getAsString(), ticks);
                            result = outcome(shown ? "expressed" : "cooldown", "Continue speaking naturally; do not describe the gesture.");
                        } else if (name.equals("get_trades")) {
                            if (Negotiation.hostile(standing(s))) result = refused();
                            else if (!canTrade(s.villager)) result = noTrade(s);
                            else result.add("offers", offers(s));
                        }
                        else if (name.equals("report_treatment")) {
                            result = treatment(s, call.getAsJsonObject("arguments").get("treatment").getAsString(), call.get("turnId").getAsInt());
                        } else if (name.equals("cancel_trade")) {
                            s.proposal = null; result = outcome("cancelled", "The proposal is cancelled. Nothing was purchased.");
                        } else if (name.equals("preview_trade")) {
                            result = previewTrade(s, call.getAsJsonObject("arguments"), call.get("turnId").getAsInt());
                        } else if (name.equals("propose_trade")) {
                            result = proposeTrade(s, call.getAsJsonObject("arguments"), call.get("turnId").getAsInt());
                        } else if (name.equals("confirm_trade")) {
                            result = confirmTrade(s, call.getAsJsonObject("arguments").get("proposalId").getAsString(), call.get("turnId").getAsInt());
                        } else if (name.equals("offer_quest")) {
                            result = offerQuest(s, call.getAsJsonObject("arguments").get("item").getAsString());
                        } else if (name.equals("follow_player")) {
                            String refusal = outings.follow(s.player, s.villager);
                            result = refusal == null ? outcome("following", "You are walking with the player now. Say something brief as you set off, "
                                + "and keep talking as you walk; game events tell you what you pass.") : outcome("refused", refusal);
                        } else if (name.equals("set_nickname")) {
                            result = nickname(s, call.getAsJsonObject("arguments").get("nickname").getAsString());
                        } else if (name.equals("write_our_story")) {
                            result = memory.stage(s.villager, s.player).ordinal() < RelationshipStage.CLOSE_FRIEND.ordinal()
                                ? outcome("refused", "You do not know each other well enough yet to write your story. Say so kindly.")
                                : keepsake(s.player, s.villager) ? outcome("given", "You wrote down your story together and gave them the book. Say so warmly.")
                                : outcome("already_given", "You already gave them the book of your story. Say they have it already.");
                        } else if (name.equals("guide_player")) {
                            result = guide(s, call.getAsJsonObject("arguments").get("destination").getAsString());
                        } else if (name.equals("stop_following")) {
                            boolean walking = outings.following(s.villager, s.player);
                            if (walking) outings.stop(s.villager, null);
                            result = walking ? outcome("stopped", "Say a brief goodbye; you are heading back home.")
                                : outcome("not_walking", "You are not walking with the player. Say so if it matters.");
                        } else if (name.equals("deliver_errand")) {
                            JsonObject errandArgs = call.getAsJsonObject("arguments");
                            result = deliverErrand(s, errandArgs != null && errandArgs.has("conveyed") ? errandArgs.get("conveyed").getAsString() : "");
                        } else if (name.equals("receive_gift")) {
                            result = receiveGift(s, call.getAsJsonObject("arguments"));
                        } else if (name.equals("complete_quest")) {
                            result = completeQuest(s, call.get("turnId").getAsInt());
                        } else result.addProperty("error", "Unknown tool.");
                        if (Set.of("offer_quest", "complete_quest", "receive_gift", "deliver_errand", "follow_player", "stop_following", "guide_player",
                                "set_nickname", "write_our_story").contains(name))
                            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Tool={} turn={} status={}",
                                name, call.get("turnId").getAsInt(), result.has("status") ? result.get("status").getAsString() : "unknown");
                        if (Set.of("preview_trade", "propose_trade", "confirm_trade", "cancel_trade").contains(name)) {
                            tradeStatus(s, result);
                            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Trade tool={} turn={} status={}",
                                name, call.get("turnId").getAsInt(), result.has("status") ? result.get("status").getAsString() : "unknown");
                        }
                        // Store before sending: retries must never execute a game action twice.
                        s.toolResults.put(callId, result);
                    }
                    submitToolResult(s, callId, result);
                }
            } catch (RuntimeException ex) { end(s.player, true); error(s.player, "Invalid voice bridge tool request."); }
        }));
    }
    private void submitToolResult(Session session, String callId, JsonObject result) {
        JsonObject body = new JsonObject(); body.add("result", result);
        request("POST", "/sessions/" + session.id + "/tools/" + URLEncoder.encode(callId, StandardCharsets.UTF_8) + "/result", body)
            .exceptionally(ex -> null);
    }
    private void tradeStatus(Session s, JsonObject result) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "trade_status");
        message.addProperty("sessionId", s.id);
        message.addProperty("entityId", s.villager.getId());
        message.addProperty("villager", s.villager.getName().getString());
        message.addProperty("status", result.has("status") ? result.get("status").getAsString() : "unavailable");
        message.addProperty("message", result.has("message") ? result.get("message").getAsString() : "Ask for a new quote.");
        if (s.proposal != null) {
            Proposal p = s.proposal;
            message.addProperty("proposalId", p.id());
            message.addProperty("expiresInMs", Math.max(0, p.expires() - System.currentTimeMillis()));
            message.add("costA", item(p.costA())); message.add("costB", item(p.costB())); message.add("receive", item(p.result()));
        }
        if (result.has("status") && result.get("status").getAsString().equals("interest")) {
            message.add("costA", result.get("costA")); message.add("costB", result.get("costB")); message.add("receive", result.get("receive"));
        }
        send(s.player, message);
    }
    private static final String RESTATE_QUOTE = "The player has not just heard this quote and may expect a different price. "
        + "Say these exact costs and received quantity, correcting any other price mentioned, then ask Confirm trade? "
        + "Wait for clear spoken acceptance, such as yes, yeah, sure, okay, sounds good, deal, or go ahead. "
        + "Refusals, questions and changed or conditional terms do not count. Do not call confirm_trade until the player answers.";
    private JsonObject outcome(String status, String message) {
        JsonObject result = new JsonObject(); result.addProperty("status", status); result.addProperty("message", message); return result;
    }
    private boolean availableForVoiceTrade(Session s) {
        return valid(s) && s.player.containerMenu == s.player.inventoryMenu && !s.villager.isTrading();
    }
    private JsonObject quote(Proposal p, String status, String message) {
        JsonObject result = outcome(status, message);
        result.addProperty("proposalId", p.id()); result.add("costA", item(p.costA()));
        result.add("costB", item(p.costB())); result.add("receive", item(p.result()));
        return result;
    }
    private JsonObject previewTrade(Session s, JsonObject arguments, int turn) {
        JsonObject result = proposeTrade(s, arguments, turn);
        if (s.proposal == null) return result;
        s.proposal = null; // Interest is never a purchasable quote.
        result.remove("proposalId");
        result.addProperty("status", "interest");
        result.addProperty("message", "Suggest this item briefly and ask Interested? A yes means interest only: call propose_trade next to quote exact terms. Do not call confirm_trade.");
        return result;
    }
    /** Sneak + use: hand over one of the held item. */
    private void gift(ServerPlayer player, Villager villager, net.minecraft.world.InteractionHand hand) {
        long now = System.currentTimeMillis();
        // Fabric reports both the interact and interact-at packets; one click is one gift.
        if (now - lastGiftClick.getOrDefault(player.getUUID(), 0L) < 300) return;
        lastGiftClick.put(player.getUUID(), now);
        ItemStack held = player.getItemInHand(hand);
        // Handing an errand package to its recipient delivers the errand, whether or not you are talking to them.
        Errand open = errand(player);
        if (open != null && open.kind().equals("item") && open.targetId().equals(villager.getUUID().toString()) && id(held).equals(open.item())) {
            Session talking = sessions.get(player.getUUID());
            deliverErrand(talking != null && talking.villager == villager ? talking : new Session(player, villager), "");
            return;
        }
        giveGift(player, villager, held, 1, false);
    }
    /** Spoken gift: the item the player named, from their hands first, then their inventory. */
    private JsonObject receiveGift(Session s, JsonObject arguments) {
        String query = arguments.get("item").getAsString();
        int count = arguments.has("count") ? arguments.get("count").getAsInt() : 1;
        ItemStack found = findGift(s.player, query);
        // Handing over an errand package is a delivery, not a gift.
        Errand open = errand(s.player);
        if (found != null && open != null && open.kind().equals("item") && open.targetId().equals(s.villager.getUUID().toString())
            && id(found).equals(open.item())) return deliverErrand(s, "");
        if (found == null) return outcome("not_found", "The player has no " + query + " to give. Say so kindly; nothing was given. "
            + "Never say you received it.");
        return giveGift(s.player, s.villager, found, count, true);
    }
    private ItemStack findGift(ServerPlayer player, String query) {
        String wanted = giftName(query);
        if (wanted.isEmpty()) return null;
        List<ItemStack> candidates = new ArrayList<>(List.of(player.getMainHandItem(), player.getOffhandItem()));
        candidates.addAll(player.getInventory().getNonEquipmentItems());
        for (boolean exact : new boolean[] {true, false}) {
            for (ItemStack stack : candidates) {
                if (stack.isEmpty()) continue;
                String name = giftName(stack.getHoverName().getString()), path = giftName(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
                if (exact ? name.equals(wanted) || path.equals(wanted)
                    : name.contains(wanted) || wanted.contains(name) || path.contains(wanted)) return stack;
            }
        }
        return null;
    }
    /** "a couple of Diamonds" and "diamond" both become "diamond". */
    private static String giftName(String text) {
        String clean = text.toLowerCase(java.util.Locale.ROOT).replace('_', ' ').replaceAll("[^a-z ]", " ")
            .replaceAll("\\b(a|an|the|some|my|this|these|of|couple|few)\\b", " ").trim().replaceAll("\\s+", " ");
        return clean.endsWith("ies") ? clean.substring(0, clean.length() - 3) + "y"
            : clean.endsWith("s") && !clean.endsWith("ss") ? clean.substring(0, clean.length() - 1) : clean;
    }
    /**
     * Judges and (unless refused) takes up to count of the given stack's item. Updates reputation and the gift
     * memory, shows feedback and the tracker card, and returns the result the voice model reports.
     */
    private JsonObject giveGift(ServerPlayer player, Villager villager, ItemStack source, int requested, boolean spoken) {
        long now = System.currentTimeMillis();
        ItemStack template = source.copyWithCount(1);
        String item = id(template), name = template.getHoverName().getString();
        String key = player.getUUID() + ":" + villager.getUUID();
        GiftLedger ledger = gifts.getOrDefault(key, new GiftLedger(List.of(), 0));
        var verdict = GiftCatalogue.judge(GiftCatalogue.preferences(villager.getUUID(), VillagerMemoryService.profession(villager)), item);
        boolean refusedBefore = ledger.refused().contains(item);
        boolean cooldown = now - ledger.lastReward() < GIFT_COOLDOWN_MS;
        TradeReputation previous = relationships.getOrDefault(key, TradeReputation.neutral());
        int next = GiftCatalogue.standingAfter(previous.score(), verdict, refusedBefore, cooldown);
        List<String> refused = new ArrayList<>(ledger.refused());
        int given = 0;
        if (verdict == GiftCatalogue.Verdict.DISLIKED) {
            if (!refusedBefore) refused.add(item); // Handed back; the item stays with the player.
        } else {
            int count = Math.max(1, Math.min(64, requested));
            var match = (java.util.function.Predicate<ItemStack>) stack -> ItemStack.isSameItemSameComponents(stack, template);
            var craft = player.inventoryMenu.getCraftSlots();
            // Taken in every game mode: a gift you still hold afterwards does not feel given.
            given = player.getInventory().clearOrCountMatchingItems(match, count, craft);
            if (given <= 0) return outcome("not_found", "The player no longer has the " + name + ". Nothing was given.");
            player.inventoryMenu.broadcastChanges();
        }
        gifts.put(key, new GiftLedger(refused, next > previous.score() ? now : ledger.lastReward()));
        saveGifts();
        Session s = sessions.get(player.getUUID());
        boolean talking = s != null && s.villager == villager && s.id != null;
        if (next != previous.score()) {
            relationships.put(key, new TradeReputation(next, now, previous.lastHit()));
            saveTradeReputations();
            if (talking && s.proposal != null) {
                s.proposal = null;
                tradeStatus(s, outcome("cancelled", "Reputation changed; ask again for the new price."));
            }
        }
        String villagerName = villager.getName().getString();
        int change = next - previous.score();
        String delta = change == 0 ? "" : String.format(" (%+d)", change);
        String gift = given > 1 ? given + " × " + name : name;
        String message = switch (verdict) {
            case LOVED -> villagerName + " loves the " + gift + "!" + (cooldown ? " (too soon for more reputation)" : delta);
            case PRECIOUS, LIKED -> villagerName + " gratefully accepts the " + gift + (cooldown ? " (too soon for more reputation)" : delta);
            case JUNK -> villagerName + " politely accepts the " + gift;
            case DISLIKED -> refusedBefore ? villagerName + " refuses the " + name + " again, annoyed" + delta
                : villagerName + " refuses the " + name + " and will remember that";
        };
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message).withStyle(
            verdict == GiftCatalogue.Verdict.DISLIKED ? net.minecraft.ChatFormatting.GOLD
                : verdict == GiftCatalogue.Verdict.JUNK ? net.minecraft.ChatFormatting.GRAY : net.minecraft.ChatFormatting.GREEN), true);
        villager.playSound(verdict == GiftCatalogue.Verdict.DISLIKED ? SoundEvents.VILLAGER_NO
            : verdict == GiftCatalogue.Verdict.JUNK ? SoundEvents.VILLAGER_AMBIENT : SoundEvents.VILLAGER_YES);
        if (verdict != GiftCatalogue.Verdict.JUNK) moodParticles(player, villager, verdict != GiftCatalogue.Verdict.DISLIKED);
        String giver = player.getName().getString();
        memory.observe(villager, player, verdict == GiftCatalogue.Verdict.DISLIKED ? "gift_refused" : "gift", switch (verdict) {
            case LOVED -> giver + " gave me " + gift + ", one of my favourite things";
            case PRECIOUS -> giver + " gave me " + gift + "; a generous gift";
            case LIKED -> giver + " gave me " + gift;
            case JUNK -> giver + " gave me " + gift + ", which was of little use";
            case DISLIKED -> giver + " offered me " + name + (refusedBefore ? " again, though I had refused it before" : "; I refused it");
        }, switch (verdict) { case LOVED, PRECIOUS -> 4; case DISLIKED, LIKED -> 3; default -> 2; }, 0);
        if (verdict != GiftCatalogue.Verdict.DISLIKED)
            memory.witnesses(villager, player, "I saw " + giver + " give " + villagerName + " a gift", 0);
        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Gift {} -> {}: {} x{} verdict={} spoken={} standing {} -> {}",
            player.getName().getString(), villagerName, item, given, verdict, spoken, previous.score(), next);
        String status = "gift_" + verdict.name().toLowerCase(java.util.Locale.ROOT);
        String feeling = switch (verdict) {
            case LOVED -> "Delighted";
            case PRECIOUS -> "Very grateful";
            case LIKED -> "Grateful";
            case JUNK -> "Polite";
            case DISLIKED -> refusedBefore ? "Annoyed" : "Not for them";
        };
        if (talking) giftStatus(s, status, template.copyWithCount(Math.max(1, given)), feeling + (cooldown && change == 0 && verdict != GiftCatalogue.Verdict.DISLIKED
            && verdict != GiftCatalogue.Verdict.JUNK ? " · too soon" : delta));
        if (talking && !spoken) {
            // Sneak-and-use while talking: the villager reacts out loud.
            JsonObject event = new JsonObject();
            event.addProperty("type", "gift");
            event.addProperty("item", gift.length() > 64 ? gift.substring(0, 64) : gift);
            event.addProperty("verdict", verdict.name().toLowerCase(java.util.Locale.ROOT));
            event.addProperty("repeat", refusedBefore);
            event.addProperty("change", change);
            request("POST", "/sessions/" + s.id + "/events", event).exceptionally(ex -> null);
        }
        String react = switch (verdict) {
            case LOVED -> "You received " + gift + ". It is one of your favourite things: be delighted. ";
            case PRECIOUS -> "You received " + gift + ", a generous gift: be warmly grateful. ";
            case LIKED -> "You received " + gift + " and like it: thank them. ";
            case JUNK -> "You received " + gift + ". It is of little use to you; accept politely without gushing. ";
            case DISLIKED -> refusedBefore ? "You refused " + name + " before and refuse it again; it was handed back. Be a little annoyed. "
                : "You do not want " + name + "; it was handed back and you will remember. Decline kindly. ";
        };
        JsonObject result = outcome(status, react + "React in one short sentence. Never claim to receive a gift without this result.");
        result.addProperty("given", given);
        result.addProperty("reputationChange", change);
        return result;
    }
    /** A book and quill describing a quest or errand, so the player does not forget who wanted what. Theirs to keep or edit. */
    private static void giveNote(ServerPlayer player, String title, List<String> pages) {
        int limit = net.minecraft.world.item.component.WritableBookContent.PAGE_EDIT_LENGTH;
        ItemStack book = new ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT, new net.minecraft.world.item.component.WritableBookContent(
            pages.stream().map(page -> page.length() > limit ? page.substring(0, limit) : page)
                .map(net.minecraft.server.network.Filterable::passThrough).toList()));
        book.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(title));
        give(player, book);
    }
    /** Into the inventory, or at the player's feet when it is full. */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false, false);
        player.inventoryMenu.broadcastChanges();
    }
    /** A new stage in the relationship: a toast for the player, and at confidant the keepsake book. */
    private void milestone(Villager villager, ServerPlayer player, RelationshipStage stage) {
        String name = villager.getName().getString();
        player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(stage == RelationshipStage.CONFIDANT
            ? name + " now trusts you with anything" : name + " now considers you a " + stage.label));
        moodParticles(player, villager, true);
        player.level().playSound(null, villager.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_CELEBRATE,
            net.minecraft.sounds.SoundSource.NEUTRAL, 1, 1);
        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Relationship milestone {} -> {}: {}", name, player.getName().getString(), stage.label);
        Session s = sessions.get(player.getUUID());
        if (s != null && s.villager == villager && s.id != null) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "milestone");
            event.addProperty("details", stage == RelationshipStage.CONFIDANT ? "a dear friend you trust with anything" : "a " + stage.label);
            request("POST", "/sessions/" + s.id + "/events", event).exceptionally(ex -> null);
        }
        if (stage == RelationshipStage.CONFIDANT) keepsake(player, villager);
    }
    /** A fond name, chosen once by a close friend and kept. */
    private JsonObject nickname(Session s, String nickname) {
        VillagerJournal journal = memory.journal();
        if (journal == null) return outcome("unavailable", "You cannot settle on a name right now.");
        if (memory.stage(s.villager, s.player).ordinal() < RelationshipStage.CLOSE_FRIEND.ordinal())
            return outcome("refused", "You do not know them well enough for a nickname yet. Use their name.");
        var summary = journal.summary(s.villager.getUUID(), s.player.getUUID());
        if (summary != null && summary.nickname() != null)
            return outcome("already_named", "You already call them " + summary.nickname() + ". Keep using that.");
        String clean = nickname.replaceAll("\\s+", " ").trim();
        journal.update(s.villager.getUUID(), s.player.getUUID(), record -> record.withNickname(clean));
        memory.observe(s.villager, s.player, "nickname", "I started calling " + s.player.getName().getString() + " \"" + clean + "\"", 3, 0);
        return outcome("named", "From now on you call them " + clean + ". Tell them in one short, fond sentence.");
    }
    /** Once per in-game day a confidant greets the player with one of its own wares. Returns what was given, or null. */
    private String dailyGift(ServerPlayer player, Villager villager) {
        VillagerJournal journal = memory.journal();
        if (journal == null || memory.stage(villager, player) != RelationshipStage.CONFIDANT || villager.isBaby()) return null;
        long today = VillagerMemoryService.gameDay(villager);
        var summary = journal.summary(villager.getUUID(), player.getUUID());
        if (summary != null && summary.lastGiftDay() >= today) return null;
        var wares = villager.getOffers().stream().map(MerchantOffer::getResult)
            .filter(stack -> !stack.isEmpty() && !stack.is(net.minecraft.world.item.Items.EMERALD)).toList();
        if (wares.isEmpty()) return null;
        ItemStack present = wares.get(Math.floorMod(villager.getUUID().hashCode() + (int) today, wares.size())).copy();
        String described = present.getCount() + " × " + present.getHoverName().getString();
        give(player, present);
        journal.update(villager.getUUID(), player.getUUID(), record -> record.withGiftDay(today));
        memory.observe(villager, player, "gave_gift", "I gave " + player.getName().getString() + " " + described + " as a little present", 3, 0);
        moodParticles(player, villager, true);
        return described;
    }
    /** "Our story", written by the villager from its journal: no model call. False if it was already given. */
    private boolean keepsake(ServerPlayer player, Villager villager) {
        VillagerJournal journal = memory.journal();
        if (journal == null) return false;
        var summary = journal.summary(villager.getUUID(), player.getUUID());
        if (summary != null && summary.keepsake()) return false;
        var episodes = journal.all(villager.getUUID(), player.getUUID(), System.currentTimeMillis());
        String author = villager.getName().getString();
        List<String> pages = Keepsake.pages(author, player.getName().getString(), summary == null ? "" : summary.text(),
            episodes.stream().map(e -> new Keepsake.Moment(e.type(), e.text(), e.gameDay(), e.salience(), e.witnessed())).toList());
        ItemStack book = new ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT, new net.minecraft.world.item.component.WrittenBookContent(
            net.minecraft.server.network.Filterable.passThrough("Our story"), author.length() > 32 ? author.substring(0, 32) : author, 0,
            pages.stream().map(page -> net.minecraft.server.network.Filterable.passThrough((net.minecraft.network.chat.Component)
                net.minecraft.network.chat.Component.literal(page))).toList(), true));
        give(player, book);
        journal.update(villager.getUUID(), player.getUUID(), VillagerJournal.Summary::withKeepsake);
        memory.observe(villager, player, "keepsake", "I wrote down our story for " + player.getName().getString() + " and gave it to them", 4, 0);
        return true;
    }
    private String villagerLine(Villager villager) {
        VillageTracker.Village village = villages.villageOf(villager);
        return villager.getName().getString() + ", the " + VillagerMemoryService.profession(villager)
            + (village == null ? "" : " of " + village.name());
    }
    private static String position(Villager villager) {
        return villager.blockPosition().getX() + ", " + villager.blockPosition().getY() + ", " + villager.blockPosition().getZ();
    }
    private static String stackText(JsonObject item) {
        return item == null || item.get("count").getAsInt() <= 0 ? "" : item.get("count").getAsInt() + " × " + item.get("name").getAsString();
    }
    /** Tracker card for a gift: what was given and how it landed. */
    private void giftStatus(Session s, String status, ItemStack gift, String reaction) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "trade_status");
        message.addProperty("sessionId", s.id);
        message.addProperty("entityId", s.villager.getId());
        message.addProperty("villager", s.villager.getName().getString());
        message.addProperty("status", status);
        message.addProperty("message", "");
        message.add("costA", item(gift));
        message.add("costB", item(ItemStack.EMPTY));
        message.addProperty("reaction", reaction);
        send(s.player, message);
    }
    private CompletableFuture<JsonObject> offerErrand(MinecraftServer server, Session s, JsonObject arguments) {
        if (Negotiation.hostile(standing(s))) return CompletableFuture.completedFuture(refused());
        Errand open = errand(s.player);
        // The model sometimes reaches for a new errand when the player is delivering one to this very villager.
        if (open != null && open.targetId().equals(s.villager.getUUID().toString())) {
            if (open.kind().equals("item")) return CompletableFuture.completedFuture(deliverErrand(s, ""));
            return CompletableFuture.completedFuture(outcome("errand_for_you", "The player has come to pass something on to you from "
                + "a neighbour. Ask what they were asked to tell you, then call deliver_errand with their words. Do not offer an errand."));
        }
        if (open != null) return CompletableFuture.completedFuture(outcome("errand_open",
            "They are still running an errand for " + open.giverName() + " (to " + open.targetName() + "). Do not offer another. "
            + "Remind them in one short, natural sentence spoken to them, as if you just remembered, such as \"Aren't you still running "
            + open.giverName() + "'s errand to " + open.targetName() + "? Finish that first.\" Do not mention rules or limits."));
        String kind = arguments.get("kind").getAsString();
        String message = arguments.has("message") ? arguments.get("message").getAsString().trim() : "";
        if (kind.equals("message") && (message.isEmpty() || message.length() > Errands.MESSAGE_LIMIT))
            return CompletableFuture.completedFuture(outcome("invalid", "A message errand needs the short message to pass on."));
        Villager giver = s.villager;
        // Someone else in the same village, preferably a short walk away.
        VillageTracker.Village village = villages.villageOf(giver);
        var centre = village == null ? giver.position() : new net.minecraft.world.phys.Vec3(village.x(), village.y(), village.z());
        double range = village == null ? 48 : 64;
        List<Villager> candidates = new ArrayList<>(giver.level().getEntitiesOfClass(Villager.class,
            new net.minecraft.world.phys.AABB(centre, centre).inflate(range, 32, range),
            v -> v != giver && v.isAlive() && !v.isBaby() && v.position().distanceToSqr(centre) <= range * range));
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(outcome("no_target",
            "There is nobody else nearby to send them to. Say so; no errand was set."));
        Collections.shuffle(candidates);
        candidates.sort(java.util.Comparator.comparing(v -> v.distanceToSqr(giver) < 64));
        Villager target = candidates.getFirst();
        ItemStack parcel = ItemStack.EMPTY;
        if (kind.equals("item")) {
            for (var offer : giver.getOffers()) {
                if (!offer.isOutOfStock()) { parcel = offer.getResult().copyWithCount(1); break; }
            }
            if (parcel.isEmpty()) parcel = new ItemStack(net.minecraft.world.item.Items.BREAD);
            if (s.player.getInventory().getFreeSlot() < 0) return CompletableFuture.completedFuture(outcome("inventory_full",
                "The player has no room to carry a package. Ask them to make space first."));
        }
        ItemStack carried = parcel;
        // Naming the target may touch disk; the errand itself is built back on the server thread.
        return personalities.prepare(target).thenCompose(prepared -> CompletableFuture.supplyAsync(() -> {
                personalities.applyName(target, prepared);
                if (!carried.isEmpty() && !s.player.getInventory().add(carried.copy()))
                    return outcome("inventory_full", "The player has no room to carry a package. Ask them to make space first.");
                Errand errand = new Errand(UUID.randomUUID().toString(), kind, giver.getUUID().toString(), giver.getName().getString(),
                    target.getUUID().toString(), target.getName().getString(), VillagerMemoryService.profession(target),
                    kind.equals("message") ? message : "", carried.isEmpty() ? "" : id(carried),
                    carried.isEmpty() ? "" : carried.getHoverName().getString(), System.currentTimeMillis());
                errands.put(s.player.getUUID().toString(), errand);
                saveErrands();
                memory.observe(giver, s.player, "errand_given", "I sent " + s.player.getName().getString() + " to " + errand.describe(), 3, 0);
                target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.GLOWING, 20 * 120));
                s.player.inventoryMenu.broadcastChanges();
                String where = where(s.player, target);
                String sender = giver.getName().getString(), recipient = target.getName().getString();
                giveNote(s.player, "Errand: " + sender + " → " + recipient, List.of(
                    "ERRAND\n\nFrom: " + villagerLine(giver) + "\nTo: " + villagerLine(target) + "\n\n"
                        + (kind.equals("message") ? "Pass on this message:\n\"" + message + "\""
                            : "Deliver: 1 × " + carried.getHoverName().getString() + "\n(" + sender + " gave it to you)"),
                    "Where: " + recipient + " was " + where + " from you, at " + position(target) + ", when " + sender + " asked.\n\n"
                        + "Reward: " + sender + " +" + Errands.GIVER_REWARD + ", " + recipient + " +" + Errands.TARGET_REWARD + " reputation.\n\n"
                        + "Lapses after two hours."));
                errandStatus(s, "errand", errand.kind().equals("message") ? "\"" + errand.message() + "\"" : "1 × " + errand.itemName(),
                    errand.targetName() + " · " + errand.targetProfession(), where);
                org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Errand {} for {}: {} -> {}", kind, s.player.getName().getString(),
                    errand.giverName(), errand.targetName());
                JsonObject result = outcome("errand_offered", "In character and briefly, send the player on this errand: "
                    + errand.describe() + ". " + errand.targetName() + " the " + errand.targetProfession() + " is " + where
                    + (carried.isEmpty() ? "" : "; you have handed them the " + errand.itemName()) + ". Promise to be grateful when it is done.");
                result.addProperty("target", errand.targetName());
                result.addProperty("direction", where);
                return result;
            }, server));
    }
    private static String where(ServerPlayer player, Villager target) {
        double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
        return Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks " + Errands.compass(dx, dz);
    }
    /** The recipient knows nothing in advance: it learns the message only from the player's own words. */
    private JsonObject deliverErrand(Session s, String conveyed) {
        Errand errand = errand(s.player);
        if (errand == null || !errand.targetId().equals(s.villager.getUUID().toString()))
            return outcome("no_errand", "Nobody sent the player to you with anything. Say you are not sure what they mean; do not pretend otherwise.");
        if (errand.kind().equals("message") && !TextTerms.conveys(conveyed, errand.message(), errand.giverName()))
            return outcome("errand_mismatch", "That is not a message you can make sense of yet. Say you are not sure what they mean and ask "
                + "them to tell you what they were asked to pass on. Do not guess the message.");
        if (errand.kind().equals("item")) {
            var identifier = net.minecraft.resources.Identifier.tryParse(errand.item());
            var item = identifier == null ? null : BuiltInRegistries.ITEM.getValue(identifier);
            var match = (java.util.function.Predicate<ItemStack>) stack -> item != null && stack.getItem() == item;
            if (item == null || s.player.getInventory().clearOrCountMatchingItems(match, 1, s.player.inventoryMenu.getCraftSlots()) < 1)
                return outcome("errand_missing", "The player does not have the " + errand.itemName() + " from " + errand.giverName()
                    + " with them. Nothing was delivered; tell them kindly.");
            s.player.inventoryMenu.broadcastChanges();
        }
        VillagerJournal journal = memory.journal();
        if (journal != null) {
            long day = VillagerMemoryService.gameDay(s.villager), at = System.currentTimeMillis();
            String runner = s.player.getName().getString();
            journal.record(UUID.fromString(errand.giverId()), s.player.getUUID(), "errand_done",
                runner + " did my errand: " + errand.describe(), day, at, 4, false, 0);
            journal.record(s.villager.getUUID(), s.player.getUUID(), "errand_received", runner + " brought me "
                + (errand.kind().equals("message") ? "word from " + errand.giverName() + ": \"" + conveyed.trim() + "\""
                    : "the " + errand.itemName() + " from " + errand.giverName()), day, at, 4, false, 0);
        }
        String key = s.player.getUUID().toString();
        errands.remove(key);
        List<String> history = new ArrayList<>(errandHistory.getOrDefault(key + ":" + errand.giverId(), List.of()));
        history.add(errand.describe());
        while (history.size() > 3) history.removeFirst();
        errandHistory.put(key + ":" + errand.giverId(), history);
        saveErrands();
        long now = System.currentTimeMillis();
        int targetGain = reward(s.player, s.villager.getUUID().toString(), Errands.TARGET_REWARD, now);
        int giverGain = reward(s.player, errand.giverId(), Errands.GIVER_REWARD, now);
        saveTradeReputations();
        s.villager.removeEffect(net.minecraft.world.effect.MobEffects.GLOWING);
        moodParticles(s.player, s.villager, true);
        s.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Errand complete: " + errand.targetName()
            + String.format(" (%+d)", targetGain) + ", " + errand.giverName() + String.format(" (%+d)", giverGain))
            .withStyle(net.minecraft.ChatFormatting.GREEN), true);
        errandStatus(s, "errand_done", errand.kind().equals("message") ? "\"" + errand.message() + "\"" : "1 × " + errand.itemName(),
            errand.targetName() + String.format(" %+d", targetGain) + " · " + errand.giverName() + String.format(" %+d", giverGain),
            "Errand complete");
        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Errand delivered by {}: {} -> {}", s.player.getName().getString(),
            errand.giverName(), errand.targetName());
        // Only the player's own words: never the original wording the recipient could not have known.
        return outcome("errand_complete", errand.giverName() + " sent the player. " + (errand.kind().equals("message")
            ? "They told you: \"" + conveyed.trim() + "\""
            : "They brought you the " + errand.itemName() + (conveyed.isBlank() ? "" : " and said: \"" + conveyed.trim() + "\""))
            + ". React to what they said in character, using only that, and thank them warmly.");
    }
    private int reward(ServerPlayer player, String villagerId, int gain, long now) {
        String key = player.getUUID() + ":" + villagerId;
        TradeReputation previous = relationships.getOrDefault(key, TradeReputation.neutral());
        int next = Errands.reward(previous.score(), gain);
        relationships.put(key, new TradeReputation(next, now, previous.lastHit()));
        return next - previous.score();
    }
    /** Both ends of an errand know about it; the giver also remembers errands the player finished. */
    private JsonObject errandContext(ServerPlayer player, Villager villager) {
        JsonObject context = new JsonObject();
        Errand errand = errand(player);
        String id = villager.getUUID().toString();
        if (errand != null && errand.giverId().equals(id)) context.addProperty("youGaveThePlayer", errand.describe() + " (not yet done)");
        // Anyone else only knows the player is busy for a neighbour, not what the errand is (the recipient learns it on delivery).
        else if (errand != null && !errand.targetId().equals(id))
            context.addProperty("playerIsBusyWith", "an errand for " + errand.giverName() + " to " + errand.targetName());
        List<String> done = errandHistory.get(player.getUUID() + ":" + id);
        if (done != null && !done.isEmpty()) {
            JsonArray finished = new JsonArray();
            done.forEach(finished::add);
            context.add("playerCompletedForYou", finished);
        }
        return context;
    }
    private void errandStatus(Session s, String status, String carrying, String recipient, String footer) {
        if (s.id == null) return; // No conversation open: the action bar says it instead.
        JsonObject message = new JsonObject();
        message.addProperty("type", "trade_status");
        message.addProperty("sessionId", s.id);
        message.addProperty("entityId", s.villager.getId());
        message.addProperty("villager", s.villager.getName().getString());
        message.addProperty("status", status);
        message.addProperty("message", "");
        JsonObject card = new JsonObject();
        card.addProperty("heading", status.equals("errand_done") ? "ERRAND COMPLETE" : "ERRAND");
        card.addProperty("leftLabel", "CARRY");
        card.addProperty("left", carrying);
        card.addProperty("rightLabel", status.equals("errand_done") ? "REPUTATION" : "DELIVER TO");
        card.addProperty("right", recipient);
        card.addProperty("footer", footer);
        message.add("card", card);
        send(s.player, message);
    }
    /** The villager's own tastes and its history with this player, so it can talk about gifts and remember refusals. */
    private JsonObject giftContext(ServerPlayer player, Villager villager) {
        var preferences = GiftCatalogue.preferences(villager.getUUID(), VillagerMemoryService.profession(villager));
        GiftLedger ledger = gifts.getOrDefault(player.getUUID() + ":" + villager.getUUID(), new GiftLedger(List.of(), 0));
        JsonObject context = new JsonObject();
        context.add("youLove", itemNames(preferences.loves()));
        context.add("youDislike", itemNames(preferences.dislikes()));
        context.add("refusedFromPlayer", itemNames(ledger.refused()));
        // Gift history from the journal: what the villager saw happen, newest last.
        List<String> history = new ArrayList<>();
        VillagerJournal journal = memory.journal();
        if (journal != null) for (var episode : journal.all(villager.getUUID(), player.getUUID(), System.currentTimeMillis()))
            if (episode.type().equals("gift")) history.add(episode.text());
        JsonArray received = new JsonArray();
        history.subList(Math.max(0, history.size() - 6), history.size()).forEach(received::add);
        context.add("receivedFromPlayer", received);
        return context;
    }
    private static JsonArray itemNames(List<String> ids) {
        JsonArray names = new JsonArray();
        for (String key : ids) {
            var identifier = net.minecraft.resources.Identifier.tryParse(key);
            names.add(identifier != null && BuiltInRegistries.ITEM.containsKey(identifier)
                ? BuiltInRegistries.ITEM.getValue(identifier).getDefaultInstance().getHoverName().getString() : key);
        }
        return names;
    }
    private final Map<String, Long> shunnedUntil = new HashMap<>();
    private static final long SHUN_MS = 30_000;
    /** A villager who dislikes the player may refuse to talk and walk off; the refusal stands for a while. */
    private boolean shuns(ServerPlayer player, Villager villager, long now) {
        String key = player.getUUID() + ":" + villager.getUUID();
        if (now < shunnedUntil.getOrDefault(key, 0L)) return true;
        shunnedUntil.values().removeIf(until -> until < now);
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= Errands.shunChance(standing(new Session(player, villager)))) return false;
        shunnedUntil.put(key, now + SHUN_MS);
        memory.observe(villager, player, "shunned", "I refused to talk to " + player.getName().getString(), 2, 10 * 60_000L);
        walkAway(player, villager);
        return true;
    }
    private void walkAway(ServerPlayer player, Villager villager) {
        stepAway(player, villager);
        villager.playSound(SoundEvents.VILLAGER_NO);
        moodParticles(player, villager, false);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(villager.getName().getString() + " turns away from you")
            .withStyle(net.minecraft.ChatFormatting.GOLD), true);
        org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("{} shunned {} (standing {})", villager.getName().getString(),
            player.getName().getString(), standing(new Session(player, villager)));
    }
    /** Turn away from the player and walk about eight blocks off. */
    private static void stepAway(ServerPlayer player, Villager villager) {
        var away = villager.position().subtract(player.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 0.01) away = new net.minecraft.world.phys.Vec3(1, 0, 0);
        away = away.normalize().scale(8);
        float yaw = (float) (Math.toDegrees(Math.atan2(away.z, away.x)) - 90);
        villager.setYRot(yaw);
        villager.setYBodyRot(yaw);
        villager.setYHeadRot(yaw);
        // Villagers walk through their brain; NoAI villagers (like demo ones) can only turn their back.
        if (!villager.isNoAi()) villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
            new net.minecraft.world.entity.ai.memory.WalkTarget(villager.position().add(away), 0.6f, 1));
    }
    /** Quest options for a lowball offer: only from villagers who are not annoyed, one open quest at a time. */
    private QuestOffer questOffer(Session s, MerchantOffer offer, Negotiation.Band limits, int index, Integer quantity, Integer payment) {
        if (payment == null || standing(s) <= -2 || quests.containsKey(relationshipKey(s))) return null;
        Negotiation.Bundle deal = exact(offer, quantity, payment);
        Negotiation.Bundle list = deal == null ? null : bundle(offer, limits, deal.quantity(), null, false);
        if (list == null || list.quantity() != deal.quantity() || deal.payment() >= list.payment()) return null;
        List<QuestCatalogue.Item> options = QuestCatalogue.options(VillagerMemoryService.profession(s.villager), deal.payment() * 100 / list.payment())
            .stream().filter(item -> questStack(s, item.key()) != null).toList();
        return options.isEmpty() ? null : new QuestOffer(index, deal, options);
    }
    private Negotiation.Bundle exact(MerchantOffer offer, Integer quantity, int payment) {
        return Negotiation.exact(offer.getCostA().getCount(), offer.getCostB().getCount(), offer.getResult().getCount(),
            offer.getMaxUses() - offer.getUses(), offer.getCostA().getMaxStackSize(),
            offer.getCostB().isEmpty() ? 64 : offer.getCostB().getMaxStackSize(), offer.getResult().getMaxStackSize(), quantity, payment);
    }
    /** The template a delivery must match; the ominous banner is a white banner identified by its components. */
    private ItemStack questStack(Session s, String key) {
        try {
            if (key.equals(QuestCatalogue.OMINOUS_BANNER))
                return net.minecraft.world.entity.raid.Raid.getOminousBannerInstance(
                    s.player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN));
            var id = net.minecraft.resources.Identifier.parse(key);
            return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id).getDefaultInstance() : null;
        } catch (RuntimeException e) { return null; }
    }
    private java.util.function.Predicate<ItemStack> delivers(String key, ItemStack template) {
        return key.equals(QuestCatalogue.OMINOUS_BANNER)
            ? stack -> ItemStack.isSameItemSameComponents(stack, template)
            : stack -> stack.getItem() == template.getItem();
    }
    private static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private JsonObject offerQuest(Session s, String key) {
        if (Negotiation.hostile(standing(s))) return refused();
        QuestOffer pending = s.questOffer;
        if (pending == null) return outcome("unavailable", "There is no haggled price to set a quest for. Only set a quest after propose_trade returns questOptions.");
        QuestCatalogue.Item choice = pending.options().stream().filter(option -> option.key().equals(key)).findFirst().orElse(null);
        if (choice == null) return outcome("invalid", "Choose an item from the latest questOptions.");
        if (pending.index() >= s.villager.getOffers().size()) return outcome("unavailable", "That offer no longer exists.");
        var offer = s.villager.getOffers().get(pending.index());
        Negotiation.Bundle deal = pending.deal();
        JsonObject terms = new JsonObject();
        terms.add("costA", item(offer.getCostA().copyWithCount(deal.payment())));
        terms.add("costB", item(offer.getCostB().isEmpty() ? ItemStack.EMPTY : offer.getCostB().copyWithCount(deal.secondary())));
        terms.add("receive", item(offer.getResult().copyWithCount(deal.quantity())));
        Quest quest = new Quest(UUID.randomUUID().toString(), pending.index(), id(offer.getCostA()), offer.getCostA().getCount(),
            id(offer.getCostB()), offer.getCostB().getCount(), id(offer.getResult()), offer.getResult().getCount(),
            offer.getResult().getHoverName().getString(), deal.payment(), deal.secondary(), deal.quantity(), deal.stockUses(),
            terms, choice.key(), choice.count(), choice.name(), false);
        quests.put(relationshipKey(s), quest);
        saveQuests();
        s.questOffer = null;
        s.proposal = null; // The quest replaces the counteroffer.
        JsonObject result = outcome("quest_offered", "In character and in one or two short sentences, set this quest: the player must bring you "
            + quest.ask() + ", and then you will trade at these terms. Do not ask Confirm trade?; the deal opens only after delivery, "
            + "when the player tells you they have the items.");
        result.addProperty("quest", quest.ask());
        result.add("deal", terms);
        questStatus(s, quest, "quest", "");
        memory.observe(s.villager, s.player, "quest_set", "I asked " + s.player.getName().getString() + " to bring me " + quest.ask()
            + " before I would trade at their price", 4, 0);
        String giver = s.villager.getName().getString();
        String pay = stackText(terms.getAsJsonObject("costA")), extra = stackText(terms.getAsJsonObject("costB"));
        giveNote(s.player, "Quest: " + giver, List.of(
            "QUEST\n\nFrom: " + villagerLine(s.villager) + "\n\nBring: " + quest.ask() + "\n\nThen " + giver + " will trade:\n"
                + pay + (extra.isEmpty() ? "" : " + " + extra) + "\n→ " + stackText(terms.getAsJsonObject("receive")),
            "Tell " + giver + " when you have it. The deal opens once, at the price you asked.\n\n"
                + giver + " was at " + position(s.villager) + " when the quest was set."));
        return result;
    }
    private JsonObject completeQuest(Session s, int turn) {
        if (Negotiation.hostile(standing(s))) return refused();
        String key = relationshipKey(s);
        Quest quest = quests.get(key);
        if (quest == null) return outcome("no_quest", "You have not set this player a quest. Say so; do not pretend otherwise.");
        boolean justDelivered = false;
        if (!quest.delivered()) {
            ItemStack template = questStack(s, quest.item());
            if (template == null) return outcome("unavailable", "That quest item no longer exists. Apologise and set a new quest another time.");
            var inventory = s.player.getInventory();
            var match = delivers(quest.item(), template);
            int have = inventory.clearOrCountMatchingItems(match, 0, s.player.inventoryMenu.getCraftSlots());
            if (have < quest.count()) {
                JsonObject result = outcome("quest_incomplete", "The player has " + have + " of the " + quest.ask()
                    + " you asked for. Nothing was handed over; tell them what is still missing.");
                result.addProperty("quest", quest.ask());
                questStatus(s, quest, "quest_incomplete", "Still need " + (quest.count() - have) + " × " + quest.itemName());
                return result;
            }
            inventory.clearOrCountMatchingItems(match, quest.count(), s.player.inventoryMenu.getCraftSlots());
            s.player.inventoryMenu.broadcastChanges();
            quest = quest.deliver();
            quests.put(key, quest);
            saveQuests();
            moodParticles(s.player, s.villager, true);
            memory.observe(s.villager, s.player, "quest_done", s.player.getName().getString() + " brought me the " + quest.ask()
                + " I asked for, as promised", 5, 0);
            JsonObject deal = quest.deal();
            memory.observe(s.villager, s.player, "deal_owed", "I owe " + s.player.getName().getString() + " a deal for that: "
                + stackText(deal.getAsJsonObject("receive")) + " for " + stackText(deal.getAsJsonObject("costA"))
                + (stackText(deal.getAsJsonObject("costB")).isEmpty() ? "" : " + " + stackText(deal.getAsJsonObject("costB")))
                + ", theirs until they use it", 5, 0);
            justDelivered = true;
        }
        String thanks = justDelivered ? "The player just handed over " + quest.ask() + " for your quest; thank them warmly. "
            : "The player already delivered your quest items. ";
        if (!availableForVoiceTrade(s)) return outcome("unavailable", thanks + "The deal is saved, but the player must step closer and close any inventory first.");
        if (quest.offerIndex() >= s.villager.getOffers().size()) return outcome("quest_deal_unavailable", thanks + "That trade no longer exists; apologise.");
        var offer = s.villager.getOffers().get(quest.offerIndex());
        // Same trade: what it costs and what it gives. Cost counts are not compared: vanilla demand moves them after every
        // restock, and an earned deal fixes its own price anyway.
        if (!id(offer.getCostA()).equals(quest.baseA()) || !id(offer.getCostB()).equals(quest.baseB())
            || !id(offer.getResult()).equals(quest.result()) || offer.getResult().getCount() != quest.resultCount()
            || !offer.getResult().getHoverName().getString().equals(quest.resultName()))
            return outcome("quest_deal_unavailable", thanks + "That trade has changed since you set the quest; apologise. The deal is saved but cannot be used.");
        if (offer.isOutOfStock() || offer.getMaxUses() - offer.getUses() < quest.stockUses())
            return outcome("quest_deal_unavailable", thanks + "You are out of stock for it right now; the deal is saved until you restock.");
        Proposal p = new Proposal(QuoteConfirmation.refresh(null, false, turn, System.currentTimeMillis()), quest.offerIndex(),
            offer.getCostA().copyWithCount(quest.payment()),
            offer.getCostB().isEmpty() ? ItemStack.EMPTY : offer.getCostB().copyWithCount(quest.secondary()),
            offer.getResult().copyWithCount(quest.quantity()), offer.getCostA().copy(), offer.getCostB().copy(), offer.getResult().copy(),
            quest.stockUses(), quest.id());
        s.proposal = p;
        s.questOffer = null;
        JsonObject result = quote(p, "awaiting_confirmation", thanks + "Now honour your word: say these exact terms, then ask Confirm trade? "
            + "Wait for the player's spoken acceptance before confirm_trade.");
        tradeStatus(s, result);
        return result;
    }
    /** HUD update for a quest: the deal it unlocks and what to bring. */
    private void questStatus(Session s, Quest quest, String status, String detail) {
        if (s.id == null) return;
        JsonObject message = new JsonObject();
        message.addProperty("type", "trade_status");
        message.addProperty("sessionId", s.id);
        message.addProperty("entityId", s.villager.getId());
        message.addProperty("villager", s.villager.getName().getString());
        message.addProperty("status", status);
        message.addProperty("message", "");
        message.addProperty("quest", detail.isEmpty() ? (quest.delivered() ? "Items delivered · ask for your deal" : "Bring " + quest.ask()) : detail);
        message.add("costA", quest.deal().get("costA")); message.add("costB", quest.deal().get("costB")); message.add("receive", quest.deal().get("receive"));
        send(s.player, message);
    }
    private JsonObject refused() {
        return outcome("refused", "You refuse to trade with this player after how they treated you. Say so angrily in one short sentence; "
            + "they must make amends before you trade again. Do not quote prices.");
    }
    private JsonObject proposeTrade(Session s, JsonObject arguments, int turn) {
        if (turn == s.lastPurchaseTurn && s.lastReceipt != null) return s.lastReceipt;
        if (Negotiation.hostile(standing(s))) { s.proposal = null; return refused(); }
        if (!canTrade(s.villager)) { s.proposal = null; return noTrade(s); }
        int index = arguments.get("offerIndex").getAsInt();
        // A quest the player already fulfilled fixes the price for that trade: re-quoting must never lose the earned deal.
        Quest earned = quests.get(relationshipKey(s));
        if (earned != null && earned.delivered() && earned.offerIndex() == index) return completeQuest(s, turn);
        Proposal previous = s.proposal;
        s.proposal = null;
        if (!availableForVoiceTrade(s)) return outcome("unavailable", "Move within eight blocks, close any inventory, and wait until the villager is free.");
        if (index < 0 || index >= s.villager.getOffers().size()) return outcome("unavailable", "That offer does not exist.");
        s.villager.setTradingPlayer(s.player);
        try {
            // Base prices only: vanilla gossip would apply reputation twice.
            var offer = s.villager.getOffers().get(index);
            if (offer.isOutOfStock()) return outcome("unavailable", "That offer is sold out.");
            Integer quantity = arguments.has("quantity") ? arguments.get("quantity").getAsInt() : null;
            Integer payment = arguments.has("payment") ? arguments.get("payment").getAsInt() : null;
            boolean discount = arguments.has("discount") && arguments.get("discount").getAsBoolean();
            Negotiation.Band limits = band(s);
            Negotiation.Bundle bundle;
            boolean counteroffer = false;
            try {
                bundle = bundle(offer, limits, quantity, payment, discount);
                // An offer below the villager's floor gets its best price instead of a refusal.
                if (bundle == null && payment != null) {
                    bundle = bundle(offer, limits, quantity, null, true);
                    counteroffer = bundle != null;
                }
            } catch (IllegalArgumentException e) { return outcome("invalid", "Use whole quantities from one to sixty-four."); }
            if (bundle == null) return outcome("unavailable", "No whole-item bundle fits this budget, stack limits and remaining stock. Try a different offer.");
            ItemStack costA = offer.getCostA().copyWithCount(bundle.payment());
            ItemStack costB = offer.getCostB().isEmpty() ? ItemStack.EMPTY : offer.getCostB().copyWithCount(bundle.secondary());
            ItemStack receive = offer.getResult().copyWithCount(bundle.quantity());
            boolean sameTerms = previous != null && previous.questId() == null && previous.index() == index && previous.stockUses() == bundle.stockUses()
                && ItemStack.matches(previous.costA(), costA) && ItemStack.matches(previous.costB(), costB)
                && ItemStack.matches(previous.result(), receive) && ItemStack.matches(previous.baseA(), offer.getCostA())
                && ItemStack.matches(previous.baseB(), offer.getCostB()) && ItemStack.matches(previous.baseResult(), offer.getResult());
            boolean stale = previous != null && previous.approval().stale(turn);
            QuoteConfirmation approval = QuoteConfirmation.refresh(previous == null ? null : previous.approval(), sameTerms, turn, System.currentTimeMillis());
            Proposal p = new Proposal(approval, index, costA, costB, receive,
                offer.getCostA().copy(), offer.getCostB().copy(), offer.getResult().copy(), bundle.stockUses(), null);
            s.proposal = p;
            s.questOffer = counteroffer ? questOffer(s, offer, limits, index, quantity, payment) : null;
            boolean retained = previous != null && approval.gate() == previous.confirmation();
            String haggle = counteroffer
                ? "The player's offer is below the lowest you will accept. Turn it down kindly and counter with these terms as your best offer. "
                : bundle.discounted() ? "You accept a better price than usual because you like this player; sound a little generous. "
                : discount || (payment != null && limits.floor() >= limits.rate()) ? "This is already your best price for this player; hold firm politely. " : "";
            JsonObject result = quote(p, "awaiting_confirmation", haggle + (retained && !stale
                ? "These current terms match the quote just presented. If the player's latest utterance accepts it, call confirm_trade with this proposalId now. Otherwise say the exact costs and quantity, then ask Confirm trade?"
                : RESTATE_QUOTE));
            if (s.questOffer != null) {
                Negotiation.Bundle deal = s.questOffer.deal();
                JsonArray options = new JsonArray();
                for (QuestCatalogue.Item item : s.questOffer.options()) {
                    JsonObject option = new JsonObject();
                    option.addProperty("item", item.key()); option.addProperty("count", item.count()); option.addProperty("name", item.name());
                    options.add(option);
                }
                result.add("questOptions", options);
                result.addProperty("message", result.get("message").getAsString() + " Instead of countering, you may set a quest: the player earns their own price ("
                    + deal.payment() + " " + offer.getCostA().getHoverName().getString() + " for " + deal.quantity() + " "
                    + offer.getResult().getHoverName().getString() + ") by first bringing you one of questOptions. "
                    + "To do that, pick the option that suits you and call offer_quest with its item; otherwise just counter.");
            }
            result.addProperty("discounted", bundle.discounted());
            if (limits.hero() > 0) result.addProperty("heroOfTheVillageDiscountPercent", limits.hero());
            result.addProperty("relationship", limits.name());
            result.addProperty("pricePercent", limits.rate());
            return result;
        } finally { s.villager.setTradingPlayer(null); }
    }
    private JsonObject confirmTrade(Session s, String id, int turn) {
        if (s.purchases.containsKey(id)) return s.purchases.get(id);
        if (Negotiation.hostile(standing(s))) { s.proposal = null; return refused(); }
        Proposal p = s.proposal;
        if (p == null || !p.id().equals(id)) return outcome("unavailable", "No matching proposal. Ask for a new quote.");
        if (System.currentTimeMillis() >= p.expires() || !availableForVoiceTrade(s)) {
            s.proposal = null; return outcome("unavailable", "Proposal expired or villager unavailable. Nothing was purchased.");
        }
        VoiceConfirmation.Result confirmation = p.approval().advance(turn, System.currentTimeMillis());
        if (confirmation == VoiceConfirmation.Result.WAIT) return outcome("wait_for_player", "Wait for a new explicit spoken confirmation. Do not call again until the player answers.");
        if (confirmation == VoiceConfirmation.Result.STALE) {
            p.confirmation().represent(turn);
            return quote(p, "restate_quote", "Nothing was purchased. " + RESTATE_QUOTE);
        }
        if (confirmation == VoiceConfirmation.Result.EXPIRED || confirmation == VoiceConfirmation.Result.CONSUMED) {
            s.proposal = null; return outcome("unavailable", "Ask for a fresh proposal. Nothing was purchased.");
        }
        // Consume before mutation; retries and duplicate tool calls cannot purchase twice.
        s.proposal = null;
        s.villager.setTradingPlayer(s.player);
        MerchantMenu menu = null;
        MerchantOffer original = null;
        boolean retryableFailure = false;
        try {
            if (p.index() >= s.villager.getOffers().size()) return outcome("unavailable", "Offer no longer exists.");
            var offer = s.villager.getOffers().get(p.index());
            if (offer.isOutOfStock() || offer.getMaxUses() - offer.getUses() < p.stockUses() || !ItemStack.matches(p.baseA(), offer.getCostA())
                || !ItemStack.matches(p.baseB(), offer.getCostB()) || !ItemStack.matches(p.baseResult(), offer.getResult()))
                return outcome("changed", "The price or offer changed. Nothing was purchased. Get a fresh quote and a new spoken confirmation.");
            // Temporarily offer this exact server-approved bundle through the native transaction path.
            // Restore the real offer in finally; its stock is charged by default-bundle equivalents.
            original = offer;
            ItemCost primary = new ItemCost(offer.getItemCostA().item(), p.costA().getCount(), offer.getItemCostA().components());
            Optional<ItemCost> secondary = offer.getItemCostB().map(cost -> new ItemCost(cost.item(), p.costB().getCount(), cost.components()));
            offer = new MerchantOffer(primary, secondary, p.result().copy(), 1, original.getXp() * p.stockUses(), 0f);
            s.villager.getOffers().set(p.index(), offer);
            // Server-only native menu: no open-screen packet, client menu, or launcher action.
            menu = new MerchantMenu(-1, s.player.getInventory(), s.villager);
            menu.setSelectionHint(p.index()); menu.tryMoveItems(p.index());
            if (!offer.satisfiedBy(menu.getSlot(0).getItem(), menu.getSlot(1).getItem())
                || !ItemStack.matches(menu.getSlot(2).getItem(), p.result())) {
                retryableFailure = true;
                return quote(p, "missing_items", "You do not have the required items. Nothing was purchased. "
                    + "Keep this proposalId. Repeat these exact costs and received quantity, then ask the player to get the items and say yes or another clear acceptance to retry. Do not confirm again in this turn.");
            }
            int space = 0;
            for (int i = 3; i < 39; i++) {
                var slot = menu.getSlot(i); ItemStack stack = slot.getItem();
                if (stack.isEmpty()) space += Math.min(slot.getMaxStackSize(p.result()), p.result().getMaxStackSize());
                else if (ItemStack.isSameItemSameComponents(stack, p.result()))
                    space += Math.max(0, Math.min(slot.getMaxStackSize(p.result()), p.result().getMaxStackSize()) - stack.getCount());
            }
            if (space < p.result().getCount()) {
                retryableFailure = true;
                return quote(p, "inventory_full", "Your inventory is full. Nothing was purchased. "
                    + "Keep this proposalId. Repeat these exact costs and received quantity, then ask the player to make room and say yes or another clear acceptance to retry. Do not confirm again in this turn.");
            }
            boolean purchased = !menu.quickMoveStack(s.player, 2).isEmpty();
            if (purchased) {
                for (int use = 0; use < p.stockUses(); use++) original.increaseUses();
                JsonObject feedback = new JsonObject();
                feedback.addProperty("type", "trade_complete");
                feedback.addProperty("tradeId", p.id());
                feedback.addProperty("entityId", s.villager.getId());
                feedback.add("costA", item(p.costA()));
                feedback.add("costB", item(p.costB()));
                feedback.add("receive", item(p.result()));
                send(s.player, feedback);
                moodParticles(s.player, s.villager, true);
            }
            JsonObject receipt = outcome(purchased ? "purchased" : "unavailable", purchased
                ? p.result().getCount() + " × " + p.result().getHoverName().getString() + " added to your inventory. Trade is FINISHED. Briefly confirm success; do not ask for confirmation or propose it again."
                : "Nothing was purchased. Ask for a fresh proposal.");
            if (purchased) {
                String pay = p.costA().getCount() + " × " + p.costA().getHoverName().getString()
                    + (p.costB().isEmpty() ? "" : " and " + p.costB().getCount() + " × " + p.costB().getHoverName().getString());
                String got = p.result().getCount() + " × " + p.result().getHoverName().getString();
                memory.observe(s.villager, s.player, "trade", s.player.getName().getString() + " bought " + got + " from me for " + pay
                    + (p.questId() != null ? ", the deal I promised for their quest" : ""), p.questId() != null ? 4 : 3, 0);
                memory.witnesses(s.villager, s.player, "I saw " + s.player.getName().getString() + " trade with " + s.villager.getName().getString(), 0);
            }
            if (purchased && p.questId() != null) {
                Quest quest = quests.get(relationshipKey(s));
                if (quest != null && quest.id().equals(p.questId())) { quests.remove(relationshipKey(s)); saveQuests(); }
            }
            if (purchased) {
                s.purchases.put(p.id(), receipt);
                s.lastPurchaseTurn = turn;
                s.lastReceipt = receipt;
            }
            return receipt;
        } finally {
            // Native cleanup returns all unused payment items, including on errors.
            try {
                if (menu != null) menu.removed(s.player);
                else s.villager.setTradingPlayer(null);
            } finally {
                if (original != null) {
                    original.resetSpecialPriceDiff();
                    s.villager.getOffers().set(p.index(), original);
                }
                s.player.inventoryMenu.broadcastChanges();
            }
            // Only known failures before quickMoveStack may retry, after returning payment items.
            // Changed offers, ambiguous transaction failures and completed purchases stay consumed.
            if (retryableFailure) {
                p.confirmation().retryAfterFailure(turn);
                s.proposal = p;
            }
        }
    }
    private Negotiation.Bundle bundle(MerchantOffer offer, Negotiation.Band rate, Integer quantity, Integer payment, boolean discount) {
        return Negotiation.quote(offer.getCostA().getCount(), offer.getCostB().getCount(), offer.getResult().getCount(),
            offer.getMaxUses() - offer.getUses(), offer.getCostA().getMaxStackSize(),
            offer.getCostB().isEmpty() ? 64 : offer.getCostB().getMaxStackSize(), offer.getResult().getMaxStackSize(),
            rate, quantity, payment, discount);
    }
    private JsonArray offers(Session s) {
        JsonArray result = new JsonArray(); int index = 0;
        for (var offer : s.villager.getOffers()) {
            JsonObject o = new JsonObject(); o.addProperty("offerIndex", index++);
            Negotiation.Bundle bundle = bundle(offer, band(s), null, null, false);
            o.addProperty("outOfStock", offer.isOutOfStock());
            if (bundle == null) {
                o.add("result", item(offer.getResult()));
                o.addProperty("unavailable", true);
            } else {
                o.add("costA", item(offer.getCostA().copyWithCount(bundle.payment())));
                o.add("costB", item(offer.getCostB().isEmpty() ? ItemStack.EMPTY : offer.getCostB().copyWithCount(bundle.secondary())));
                o.add("result", item(offer.getResult().copyWithCount(bundle.quantity())));
            }
            result.add(o);
        }
        return result;
    }
    private JsonObject item(ItemStack stack) {
        JsonObject item = new JsonObject(); item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("name", stack.getHoverName().getString()); item.addProperty("count", stack.getCount()); return item;
    }
    private void end(ServerPlayer player, boolean notify) {
        end(player, notify, "Conversation ended.");
    }
    private void end(ServerPlayer player, boolean notify, String reason) {
        Session s = sessions.remove(player.getUUID()); if (s == null) return;
        // The conversation is over, so is any walk with this player (a guide handing over stays a moment longer).
        if (!handingOff) outings.release(player);
        // Who was in this conversation: switching to one of them shortly after continues it rather than starting afresh.
        Set<UUID> present = new HashSet<>(Set.of(s.villager.getUUID()));
        if (s.companion != null) present.add(s.companion.getUUID());
        recentConversations.put(player.getUUID(), new RecentConversation(present, s.villager.getName().getString(),
            System.currentTimeMillis() + CONTINUE_MS));
        if (s.id != null) delete(s.id);
        if (notify) { JsonObject message = new JsonObject(); message.addProperty("type", "end"); message.addProperty("message", reason); send(player, message); }
    }
    private void delete(String id) { request("DELETE", "/sessions/" + id, null).exceptionally(ex -> null); }
    private void error(ServerPlayer player, String message) {
        JsonObject response = new JsonObject(); response.addProperty("type", "error"); response.addProperty("message", message); send(player, response);
    }
    private void send(ServerPlayer player, JsonObject message) {
        if (ServerPlayNetworking.canSend(player, ControlPayload.TYPE)) ServerPlayNetworking.send(player, new ControlPayload(GSON.toJson(message)));
    }
    private CompletableFuture<JsonElement> request(String method, String path, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(backend + path)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Bridge HTTP " + response.statusCode());
            return response.body().isBlank() ? JsonNull.INSTANCE : JsonParser.parseString(response.body());
        });
    }
    private Map<String, String> config() {
        Map<String, String> values = new HashMap<>();
        // Minecraft may launch from project root, server-cardboard/, or mod/run/.
        Path directory = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && directory != null; i++, directory = directory.getParent()) {
            Path file = directory.resolve(".env");
            if (!Files.isRegularFile(file)) continue;
            try {
                for (String line : Files.readAllLines(file)) {
                    line = line.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
                    int split = line.indexOf('='); if (split < 1) continue;
                    String value = line.substring(split + 1).trim();
                    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                    values.put(line.substring(0, split).trim(), value);
                }
            } catch (java.io.IOException ignored) { }
            break;
        }
        values.putAll(System.getenv()); return values;
    }
}
