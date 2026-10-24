package dev.higgs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Villagers scream when a zombie is chasing them. The backend voices a short cry in the villager's own voice (cached per
 * voice) and nearby players hear it from the villager through the chatter listen channel.
 */
final class VillagerAlarm {
    interface Host {
        CompletableFuture<JsonElement> request(String method, String path, JsonObject body);
        void send(ServerPlayer player, JsonObject message);
        boolean canSend(ServerPlayer player);
    }
    private static final double WATCH = 24, HEAR = 32, CHASE = 8;
    private static final int MAX_PENDING = 3;
    private final Host host;
    private final VillagerPersonalityService personalities;
    private final String publicBackend;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private int pending;

    VillagerAlarm(Host host, VillagerPersonalityService personalities, String publicBackend) {
        this.host = host;
        this.personalities = personalities;
        this.publicBackend = publicBackend;
    }

    /** A zombie close by that is hunting this villager; works even for villagers whose own AI is disabled. */
    static boolean chased(Villager villager) {
        var feared = villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE).orElse(null);
        return !villager.level().getEntitiesOfClass(Zombie.class, villager.getBoundingBox().inflate(CHASE),
            zombie -> zombie.isAlive() && (zombie.getTarget() == villager || zombie == feared)).isEmpty();
    }

    void tick(MinecraftServer server, int ticks) {
        if (ticks % 10 != 0) return;
        long now = System.currentTimeMillis();
        cooldown.values().removeIf(until -> until < now);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!host.canSend(player)) continue;
            for (Villager villager : player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(WATCH),
                    v -> v.isAlive() && !v.isBaby() && !v.isSleeping() && !cooldown.containsKey(v.getUUID()))) {
                if (pending >= MAX_PENDING) return;
                if (chased(villager)) scream(server, villager, now);
            }
        }
    }

    private void scream(MinecraftServer server, Villager villager, long now) {
        // A fresh cry every 8–14 seconds while the chase lasts.
        cooldown.put(villager.getUUID(), now + 8000 + ThreadLocalRandom.current().nextLong(6000));
        pending++;
        personalities.prepare(villager).whenComplete((prepared, failure) -> server.execute(() -> {
            if (failure != null || !villager.isAlive()) { pending--; return; }
            personalities.applyName(villager, prepared);
            JsonObject speaker = new JsonObject();
            speaker.addProperty("entityId", villager.getId());
            speaker.addProperty("name", villager.getName().getString());
            speaker.addProperty("profession", VillagerMemoryService.profession(villager));
            var profile = prepared.profile();
            if (profile != null) {
                JsonObject personality = new JsonObject();
                personality.addProperty("catalogueVersion", profile.catalogueVersion());
                personality.addProperty("archetypeId", profile.archetypeId());
                personality.addProperty("styleId", profile.styleId());
                speaker.add("personality", personality);
            }
            JsonObject body = new JsonObject();
            body.addProperty("kind", "zombie");
            body.add("speaker", speaker);
            host.request("POST", "/shouts", body).whenComplete((reply, error) -> server.execute(() -> {
                pending--;
                if (error != null || !villager.isAlive()) return;
                JsonObject created = reply.getAsJsonObject();
                JsonObject message = new JsonObject();
                message.addProperty("type", "scream");
                message.addProperty("sceneId", created.get("sceneId").getAsString());
                message.addProperty("url", publicBackend.replaceFirst("^http", "ws") + "/chatter?scene="
                    + URLEncoder.encode(created.get("sceneId").getAsString(), StandardCharsets.UTF_8)
                    + "&token=" + URLEncoder.encode(created.get("token").getAsString(), StandardCharsets.UTF_8));
                for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
                    if (listener.level() == villager.level() && listener.distanceToSqr(villager) <= HEAR * HEAR && host.canSend(listener))
                        host.send(listener, message);
                }
            }));
        }));
    }

    void clear() { cooldown.clear(); pending = 0; }
}
