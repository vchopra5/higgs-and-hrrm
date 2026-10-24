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
    private Negotiation.Band band(Session s) { return Negotiation.band(standing(s)); }
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
            if (world.isClientSide() || !(player instanceof ServerPlayer sp) || !(entity instanceof Villager villager)
                || !Negotiation.hostile(standing(new Session(sp, villager)))) return net.minecraft.world.InteractionResult.PASS;
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
            // Rotate explicitly: the demo villagers have NoAI, so look controls alone do nothing.
            Map<UUID, Session> facing = new HashMap<>();
            for (Session s : sessions.values()) {
                if (!valid(s, 144)) continue;
                facing.merge(s.villager.getUUID(), s, (a, b) ->
                    a.player.distanceToSqr(a.villager) <= b.player.distanceToSqr(b.villager) ? a : b);
            }
            for (Session s : facing.values()) {
                double dx = s.player.getX() - s.villager.getX();
                double dz = s.player.getZ() - s.villager.getZ();
                if (dx * dx + dz * dz < 0.001) continue;
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                float turn = net.minecraft.util.Mth.approachDegrees(s.villager.getYRot(), yaw, 12);
                s.villager.setYRot(turn);
                s.villager.setYBodyRot(turn);
                s.villager.setYHeadRot(turn);
                s.villager.getLookControl().setLookAt(s.player, 30, 30);
                s.villager.getNavigation().stop();
            }
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
            if (ticks % 10 != 0) return;
            for (Session s : List.copyOf(sessions.values())) {
                if (!valid(s, 144)) end(s.player, true, "Conversation ended: stay within 12 blocks of an awake villager.");
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
            memory.stop();
            personalities.stop();
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
            end(player, false);
            sessions.put(player.getUUID(), s);
            personalities.prepare(villager).whenComplete((prepared, failure) -> {
                if (server.isStopped()) return;
                server.execute(() -> {
                    if (sessions.get(player.getUUID()) != s) return;
                    if (!valid(s)) { end(player, true); return; }
                    if (failure != null) { end(player, true); error(player, "Could not save villager identity. Check world save permissions or cast tags."); return; }
                    personalities.applyName(villager, prepared);
                    s.profile = prepared.profile();
                    startVoice(server, s, autoGreeting);
                });
            });
        } catch (RuntimeException ex) { error(player, "Invalid villager request."); }
    }
    private void startVoice(MinecraftServer server, Session s, boolean autoGreeting) {
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
            memory.conversationContext(villager, player).entrySet().forEach(entry -> context.add(entry.getKey(), entry.getValue()));
            if (s.profile != null) context.add("personality", personalities.context(s.profile, player));
            context.addProperty("autoGreeting", autoGreeting);
            context.add("offers", offers(s));
            Quest quest = quests.get(relationshipKey(s));
            if (quest != null) {
                JsonObject open = new JsonObject();
                open.addProperty("wants", quest.ask());
                open.add("deal", quest.deal());
                open.addProperty("delivered", quest.delivered());
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
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "session"); response.addProperty("sessionId", s.id);
                    String ticket = r.get("ticket").getAsString(); response.addProperty("ticket", ticket);
                    response.addProperty("url", publicBackend.replaceFirst("^http", "ws") + "/audio?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8));
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
                        if (name.equals("get_trades")) {
                            if (Negotiation.hostile(standing(s))) result = refused();
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
                        } else if (name.equals("complete_quest")) {
                            result = completeQuest(s, call.get("turnId").getAsInt());
                        } else result.addProperty("error", "Unknown tool.");
                        if (Set.of("offer_quest", "complete_quest").contains(name))
                            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Quest tool={} turn={} status={}",
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
            justDelivered = true;
        }
        String thanks = justDelivered ? "The player just handed over " + quest.ask() + " for your quest; thank them warmly. "
            : "The player already delivered your quest items. ";
        if (!availableForVoiceTrade(s)) return outcome("unavailable", thanks + "The deal is saved, but the player must step closer and close any inventory first.");
        if (quest.offerIndex() >= s.villager.getOffers().size()) return outcome("quest_deal_unavailable", thanks + "That trade no longer exists; apologise.");
        var offer = s.villager.getOffers().get(quest.offerIndex());
        if (!id(offer.getCostA()).equals(quest.baseA()) || offer.getCostA().getCount() != quest.baseACount()
            || !id(offer.getCostB()).equals(quest.baseB()) || offer.getCostB().getCount() != quest.baseBCount()
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
        int index = arguments.get("offerIndex").getAsInt();
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
