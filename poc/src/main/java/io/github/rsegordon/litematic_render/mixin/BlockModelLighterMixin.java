package io.github.rsegordon.litematic_render.mixin;

import io.github.rsegordon.litematic_render.OffscreenRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forces maximum block and sky light only for paper-color chunk geometry. */
@Mixin(BlockModelLighter.class)
public abstract class BlockModelLighterMixin {
    private static final int FULL_BRIGHT = 0x00F000F0;

    @Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
    private void litematicRender$useFullBrightPaperLight(
            BlockState state, BlockAndTintGetter level, BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {
        if (OffscreenRenderer.isPaperFullbright()) cir.setReturnValue(FULL_BRIGHT);
    }
}
