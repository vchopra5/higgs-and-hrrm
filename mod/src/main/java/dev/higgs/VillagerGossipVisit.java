package dev.higgs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;

/** One visible gossip visit at a time: approach, face, Realtime exchange, durable memory, reaction. */
final class VillagerGossipVisit implements VillagerMemoryService.GossipPresentation {
    private final VillagerChatter chatter;
    private final Predicate<Villager> occupied;
    private final boolean voice;
    private final java.util.function.BiConsumer<Villager, Boolean> activity;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private Visit active;

    private static final class Visit {
        final MinecraftServer server;
        final Villager source, target;
        final VillagerMemoryStore.Memory story;
        final BooleanSupplier valid;
        final Supplier<CompletableFuture<Boolean>> deliver;
        final GossipEncounter encounter;
        final boolean sourceNoAi, targetNoAi;
        boolean voiceStarted, reacted;
        Visit(MinecraftServer server, Villager source, Villager target, VillagerMemoryStore.Memory story,
              BooleanSupplier valid, Supplier<CompletableFuture<Boolean>> deliver, long now) {
            this.server = server; this.source = source; this.target = target; this.story = story;
            this.valid = valid; this.deliver = deliver; encounter = new GossipEncounter(now);
            sourceNoAi = source.isNoAi(); targetNoAi = target.isNoAi();
        }
    }

    VillagerGossipVisit(VillagerChatter chatter, Predicate<Villager> occupied, boolean voice,
                       java.util.function.BiConsumer<Villager, Boolean> activity) {
        this.chatter = chatter; this.occupied = occupied; this.voice = voice; this.activity = activity;
    }

    boolean busy(Villager villager) { return active != null && (active.source == villager || active.target == villager); }

    boolean readyForDeparture(Villager source) {
        return active == null && chatter.availableForGossip()
            && cooldowns.getOrDefault(source.getUUID(), 0L) <= System.currentTimeMillis();
    }
    boolean readyForListener(Villager target) {
        return active == null && chatter.availableForGossip() && free(target)
            && cooldowns.getOrDefault(target.getUUID(), 0L) <= System.currentTimeMillis();
    }

    private boolean free(Villager villager) {
        return villager.isAlive() && !villager.isRemoved() && !villager.isBaby() && !villager.isSleeping()
            && !villager.isTrading() && !HiggsVillagers.bedtime(villager) && !VillagerAlarm.chased(villager)
            && !occupied.test(villager) && villager.level() instanceof ServerLevel level
            && level.isPositionEntityTicking(villager.blockPosition());
    }

    @Override public boolean offer(MinecraftServer server, Villager source, Villager target, VillagerMemoryStore.Memory story,
                                   BooleanSupplier valid, Supplier<CompletableFuture<Boolean>> deliver) {
        long now = System.currentTimeMillis();
        if (active != null || !chatter.availableForGossip() || !free(source) || !free(target) || !valid.getAsBoolean()) return false;
        cooldowns.values().removeIf(until -> until <= now);
        if (cooldowns.containsKey(source.getUUID()) || cooldowns.containsKey(target.getUUID())) return false;
        active = new Visit(server, source, target, story, valid, deliver, now);
        // Authored NoAI villagers can take part, then regain their original AI setting on release.
        source.setNoAi(false);
        activity.accept(source, true); activity.accept(target, true);
        if (story.interest().score() >= 80 && source.onGround()) source.jumpFromGround();
        face(source, target);
        return true;
    }

    void tick(int ticks) {
        if (ticks % 5 != 0 || active == null) return;
        Visit visit = active;
        if (ticks % 20 == 0) { activity.accept(visit.source, true); activity.accept(visit.target, true); }
        boolean available = free(visit.source) && free(visit.target) && visit.source.level() == visit.target.level();
        var action = visit.encounter.tick(System.currentTimeMillis(), available, visit.valid.getAsBoolean(),
            visit.source.distanceToSqr(visit.target), visit.source.hasLineOfSight(visit.target));
        switch (action) {
            case APPROACH -> {
                hold(visit.target); face(visit.target, visit.source);
                face(visit.source, visit.target);
                visit.source.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(visit.target, GossipEncounter.speed(visit.story.interest().score()), 1));
            }
            case START_VOICE -> {
                facePair(visit);
                visit.voiceStarted = voice;
                var spoken = voice ? chatter.whisper(visit.server, visit.source, visit.target, visit.story)
                    : CompletableFuture.completedFuture(false);
                spoken.whenComplete((ok, error) -> visit.server.execute(() -> {
                    if (active == visit) visit.encounter.voiceDone();
                }));
            }
            case FACE -> facePair(visit);
            case DELIVER -> {
                facePair(visit);
                visit.deliver.get().whenComplete((ok, error) -> visit.server.execute(() -> {
                    if (active == visit) visit.encounter.saved(error == null && Boolean.TRUE.equals(ok), System.currentTimeMillis());
                }));
            }
            case REACT -> {
                facePair(visit);
                if (!visit.reacted) {
                    visit.reacted = true;
                    particles(visit.source); particles(visit.target);
                    if (visit.story.interest().score() >= 80 && visit.target.onGround()) visit.target.jumpFromGround();
                }
                visit.target.setXRot((float) Math.sin(ticks * 0.5) * 12);
            }
            case FINISH, CANCEL -> release();
        }
    }

    private static void hold(Villager villager) {
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getNavigation().stop();
    }
    private static void facePair(Visit visit) {
        hold(visit.source); hold(visit.target);
        face(visit.source, visit.target); face(visit.target, visit.source);
    }
    private static void face(Villager villager, Villager other) {
        double dx = other.getX() - villager.getX(), dz = other.getZ() - villager.getZ();
        if (dx * dx + dz * dz < 0.001) return;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float turn = net.minecraft.util.Mth.approachDegrees(villager.getYRot(), yaw, 20);
        villager.setYRot(turn); villager.setYBodyRot(turn); villager.setYHeadRot(turn);
        villager.getLookControl().setLookAt(other, 30, 30);
    }
    private static void particles(Villager villager) {
        if (villager.level() instanceof ServerLevel level)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getEyeY() + 0.4, villager.getZ(),
                5, 0.2, 0.15, 0.2, 0);
    }
    private void release() {
        Visit visit = active; active = null;
        if (visit == null) return;
        if (visit.voiceStarted) chatter.cancelWhisper(visit.source, visit.target);
        activity.accept(visit.source, false); activity.accept(visit.target, false);
        hold(visit.source); hold(visit.target);
        visit.source.setNoAi(visit.sourceNoAi); visit.target.setNoAi(visit.targetNoAi);
        visit.source.setXRot(0); visit.target.setXRot(0);
        long next = System.currentTimeMillis() + VillagerGossip.pacedDelay(visit.story.interest().score(), 30_000);
        cooldowns.put(visit.source.getUUID(), next); cooldowns.put(visit.target.getUUID(), next);
    }
    void clear() { release(); cooldowns.clear(); }
}
