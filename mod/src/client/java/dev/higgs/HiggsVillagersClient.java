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
    private boolean showDiagnostics;
    private boolean tutorialDisabled;
    private VoiceSession lastVoice;
    private long diagnosticRefresh;
    private String[] diagnosticLines = new String[0];
    private static volatile String lastStatus = "";
    private static volatile long lastStatusAt;

    @Override public void onInitializeClient() {
        VillagerExpressions.initializeAnimations();
        ReputationBar.register();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("higgs_villagers", "voice"));
        KeyMapping talk = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.talk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
        KeyMapping diagnostics = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.diagnostics", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
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
                long now = System.currentTimeMillis();
                if (!showDiagnostics || client.player == null || client.options.hideGui
                    || (voice == null && !pending && now - lastStatusAt > 15_000)) return;
                VoiceSession source = voice != null ? voice : lastVoice;
                if (now - diagnosticRefresh >= 250) {
                    diagnosticRefresh = now;
                    diagnosticLines = source == null ? new String[]{"Higgs diagnostics [F8]", pending ? "Connecting…" : "No active session"} : source.diagnosticLines();
                }
                int width = Math.min(390, graphics.guiWidth() - 20);
                int x = 10, y = 10;
                int height = (diagnosticLines.length + 1) * 11 + 16;
                graphics.fill(x, y, x + width, y + height, 0xE0141B24);
                int row = y + 5;
                for (String text : diagnosticLines) {
                    graphics.text(client.font, client.font.plainSubstrByWidth(text, width - 12), x + 6, row, 0xFFD3DBE5, false);
                    row += 11;
                }
                String status = lastStatus.length() > 200 ? lastStatus.substring(0, 200) : lastStatus;
                graphics.text(client.font, client.font.plainSubstrByWidth(status, width - 12), x + 6, row, 0xFFFFCF70, false);
                double dbfs = source == null ? -96 : source.microphoneDbfs();
                int meter = (int) ((width - 12) * Math.max(0, Math.min(1, (dbfs + 60) / 60)));
                graphics.fill(x + 6, y + height - 5, x + width - 6, y + height - 3, 0xFF384451);
                if (meter > 0) graphics.fill(x + 6, y + height - 5, x + 6 + meter, y + height - 3, dbfs > -3 ? 0xFFFF7070 : 0xFF70EF9B);
            });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("higgs_villagers", "trade_tracker"),
            (graphics, delta) -> tracker.render(graphics));
        ClientPlayNetworking.registerGlobalReceiver(ControlPayload.TYPE, (payload, context) ->
            context.client().execute(() -> receive(payload.json())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { stop(false); handoff.clear(); VillagerExpressions.clear(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            handoff.tick(client);
            if (client.player == null || client.level == null) { stop(false); return; }
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
            while (diagnostics.consumeClick()) {
                if (client.screen == null) showDiagnostics = !showDiagnostics;
            }
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

    private void start(Minecraft client, Villager target) {
        if (!ClientPlayNetworking.canSend(ControlPayload.TYPE)) {
            status("This server needs the Higgs Villagers mod.");
            return;
        }
        JsonObject request = new JsonObject();
        request.addProperty("type", "start");
        request.addProperty("entityId", target.getId());
        targetId = target.getId();
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
                case "trade_status" -> {
                    if (activeSessionId != null && activeSessionId.equals(message.get("sessionId").getAsString())
                        && targetId == message.get("entityId").getAsInt()) tracker.update(message);
                }
                case "reaction_start" -> {
                    stop(false);
                    targetId = message.get("entityId").getAsInt();
                    pending = true;
                    requestTime = System.currentTimeMillis();
                }
                case "session" -> {
                    if (!pending) return;
                    pending = false;
                    activeSessionId = message.get("sessionId").getAsString();
                    long current = ++generation;
                    String villagerName = villagerName(targetId);
                    voice = new VoiceSession(message.get("url").getAsString(), message.get("ticket").getAsString(),
                        HiggsVillagersClient::status, line -> postChat(villagerName, line), () -> Minecraft.getInstance().execute(() -> {
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
        lastStatus = message;
        lastStatusAt = System.currentTimeMillis();
        org.slf4j.LoggerFactory.getLogger("HiggsVillagersVoice").info("{}", message);
        Minecraft client = Minecraft.getInstance();
        // Audio-only by default. F8 exposes diagnostic status when explicitly requested.
    }
}
