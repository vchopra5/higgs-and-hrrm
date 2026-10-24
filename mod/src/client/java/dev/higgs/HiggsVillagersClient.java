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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

public final class HiggsVillagersClient implements ClientModInitializer {
    private VoiceSession voice;
    private boolean pending;
    private long requestTime;
    private long generation;

    @Override public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("higgs_villagers", "voice"));
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.conversation", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
        KeyMapping mute = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.mute", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("higgs_villagers", "voice_indicator"),
            (graphics, delta) -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null || client.options.hideGui || (voice == null && !pending)) return;
                boolean transmitting = voice != null && voice.isTransmitting();
                boolean muted = voice != null && voice.isMuted();
                boolean speaking = voice != null && voice.isSpeaking();
                boolean ready = voice != null && voice.isReady();
                String label = muted ? "Muted" : speaking ? "Speaking" : transmitting ? "Listening" : ready ? "Starting mic..." : "Connecting...";
                int color = muted ? 0xFFFFCF70 : speaking ? 0xFF70EF9B : ready ? 0xFFD3DBE5 : 0xFFFFCF70;
                int width = 32 + client.font.width(label);
                int x = graphics.guiWidth() - width - 10, y = 10;
                graphics.fill(x, y, x + width, y + 26, 0xDF141B24);
                graphics.fill(x, y, x + 2, y + 26, color);
                // Pixel microphone: capsule, cradle and stand; no font glyph dependency.
                int mx = x + 12, my = y + 5;
                graphics.fill(mx - 2, my, mx + 2, my + 9, color);
                graphics.fill(mx - 4, my + 5, mx - 3, my + 11, color);
                graphics.fill(mx + 3, my + 5, mx + 4, my + 11, color);
                graphics.fill(mx - 3, my + 10, mx + 3, my + 12, color);
                graphics.fill(mx - 1, my + 12, mx + 1, my + 15, color);
                graphics.fill(mx - 3, my + 15, mx + 3, my + 16, color);
                graphics.text(client.font, label, x + 24, y + 9, color, false);
            });
        ClientPlayNetworking.registerGlobalReceiver(ControlPayload.TYPE, (payload, context) ->
            context.client().execute(() -> receive(payload.json())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stop(false));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) { stop(false); return; }
            while (toggle.consumeClick()) {
                if (client.screen != null) continue;
                if (voice != null || pending) { stop(true); status("Conversation ended."); }
                else start(client);
            }
            if (pending && System.currentTimeMillis() - requestTime > 15_000) {
                stop(true);
                status("Villager connection timed out. Check the backend.");
            }
            while (mute.consumeClick()) {
                if (client.screen == null && voice != null) voice.toggleMute();
            }
        });
    }

    private void start(Minecraft client) {
        if (!ClientPlayNetworking.canSend(ControlPayload.TYPE)) {
            status("This server needs the Higgs Villagers mod.");
            return;
        }
        if (!(client.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Villager)) {
            status("Look at a nearby villager, then press V.");
            return;
        }
        JsonObject request = new JsonObject();
        request.addProperty("type", "start");
        request.addProperty("entityId", hit.getEntity().getId());
        pending = true;
        requestTime = System.currentTimeMillis();
        ClientPlayNetworking.send(new ControlPayload(request.toString()));
        status("Connecting to villager…");
    }

    private void receive(String raw) {
        try {
            JsonObject message = JsonParser.parseString(raw).getAsJsonObject();
            switch (message.get("type").getAsString()) {
                case "session" -> {
                    if (!pending) return;
                    pending = false;
                    long current = ++generation;
                    voice = new VoiceSession(message.get("url").getAsString(), message.get("ticket").getAsString(),
                        HiggsVillagersClient::status, () -> Minecraft.getInstance().execute(() -> {
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
        VoiceSession previous = voice;
        voice = null;
        if (previous != null) previous.close();
        if (notifyServer && active && ClientPlayNetworking.canSend(ControlPayload.TYPE))
            ClientPlayNetworking.send(new ControlPayload("{\"type\":\"end\"}"));
    }

    private static void status(String message) {
        org.slf4j.LoggerFactory.getLogger("HiggsVillagersVoice").info("{}", message);
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) client.gui.setOverlayMessage(Component.literal("[Higgs] " + message), false);
        });
    }
}
