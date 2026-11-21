// language: Java, file: LookupCommand.java, target: Fabric 26.2
package com.baggute.cheatclient.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * {@code .lookup} - gear, durability, enchants and health for a cached player.
 *
 * <p>Prefix parsing and dispatch live in {@link ChatCommands}; this class only
 * receives the already-split arguments, with {@code parts[0]} being the full
 * {@code ".lookup"} token.
 *
 * <pre>
 *   .lookup &lt;name&gt;   gear, durability, enchants and health
 *   .lookup          the last player you looked at
 *   .lookup list     everyone currently cached
 * </pre>
 */
public final class LookupCommand {

    private static String lastQuery = "";

    private LookupCommand() {}

    /**
     * Runs one lookup. Arguments are pre-split by the dispatcher.
     */
    public static void run(String[] parts) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String argument = parts.length > 1 ? parts[1] : "";
        if (argument.equalsIgnoreCase("list")) {
            printList();
            return;
        }

        String query = argument.isEmpty() ? lastQuery : argument;
        if (query.isEmpty()) {
            say("usage: .lookup <player>", ChatFormatting.GRAY);
            return;
        }
        lastQuery = query;

        PlayerSnapshots.Snapshot snapshot = PlayerSnapshots.find(query);
        if (snapshot == null) {
            say("no data for '" + query + "' — not seen yet", ChatFormatting.RED);
            return;
        }

        print(snapshot, PlayerSnapshots.isOnline(mc, snapshot.name()));
    }

    private static void print(PlayerSnapshots.Snapshot s, boolean online) {
        String age = online ? "live" : age(s.stamp());

        say("── " + s.name() + "  (" + age + ") ──", ChatFormatting.AQUA);

        // OP line. Read off the player entity's own PermissionSet rather than the
        // tab-list entry: PlayerInfo carries skin, latency and mode, never
        // permissions. COMMANDS_MODERATOR is the level-2 gate - the permission a
        // server grants an operator before it grants /gamemode or /give.
        Minecraft mc = Minecraft.getInstance();
        Player entity = onlinePlayer(mc, s.name());
        String op = operatorStatus(mc, entity);
        say("[Lookup] " + s.name() + " - OP: " + op,
                op.equals("true") ? ChatFormatting.RED : ChatFormatting.GRAY);

        say(String.format("hp %.1f / %.0f", s.health(), s.maxHealth()),
                s.health() / Math.max(1f, s.maxHealth()) < 0.35f
                        ? ChatFormatting.RED : ChatFormatting.GREEN);

        boolean anyArmor = false;
        for (PlayerSnapshots.Piece piece : s.armor()) {
            if (piece.isEmpty()) continue;
            anyArmor = true;
            say(piece.line(), durabilityColor(piece));
        }
        if (!anyArmor) say("armour      none", ChatFormatting.DARK_GRAY);

        for (PlayerSnapshots.Piece piece : s.tools()) {
            if (piece.isEmpty()) continue;
            say(piece.line(), durabilityColor(piece));
        }
    }

    private static ChatFormatting durabilityColor(PlayerSnapshots.Piece piece) {
        if (piece.max() <= 0) return ChatFormatting.WHITE;
        double frac = (double) piece.remaining() / piece.max();
        if (frac < 0.2) return ChatFormatting.RED;
        if (frac < 0.5) return ChatFormatting.YELLOW;
        return ChatFormatting.WHITE;
    }

    /**
     * The entity for a name, or null when they are outside tracking range.
     *
     * <p>{@code Mc.level.players()} only holds players the client is tracking, so
     * an out-of-range name still appears in the tab list but has no entity to read
     * permissions from. Callers treat that as "not an operator" rather than
     * guessing, because a wrong yes on a staff check is worse than a missing one.
     */
    private static Player onlinePlayer(Minecraft mc, String name) {
        if (mc.level == null || name == null) return null;
        for (Player player : mc.level.players()) {
            String candidate = player.getGameProfile().name();
            if (candidate != null && candidate.equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    /**
     * Operator check.
     *
     * <p>{@code COMMANDS_MODERATOR} is the level-2 permission, the one that gates
     * {@code /gamemode}, {@code /give} and {@code /tp}. The old
     * {@code hasPermissions(int)} helper does not exist in 1.21.9+; permissions were
     * reworked into a {@code PermissionSet} of {@code Permission} values and this is
     * the replacement.
     */
    private static boolean isOperator(Player player) {
        try {
            return player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
        } catch (Throwable ignored) {
            return false;
        }
    }
    public static String operatorStatus(Minecraft mc,Player player){
        if(mc==null||player==null)return "unknown (not exposed by server)";
        if(mc.getSingleplayerServer()!=null)
            return Boolean.toString(mc.getSingleplayerServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile())));
        if(player==mc.player&&isOperator(player))return "true";
        return "unknown (not exposed by server)";
    }

    private static void printList() {
        List<String> names = PlayerSnapshots.names();
        if (names.isEmpty()) {
            say("cache is empty — nobody seen yet", ChatFormatting.GRAY);
            return;
        }
        say("cached: " + String.join(", ", names), ChatFormatting.GRAY);
    }

    private static String age(long stamp) {
        long seconds = Math.max(0, (System.currentTimeMillis() - stamp) / 1000);
        if (seconds < 60) return "seen " + seconds + "s ago";
        if (seconds < 3600) return "seen " + (seconds / 60) + "m ago";
        return "seen " + (seconds / 3600) + "h ago";
    }

    private static void say(String text, ChatFormatting color) {
        ChatCommands.say(text, color);
    }
}
