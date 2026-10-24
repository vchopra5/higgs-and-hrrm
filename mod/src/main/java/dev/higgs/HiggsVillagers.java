package dev.higgs;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import dev.higgs.mixin.VillagerPrices;
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
        s.mood = -2;
        boolean wasTrading = s.proposal != null && s.proposal.expires() > now;
        s.proposal = null;
        if (wasTrading) tradeStatus(s, outcome("cancelled", "Trade cancelled after being hit."));
        JsonObject event = new JsonObject(); event.addProperty("type", "hit");
        event.addProperty("windowMs", RecentHits.WINDOW_MS);
        event.addProperty("count", stats.count());
        event.addProperty("spanMs", stats.spanMs());
        event.addProperty("sincePreviousMs", stats.sincePreviousMs());
        event.addProperty("tradeCancelled", wasTrading || (s.hitEvent != null && s.hitEvent.get("tradeCancelled").getAsBoolean()));
        s.hitEvent = event;
        s.hitAt = now;
    }
    private final Map<String, Relationship> relationships = new HashMap<>();
    private Path relationshipFile;
    private record Relationship(int score, long updated) {}
    private String relationshipKey(Session s) { return s.player.getUUID() + ":" + s.villager.getUUID(); }
    private Negotiation.Band band(Session s) {
        return Negotiation.band(relationships.getOrDefault(relationshipKey(s), new Relationship(0, 0)).score(),
            s.mood, s.villager.getPlayerReputation(s.player));
    }
    private void saveRelationships() {
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
        s.treatmentTurn = turn;
        s.mood = tone.equals("polite") ? 1 : tone.equals("rude") ? -1 : s.mood;
        String key = relationshipKey(s);
        Relationship previous = relationships.getOrDefault(key, new Relationship(0, 0));
        long now = System.currentTimeMillis();
        if (!tone.equals("neutral") && now - previous.updated() >= 60_000) {
            relationships.put(key, new Relationship(Math.max(-5, Math.min(5, previous.score() + (tone.equals("polite") ? 1 : -2))), now));
            saveRelationships();
        }
        Negotiation.Band limits = band(s);
        JsonObject result = outcome("assessed", "Treatment affects future quotes; existing quotes keep their agreed price.");
        result.addProperty("relationship", limits.name());
        result.addProperty("minPricePercent", limits.min()); result.addProperty("maxPricePercent", limits.max());
        return result;
    }
    private static final class Session {
        final ServerPlayer player;
        final Villager villager;
        String id;
        boolean polling;
        Proposal proposal;
        int pollFailures;
        int mood, treatmentTurn;
        JsonObject hitEvent;
        long hitAt;
        int lastPurchaseTurn = -1;
        JsonObject lastReceipt;
        final Map<String, JsonObject> purchases = new HashMap<>();
        final Map<String, JsonObject> toolResults = new HashMap<>();
        final Set<String> pendingTools = new HashSet<>();
        Session(ServerPlayer player, Villager villager) { this.player = player; this.villager = villager; }
    }
    private record Proposal(String id, int index, long expires, ItemStack costA,
                            ItemStack costB, ItemStack result, VoiceConfirmation confirmation, ItemStack baseA, ItemStack baseB, ItemStack baseResult, int stockUses) { }
    @Override public void onInitialize() {
        Map<String, String> config = config();
        backend = config.getOrDefault("HIGGS_BACKEND_URL", "http://127.0.0.1:8787").replaceAll("/+$", "");
        publicBackend = config.getOrDefault("HIGGS_PUBLIC_BACKEND_URL", backend).replaceAll("/+$", "");
        token = config.getOrDefault("HIGGS_BRIDGE_TOKEN", "");
        memory = new VillagerMemoryService(config);
        ServerLifecycleEvents.SERVER_STARTED.register(memory::start);
        PayloadTypeRegistry.serverboundPlay().register(ControlPayload.TYPE, ControlPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ControlPayload.TYPE, ControlPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ControlPayload.TYPE, (payload, ctx) ->
            ctx.server().execute(() -> receive(ctx.server(), ctx.player(), payload.json())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            end(handler.player, false); lastStart.remove(handler.player.getUUID());
        });
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer sp && entity instanceof Villager villager)
                hit(sp.level().getServer(), sp, villager);
            return net.minecraft.world.InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            memory.tick(server, ++ticks);
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
            relationshipFile = server.getWorldPath(LevelResource.ROOT).resolve("data/higgs-relationships.json");
            relationships.clear();
            if (Files.exists(relationshipFile)) try {
                JsonObject saved = JsonParser.parseString(Files.readString(relationshipFile)).getAsJsonObject();
                for (var entry : saved.entrySet()) {
                    Relationship value = GSON.fromJson(entry.getValue(), Relationship.class);
                    relationships.put(entry.getKey(), new Relationship(Math.max(-5, Math.min(5, value.score())), value.updated()));
                }
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not load villager relationships"); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Session s : List.copyOf(sessions.values())) end(s.player, false);
            lastStart.clear();
            memory.stop();
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
            JsonObject body = new JsonObject();
            body.addProperty("playerId", player.getUUID().toString());
            body.addProperty("villagerId", villager.getUUID().toString());
            JsonObject context = new JsonObject();
            context.addProperty("name", villager.getName().getString());
            context.addProperty("profession", VillagerMemoryService.profession(villager));
            context.addProperty("level", villager.getVillagerData().level());
            context.addProperty("playerName", player.getName().getString());
            memory.conversationContext(villager, player).entrySet().forEach(entry -> context.add(entry.getKey(), entry.getValue()));
            context.addProperty("autoGreeting", autoGreeting);
            context.add("offers", offers(villager)); body.add("context", context);
            request("POST", "/sessions", body).whenComplete((reply, failure) -> server.execute(() -> {
                if (failure != null) {
                    if (sessions.get(player.getUUID()) == s) { sessions.remove(player.getUUID()); error(player, "Voice bridge unavailable. Check backend configuration and logs."); }
                    return;
                }
                try {
                    JsonObject r = reply.getAsJsonObject(); s.id = r.get("sessionId").getAsString();
                    if (sessions.get(player.getUUID()) != s || !valid(s)) { delete(s.id); return; }
                    if (autoGreeting) memory.conversationOpened(villager, player, context);
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "session"); response.addProperty("sessionId", s.id);
                    String ticket = r.get("ticket").getAsString(); response.addProperty("ticket", ticket);
                    response.addProperty("url", publicBackend.replaceFirst("^http", "ws") + "/audio?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8));
                    send(player, response);
                } catch (RuntimeException ex) { end(player, true); error(player, "Invalid voice bridge response."); }
            }));
        } catch (RuntimeException ex) { error(player, "Invalid villager request."); }
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
                        if (name.equals("get_trades")) result.add("offers", offers(s.villager));
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
                        } else result.addProperty("error", "Unknown tool.");
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
    private JsonObject proposeTrade(Session s, JsonObject arguments, int turn) {
        if (turn == s.lastPurchaseTurn && s.lastReceipt != null) return s.lastReceipt;
        int index = arguments.get("offerIndex").getAsInt();
        Proposal previous = s.proposal;
        s.proposal = null;
        if (!availableForVoiceTrade(s)) return outcome("unavailable", "Move within eight blocks, close any inventory, and wait until the villager is free.");
        if (index < 0 || index >= s.villager.getOffers().size()) return outcome("unavailable", "That offer does not exist.");
        s.villager.setTradingPlayer(s.player);
        try {
            ((VillagerPrices) s.villager).higgs$updateSpecialPrices(s.player);
            var offer = s.villager.getOffers().get(index);
            if (offer.isOutOfStock()) return outcome("unavailable", "That offer is sold out.");
            Integer quantity = arguments.has("quantity") ? arguments.get("quantity").getAsInt() : null;
            Integer payment = arguments.has("payment") ? arguments.get("payment").getAsInt() : null;
            Negotiation.Band limits = band(s);
            Negotiation.Bundle bundle;
            try {
                bundle = Negotiation.quote(offer.getCostA().getCount(), offer.getCostB().getCount(), offer.getResult().getCount(),
                    offer.getMaxUses() - offer.getUses(), offer.getCostA().getMaxStackSize(),
                    offer.getCostB().isEmpty() ? 64 : offer.getCostB().getMaxStackSize(), offer.getResult().getMaxStackSize(),
                    limits, quantity, payment, arguments.has("discount") && arguments.get("discount").getAsBoolean());
            } catch (IllegalArgumentException e) { return outcome("invalid", "Use whole quantities from one to sixty-four."); }
            if (bundle == null) return outcome("unavailable", "No whole-item bundle fits this price range and remaining stock. Try a different offer.");
            Proposal p = new Proposal(UUID.randomUUID().toString(), index, System.currentTimeMillis() + 120_000,
                offer.getCostA().copyWithCount(bundle.payment()), offer.getCostB().isEmpty() ? ItemStack.EMPTY : offer.getCostB().copyWithCount(bundle.secondary()),
                offer.getResult().copyWithCount(bundle.quantity()), new VoiceConfirmation(turn, System.currentTimeMillis() + 120_000),
                offer.getCostA().copy(), offer.getCostB().copy(), offer.getResult().copy(), bundle.stockUses());
            if (previous != null && previous.expires() > System.currentTimeMillis()
                && previous.index() == p.index() && previous.stockUses() == p.stockUses()
                && ItemStack.matches(previous.costA(), p.costA()) && ItemStack.matches(previous.costB(), p.costB())
                && ItemStack.matches(previous.result(), p.result()) && ItemStack.matches(previous.baseA(), p.baseA())
                && ItemStack.matches(previous.baseB(), p.baseB()) && ItemStack.matches(previous.baseResult(), p.baseResult())) {
                s.proposal = previous;
                return quote(previous, "quote_unchanged", "This is the same active quote, not a new offer. If the player's latest utterance accepts it, call confirm_trade with this proposalId now. Do not ask for another yes. Otherwise keep this quote and answer their question.");
            }
            s.proposal = p;
            JsonObject result = quote(p, bundle.counteroffer() ? "counteroffer" : "awaiting_confirmation",
                "Say the exact costs and received quantity once, then ask Confirm trade? Wait for one spoken yes. No extra explanation.");
            result.addProperty("relationship", limits.name());
            result.addProperty("minPricePercent", limits.min()); result.addProperty("maxPricePercent", limits.max());
            return result;
        } finally { s.villager.setTradingPlayer(null); }
    }
    private JsonObject confirmTrade(Session s, String id, int turn) {
        if (s.purchases.containsKey(id)) return s.purchases.get(id);
        Proposal p = s.proposal;
        if (p == null || !p.id().equals(id)) return outcome("unavailable", "No matching proposal. Ask for a new quote.");
        if (System.currentTimeMillis() >= p.expires() || !availableForVoiceTrade(s)) {
            s.proposal = null; return outcome("unavailable", "Proposal expired or villager unavailable. Nothing was purchased.");
        }
        VoiceConfirmation.Result confirmation = p.confirmation().advance(turn, System.currentTimeMillis());
        if (confirmation == VoiceConfirmation.Result.WAIT) return outcome("wait_for_player", "Wait for a new explicit spoken confirmation. Do not call again until the player answers.");
        if (confirmation == VoiceConfirmation.Result.EXPIRED || confirmation == VoiceConfirmation.Result.CONSUMED) {
            s.proposal = null; return outcome("unavailable", "Ask for a fresh proposal. Nothing was purchased.");
        }
        // Consume before mutation; retries and duplicate tool calls cannot purchase twice.
        s.proposal = null;
        s.villager.setTradingPlayer(s.player);
        MerchantMenu menu = null;
        MerchantOffer original = null;
        try {
            ((VillagerPrices) s.villager).higgs$updateSpecialPrices(s.player);
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
                || !ItemStack.matches(menu.getSlot(2).getItem(), p.result()))
                return outcome("missing_items", "You do not have the required items. Nothing was purchased.");
            int space = 0;
            for (int i = 3; i < 39; i++) {
                var slot = menu.getSlot(i); ItemStack stack = slot.getItem();
                if (stack.isEmpty()) space += Math.min(slot.getMaxStackSize(p.result()), p.result().getMaxStackSize());
                else if (ItemStack.isSameItemSameComponents(stack, p.result()))
                    space += Math.max(0, Math.min(slot.getMaxStackSize(p.result()), p.result().getMaxStackSize()) - stack.getCount());
            }
            if (space < p.result().getCount()) return outcome("inventory_full", "Your inventory is full. Nothing was purchased.");
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
            }
            JsonObject receipt = outcome(purchased ? "purchased" : "unavailable", purchased
                ? p.result().getCount() + " × " + p.result().getHoverName().getString() + " added to your inventory. Trade is FINISHED. Briefly confirm success; do not ask for confirmation or propose it again."
                : "Nothing was purchased. Ask for a fresh proposal.");
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
        }
    }
    private JsonArray offers(Villager villager) {
        JsonArray result = new JsonArray(); int index = 0;
        for (var offer : villager.getOffers()) {
            JsonObject o = new JsonObject(); o.addProperty("offerIndex", index++);
            o.add("costA", item(offer.getCostA())); o.add("costB", item(offer.getCostB()));
            o.add("result", item(offer.getResult())); o.addProperty("outOfStock", offer.isOutOfStock());
            o.addProperty("priceNote", "Preview only: propose_trade returns exact player-specific prices before spoken confirmation.");
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
