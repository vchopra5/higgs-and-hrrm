package dev.higgs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class HiggsVillagersClient implements ClientModInitializer {
    private VoiceSession voice;
    private final TradeHandoff handoff = new TradeHandoff();
    private final TradeTracker tracker = new TradeTracker();
    private String activeSessionId;
    private boolean pending;
    private int targetId = -1;
    private final SwitchTalkGate switchTalkGate = new SwitchTalkGate();
    private boolean wasTalking;
    private long nextConnectAt;
    private long requestTime;
    private long generation;
    private int debugPage; // 0 hidden; otherwise the F-key of the page shown.
    private int debugScroll;
    private boolean tutorialDisabled;
    private VoiceSession lastVoice;
    private AmbientChatter chatter;
    private volatile String companionName = "Villager";
    private long diagnosticRefresh;
    private int diagnosticWidth;
    private List<String> diagnosticLines = List.of();
    private record Status(long at, String message) {}
    private static final Deque<Status> STATUS_HISTORY = new ConcurrentLinkedDeque<>();

    @Override public void onInitializeClient() {
        VillagerExpressions.initializeAnimations();
        ReputationBar.register();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("higgs_villagers", "voice"));
        KeyMapping talk = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.talk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
        KeyMapping conversationDebug = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.debug_conversation", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, category));
        KeyMapping contextDebug = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.debug_context", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, category));
        KeyMapping diagnostics = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.diagnostics", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        KeyMapping scrollUp = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.debug_scroll_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_UP, category));
        KeyMapping scrollDown = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.debug_scroll_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_DOWN, category));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("higgs_villagers", "voice_indicator"),
            (graphics, delta) -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null || client.options.hideGui || (voice == null && !pending)) return;
                boolean transmitting = voice != null && voice.isTransmitting();
                boolean muted = voice != null && voice.isMuted();
                boolean speaking = voice != null && voice.isSpeaking();
                boolean ready = voice != null && voice.isReady();
                String label = !ready ? "Connecting..." : muted ? "Hold B to talk" : speaking ? "Speaking" : "Starting mic...";
                int color = muted ? 0xFFFFCF70 : speaking ? 0xFF70EF9B : ready ? 0xFFD3DBE5 : 0xFFFFCF70;
                double dbfs = voice == null ? -96 : voice.microphoneDbfs();
                String strength = muted ? "Mic off" : !transmitting ? "Mic inactive"
                    : dbfs <= -96 ? "Mic: no signal" : "Mic: " + Math.round(dbfs) + " dBFS";
                int width = Math.max(128, Math.max(32 + client.font.width(label), 16 + client.font.width(strength)));
                int x = graphics.guiWidth() - width - 10, y = 10;
                graphics.fill(x, y, x + width, y + 49, 0xDF141B24);
                graphics.fill(x, y, x + 2, y + 49, color);
                // Pixel microphone: capsule, cradle and stand; no font glyph dependency.
                int mx = x + 12, my = y + 5;
                graphics.fill(mx - 2, my, mx + 2, my + 9, color);
                graphics.fill(mx - 4, my + 5, mx - 3, my + 11, color);
                graphics.fill(mx + 3, my + 5, mx + 4, my + 11, color);
                graphics.fill(mx - 3, my + 10, mx + 3, my + 12, color);
                graphics.fill(mx - 1, my + 12, mx + 1, my + 15, color);
                graphics.fill(mx - 3, my + 15, mx + 3, my + 16, color);
                graphics.text(client.font, label, x + 24, y + 9, color, false);
                graphics.text(client.font, strength, x + 8, y + 25, 0xFFD3DBE5, false);
                // Measure local PCM directly, even before the provider detects speech.
                double level = transmitting ? Math.max(0, Math.min(1, (dbfs + 60) / 60)) : 0;
                int lit = (int) Math.ceil(level * 12);
                for (int bar = 0; bar < 12; bar++) {
                    int left = x + 8 + bar * (width - 16) / 12;
                    int right = x + 8 + (bar + 1) * (width - 16) / 12 - 2;
                    int barColor = bar >= 11 ? 0xFFFF7070 : bar >= 9 ? 0xFFFFCF70 : 0xFF70EF9B;
                    graphics.fill(left, y + 39, right, y + 44, bar < lit ? barColor : 0xFF384451);
                }
            });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("higgs_villagers", "diagnostics"),
            (graphics, delta) -> {
                Minecraft client = Minecraft.getInstance();
                if (debugPage == 0 || client.player == null || client.options.hideGui) return;
                long now = System.currentTimeMillis();
                VoiceSession source = voice != null ? voice : lastVoice;
                // Leave the top-right corner to the voice indicator.
                int width = Math.max(160, Math.min(420, graphics.guiWidth() - 170));
                if (now - diagnosticRefresh >= 250 || width != diagnosticWidth) {
                    diagnosticRefresh = now;
                    diagnosticWidth = width;
                    List<String> raw = switch (debugPage) {
                        case 6 -> source == null ? List.of(pending ? "Connecting…" : "No conversation yet.") : DebugPages.conversation(source.backendDiagnostics());
                        case 7 -> DebugPages.context(source == null ? null : source.debugContext());
                        default -> connectionLines(client, source, now);
                    };
                    List<String> wrapped = new ArrayList<>();
                    for (String line : raw) wrapped.addAll(DebugPages.wrap(line, width - 12, client.font::width));
                    diagnosticLines = wrapped;
                }
                String state = source == null ? pending ? "CONNECTING" : "IDLE" : voice == null ? "ENDED" : source.isReady() ? "LIVE" : "CONNECTING";
                String title = switch (debugPage) {
                    case 6 -> "Conversation · Boson [F6]";
                    case 7 -> "Conversation context [F7]";
                    default -> "Audio & connection [F8]";
                } + "  " + state;
                int footer = debugPage == 8 ? 6 : 0;
                int rows = Math.max(1, (graphics.guiHeight() - 20 - 21 - footer) / 11);
                debugScroll = Math.max(0, Math.min(debugScroll, diagnosticLines.size() - rows));
                int shown = Math.min(rows, diagnosticLines.size() - debugScroll);
                if (diagnosticLines.size() > rows)
                    title += "  PgUp/PgDn " + (debugScroll + 1) + "–" + (debugScroll + shown) + "/" + diagnosticLines.size();
                int x = 10, y = 10;
                int height = (shown + 1) * 11 + 10 + footer;
                graphics.fill(x, y, x + width, y + height, 0xE0141B24);
                graphics.fill(x, y, x + 2, y + height, 0xFF70B7FF);
                graphics.text(client.font, client.font.plainSubstrByWidth(title, width - 12), x + 6, y + 5, 0xFF70B7FF, false);
                int row = y + 16;
                for (int i = debugScroll; i < debugScroll + shown; i++) {
                    graphics.text(client.font, diagnosticLines.get(i), x + 6, row, 0xFFD3DBE5, false);
                    row += 11;
                }
                if (debugPage == 8) {
                    double dbfs = source == null ? -96 : source.microphoneDbfs();
                    int meter = (int) ((width - 12) * Math.max(0, Math.min(1, (dbfs + 60) / 60)));
                    graphics.fill(x + 6, y + height - 5, x + width - 6, y + height - 3, 0xFF384451);
                    if (meter > 0) graphics.fill(x + 6, y + height - 5, x + 6 + meter, y + height - 3, dbfs > -3 ? 0xFFFF7070 : 0xFF70EF9B);
                }
            });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("higgs_villagers", "trade_tracker"),
            (graphics, delta) -> tracker.render(graphics));
        ClientPlayNetworking.registerGlobalReceiver(ControlPayload.TYPE, (payload, context) ->
            context.client().execute(() -> receive(payload.json())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { stop(false); stopChatter(); handoff.clear(); VillagerExpressions.clear(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            handoff.tick(client);
            if (client.player == null || client.level == null) { stop(false); stopChatter(); return; }
            if (voice != null) voice.tick();
            if (chatter != null) {
                chatter.tick(client);
                if (chatter.closed()) chatter = null;
            }
            // Keep the local voice demo free of the unsigned-chat toast.
            // Scope this to loopback so other servers retain their warning.
            if (client.getConnection() != null
                && client.getConnection().getConnection().getRemoteAddress() instanceof java.net.InetSocketAddress address
                && address.getAddress() != null && address.getAddress().isLoopbackAddress()) {
                net.minecraft.client.gui.components.toasts.SystemToast.forceHide(client.getToastManager(),
                    net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.UNSECURE_SERVER_WARNING);
            }
            if (!tutorialDisabled) {
                client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                tutorialDisabled = true;
            }
            toggleDebugPage(client, conversationDebug, 6);
            toggleDebugPage(client, contextDebug, 7);
            toggleDebugPage(client, diagnostics, 8);
            while (scrollUp.consumeClick()) debugScroll = Math.max(0, debugScroll - 10);
            while (scrollDown.consumeClick()) debugScroll += 10; // Clamped against the page length when drawn.
            if (pending && System.currentTimeMillis() - requestTime > 15_000) {
                stop(true);
                status("Villager connection timed out. Check the backend.");
            }
            while (talk.consumeClick()) { /* Held state below owns push-to-talk. */ }
            boolean held = client.screen == null && client.isWindowActive() && talk.isDown();
            Villager target = client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Villager v
                && !v.isBaby() && client.player.distanceToSqr(v) <= 64 ? v : null;
            // With no partner selected, B also finds a nearby villager in any direction.
            // Keep the existing partner when glancing away during a conversation.
            if (target == null && voice == null && !pending && held && !wasTalking) {
                target = client.level.getEntitiesOfClass(Villager.class, client.player.getBoundingBox().inflate(8),
                    v -> v.isAlive() && !v.isBaby() && !v.isSleeping()
                        && client.player.distanceToSqr(v) <= 64 && client.player.hasLineOfSight(v))
                    .stream().min(java.util.Comparator.comparingDouble(v -> client.player.distanceToSqr(v))).orElse(null);
            }
            // Warm up the connection while looking at a villager. Switch partners only
            // on a fresh push-to-talk press, never halfway through an utterance.
            if (held && !wasTalking && target != null && target.getId() != targetId && !pending) {
                switchTalkGate.switched();
                stop(false); // The start request replaces the server session without an old end acknowledgement.
                nextConnectAt = Math.max(System.currentTimeMillis(), requestTime + 1500);
            }
            if (voice == null && !pending && target != null && System.currentTimeMillis() >= nextConnectAt
                && client.screen == null && client.isWindowActive()) start(client, target);
            boolean recordSpeech = switchTalkGate.talking(held);
            if (voice != null) voice.setTalking(recordSpeech);
            wasTalking = held;
        });
    }

    private void toggleDebugPage(Minecraft client, KeyMapping key, int page) {
        while (key.consumeClick()) {
            if (client.screen != null) continue;
            debugPage = debugPage == page ? 0 : page;
            debugScroll = 0;
            diagnosticRefresh = 0;
        }
    }

    private List<String> connectionLines(Minecraft client, VoiceSession source, long now) {
        List<String> lines = new ArrayList<>(source == null ? List.of("No voice session yet.") : List.of(source.diagnosticLines()));
        lines.add("");
        String target = "none";
        if (targetId >= 0 && client.level != null && client.level.getEntity(targetId) instanceof Villager villager)
            target = "#" + targetId + " " + villager.getName().getString()
                + String.format(java.util.Locale.ROOT, " %.1fm", Math.sqrt(client.player.distanceToSqr(villager)));
        lines.add("Client " + (pending ? "PENDING" : voice != null ? "ACTIVE" : "IDLE") + "  target " + target
            + "  generation " + generation);
        lines.add("Server mod " + (ClientPlayNetworking.canSend(ControlPayload.TYPE) ? "present" : "missing")
            + "  window " + (client.isWindowActive() ? "focused" : "unfocused") + "  B " + (wasTalking ? "held" : "up"));
        lines.add("Overheard chatter " + (chatter == null ? "none" : chatter.sceneId.substring(0, Math.min(8, chatter.sceneId.length())) + " " + chatter.status()));
        lines.add("Status history (newest first)");
        for (Status status : STATUS_HISTORY) lines.add("  " + DebugPages.seconds(now - status.at()) + "  " + status.message());
        return lines;
    }

    private void stopChatter() {
        if (chatter != null) chatter.stop();
        chatter = null;
    }

    private void start(Minecraft client, Villager target) {
        stopChatter(); // Your own conversation takes priority over overheard chatter.
        if (!ClientPlayNetworking.canSend(ControlPayload.TYPE)) {
            status("This server needs the Higgs Villagers mod.");
            return;
        }
        JsonObject request = new JsonObject();
        request.addProperty("type", "start");
        request.addProperty("entityId", target.getId());
        targetId = target.getId();
        ConversationOutline.set(targetId);
        nextConnectAt = System.currentTimeMillis() + 5000;
        pending = true;
        lastVoice = null;
        diagnosticRefresh = 0;
        requestTime = System.currentTimeMillis();
        ClientPlayNetworking.send(new ControlPayload(request.toString()));
        status("Connecting to villager…");
    }

    private void receive(String raw) {
        try {
            JsonObject message = JsonParser.parseString(raw).getAsJsonObject();
            switch (message.get("type").getAsString()) {
                case "villager_expression" -> VillagerExpressions.update(
                    java.util.UUID.fromString(message.get("villagerId").getAsString()), message.get("expression").getAsString(),
                    message.has("standing") ? message.get("standing").getAsInt() : 0);
                case "trade_complete" -> handoff.start(Minecraft.getInstance(), message);
                case "companion_join" -> {
                    if (voice == null || activeSessionId == null || !activeSessionId.equals(message.get("sessionId").getAsString())) return;
                    var entity = Minecraft.getInstance().level.getEntity(message.get("entityId").getAsInt());
                    if (entity == null) return;
                    companionName = message.get("name").getAsString();
                    voice.setCompanion(entity);
                    Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal(companionName + " joins the conversation")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
                case "chatter" -> {
                    if (voice != null || pending) return;
                    stopChatter();
                    chatter = new AmbientChatter(message.get("sceneId").getAsString(), message.get("url").getAsString());
                }
                case "chatter_end" -> {
                    if (chatter != null && chatter.sceneId.equals(message.get("sceneId").getAsString())) stopChatter();
                }
                case "trade_status" -> {
                    if (activeSessionId != null && activeSessionId.equals(message.get("sessionId").getAsString())
                        && targetId == message.get("entityId").getAsInt()) tracker.update(message);
                }
                case "reaction_start" -> {
                    stop(false);
                    stopChatter();
                    targetId = message.get("entityId").getAsInt();
                    ConversationOutline.set(targetId);
                    pending = true;
                    requestTime = System.currentTimeMillis();
                }
                case "session" -> {
                    if (!pending) return;
                    pending = false;
                    activeSessionId = message.get("sessionId").getAsString();
                    long current = ++generation;
                    String villagerName = villagerName(targetId);
                    int companionId = message.has("companionEntityId") ? message.get("companionEntityId").getAsInt() : -1;
                    companionName = message.has("companionName") ? message.get("companionName").getAsString() : "Villager";
                    if (companionId >= 0) ConversationOutline.setCompanion(companionId);
                    voice = new VoiceSession(message.get("url").getAsString(), message.get("ticket").getAsString(),
                        Minecraft.getInstance().level.getEntity(targetId),
                        companionId < 0 ? null : Minecraft.getInstance().level.getEntity(companionId),
                        HiggsVillagersClient::status, line -> postChat(line.speaker().equals("companion") ? companionName : villagerName, line),
                        () -> Minecraft.getInstance().execute(() -> {
                            if (generation == current) stop(true);
                        }));
                }
                case "error" -> { stop(false); status(message.get("message").getAsString()); }
                case "end" -> {
                    boolean active = pending || voice != null;
                    stop(false);
                    if (active) status(message.has("message") ? message.get("message").getAsString() : "Conversation ended.");
                }
                default -> { }
            }
        } catch (Exception e) {
            stop(true);
            status("Could not start voice session. Check the server configuration.");
        }
    }

    private void stop(boolean notifyServer) {
        boolean active = pending || voice != null;
        generation++;
        pending = false;
        targetId = -1;
        ConversationOutline.clear();
        activeSessionId = null;
        tracker.clear();
        VoiceSession previous = voice;
        voice = null;
        if (previous != null) { lastVoice = previous; previous.close(); }
        if (notifyServer && active && ClientPlayNetworking.canSend(ControlPayload.TYPE))
            ClientPlayNetworking.send(new ControlPayload("{\"type\":\"end\"}"));
    }

    private static String villagerName(int entityId) {
        var level = Minecraft.getInstance().level;
        var entity = level == null ? null : level.getEntity(entityId);
        return entity == null ? "Villager" : entity.getName().getString();
    }

    /** Local-only chat lines; nothing is sent to the server. Called from network threads. */
    private static void postChat(String villagerName, VoiceCaptions.Line line) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player == null) return;
            boolean you = line.speaker().equals("you");
            String name = you ? client.player.getName().getString() : villagerName;
            client.gui.getChat().addClientSystemMessage(Component.literal("<" + name + "> ")
                .withStyle(you ? ChatFormatting.AQUA : ChatFormatting.GREEN)
                .append(Component.literal(line.text()).withStyle(ChatFormatting.WHITE)));
        });
    }

    private static void status(String message) {
        STATUS_HISTORY.addFirst(new Status(System.currentTimeMillis(), message.length() > 200 ? message.substring(0, 200) : message));
        while (STATUS_HISTORY.size() > 10) STATUS_HISTORY.pollLast();
        org.slf4j.LoggerFactory.getLogger("HiggsVillagersVoice").info("{}", message);
        Minecraft client = Minecraft.getInstance();
        // Audio-only by default. F8 exposes diagnostic status when explicitly requested.
    }
}
