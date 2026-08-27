package com.rsegordon.poc;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class LitematicRenderCommand {
    private LitematicRenderCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            literal("litematic-render")
                .then(argument("input", StringArgumentType.string())
                .then(argument("out", StringArgumentType.string()).executes(context -> {
                    OffscreenRenderer.arm(
                        StringArgumentType.getString(context, "input"),
                        StringArgumentType.getString(context, "out"));
                    return 1;
                })))));
    }
}
