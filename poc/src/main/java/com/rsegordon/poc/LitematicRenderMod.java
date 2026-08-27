package com.rsegordon.poc;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class LitematicRenderMod implements ClientModInitializer {
    @Override public void onInitializeClient() {
        LitematicRenderCommand.register();
        String[] args = FabricLoader.getInstance().getLaunchArguments(false);
        int inputIndex = positionalInputIndex(args);
        String input = inputIndex >= 0 ? args[inputIndex] : null;
        String output = positionalOutput(args, inputIndex);
        if (input == null) input = System.getProperty("litematic.input");
        if (output == null) output = System.getProperty("litematic.output");
        if (input != null && output != null) OffscreenRenderer.arm(input, output);
    }

    private static int positionalInputIndex(String[] args) {
        // Fabric Loader serializes recognized --key/value pairs before its
        // preserved positional arguments, even when the user supplied these
        // two paths first. Locate args[0] within that preserved tail.
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) continue;
            try {
                Path candidate = Path.of(args[i]);
                if (Files.isRegularFile(candidate)
                        && candidate.getFileName().toString().toLowerCase().endsWith(".litematic")) {
                    return i;
                }
            } catch (InvalidPathException ignored) {
                // Keep looking; malformed unrelated launch arguments are not inputs.
            }
        }
        return -1;
    }

    private static String positionalOutput(String[] args, int inputIndex) {
        int outputIndex = inputIndex + 1;
        if (inputIndex < 0 || outputIndex >= args.length || args[outputIndex].startsWith("--")) return null;
        try {
            return Path.of(args[outputIndex]).toString();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }
}
