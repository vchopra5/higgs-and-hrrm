package dev.higgs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds the web dashboard: every few seconds the backend is told what the server holds (villages, loaded villagers with
 * their trades, memories and relationships, players with their errands and quests) and hands back edits made in the
 * browser, which are applied here on the game thread. A full snapshot is only built while someone is watching.
 */
final class VillageDashboard {
    interface Host {
        CompletableFuture<JsonElement> request(String method, String path, JsonObject body);
        int standing(UUID player, UUID villager);
        void setStanding(UUID player, UUID villager, int value);
        Set<UUID> playersWithStanding(UUID villager);
        /** The open or earned quest between them, or null. */
        JsonObject quest(UUID player, UUID villager);
        boolean clearQuest(UUID player, UUID villager);
        boolean deliverQuest(UUID player, UUID villager);
        boolean clearErrand(UUID player);
        /** The F9 state: conversation, quote, errand, quests, deals, walk. */
        JsonObject playerState(MinecraftServer server, ServerPlayer player);
        /** Name of the player talking with this villager, or null. */
        String talkingWith(Villager villager);
    }
    private static final Logger LOG = LoggerFactory.getLogger("HiggsDashboard");
    private static final int EVERY_TICKS = 60, EPISODES = 25, MEMORIES = 20;
    private final Host host;
    private final VillagerMemoryService memory;
    private final VillageTracker villages;
    private final VillagerPersonalityService personalities;
    private final Map<UUID, String> names = new HashMap<>();
    private final List<JsonObject> results = new ArrayList<>();
    private boolean busy, watching;

    VillageDashboard(Host host, VillagerMemoryService memory, VillageTracker villages, VillagerPersonalityService personalities) {
        this.host = host;
        this.memory = memory;
        this.villages = villages;
        this.personalities = personalities;
    }

    void tick(MinecraftServer server, int ticks) {
        if (busy || ticks % EVERY_TICKS != 0) return;
        busy = true;
        JsonObject body = new JsonObject();
        if (watching) {
            try { body.add("snapshot", snapshot(server)); }
            catch (RuntimeException failure) { LOG.warn("Could not build the dashboard snapshot", failure); }
        }
        JsonArray done = new JsonArray();
        results.forEach(done::add);
        results.clear();
        body.add("results", done);
        host.request("POST", "/dashboard/sync", body).whenComplete((reply, failure) -> server.execute(() -> {
            busy = false;
            if (failure != null || reply == null || !reply.isJsonObject()) { watching = false; return; }
            JsonObject answer = reply.getAsJsonObject();
            watching = answer.has("watching") && answer.get("watching").getAsBoolean();
            if (!answer.has("commands")) return;
            for (JsonElement element : answer.getAsJsonArray("commands")) {
                JsonObject command = element.getAsJsonObject();
                JsonObject result = new JsonObject();
                result.addProperty("id", command.has("id") ? command.get("id").getAsString() : "?");
                try {
                    result.addProperty("message", apply(server, command));
                    result.addProperty("ok", true);
                } catch (RuntimeException failed) {
                    result.addProperty("message", failed.getMessage() == null ? "Could not apply that edit" : failed.getMessage());
                    result.addProperty("ok", false);
                }
                LOG.info("Dashboard edit {}: {}", command.get("type").getAsString(), result.get("message").getAsString());
                results.add(result);
            }
        }));
    }

    // ---- Snapshot ----

    private JsonObject snapshot(MinecraftServer server) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("at", System.currentTimeMillis());
        snapshot.addProperty("day", Math.floorDiv(server.overworld().getOverworldClockTime(), 24000L));
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.put(player.getUUID(), player.getName().getString());
            JsonObject row = new JsonObject();
            row.addProperty("id", player.getUUID().toString());
            row.addProperty("name", player.getName().getString());
            position(row, player.level(), player.getBlockX(), player.getBlockY(), player.getBlockZ());
            row.add("state", host.playerState(server, player));
            players.add(row);
        }
        snapshot.add("players", players);
        JsonArray villageList = new JsonArray();
        for (VillageTracker.Village village : villages.all()) {
            JsonObject row = new JsonObject();
            row.addProperty("key", village.key());
            row.addProperty("name", village.name());
            row.addProperty("dimension", village.dimension());
            row.addProperty("x", village.x());
            row.addProperty("y", village.y());
            row.addProperty("z", village.z());
            row.addProperty("radius", villages.radius());
            JsonObject reputation = new JsonObject();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level().dimension().identifier().toString().equals(village.dimension()))
                    reputation.addProperty(player.getName().getString(), Math.round(villages.reputation(player, village) * 10) / 10.0);
            }
            row.add("reputation", reputation);
            ServerLevel level = level(server, village.dimension());
            if (level != null && level.hasChunkAt(village.x(), village.z())) {
                row.add("map", map(level, village));
                row.add("landmarks", landmarks(level, village));
            }
            villageList.add(row);
        }
        snapshot.add("villages", villageList);
        JsonArray villagers = new JsonArray();
        for (ServerLevel level : server.getAllLevels())
            for (Villager villager : level.getEntities(EntityType.VILLAGER, Villager::isAlive)) villagers.add(villager(villager));
        snapshot.add("villagers", villagers);
        return snapshot;
    }

    private static ServerLevel level(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) if (level.dimension().identifier().toString().equals(dimension)) return level;
        return null;
    }

    private record Drawn(long at, JsonObject map) {}
    private final Map<String, Drawn> maps = new HashMap<>();
    private static final long MAP_MS = 60_000;

    /**
     * A top-down picture of the village like a vanilla map: the map colour of the top block about every two blocks,
     * shaded by height against the cell to the north. About 64 × 64 cells whatever the radius; unloaded chunks stay
     * transparent. Redrawn at most once a minute.
     */
    private JsonObject map(ServerLevel level, VillageTracker.Village village) {
        Drawn cached = maps.get(village.key());
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < MAP_MS) return cached.map();
        int radius = villages.radius(), step = Math.max(1, radius / 32), cells = radius * 2 / step;
        int originX = village.x() - radius, originZ = village.z() - radius;
        int[] heights = new int[cells * cells], colours = new int[cells * cells];
        boolean[] known = new boolean[cells * cells];
        var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int x = originX + i * step, z = originZ + j * step, at = j * cells + i;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                cursor.set(x, y, z);
                var colour = level.getBlockState(cursor).getMapColor(level, cursor);
                if (colour == net.minecraft.world.level.material.MapColor.NONE) {
                    cursor.move(0, -1, 0);
                    colour = level.getBlockState(cursor).getMapColor(level, cursor);
                }
                known[at] = true;
                heights[at] = y;
                colours[at] = colour.col;
            }
        }
        byte[] pixels = new byte[cells * cells * 4];
        for (int at = 0; at < cells * cells; at++) {
            if (!known[at]) continue;
            int north = at - cells;
            double shade = north < 0 || !known[north] ? 1 : heights[at] > heights[north] ? 1.12 : heights[at] < heights[north] ? 0.82 : 1;
            int colour = colours[at];
            pixels[at * 4] = (byte) Math.min(255, (int) (((colour >> 16) & 255) * shade));
            pixels[at * 4 + 1] = (byte) Math.min(255, (int) (((colour >> 8) & 255) * shade));
            pixels[at * 4 + 2] = (byte) Math.min(255, (int) ((colour & 255) * shade));
            pixels[at * 4 + 3] = (byte) 255;
        }
        JsonObject map = new JsonObject();
        map.addProperty("originX", originX);
        map.addProperty("originZ", originZ);
        map.addProperty("step", step);
        map.addProperty("cells", cells);
        map.addProperty("rgba", java.util.Base64.getEncoder().encodeToString(pixels));
        maps.put(village.key(), new Drawn(now, map));
        return map;
    }

    /** The bell, workstations and beds within the village, from the game's points of interest. */
    private JsonArray landmarks(ServerLevel level, VillageTracker.Village village) {
        JsonArray list = new JsonArray();
        var centre = new net.minecraft.core.BlockPos(village.x(), village.y(), village.z());
        level.getPoiManager().getInRange(type -> true, centre, villages.radius(), net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
            .limit(300).forEach(poi -> {
                JsonObject mark = new JsonObject();
                mark.addProperty("type", poi.getPoiType().unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown"));
                mark.addProperty("x", poi.getPos().getX());
                mark.addProperty("z", poi.getPos().getZ());
                list.add(mark);
            });
        return list;
    }

    private JsonObject villager(Villager villager) {
        JsonObject row = new JsonObject();
        UUID id = villager.getUUID();
        row.addProperty("id", id.toString());
        row.addProperty("entityId", villager.getId());
        row.addProperty("name", villager.getName().getString());
        row.addProperty("profession", VillagerMemoryService.profession(villager));
        row.addProperty("level", villager.getVillagerData().level());
        row.addProperty("xp", villager.getVillagerXp());
        row.addProperty("baby", villager.isBaby());
        row.addProperty("sleeping", villager.isSleeping());
        row.addProperty("noAi", villager.isNoAi());
        position(row, villager.level(), villager.getBlockX(), villager.getBlockY(), villager.getBlockZ());
        VillageTracker.Village village = villages.villageOf(villager);
        if (village != null) row.addProperty("village", village.key());
        String talking = host.talkingWith(villager);
        if (talking != null) row.addProperty("talkingWith", talking);
        var profile = personalities.profile(id);
        if (profile != null) {
            JsonObject personality = new JsonObject();
            personality.addProperty("archetype", profile.archetypeId());
            personality.addProperty("style", profile.styleId());
            row.add("personality", personality);
        }
        row.add("mind", memory.mind(villager));
        JsonArray trades = new JsonArray();
        var offers = villager.getOffers();
        for (int i = 0; i < offers.size(); i++) {
            var offer = offers.get(i);
            JsonObject trade = new JsonObject();
            trade.addProperty("index", i);
            trade.add("costA", stack(offer.getCostA()));
            if (!offer.getCostB().isEmpty()) trade.add("costB", stack(offer.getCostB()));
            trade.add("result", stack(offer.getResult()));
            trade.addProperty("baseCost", offer.getBaseCostA().getCount());
            trade.addProperty("uses", offer.getUses());
            trade.addProperty("maxUses", offer.getMaxUses());
            trade.addProperty("outOfStock", offer.isOutOfStock());
            trade.addProperty("demand", offer.getDemand());
            trade.addProperty("xp", offer.getXp());
            trades.add(trade);
        }
        row.add("trades", trades);
        VillagerJournal journal = memory.journal();
        Set<UUID> known = new LinkedHashSet<>(host.playersWithStanding(id));
        if (journal != null) known.addAll(journal.players(id));
        JsonArray relationships = new JsonArray();
        long now = System.currentTimeMillis();
        for (UUID player : known) {
            JsonObject bond = new JsonObject();
            int standing = host.standing(player, id);
            bond.addProperty("playerId", player.toString());
            bond.addProperty("playerName", names.getOrDefault(player, player.toString().substring(0, 8)));
            bond.addProperty("standing", standing);
            bond.addProperty("band", Negotiation.band(standing).name());
            bond.addProperty("stage", memory.stage(villager, player).label);
            if (journal != null) {
                var summary = journal.summary(id, player);
                if (summary != null) {
                    if (!summary.text().isEmpty()) bond.addProperty("summary", summary.text());
                    if (summary.nickname() != null) bond.addProperty("nickname", summary.nickname());
                    bond.addProperty("keepsake", summary.keepsake());
                    if (summary.lastGiftDay() >= 0) bond.addProperty("lastGiftDay", summary.lastGiftDay());
                }
                JsonObject counts = new JsonObject();
                journal.counts(id, player, now).forEach(counts::addProperty);
                bond.add("counts", counts);
                JsonArray promises = new JsonArray();
                for (var open : journal.openPromises(id, player, now)) promises.add(episode(open));
                bond.add("openPromises", promises);
                JsonArray episodes = new JsonArray();
                var all = journal.all(id, player, now);
                for (int i = all.size() - 1; i >= Math.max(0, all.size() - EPISODES); i--) episodes.add(episode(all.get(i)));
                bond.add("episodes", episodes);
            }
            JsonObject quest = host.quest(player, id);
            if (quest != null) bond.add("quest", quest);
            relationships.add(bond);
        }
        row.add("relationships", relationships);
        JsonArray memories = new JsonArray();
        for (var held : memory.memoriesOf(villager).stream().limit(MEMORIES).toList()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("summary", held.summary());
            entry.addProperty("about", held.playerName());
            entry.addProperty("from", held.sourceName() != null ? held.sourceName() : held.originName());
            entry.addProperty("hops", held.hops());
            entry.addProperty("private", held.privateMemory());
            if (held.interest() != null) entry.addProperty("interest", held.interest().score());
            entry.addProperty("learnedAt", held.learnedAt());
            memories.add(entry);
        }
        row.add("memories", memories);
        return row;
    }

    private static JsonObject episode(VillagerJournal.Episode episode) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", episode.id().toString());
        entry.addProperty("type", episode.type());
        entry.addProperty("text", episode.text());
        entry.addProperty("day", episode.gameDay());
        entry.addProperty("salience", episode.salience());
        if (episode.witnessed()) entry.addProperty("witnessed", true);
        return entry;
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("name", stack.getHoverName().getString());
        item.addProperty("count", stack.getCount());
        return item;
    }

    private static void position(JsonObject row, net.minecraft.world.level.Level level, int x, int y, int z) {
        row.addProperty("dimension", level.dimension().identifier().toString());
        row.addProperty("x", x);
        row.addProperty("y", y);
        row.addProperty("z", z);
    }

    // ---- Edits ----

    private static Villager loaded(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) if (level.getEntity(id) instanceof Villager villager) return villager;
        throw new IllegalArgumentException("That villager is not loaded");
    }

    private static UUID uuid(JsonObject command, String key) {
        try { return UUID.fromString(command.get(key).getAsString()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Missing or invalid " + key); }
    }

    /** Applies one browser edit; returns what happened, or throws with the reason it could not. */
    String apply(MinecraftServer server, JsonObject command) {
        String type = command.get("type").getAsString();
        VillagerJournal journal = memory.journal();
        switch (type) {
            case "set_standing" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                int value = command.get("value").getAsInt();
                if (value < -5 || value > 5) throw new IllegalArgumentException("Standing must be -5 to 5");
                host.setStanding(uuid(command, "playerId"), villager.getUUID(), value);
                return villager.getName().getString() + " standing set to " + value;
            }
            case "clear_quest" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                if (!host.clearQuest(uuid(command, "playerId"), villager.getUUID())) throw new IllegalArgumentException("No quest to clear");
                return "Quest with " + villager.getName().getString() + " cleared";
            }
            case "deliver_quest" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                if (!host.deliverQuest(uuid(command, "playerId"), villager.getUUID())) throw new IllegalArgumentException("No open quest to mark delivered");
                return "Quest with " + villager.getName().getString() + " marked delivered";
            }
            case "clear_errand" -> {
                if (!host.clearErrand(uuid(command, "playerId"))) throw new IllegalArgumentException("No errand to clear");
                return "Errand cleared";
            }
            case "set_nickname" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                if (journal == null) throw new IllegalArgumentException("Journal unavailable");
                String nickname = command.has("nickname") && !command.get("nickname").isJsonNull() ? command.get("nickname").getAsString().trim() : null;
                String value = nickname == null || nickname.isEmpty() ? null : nickname;
                journal.update(villager.getUUID(), uuid(command, "playerId"), summary -> summary.withNickname(value));
                return value == null ? "Nickname cleared" : "Nickname set to " + value;
            }
            case "reset_relationship" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                UUID player = uuid(command, "playerId");
                host.setStanding(player, villager.getUUID(), 0);
                if (journal != null) journal.forget(villager.getUUID(), player);
                return villager.getName().getString() + " standing and relationship journal reset; saved memories remain";
            }
            case "forget_episode" -> {
                if (journal == null || !journal.remove(uuid(command, "episodeId"))) throw new IllegalArgumentException("No such episode");
                return "Episode forgotten";
            }
            case "restock" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                villager.getOffers().forEach(net.minecraft.world.item.trading.MerchantOffer::resetUses);
                return villager.getName().getString() + " restocked";
            }
            case "set_ai" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                boolean enabled = command.get("enabled").getAsBoolean();
                villager.setNoAi(!enabled);
                return villager.getName().getString() + " AI " + (enabled ? "on" : "off");
            }
            case "rename" -> {
                Villager villager = loaded(server, uuid(command, "villagerId"));
                String name = command.get("name").getAsString().trim();
                if (name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("Names are 1 to 32 characters");
                villager.setCustomName(Component.literal(name));
                return "Renamed to " + name;
            }
            default -> throw new IllegalArgumentException("Unknown edit " + type);
        }
    }

    void clear() { names.clear(); results.clear(); maps.clear(); busy = false; watching = false; }
}
