package com.rsegordon.poc;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class LitematicRenderMod implements ClientModInitializer {
    @Override public void onInitializeClient() {
        long parseStarted = System.nanoTime();
        LitematicRenderCommand.register();
        String[] args = FabricLoader.getInstance().getLaunchArguments(false);
        rememberRequestedWindow(args);
        int inputIndex = positionalInputIndex(args);
        String input = inputIndex >= 0 ? args[inputIndex] : null;
        String output = positionalOutput(args, inputIndex);
        String title = optionValue(args, "--title");
        if (input == null) input = System.getProperty("litematic.input");
        if (output == null) output = System.getProperty("litematic.output");
        if (title == null) title = System.getProperty("litematic.title");
        System.out.printf("[STEP 1] parse args%n  - input: %s%n  - output: %s%n  - title: %s%n  - elapsed: %d ms%n",
                input, output, title, (System.nanoTime() - parseStarted) / 1_000_000);
        if (input != null && output != null) OffscreenRenderer.arm(input, output, title);
        else System.out.println("[STEP ERROR] missing input or output path; renderer not armed");
    }

    private static void rememberRequestedWindow(String[] args) {
        for (int i=0;i<args.length;i++) {
            if ((args[i].equals("--width") || args[i].equals("--height")) && i+1<args.length) {
                System.setProperty("litematic.requested."+args[i].substring(2),args[++i]);
            } else if (args[i].startsWith("--width=")) {
                System.setProperty("litematic.requested.width",args[i].substring("--width=".length()));
            } else if (args[i].startsWith("--height=")) {
                System.setProperty("litematic.requested.height",args[i].substring("--height=".length()));
            }
        }
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

    private static String optionValue(String[] args, String option) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(option) && i + 1 < args.length) return args[i + 1];
            if (args[i].startsWith(option + "=")) return args[i].substring(option.length() + 1);
        }
        return null;
    }
}
