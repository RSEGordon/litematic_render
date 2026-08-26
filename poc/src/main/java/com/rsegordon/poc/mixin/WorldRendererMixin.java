package com.rsegordon.poc.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.rsegordon.poc.BackgroundPass;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void litematicRender$transparentSky(
            Matrix4f positionMatrix, Matrix4f projectionMatrix, float tickDelta,
            Camera camera, boolean thickFog, Runnable fogCallback, CallbackInfo ci) {
        float background = BackgroundPass.isWhite() ? 1.0f : 0.0f;
        RenderSystem.clearColor(background, background, background, 0.0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        ci.cancel();
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void litematicRender$cancelClouds(
            MatrixStack matrices, Matrix4f positionMatrix, Matrix4f projectionMatrix,
            float tickDelta, double cameraX, double cameraY, double cameraZ,
            CallbackInfo ci) {
        ci.cancel();
    }
}
