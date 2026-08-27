package com.rsegordon.poc.mixin;

import com.rsegordon.poc.OffscreenRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installs the orthographic projection before 26.2 performs frustum extraction. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "extractCamera", at = @At("RETURN"))
    private void litematicRender$applyOrthographicProjection(
            DeltaTracker deltaTracker, float tickDelta, float cameraTickDelta,
            CallbackInfo ci) {
        GameRenderer self = (GameRenderer) (Object) this;
        CameraRenderState camera = self.gameRenderState().levelRenderState.cameraRenderState;
        OffscreenRenderer.applyProjection(camera);
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void litematicRender$diagnoseEntityExtraction(
            DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        GameRenderer self = (GameRenderer) (Object) this;
        OffscreenRenderer.diagnoseEntityExtraction(self.gameRenderState().levelRenderState);
    }
}
