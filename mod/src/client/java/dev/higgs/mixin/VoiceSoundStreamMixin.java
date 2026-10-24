package dev.higgs.mixin;

import dev.higgs.SpatialVoice;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies live PCM only for our generated stream identifiers; ordinary sounds are untouched. */
@Mixin(SoundBufferLibrary.class)
public class VoiceSoundStreamMixin {
    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void higgs$stream(Identifier path, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        AudioStream stream = SpatialVoice.stream(path);
        if (stream != null) cir.setReturnValue(CompletableFuture.completedFuture(stream));
    }
}
