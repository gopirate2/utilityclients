// language: Java, file: AntiVanish.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records players who leave the tab list while their entity is still around.
 *
 * <p>The signal is the tab list, not the entity. Dropping off render distance is
 * normal play and the entity leaves the client's level with it; leaving the tab
 * list while the entity stays is what a vanish looks like from the outside.
 *
 * <p>The hard part is telling a vanish apart from a logout, because both remove
 * the player with no further packets. Two discriminators, in order:
 *
 * <ol>
 *   <li>Still in {@code playerInfoMap} but not in the listed set - the server
 *       cleared the LISTED flag and kept the entry. That is never a logout.
 *   <li>Gone from the map entirely - then the entity is the tiebreak. A logout
 *       takes the entity with it; a vanish leaves it standing, frozen or still
 *       moving, with no remove-entities packet behind it.
 * </ol>
 *
 * <p>Everything runs on the client tick. Diffing inside the packet handler would
 * need the map state on both sides of vanilla's apply, and an off-thread read of
 * the level is a crash waiting for a bad day.
 */
public final class AntiVanish extends Module {

    /** A player who left the list: where they were, and when we noticed. */
    public record Ghost(String name, Vec3 pos, int goneAtTick) {}

    /** Ghosts visible to the renderer. Sampled on tick, drawn per frame. */
    private static final Map<UUID, Ghost> VANISHED = new ConcurrentHashMap<>();

    private final BoolSetting esp =
            new BoolSetting("esp", "Draw a marker where vanished players were", true);
    private final BoolSetting chat =
            new BoolSetting("sendChatFeedback", "Send Chat Feedback: print vanish and reveal in local chat", false);
    private final DoubleSetting timeout =
            new DoubleSetting("timeout", "Seconds to keep a ghost", 60.0, 5.0, 600.0);
    private final ColorSetting color =
            new ColorSetting("color", "Ghost box color", 0xFFFF4DD2);

    private final Set<UUID> listed = new HashSet<>();
    private boolean seeded;
    private int tick;
    private int expiryTimer;

    public AntiVanish() {
        super("AntiVanish", "Marks players who leave the tab list without logging out.",
                Category.RENDER);
        addSetting(esp);
        addSetting(chat);
        addSetting(timeout);
        addSetting(color);
    }

    public boolean drawEsp() {
        return isEnabled() && esp.get();
    }

    public int getColor() {
        return color.get();
    }

    /** Snapshot for the renderer; never hands out the live map. */
    public List<Ghost> ghosts() {
        return List.copyOf(VANISHED.values());
    }

    @Override
    protected void onEnable() {
        VANISHED.clear();
        listed.clear();
        seeded = false;
        tick = 0;
        expiryTimer = 0;
    }

    @Override
    protected void onDisable() {
        VANISHED.clear();
        listed.clear();
        seeded = false;
        tick = 0;
        expiryTimer = 0;
    }

    private net.minecraft.client.multiplayer.ClientLevel sessionLevel;

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (sessionLevel != mc.level) {
            VANISHED.clear(); listed.clear(); seeded = false; tick = 0; expiryTimer = 0;
            sessionLevel = mc.level;
        }
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            // Leaving the world: the next join reseeds rather than diffs against
            // a list from a different server. The tick clock goes with it, or the
            // timeout compares against a counter from the previous session.
            VANISHED.clear();
            listed.clear();
            seeded = false;
            tick = 0;
            expiryTimer = 0;
            return;
        }
        tick++;

        Set<UUID> current = new HashSet<>();
        for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            current.add(info.getProfile().id());
        }

        if (!seeded) {
            listed.clear();
            listed.addAll(current);
            seeded = true;
            return;
        }

        for (UUID uuid : listed) {
            if (!current.contains(uuid)) onLeaveList(mc, uuid);
        }
        for (UUID uuid : current) {
            if (!listed.contains(uuid)) onReturnToList(uuid);
        }

        listed.clear();
        listed.addAll(current);

        // The position stored at detection is a snapshot. A vanished player keeps
        // moving (that is the whole point of ghosting them), so re-read it from the
        // entity while the entity is still on the client. Without this the box stays
        // pinned to wherever they were standing when the list entry disappeared.
        for (Map.Entry<UUID, Ghost> entry : VANISHED.entrySet()) {
            Player live = findPlayer(mc, entry.getKey());
            if (live == null) continue;
            Ghost old = entry.getValue();
            entry.setValue(new Ghost(old.name(), live.position(), tick));
        }

        if (++expiryTimer >= 20) {
            expiryTimer = 0;
            long cutoff = tick - Math.round(timeout.get() * 20.0);
            VANISHED.values().removeIf(ghost -> ghost.goneAtTick() < cutoff);
        }
    }

    private void onLeaveList(Minecraft mc, UUID uuid) {
        // Never flag ourselves: a server can re-send our own entry at any time.
        if (mc.player != null && uuid.equals(mc.player.getUUID())) return;

        Player entity = findPlayer(mc, uuid);
        boolean stillKnown = isStillKnown(mc, uuid);

        if (entity == null && !stillKnown) {
            // Logout: entity gone and the map entry gone with it, nothing to mark.
            return;
        }

        String name = entity != null ? entity.getName().getString() : lookupName(mc, uuid);
        Vec3 pos = entity != null ? entity.position() : null;
        if (pos == null) {
            // Known to the server but no entity and no last position: nothing
            // meaningful to draw, and a GUESSED position is worse than none.
            return;
        }

        if (VANISHED.put(uuid, new Ghost(name, pos, tick)) == null && chat.get()) {
            message(ChatFormatting.RED, name + " left the player list");
        }
    }

    private void onReturnToList(UUID uuid) {
        Ghost ghost = VANISHED.remove(uuid);
        if (ghost != null && chat.get()) {
            message(ChatFormatting.GREEN, ghost.name() + " came back");
        }
    }

    /** True when the player is still in {@code playerInfoMap}, only unlisted. */
    private boolean isStillKnown(Minecraft mc, UUID uuid) {
        return mc.getConnection() != null && mc.getConnection().getPlayerInfo(uuid) != null;
    }

    private String lookupName(Minecraft mc, UUID uuid) {
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
        return info == null ? uuid.toString() : info.getProfile().name();
    }

    private Player findPlayer(Minecraft mc, UUID uuid) {
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(uuid)) return player;
        }
        return null;
    }

    private void message(ChatFormatting accent, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Component line = Component.literal("[Vanish] ").withStyle(accent)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY));
        mc.gui.chatListener().handleSystemMessage(line, false);
    }
}
