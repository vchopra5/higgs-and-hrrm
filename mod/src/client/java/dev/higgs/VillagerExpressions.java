package dev.higgs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-viewer reputation; drives the optional Fresh Animations expression overlay. */
public final class VillagerExpressions {
    private record Opinion(String face, int standing, long expires) {}
    private static final Map<UUID, Opinion> opinions = new HashMap<>();
    public static void update(UUID villager, String face, int standing) {
        opinions.entrySet().removeIf(entry -> entry.getValue().expires() < System.currentTimeMillis());
        opinions.put(villager, new Opinion(face, standing, System.currentTimeMillis() + 3000));
    }
    public static void clear() { opinions.clear(); }
    public static String face(UUID villager, boolean hurt) {
        if (hurt) return "hurt";
        Opinion opinion = opinions.get(villager);
        return opinion == null || opinion.expires() < System.currentTimeMillis() ? "neutral" : opinion.face();
    }
    /** Standing from -5 to 5 towards this player, or null when the server has not reported one recently. */
    public static Integer standing(UUID villager) {
        Opinion opinion = opinions.get(villager);
        return opinion == null || opinion.expires() < System.currentTimeMillis() ? null : opinion.standing();
    }
    /** Optional integration: Fresh Animations owns the geometry/textures. */
    public static void initializeAnimations() {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("entity_model_features")) return;
        try {
            Class<?> api = Class.forName("traben.entity_model_features.EMFAnimationApi");
            var currentEntity = api.getMethod("getCurrentEntity");
            java.util.function.Supplier<Float> reputation = () -> {
                try {
                    Object entity = currentEntity.invoke(null);
                    if (entity instanceof net.minecraft.world.entity.npc.villager.Villager villager) {
                        return switch (face(villager.getUUID(), false)) {
                            case "friendly" -> 1f;
                            case "annoyed" -> -1f;
                            default -> 0f;
                        };
                    }
                } catch (ReflectiveOperationException ignored) { }
                return 0f;
            };
            api.getMethod("registerSingletonAnimationVariable", String.class, String.class, String.class,
                java.util.function.Supplier.class).invoke(null, "Higgs Villagers", "higgs_reputation",
                    "Player-specific trade reputation: friendly 1, neutral 0, annoyed -1", reputation);
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").info("Fresh Animations reputation variable registered");
        } catch (ReflectiveOperationException error) {
            org.slf4j.LoggerFactory.getLogger("HiggsVillagers").warn("Could not register Fresh Animations reputation variable", error);
        }
    }
}
