package dev.higgs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Small village moments. A playing jukebox sets nearby villagers dancing, and one of them raps. Ringing a bell brings a
 * few villagers over (instead of sending everyone indoors), and each calls out as it arrives.
 */
final class VillageMoments {
    private static final double DANCE_RADIUS = 12, BELL_RADIUS = 32, ARRIVED = 4;
    private static final long RAP_EVERY_MS = 90_000, BELL_COOLDOWN_MS = 10_000, SUMMON_MS = 30_000;
    private record Summon(BlockPos bell, long deadline, long callAt) {}
    private final VillagerAlarm voice;
    private final Predicate<Villager> talking;
    private final List<Villager> dancers = new ArrayList<>();
    private final Map<BlockPos, Long> lastRap = new HashMap<>();
    private final Map<BlockPos, Long> lastRing = new HashMap<>();
    private final Map<Villager, Summon> summoned = new HashMap<>();

    VillageMoments(VillagerAlarm voice, Predicate<Villager> talking) {
        this.voice = voice;
        this.talking = talking;
    }

    private boolean free(Villager villager) {
        return villager.isAlive() && !villager.isBaby() && !villager.isSleeping() && !villager.isTrading() && !talking.test(villager);
    }

    void tick(MinecraftServer server, int ticks) {
        if (ticks % 20 == 0) findDancers(server);
        if (ticks % 2 == 0) dance(ticks);
        if (ticks % 10 == 0) answerBell(server);
    }

    /** Jukeboxes playing in loaded chunks around players; never loads chunks. */
    private void findDancers(MinecraftServer server) {
        dancers.clear();
        long now = System.currentTimeMillis();
        Set<BlockPos> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            ChunkPos centre = ChunkPos.containing(player.blockPosition());
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (!level.hasChunk(centre.x() + dx, centre.z() + dz)) continue;
                for (var entity : level.getChunk(centre.x() + dx, centre.z() + dz).getBlockEntities().values()) {
                    if (!(entity instanceof JukeboxBlockEntity jukebox) || !jukebox.getSongPlayer().isPlaying() || !seen.add(jukebox.getBlockPos())) continue;
                    List<Villager> nearby = level.getEntitiesOfClass(Villager.class, new AABB(jukebox.getBlockPos()).inflate(DANCE_RADIUS),
                        v -> free(v) && v.distanceToSqr(Vec3.atCenterOf(jukebox.getBlockPos())) <= DANCE_RADIUS * DANCE_RADIUS);
                    dancers.addAll(nearby);
                    // One rapper per record: someone starts a verse soon after the music begins.
                    if (!nearby.isEmpty() && now - lastRap.getOrDefault(jukebox.getBlockPos(), 0L) > RAP_EVERY_MS
                        && voice.shout(server, nearby.get(ThreadLocalRandom.current().nextInt(nearby.size())), "rap"))
                        lastRap.put(jukebox.getBlockPos(), now);
                }
            }
        }
        lastRap.values().removeIf(at -> now - at > RAP_EVERY_MS * 4);
    }

    /** Villagers have no dance animation: a spin, a nod, a hop now and then, and floating notes. */
    private void dance(int ticks) {
        for (Villager villager : dancers) {
            if (!free(villager)) continue;
            float yaw = villager.getYRot() + 24;
            villager.setYRot(yaw);
            villager.setYBodyRot(yaw);
            villager.setYHeadRot(yaw);
            villager.setXRot((float) Math.sin(ticks * 0.6 + villager.getId()) * 20);
            villager.getNavigation().stop();
            if ((ticks + villager.getId()) % 20 == 0 && villager.onGround()) villager.jumpFromGround();
            if ((ticks + villager.getId()) % 10 == 0 && villager.level() instanceof ServerLevel level)
                level.sendParticles(ParticleTypes.NOTE, villager.getX(), villager.getY() + villager.getBbHeight() + 0.4, villager.getZ(),
                    1, 0.3, 0.1, 0.3, ThreadLocalRandom.current().nextDouble());
        }
    }

    /** A player rang a bell: up to three villagers come over to see what the matter is. */
    void bellRung(ServerPlayer player, BlockPos bell) {
        long now = System.currentTimeMillis();
        if (now - lastRing.getOrDefault(bell, 0L) < BELL_COOLDOWN_MS) return;
        lastRing.put(bell, now);
        lastRing.values().removeIf(at -> now - at > BELL_COOLDOWN_MS * 6);
        Vec3 centre = Vec3.atCenterOf(bell);
        List<Villager> nearest = player.level().getEntitiesOfClass(Villager.class, new AABB(bell).inflate(BELL_RADIUS),
                v -> free(v) && !summoned.containsKey(v) && v.distanceToSqr(centre) <= BELL_RADIUS * BELL_RADIUS)
            .stream().sorted(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(centre))).limit(3).toList();
        for (Villager villager : nearest) {
            // Villagers without AI cannot walk over; the ones already close still answer after a moment.
            if (villager.isNoAi() && villager.distanceToSqr(centre) > 36) continue;
            summoned.put(villager, new Summon(bell, now + SUMMON_MS, now + 800 + ThreadLocalRandom.current().nextLong(2000)));
        }
    }

    private void answerBell(MinecraftServer server) {
        long now = System.currentTimeMillis();
        summoned.entrySet().removeIf(entry -> {
            Villager villager = entry.getKey();
            Summon summon = entry.getValue();
            if (!villager.isAlive() || villager.isSleeping() || talking.test(villager) || now > summon.deadline()) return true;
            Vec3 bell = Vec3.atCenterOf(summon.bell());
            if (!villager.isNoAi()) {
                // A rung bell normally sends villagers indoors; these come to see what is the matter instead.
                villager.getBrain().eraseMemory(MemoryModuleType.HEARD_BELL_TIME);
                if (villager.distanceToSqr(bell) > ARRIVED * ARRIVED) {
                    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(summon.bell(), 0.7f, 2));
                    return false;
                }
            }
            if (now < summon.callAt()) return false;
            voice.shout(server, villager, "bell");
            return true;
        });
    }

    void clear() { dancers.clear(); summoned.clear(); lastRap.clear(); lastRing.clear(); }
}
