package com.rsegordon.poc;

import net.fabricmc.api.ClientModInitializer;

public final class LitematicRenderMod implements ClientModInitializer {
    @Override public void onInitializeClient() {
        LitematicRenderCommand.register();
        String input = System.getProperty("litematic.input");
        String output = System.getProperty("litematic.output");
        if (input != null && output != null) OffscreenRenderer.arm(input, output);
    }
}
