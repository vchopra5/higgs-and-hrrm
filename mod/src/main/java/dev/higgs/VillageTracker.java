package dev.higgs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntBiFunction;
import java.util.random.RandomGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Names the world's generated villages and tracks which one each player is in. A village is the area within a radius
 * of its structure's centre; its reputation is the mean standing of the villagers there towards that player.
 */
final class VillageTracker {
    record Village(String key, String name, String dimension, int x, int y, int z) {}
    private static final Gson GSON = new Gson();
    /** Leaving needs a little extra distance so walking along the edge does not repeat the title. */
    private static final int EXIT_MARGIN = 16;
    private final ToIntBiFunction<ServerPlayer, Villager> standing;
    private final int radius;
    int radius() { return radius; }
    private final List<String> names = loadNames();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<String, Village> villages = new HashMap<>();
    private final Map<UUID, String> current = new HashMap<>();
    private Path file;

    VillageTracker(Map<String, String> config, ToIntBiFunction<ServerPlayer, Villager> standing) {
        this.standing = standing;
        int configured;
        try { configured = Integer.parseInt(config.getOrDefault("HIGGS_VILLAGE_RADIUS", "64").trim()); }
        catch (NumberFormatException e) { configured = 64; }
        radius = Math.max(32, Math.min(160, configured));
    }

    private static List<String> loadNames() {
        List<String> result = new ArrayList<>();
        try (InputStream stream = VillageTracker.class.getResourceAsStream("/higgs_villagers/village_names.txt")) {
            if (stream == null) return result;
            new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(result::add);
        } catch (java.io.IOException ignored) { }
        return result;
    }

    void start(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("data/higgs-villages.json");
        villages.clear();
        current.clear();
        if (Files.exists(file)) try {
            JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var entry : saved.entrySet()) villages.put(entry.getKey(), GSON.fromJson(entry.getValue(), Village.class));
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not load village names"); }
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling("higgs-villages.json.tmp");
            Files.writeString(temporary, GSON.toJson(villages));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not save village names");
        }
    }

    void tick(MinecraftServer server, int ticks) {
        if (ticks % 20 != 0) return;
        boolean discover = ticks % 100 == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (discover) discover(player);
            Village previous = villages.get(current.get(player.getUUID()));
            Village village = previous != null && distanceSquared(previous, player) <= square(radius + EXIT_MARGIN)
                && previous.dimension().equals(dimension(player)) ? previous : nearest(player, radius);
            if (village == null) { current.remove(player.getUUID()); continue; }
            if (village == previous) continue;
            current.put(player.getUUID(), village.key());
            announce(player, village);
        }
        current.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** Names any generated village whose structure covers the chunks around the player. Never loads chunks. */
    private void discover(ServerPlayer player) {
        ServerLevel level = player.level();
        var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        ChunkPos centre = ChunkPos.containing(player.blockPosition());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = centre.x() + dx, z = centre.z() + dz;
                if (!level.hasChunk(x, z)) continue;
                for (StructureStart start : level.structureManager().startsForStructure(new ChunkPos(x, z),
                        structure -> structures.wrapAsHolder(structure).is(StructureTags.VILLAGE))) {
                    if (!start.isValid()) continue;
                    String key = dimension(player) + ":" + start.getChunkPos().x() + "," + start.getChunkPos().z();
                    if (villages.containsKey(key)) continue;
                    var middle = start.getBoundingBox().getCenter();
                    String name = VillageReputation.pickName(names, new HashSet<>(villages.values().stream().map(Village::name).toList()), random);
                    villages.put(key, new Village(key, name, dimension(player), middle.getX(), middle.getY(), middle.getZ()));
                    save();
                    org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Named village {} at {}", name, middle);
                }
            }
        }
    }

    private Village nearest(Entity entity, int within) {
        Village best = null;
        double bestDistance = square(within);
        String dimension = dimension(entity);
        for (Village village : villages.values()) {
            if (!village.dimension().equals(dimension)) continue;
            double distance = distanceSquared(village, entity);
            if (distance <= bestDistance) { best = village; bestDistance = distance; }
        }
        return best;
    }

    /** The village a villager lives in, if it is within a named village's radius. */
    Village villageOf(Villager villager) { return nearest(villager, radius); }

    /** Mean standing towards the player of the loaded villagers within the village radius. */
    double reputation(ServerPlayer player, Village village) {
        List<Integer> standings = new ArrayList<>();
        ServerLevel level = player.level();
        AABB area = new AABB(village.x() - radius, village.y() - 48, village.z() - radius,
            village.x() + radius, village.y() + 48, village.z() + radius);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, area, Villager::isAlive)) {
            if (distanceSquared(village, villager) <= square(radius)) standings.add(standing.applyAsInt(player, villager));
        }
        return VillageReputation.average(standings);
    }

    /** Conversation context: where this villager lives and how the village as a whole regards the player. */
    JsonObject context(ServerPlayer player, Villager villager) {
        Village village = villageOf(villager);
        if (village == null) return null;
        double average = reputation(player, village);
        JsonObject result = new JsonObject();
        result.addProperty("name", village.name());
        result.addProperty("reputation", VillageReputation.band(average));
        result.addProperty("averageStanding", Math.round(average * 10) / 10.0);
        return result;
    }

    private void announce(ServerPlayer player, Village village) {
        double average = reputation(player, village);
        String band = VillageReputation.band(average);
        ChatFormatting colour = switch (band) {
            case "friendly" -> ChatFormatting.GREEN;
            case "annoyed" -> ChatFormatting.GOLD;
            case "hostile" -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
        player.connection.send(new ClientboundSetTitlesAnimationPacket(15, 50, 30));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("Reputation: " + VillageReputation.describe(average)).withStyle(colour)));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(village.name()).withStyle(ChatFormatting.GOLD)));
    }

    private static String dimension(Entity entity) { return entity.level().dimension().identifier().toString(); }
    private static double square(double value) { return value * value; }
    private static double distanceSquared(Village village, Entity entity) {
        double dx = entity.getX() - village.x(), dz = entity.getZ() - village.z();
        return dx * dx + dz * dz;
    }
}
