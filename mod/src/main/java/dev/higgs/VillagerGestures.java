package dev.higgs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/** Short, model-selected reactions. Never moves an entity or changes its reputation. */
final class VillagerGestures {
    private record Gesture(Villager villager, String emotion, int started, float pitch) {}
    private final Map<UUID, Gesture> active = new HashMap<>();
    private final Map<UUID, Integer> last = new HashMap<>();
    boolean show(Villager v, String emotion, int tick) {
        if (!java.util.Set.of("excited", "worried", "angry", "thoughtful", "grateful").contains(emotion)
                || !v.isAlive() || v.isSleeping() || v.isTrading() || tick - last.getOrDefault(v.getUUID(), tick - 200) < 160) return false;
        last.entrySet().removeIf(e -> tick - e.getValue() > 1200);
        last.put(v.getUUID(), tick);
        active.put(v.getUUID(), new Gesture(v, emotion, tick, v.getXRot()));
        if (emotion.equals("excited") && v.onGround()) v.jumpFromGround();
        if (v.level() instanceof ServerLevel level && !emotion.equals("thoughtful") && !emotion.equals("worried"))
            level.sendParticles(emotion.equals("angry") ? ParticleTypes.ANGRY_VILLAGER
                    : emotion.equals("grateful") ? ParticleTypes.HEART : ParticleTypes.HAPPY_VILLAGER,
                v.getX(), v.getEyeY() + 0.3, v.getZ(), 3, 0.15, 0.1, 0.15, 0);
        return true;
    }
    void tick(int tick) {
        active.values().removeIf(g -> {
            var v = g.villager;
            int age = tick - g.started;
            if (age >= 50 || !v.isAlive() || v.isRemoved() || v.isSleeping()) {
                v.setXRot(g.pitch); return true;
            }
            double wave = Math.sin(age * 0.35);
            switch (g.emotion) {
                case "excited" -> v.setXRot((float) (wave * 12));
                case "grateful" -> v.setXRot((float) (6 + wave * 9));
                case "thoughtful" -> v.setXRot((float) (10 + wave * 3));
                case "angry" -> { v.setXRot(-6); v.setYHeadRot(v.getYRot() + (float) wave * 15); }
                case "worried" -> { v.setXRot(5); v.setYHeadRot(v.getYRot() + (float) Math.sin(age * 0.2) * 28); }
            }
            return false;
        });
    }
    void clear() { active.values().forEach(g -> g.villager.setXRot(g.pitch)); active.clear(); last.clear(); }
}
