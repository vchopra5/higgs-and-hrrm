package dev.higgs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Small village moments. A playing jukebox sets nearby villagers dancing, and one of them raps. Ringing a bell brings a
 * few villagers over (instead of sending everyone indoors), and each calls out as it arrives. Fireworks draw an "ooh" and
 * applause from friends. Children play tag by day and are called home at dusk. Villagers remark on the player's pets.
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
    private final ToIntBiFunction<ServerPlayer, Villager> standing;
    private final Set<UUID> seenRockets = new HashSet<>();
    private final Map<Villager, Long> gazing = new HashMap<>();
    private long nextFireworkCheer;
    /** A game of tag between two children: "it" chases, the runner flees, and a tag swaps them. */
    private static final class Tag { Villager it, runner; long until; Tag(Villager it, Villager runner, long until) { this.it = it; this.runner = runner; this.until = until; } }
    private final List<Tag> games = new ArrayList<>();
    private final Set<String> calledHome = new HashSet<>();
    private final Map<UUID, Long> petCooldown = new HashMap<>();
    private long nextPetRemark;

    VillageMoments(VillagerAlarm voice, Predicate<Villager> talking, ToIntBiFunction<ServerPlayer, Villager> standing) {
        this.voice = voice;
        this.talking = talking;
        this.standing = standing;
    }

    private boolean free(Villager villager) {
        return villager.isAlive() && !villager.isBaby() && !villager.isSleeping() && !villager.isTrading() && !talking.test(villager);
    }

    void tick(MinecraftServer server, int ticks) {
        if (ticks % 20 == 0) findDancers(server);
        if (ticks % 2 == 0) dance(ticks);
        if (ticks % 10 == 0) answerBell(server);
        if (ticks % 5 == 0) fireworks(server);
        if (ticks % 10 == 0) children(server, ticks);
        if (ticks % 40 == 0) pets(server);
    }

    /** New rockets near players: villagers look up, one cheers, and friends of whoever launched it clap. */
    private void fireworks(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Set<UUID> alive = new HashSet<>();
        gazing.entrySet().removeIf(entry -> now > entry.getValue());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (FireworkRocketEntity rocket : player.level().getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(32))) {
                alive.add(rocket.getUUID());
                if (!seenRockets.add(rocket.getUUID())) continue;
                ServerPlayer launcher = rocket.getOwner() instanceof ServerPlayer owner ? owner : player;
                List<Villager> watchers = player.level().getEntitiesOfClass(Villager.class, rocket.getBoundingBox().inflate(24),
                    v -> v.isAlive() && !v.isSleeping() && !talking.test(v));
                for (Villager watcher : watchers) {
                    gazing.put(watcher, now + 3000);
                    if (!watcher.isBaby() && Negotiation.band(standing.applyAsInt(launcher, watcher)).name().equals("friendly")) {
                        // Applause: a happy hop and sparkles from villagers who like the launcher.
                        if (watcher.onGround()) watcher.jumpFromGround();
                        if (watcher.level() instanceof ServerLevel level)
                            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, watcher.getX(), watcher.getY() + watcher.getBbHeight(), watcher.getZ(), 6, 0.4, 0.3, 0.4, 0);
                    }
                }
                List<Villager> adults = watchers.stream().filter(v -> !v.isBaby() && free(v)).toList();
                if (!adults.isEmpty() && now >= nextFireworkCheer
                    && voice.shout(server, adults.get(ThreadLocalRandom.current().nextInt(adults.size())), "fireworks"))
                    nextFireworkCheer = now + 8000;
            }
        }
        seenRockets.retainAll(alive);
        // Heads tilt up to the sky while the show lasts.
        for (Villager watcher : gazing.keySet()) watcher.setXRot(-50);
    }

    private static boolean dusk(Villager villager) {
        long time = Math.floorMod(villager.level().getOverworldClockTime(), 24000L);
        return time >= 11500 && time < 13000;
    }

    /** Children play tag by day; at dusk an adult calls them home. Only children whose AI runs can play. */
    private void children(MinecraftServer server, int ticks) {
        long now = System.currentTimeMillis();
        games.removeIf(game -> now > game.until || !game.it.isAlive() || !game.runner.isAlive() || dusk(game.it) || HiggsVillagers.bedtime(game.it));
        for (Tag game : games) {
            if (game.it.distanceToSqr(game.runner) < 2.25) {
                // Tagged: swap roles, with a giggle.
                Villager tagged = game.runner;
                game.runner = game.it;
                game.it = tagged;
                tagged.playSound(SoundEvents.VILLAGER_YES, 0.6f, 1.8f);
                if (tagged.level() instanceof ServerLevel level)
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, tagged.getX(), tagged.getY() + tagged.getBbHeight(), tagged.getZ(), 4, 0.3, 0.2, 0.3, 0);
            }
            game.it.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(game.runner, 0.9f, 0));
            Vec3 away = game.runner.position().subtract(game.it.position()).multiply(1, 0, 1);
            if (away.lengthSqr() > 0.01) game.runner.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(game.runner.position().add(away.normalize().scale(5)), 0.85f, 1));
        }
        if (ticks % 100 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            List<Villager> kids = player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(32),
                v -> v.isBaby() && v.isAlive() && !v.isSleeping() && !v.isNoAi() && games.stream().noneMatch(g -> g.it == v || g.runner == v));
            if (kids.isEmpty()) continue;
            if (dusk(kids.getFirst())) {
                callHome(server, player, kids);
                continue;
            }
            if (HiggsVillagers.bedtime(kids.getFirst())) continue;
            for (int i = 0; i + 1 < kids.size(); i += 2) {
                if (kids.get(i).distanceToSqr(kids.get(i + 1)) <= 100 && ThreadLocalRandom.current().nextBoolean())
                    games.add(new Tag(kids.get(i), kids.get(i + 1), now + 60_000));
            }
        }
    }

    /** Once an evening per area, the nearest adult calls the children in and they run to it. */
    private void callHome(MinecraftServer server, ServerPlayer player, List<Villager> kids) {
        Villager first = kids.getFirst();
        String key = Math.floorDiv(first.level().getOverworldClockTime(), 24000L) + ":" + ChunkPos.containing(first.blockPosition()).x() / 4
            + ":" + ChunkPos.containing(first.blockPosition()).z() / 4;
        if (!calledHome.add(key)) return;
        if (calledHome.size() > 64) calledHome.clear();
        Villager parent = player.level().getEntitiesOfClass(Villager.class, first.getBoundingBox().inflate(24), v -> !v.isBaby() && free(v))
            .stream().min(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(first))).orElse(null);
        if (parent == null || !voice.shout(server, parent, "callhome")) return;
        for (Villager kid : kids) if (kid.distanceToSqr(parent) <= 32 * 32)
            kid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(parent, 0.9f, 2));
    }

    /** A villager meeting the player's tamed pet says something about it; some are nervous of wolves and step back. */
    private void pets(MinecraftServer server) {
        long now = System.currentTimeMillis();
        petCooldown.values().removeIf(until -> until < now);
        if (now < nextPetRemark) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (TamableAnimal pet : player.level().getEntitiesOfClass(TamableAnimal.class, player.getBoundingBox().inflate(16),
                    animal -> animal.isAlive() && animal.isTame() && animal.isOwnedBy(player))) {
                Villager villager = player.level().getEntitiesOfClass(Villager.class, pet.getBoundingBox().inflate(4),
                        v -> free(v) && !petCooldown.containsKey(v.getUUID()))
                    .stream().min(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(pet))).orElse(null);
                if (villager == null) continue;
                String kind = pet.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
                String detail = pet.hasCustomName() ? kind + " named " + pet.getCustomName().getString() : kind;
                if (detail.length() > 60) detail = kind;
                // A fixed trait: about one villager in four is nervous of wolves.
                boolean nervous = pet instanceof net.minecraft.world.entity.animal.wolf.Wolf
                    && Math.floorMod(villager.getUUID().getLeastSignificantBits() ^ 0x5bd1e995L, 4L) == 0;
                if (!voice.shout(server, villager, nervous ? "pet_scared" : "pet", detail)) return;
                if (nervous && !villager.isNoAi()) {
                    Vec3 away = villager.position().subtract(pet.position()).multiply(1, 0, 1);
                    if (away.lengthSqr() > 0.01) villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                        new WalkTarget(villager.position().add(away.normalize().scale(5)), 0.7f, 1));
                }
                petCooldown.put(villager.getUUID(), now + 5 * 60_000);
                nextPetRemark = now + 30_000;
                return;
            }
        }
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

    void clear() {
        dancers.clear(); summoned.clear(); lastRap.clear(); lastRing.clear(); seenRockets.clear(); gazing.clear();
        games.clear(); calledHome.clear(); petCooldown.clear();
    }
}
