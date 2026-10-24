package dev.higgs.mixin;

import dev.higgs.ConversationOutline;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overrides the outline colour after vanilla extraction, so it also replaces team-coloured glowing. */
@Mixin(EntityRenderer.class)
abstract class ConversationOutlineMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void higgs$outlinePartner(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (ConversationOutline.isPartner(entity.getId())) state.outlineColor = ConversationOutline.COLOUR;
    }
}
