package io.github.rsegordon.litematic_render.mixin;

import io.github.rsegordon.litematic_render.OffscreenRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes every face equally bright only while the paper capture pass is active. */
@Mixin(CardinalLighting.class)
public abstract class CardinalLightingMixin {
    @Inject(method = "byFace", at = @At("HEAD"), cancellable = true)
    private void litematicRender$useFlatPaperLighting(
            Direction direction, CallbackInfoReturnable<Float> cir) {
        if (OffscreenRenderer.isPaperFullbright()) cir.setReturnValue(1.0F);
    }
}
