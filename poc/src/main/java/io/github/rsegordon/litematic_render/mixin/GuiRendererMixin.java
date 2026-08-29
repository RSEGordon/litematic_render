package io.github.rsegordon.litematic_render.mixin;

import io.github.rsegordon.litematic_render.OffscreenRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures material icons only after Minecraft has drawn its native GUI ItemStack atlas. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Inject(method="render",at=@At("RETURN"))
    private void litematicRender$captureCompositedItemGui(CallbackInfo ci) {
        OffscreenRenderer.afterGuiRendered();
    }
}
