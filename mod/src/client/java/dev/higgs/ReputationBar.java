package dev.higgs;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/** Bar above nearby villagers showing their standing towards this player, coloured by price band. */
final class ReputationBar {
    static final int CELLS = 10;
    private static final double MAX_DISTANCE_SQUARED = 16 * 16;

    static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null || client.options.hideGui) return;
            CameraRenderState camera = context.levelState().cameraRenderState;
            float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            PoseStack pose = context.poseStack();
            for (Entity entity : client.level.entitiesForRendering()) {
                if (!(entity instanceof Villager villager) || villager.isBaby() || villager.isInvisible()) continue;
                Integer standing = VillagerExpressions.standing(villager.getUUID());
                if (standing == null) continue;
                Vec3 position = villager.getPosition(partialTick);
                double distance = position.distanceToSqr(camera.pos);
                if (distance > MAX_DISTANCE_SQUARED) continue;
                // Camera-relative like entity renderers; the name tag path billboards it.
                pose.pushPose();
                pose.translate(position.x - camera.pos.x, position.y - camera.pos.y, position.z - camera.pos.z);
                context.submitNodeCollector().submitNameTag(pose, new Vec3(0, villager.getBbHeight() + 0.75, 0), 0,
                    bar(standing), false, LightCoordsUtil.FULL_BRIGHT, distance, camera);
                pose.popPose();
            }
        });
    }

    /** Standing -5..5 fills 0..10 cells; red when annoyed, yellow neutral, green friendly. */
    static Component bar(int standing) {
        int filled = Math.max(0, Math.min(CELLS, standing + 5));
        ChatFormatting colour = standing <= -2 ? ChatFormatting.RED : standing >= 2 ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        return Component.literal("■".repeat(filled)).withStyle(colour)
            .append(Component.literal("■".repeat(CELLS - filled)).withStyle(ChatFormatting.DARK_GRAY));
    }
}
