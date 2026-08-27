package com.rsegordon.poc.mixin;

import com.rsegordon.poc.OffscreenRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves fluid colors while removing world-light darkness in paper captures. */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    private static final int FULL_BRIGHT = 0x00F000F0;

    @Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
    private void litematicRender$useFullBrightPaperLight(
            BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (OffscreenRenderer.isPaperFullbright()) cir.setReturnValue(FULL_BRIGHT);
    }
}
