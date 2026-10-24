package dev.higgs;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;

/**
 * Finds the nearest place a rumour points to: a biome or a structure, searched outward from the village. Searches are
 * bounded and cached per village, since the world does not move: asking twice costs nothing.
 */
final class Wilderness {
    /** Biomes within 1024 blocks; structures within 48 chunks (buried treasure, which needs many checks, 32). */
    private static final int BIOME_RADIUS = 1024, BIOME_STEP = 32, STRUCTURE_CHUNKS = 48, TREASURE_CHUNKS = 32;
    private final Map<String, Optional<BlockPos>> cache = new HashMap<>();

    /** origin: where to search from (the village centre); ownVillage: the centre to skip when looking for another village. */
    Optional<BlockPos> locate(ServerLevel level, Wilds.Place place, BlockPos origin, BlockPos ownVillage, int villageRadius) {
        String key = level.dimension().identifier() + "|" + (origin.getX() >> 6) + "," + (origin.getZ() >> 6) + "|" + place.kind() + ":" + place.id();
        Optional<BlockPos> known = cache.get(key);
        if (known != null) return known;
        Optional<BlockPos> found = Optional.ofNullable(search(level, place, origin, ownVillage, villageRadius));
        if (cache.size() > 256) cache.clear();
        cache.put(key, found);
        return found;
    }

    private static BlockPos search(ServerLevel level, Wilds.Place place, BlockPos origin, BlockPos ownVillage, int villageRadius) {
        Identifier id = Identifier.withDefaultNamespace(place.id());
        try {
            return switch (place.kind()) {
                case BIOME -> {
                    var biome = ResourceKey.create(Registries.BIOME, id);
                    var found = level.findClosestBiome3d(holder -> holder.is(biome), origin, BIOME_RADIUS, BIOME_STEP, 64);
                    yield found == null ? null : found.getFirst();
                }
                case BIOME_TAG -> {
                    var tag = TagKey.create(Registries.BIOME, id);
                    var found = level.findClosestBiome3d(holder -> holder.is(tag), origin, BIOME_RADIUS, BIOME_STEP, 64);
                    yield found == null ? null : found.getFirst();
                }
                case STRUCTURE -> {
                    var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, id));
                    if (structure.isEmpty()) yield null;
                    var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(structure.get()), origin,
                        place.id().equals("buried_treasure") ? TREASURE_CHUNKS : STRUCTURE_CHUNKS, false);
                    yield found == null ? null : found.getFirst();
                }
                case STRUCTURE_TAG -> level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id), origin, STRUCTURE_CHUNKS, false);
                case OTHER_VILLAGE -> otherVillage(level, origin, ownVillage, villageRadius);
            };
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not search for {}: {}", place.id(), failure.toString());
            return null;
        }
    }

    /** The nearest village start that is not this one: searched from just outside the village in four directions. */
    private static BlockPos otherVillage(ServerLevel level, BlockPos origin, BlockPos own, int villageRadius) {
        var tag = TagKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("village"));
        int away = villageRadius + 160, beyond = villageRadius + 48;
        BlockPos best = null;
        for (int[] step : new int[][] {{0, 0}, {away, 0}, {-away, 0}, {0, away}, {0, -away}}) {
            BlockPos found = level.findNearestMapStructure(tag, origin.offset(step[0], 0, step[1]), 24, false);
            if (found == null || horizontal(found, own) <= beyond) continue;
            if (best == null || horizontal(found, origin) < horizontal(best, origin)) best = found;
        }
        return best;
    }

    static double horizontal(BlockPos a, BlockPos b) { return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()); }

    void clear() { cache.clear(); }
}
