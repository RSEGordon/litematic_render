package io.github.rsegordon.litematic_render.mixin;

import io.github.rsegordon.litematic_render.OffscreenRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Covers the cached light/AO samples used by ambient-occlusion block quads. */
@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelLighter$Cache")
public abstract class BlockModelLighterCacheMixin {
    private static final int FULL_BRIGHT = 0x00F000F0;

    @Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
    private void litematicRender$useFullBrightPaperLight(
            BlockState state, BlockAndTintGetter level, BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {
        if (OffscreenRenderer.isPaperFullbright()) cir.setReturnValue(FULL_BRIGHT);
    }

    @Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
    private void litematicRender$removePaperAmbientOcclusion(
            BlockState state, BlockAndTintGetter level, BlockPos pos,
            CallbackInfoReturnable<Float> cir) {
        if (OffscreenRenderer.isPaperFullbright()) cir.setReturnValue(1.0F);
    }
}
