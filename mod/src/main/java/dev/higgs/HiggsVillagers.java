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
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
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
    private static final class Session {
        final ServerPlayer player;
        final Villager villager;
        String id;
        boolean polling;
        int pollFailures;
        final Map<String, JsonObject> toolResults = new HashMap<>();
        final Set<String> pendingTools = new HashSet<>();
        Session(ServerPlayer player, Villager villager) { this.player = player; this.villager = villager; }
    }
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
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            memory.tick(server, ++ticks);
            if (ticks % 10 != 0) return;
            for (Session s : List.copyOf(sessions.values())) {
                if (!valid(s, 144)) end(s.player, true, "Conversation ended: stay within 12 blocks of an awake villager.");
                else if (s.id != null && !s.polling) poll(server, s);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Session s : List.copyOf(sessions.values())) end(s.player, false);
            lastStart.clear();
            memory.stop();
        });
    }
    private void receive(MinecraftServer server, ServerPlayer player, String json) {
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
            context.addProperty("autoGreeting", true);
            context.add("offers", offers(villager)); body.add("context", context);
            request("POST", "/sessions", body).whenComplete((reply, failure) -> server.execute(() -> {
                if (failure != null) {
                    if (sessions.get(player.getUUID()) == s) { sessions.remove(player.getUUID()); error(player, "Voice bridge unavailable. Check backend configuration and logs."); }
                    return;
                }
                try {
                    JsonObject r = reply.getAsJsonObject(); s.id = r.get("sessionId").getAsString();
                    if (sessions.get(player.getUUID()) != s || !valid(s)) { delete(s.id); return; }
                    memory.conversationOpened(villager, player, context);
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
                if (++s.pollFailures >= 3) end(s.player, true, "Voice bridge unreachable after three attempts. Press V to reconnect.");
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
                        else if (name.equals("propose_trade")) {
                            JsonElement args = call.get("arguments");
                            JsonObject arguments = args.isJsonPrimitive() ? JsonParser.parseString(args.getAsString()).getAsJsonObject() : args.getAsJsonObject();
                            int index = arguments.get("offerIndex").getAsInt();
                            var offers = s.villager.getOffers();
                            if (!valid(s)) result.addProperty("error", "Move within 8 blocks of the villager to open trading.");
                            else if (index < 0 || index >= offers.size() || offers.get(index).isOutOfStock()) result.addProperty("error", "Offer unavailable.");
                            else if (s.villager.isTrading()) result.addProperty("error", "Villager already has a trade screen open.");
                            else if (s.player.getMainHandItem().is(Items.VILLAGER_SPAWN_EGG)) result.addProperty("error", "Put away the villager spawn egg before trading.");
                            else {
                                // Native interaction applies reputation/Hero discounts and Minecraft trading rules.
                                s.villager.mobInteract(s.player, InteractionHand.MAIN_HAND);
                                result.addProperty("status", "Trade screen opened. Player must select and confirm the trade; no purchase was made.");
                                result.addProperty("offerIndex", index);
                            }
                        } else result.addProperty("error", "Unknown tool.");
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
    private JsonArray offers(Villager villager) {
        JsonArray result = new JsonArray(); int index = 0;
        for (var offer : villager.getOffers()) {
            JsonObject o = new JsonObject(); o.addProperty("offerIndex", index++);
            o.add("costA", item(offer.getCostA())); o.add("costB", item(offer.getCostB()));
            o.add("result", item(offer.getResult())); o.addProperty("outOfStock", offer.isOutOfStock());
            o.addProperty("priceNote", "Preview only: player reputation and Hero discounts are applied in the native trade screen.");
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
