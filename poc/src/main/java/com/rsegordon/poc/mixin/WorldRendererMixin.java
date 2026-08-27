package com.rsegordon.poc.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.rsegordon.poc.BackgroundPass;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes atmospheric passes and controls the framebuffer clear for alpha matting. */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void litematicRender$setMatteBackground(
            GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
            boolean renderBlockOutline, CameraRenderState camera, Matrix4fc modelView,
            GpuBufferSlice projection, Vector4f clearColor, boolean renderSky,
            CallbackInfo ci) {
        float background = BackgroundPass.isWhite() ? 1.0f : 0.0f;
        clearColor.set(background, background, background, 1.0f);
    }

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void litematicRender$cancelSky(
            FrameGraphBuilder frameGraph, CameraRenderState camera,
            GpuBufferSlice projection, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
    private void litematicRender$cancelClouds(
            FrameGraphBuilder frameGraph, CloudStatus cloudStatus, Vec3 cameraPosition,
            long gameTime, float tickDelta, int cloudColor, float cloudHeight,
            int cloudRange, CallbackInfo ci) {
        ci.cancel();
    }
}
