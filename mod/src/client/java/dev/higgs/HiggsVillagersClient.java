package dev.higgs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
    private boolean talking;
    private long requestTime;
    private long generation;

    @Override public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("higgs_villagers", "voice"));
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.conversation", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
        KeyMapping pushToTalk = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.higgs_villagers.talk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
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
            boolean held = client.screen == null && pushToTalk.isDown() && voice != null && voice.isReady();
            if (held != talking) {
                talking = held;
                if (voice != null) {
                    if (held) voice.beginTalking(); else voice.endTalking();
                }
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
                case "end" -> { stop(false); status("Conversation ended."); }
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
        talking = false;
        VoiceSession previous = voice;
        voice = null;
        if (previous != null) previous.close();
        if (notifyServer && active && ClientPlayNetworking.canSend(ControlPayload.TYPE))
            ClientPlayNetworking.send(new ControlPayload("{\"type\":\"end\"}"));
    }

    private static void status(String message) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) client.gui.setOverlayMessage(Component.literal("[Higgs] " + message), false);
        });
    }
}
