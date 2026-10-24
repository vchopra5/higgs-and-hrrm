package dev.higgs;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/**
 * Villagers walking with the player while they talk, following or leading the way to a destination. A villager whose AI is disabled (like the demo villagers) gets it
 * back for the walk; afterwards it walks home and is put back exactly where it started, AI off again. Events along the
 * way (the village edge, nightfall, danger, the player running ahead, what is nearby) become spoken cues.
 */
final class VillagerOuting {
    interface Host {
        /** Sends a walking event to the player's live conversation, if any. */
        void event(ServerPlayer player, JsonObject event);
        int standing(ServerPlayer player, Villager villager);
        VillageTracker.Village village(Villager villager);
        int villageRadius();
        void observe(Villager villager, ServerPlayer player, String type, String text, int salience);
        /** The guide brought the player to the villager they asked for: hand the conversation over. */
        void arrived(ServerPlayer player, Villager guide, Villager target);
    }
    private static final long PROGRESS_MS = 10_000, GUIDE_MS = 90_000;
    private static final double WAIT_FOR_PLAYER = 8, ARRIVED = 3;
    private static final long LAG_EVERY_MS = 20_000, SCENERY_EVERY_MS = 45_000, LOST_AFTER_MS = 10_000, HOMEWARD_MS = 60_000;
    private static final double LAGGING = 10, LOST = 16;

    private static final class Outing {
        final Villager villager;
        final ServerPlayer player;
        final boolean wasNoAi;
        final BlockPos home;
        final long started;
        final Set<String> passed = new LinkedHashSet<>();
        Vec3 last;
        double walked;
        // Guiding: where to, and whether it is still getting closer.
        Villager targetVillager;
        BlockPos targetPos;
        String label;
        double best = Double.MAX_VALUE;
        long lastProgress, guideUntil;
        /** Arrived and handed over: stays put until the player's conversation ends. */
        boolean staying;
        boolean guiding() { return label != null; }
        long lastLag, lastScenery, farSince = -1, homewardUntil = -1;
        Outing(Villager villager, ServerPlayer player, long now) {
            this.villager = villager;
            this.player = player;
            this.wasNoAi = villager.isNoAi();
            this.home = villager.blockPosition();
            this.started = now;
            this.last = villager.position();
            this.lastScenery = now;
        }
        boolean homeward() { return homewardUntil >= 0; }
    }

    private final Host host;
    private final Map<UUID, Outing> outings = new HashMap<>();

    VillagerOuting(Host host) { this.host = host; }

    /** Walking with the player or on its way home: either way, leave its movement alone. */
    boolean walking(Villager villager) { return outings.containsKey(villager.getUUID()); }

    boolean following(Villager villager, ServerPlayer player) {
        Outing outing = outings.get(villager.getUUID());
        return outing != null && !outing.homeward() && outing.player == player;
    }

    /** Starts a walk; returns why the villager will not come, or null when it sets off. */
    String follow(ServerPlayer player, Villager villager) {
        String refusal = refusal(player, villager);
        if (refusal != null) return refusal;
        Outing outing = begin(player, villager);
        outing.label = null; // Following, not guiding.
        outing.targetVillager = null;
        outing.targetPos = null;
        return null; // The tool result already cues what to say as it sets off.
    }

    private String refusal(ServerPlayer player, Villager villager) {
        if (Negotiation.band(host.standing(player, villager)).name().matches("annoyed|hostile"))
            return "You do not like this player enough to go walking with them. Decline in character.";
        if (HiggsVillagers.bedtime(villager)) return "It is getting dark and you are going home to bed. Decline kindly.";
        if (VillagerAlarm.chased(villager)) return "A zombie is after you; you are not going anywhere but home.";
        return null;
    }

    private Outing begin(ServerPlayer player, Villager villager) {
        Outing current = outings.get(villager.getUUID());
        Outing outing = current != null ? current : new Outing(villager, player, System.currentTimeMillis());
        outing.homewardUntil = -1;
        outing.staying = false;
        outings.put(villager.getUUID(), outing);
        if (villager.isNoAi()) villager.setNoAi(false);
        return outing;
    }

    /** Leads the player to a villager or a place; returns why it will not, or null when it sets off. */
    String guide(ServerPlayer player, Villager villager, Villager targetVillager, BlockPos targetPos, String label) {
        String refusal = refusal(player, villager);
        if (refusal != null) return refusal;
        Outing outing = begin(player, villager);
        long now = System.currentTimeMillis();
        outing.targetVillager = targetVillager;
        outing.targetPos = targetPos;
        outing.label = label;
        outing.best = Double.MAX_VALUE;
        outing.lastProgress = now;
        outing.guideUntil = now + GUIDE_MS;
        return null;
    }

    /** The player's conversation is over: every walk with them ends, including a guide that stayed after a hand-over. */
    void release(ServerPlayer player) {
        for (Outing outing : List.copyOf(outings.values())) {
            if (outing.player == player && !outing.homeward()) stopNow(outing, null);
        }
    }

    /** A hand-over is about to end the guide's own conversation: keep it standing there for now. */
    void stay(Villager villager) {
        Outing outing = outings.get(villager.getUUID());
        if (outing != null) outing.staying = true;
    }

    /** Ends the walk: the villager heads home; a villager without AI is put back at its post. */
    void stop(Villager villager, String cue) {
        Outing outing = outings.get(villager.getUUID());
        if (outing == null || outing.homeward()) return;
        if (cue != null) host.event(outing.player, cue.equals("walk_boundary") ? place(event(cue), villager) : event(cue));
        remember(outing);
        if (!outing.wasNoAi) { outings.remove(villager.getUUID()); return; }
        outing.homewardUntil = System.currentTimeMillis() + HOMEWARD_MS;
    }

    void tick(int ticks) {
        if (ticks % 5 != 0) return;
        long now = System.currentTimeMillis();
        for (Iterator<Outing> it = outings.values().iterator(); it.hasNext();) {
            Outing outing = it.next();
            Villager villager = outing.villager;
            if (!villager.isAlive()) { it.remove(); continue; }
            if (outing.homeward()) {
                if (villager.blockPosition().distSqr(outing.home) <= 4 || now > outing.homewardUntil) {
                    settle(outing);
                    it.remove();
                } else steer(villager, Vec3.atBottomCenterOf(outing.home), 0.6f);
                continue;
            }
            ServerPlayer player = outing.player;
            Vec3 here = villager.position();
            outing.walked += here.distanceTo(outing.last);
            outing.last = here;
            double distance = Math.sqrt(villager.distanceToSqr(player));
            if (outing.staying) { villager.getNavigation().stop(); continue; }
            if (outing.guiding()) { lead(outing, distance, now); continue; }
            steer(villager, player, distance > 6 ? 0.85f : 0.6f);
            if (!player.isAlive() || player.level() != villager.level()) { stopNow(outing, null); continue; }
            VillageTracker.Village village = host.village(villager);
            if (village == null || Math.hypot(here.x - village.x(), here.z - village.z()) > host.villageRadius()) {
                stopNow(outing, "walk_boundary");
                continue;
            }
            if (HiggsVillagers.bedtime(villager)) { stopNow(outing, "walk_night"); continue; }
            if (VillagerAlarm.chased(villager)) { stopNow(outing, "walk_danger"); continue; }
            if (distance > LOST) {
                if (outing.farSince < 0) outing.farSince = now;
                if (now - outing.farSince > LOST_AFTER_MS) { stopNow(outing, null); continue; }
            } else outing.farSince = -1;
            if (distance > LAGGING && now - outing.lastLag > LAG_EVERY_MS) {
                outing.lastLag = now;
                host.event(player, event("walk_lagging"));
            }
            note(outing);
            if (now - outing.lastScenery > SCENERY_EVERY_MS && outing.walked > 8) {
                outing.lastScenery = now;
                host.event(player, scenery(outing));
            }
        }
    }

    /** Leading the way: waits for a player who falls behind, gives up if it stops getting closer, hands over on arrival. */
    private void lead(Outing outing, double playerDistance, long now) {
        Villager villager = outing.villager;
        ServerPlayer player = outing.player;
        if (outing.targetVillager != null && !outing.targetVillager.isAlive()) { fail(outing); return; }
        Vec3 destination = outing.targetVillager != null ? outing.targetVillager.position() : Vec3.atBottomCenterOf(outing.targetPos);
        double remaining = Math.hypot(destination.x - villager.getX(), destination.z - villager.getZ());
        if (remaining <= ARRIVED && playerDistance > 6) {
            // At the destination: wait for the player to catch up before announcing it.
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(player, 30, 30);
            return;
        }
        if (remaining <= ARRIVED) {
            if (outing.targetVillager != null) {
                outing.staying = true;
                host.observe(villager, player, "guided", "I showed " + player.getName().getString() + " the way to " + outing.label, 3);
                host.arrived(player, villager, outing.targetVillager);
            } else {
                JsonObject arrived = event("guide_arrived");
                arrived.addProperty("place", outing.label);
                host.event(player, arrived);
                host.observe(villager, player, "guided", "I showed " + player.getName().getString() + " the way to " + outing.label, 3);
                outing.label = null; // Arrived: no further memory of a walk to write.
                stopNow(outing, null);
            }
            return;
        }
        if (playerDistance > WAIT_FOR_PLAYER) {
            // Stop and wait; the clock that detects being stuck pauses while waiting.
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(player, 30, 30);
            outing.lastProgress = now;
            if (now - outing.lastLag > LAG_EVERY_MS) {
                outing.lastLag = now;
                JsonObject lagging = event("walk_lagging");
                lagging.addProperty("guiding", true);
                host.event(player, lagging);
            }
            return;
        }
        if (outing.targetVillager != null) steer(villager, outing.targetVillager.position(), 0.6f);
        else steer(villager, destination, 0.6f);
        if (remaining < outing.best - 0.5) { outing.best = remaining; outing.lastProgress = now; }
        if (now - outing.lastProgress > PROGRESS_MS || now > outing.guideUntil) fail(outing);
    }

    /** Lost the way: apologise and give directions from here instead. */
    private void fail(Outing outing) {
        Villager villager = outing.villager;
        JsonObject failed = event("guide_failed");
        failed.addProperty("place", outing.label.length() > 40 ? outing.label.substring(0, 40) : outing.label);
        Vec3 destination = outing.targetVillager != null && outing.targetVillager.isAlive() ? outing.targetVillager.position()
            : outing.targetPos != null ? Vec3.atBottomCenterOf(outing.targetPos) : villager.position();
        double dx = destination.x - outing.player.getX(), dz = destination.z - outing.player.getZ();
        failed.addProperty("details", "about " + Math.round(Math.hypot(dx, dz)) + " blocks " + Errands.compass(dx, dz) + " of the player");
        host.event(outing.player, failed);
        outing.label = null;
        stopNow(outing, null);
    }

    /** Called from the tick loop: stop() would modify the map we are iterating, so the outing is updated in place. */
    private void stopNow(Outing outing, String cue) {
        if (cue != null) host.event(outing.player, cue.equals("walk_boundary") ? place(event(cue), outing.villager) : event(cue));
        remember(outing);
        outing.homewardUntil = outing.wasNoAi ? System.currentTimeMillis() + HOMEWARD_MS : 0;
    }

    /** Villagers passed on the way, for the scenery cues and the memory of the walk. */
    private void note(Outing outing) {
        for (Villager other : outing.villager.level().getEntitiesOfClass(Villager.class, outing.villager.getBoundingBox().inflate(8),
                v -> v != outing.villager && v.isAlive() && v.hasCustomName())) {
            if (outing.passed.size() < 8) outing.passed.add(other.getName().getString() + " the " + VillagerMemoryService.profession(other));
        }
    }

    private JsonObject scenery(Outing outing) {
        StringBuilder details = new StringBuilder();
        Villager villager = outing.villager;
        villager.level().getEntitiesOfClass(Villager.class, villager.getBoundingBox().inflate(8), v -> v != villager && v.isAlive() && v.hasCustomName())
            .stream().min(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(villager)))
            .ifPresent(near -> details.append(near.getName().getString()).append(" the ").append(VillagerMemoryService.profession(near)).append(" is close by; "));
        VillageTracker.Village village = host.village(villager);
        if (village != null) details.append("you are in ").append(village.name()).append("; ");
        details.append("you have walked about ").append(Math.round(outing.walked)).append(" blocks together");
        JsonObject event = event("walk_scenery");
        event.addProperty("details", details.length() > 300 ? details.substring(0, 300) : details.toString());
        return event;
    }

    private void remember(Outing outing) {
        long blocks = Math.round(outing.walked);
        if (blocks < 4 || outing.guiding()) return; // Guided walks are remembered as "guided" on arrival.
        VillageTracker.Village village = host.village(outing.villager);
        String through = village == null ? "" : " through " + village.name();
        String past = outing.passed.isEmpty() ? "" : ", past " + String.join(", ", outing.passed);
        host.observe(outing.villager, outing.player, "outing", "I walked with " + outing.player.getName().getString() + through + past
            + " (about " + blocks + " blocks)", blocks > 80 ? 4 : 3);
    }

    /** Back at its post: a villager that had no AI is set down exactly there, with AI off again. */
    private static void settle(Outing outing) {
        Villager villager = outing.villager;
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (!outing.wasNoAi) return;
        villager.getNavigation().stop();
        villager.teleportTo(outing.home.getX() + 0.5, outing.home.getY(), outing.home.getZ() + 0.5);
        villager.setNoAi(true);
    }

    private static void steer(Villager villager, Object target, float speed) {
        WalkTarget walk = target instanceof ServerPlayer player ? new WalkTarget(player, speed, 2) : new WalkTarget((Vec3) target, speed, 1);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walk);
    }

    private JsonObject place(JsonObject event, Villager villager) {
        VillageTracker.Village village = host.village(villager);
        if (village != null) event.addProperty("place", village.name());
        return event;
    }

    private static JsonObject event(String type) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        return event;
    }

    /** Server stopping: everyone is put straight back where they started. */
    void clear() {
        for (Outing outing : outings.values()) if (outing.villager.isAlive()) settle(outing);
        outings.clear();
    }
}
