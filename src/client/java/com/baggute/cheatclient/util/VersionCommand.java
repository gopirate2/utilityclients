// language: Java, file: VersionCommand.java, target: Fabric 26.2
package com.baggute.cheatclient.util;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Brigadier client command: {@code /version}.
 *
 * <p>Registered through Fabric's client command dispatcher, so it runs locally and
 * never reaches the server. It shares the name with the server command of the same
 * name, and Fabric's client dispatcher resolves a local registration first - the
 * player gets this client's answer rather than the server's, without either side
 * knowing the other exists.
 *
 * <p>The same block is reachable as {@code .version} in chat via
 * {@link ChatCommands}, and as {@code version} in the tilde terminal. All three go
 * through {@link VersionInfo}, so they cannot disagree.
 */
public final class VersionCommand {

    private static final String ROOT = "version";

    private VersionCommand() {
    }

    /** Registers the command. Safe to call more than once; Brigadier replaces. */
    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        ClientCommands.literal(ROOT)
                                .executes(context -> report(context.getSource()))));
    }

    /**
     * Prints the headline plus the detail block.
     *
     * @return Brigadier's success value, 1
     */
    public static int report(FabricClientCommandSource source) {
        List<String> lines = VersionInfo.lines();

        source.sendFeedback(Component.literal(lines.isEmpty() ? VersionInfo.headline() : lines.get(0))
                .withStyle(ChatFormatting.AQUA));

        for (int i = 1; i < lines.size(); i++) {
            source.sendFeedback(Component.literal(lines.get(i)).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }
}
