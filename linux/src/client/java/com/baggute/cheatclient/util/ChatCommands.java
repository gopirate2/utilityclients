// language: Java, file: ChatCommands.java, target: Fabric 26.2
package com.baggute.cheatclient.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Router for client-side chat commands.
 *
 * <p>A message starting with "." is consumed here before it is ever sent, so the
 * chat screen behaves as if it had started with "/" except the command runs
 * entirely on this client. Registered against
 * {@code ClientSendMessageEvents.ALLOW_CHAT}; returning {@code true} from
 * {@link #handle} cancels the send.
 *
 * <pre>
 *   .version          client, minecraft, loader, fabric api, java, build
 *   .lookup &lt;name&gt;    gear, durability, enchants and health
 *   .lookup list      everyone currently cached
 * </pre>
 *
 * <p>This class owns the prefix parsing and the unknown-command branch. Adding a
 * command is one case in the switch; no existing command has to know about it.
 */
public final class ChatCommands {

    private static final String PREFIX = ".";
    private static final String FALLBACK_HINT = "  (try .version or .lookup)";

    private ChatCommands() {
    }

    /**
     * @return true when the message was consumed and must not reach the server
     */
    public static boolean handle(String message) {
        if (message == null || !message.startsWith(PREFIX)) return false;

        String trimmed = message.trim();
        if (trimmed.length() < 2) {
            say("empty client command" + FALLBACK_HINT, ChatFormatting.GRAY);
            return true;
        }

        String[] parts = trimmed.split("\\s+");
        String root = parts[0].substring(1).toLowerCase(Locale.ROOT);

        switch (root) {
            case "version", "ver" -> printVersion();
            case "lookup" -> LookupCommand.run(parts);
            default -> say("unknown command: ." + root + FALLBACK_HINT, ChatFormatting.GRAY);
        }
        return true;
    }

    private static void printVersion() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        List<String> lines = VersionInfo.lines();
        if (lines.isEmpty()) return;

        say("── " + lines.get(0) + " ──", ChatFormatting.AQUA);
        for (int i = 1; i < lines.size(); i++) {
            say(lines.get(i), ChatFormatting.GRAY);
        }
    }

    /** Writes a line into the client chat log only. Never reaches the server. */
    public static void say(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        mc.gui.chatListener().handleSystemMessage(
                Component.literal(text).withStyle(color), false);
    }
}
