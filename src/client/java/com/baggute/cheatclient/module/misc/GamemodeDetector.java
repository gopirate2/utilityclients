// language: Java, file: GamemodeDetector.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.util.ChatCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reports every gamemode change the client is told about.
 *
 * <pre>
 *   [Detector] Steve switched to Creative
 *   [Detector] Alex switched to Survival
 * </pre>
 *
 * <p><b>Source of truth.</b> {@code ClientPacketListener#handlePlayerInfoUpdate} is
 * the only place the server states a player's mode. It is read here from the
 * per-player {@link PlayerInfo} record rather than from
 * {@code ClientPacketListener.getPlayerInfo(...)} at decision time, because the
 * packet can carry {@code UPDATE_GAME_MODE} for a player who is not in the same
 * dimension and therefore has no entity on the client at all - the tab-list entry
 * exists regardless. A detector that watched entities would miss exactly the mode
 * changes that matter: a staff member switching to creative to vanish from view.
 *
 * <p><b>Two triggers.</b>
 *
 * <ul>
 *   <li>{@code PlayerInfoMixin} pings {@link #onPlayerInfoPacket()} the instant the
 *       packet lands, so the alert arrives on the tick the server sent it.</li>
 *   <li>{@link #onTick()} reconciles the whole tab list every tick anyway. The
 *       mixin is an accelerator, not a dependency: if the injection fails against
 *       a future mapping the poll still sees the change one tick later, which is
 *       why the module cannot be the thing that breaks on a version bump.</li>
 * </ul>
 *
 * <p>The first sighting of a player seeds the baseline without an alert. Joining a
 * server is not a teammate switching modes, and a detector that fired on every
 * player in the tab list on login would be muted before it was ever useful.
 */
public final class GamemodeDetector extends Module {

    /** Mode of every player this client has already reported, keyed by UUID. */
    private static final Map<UUID, GameType> known = new HashMap<>();

    /** Completed packet-driven checks, surfaced in the diagnostic line. */
    private static volatile long packetsSeen;

    /** Wall-clock of the last packet-driven check, for the self-test line. */
    private static volatile long lastPacketMs;
    private static ClientPacketListener trackedConnection;
    private static String alert;
    private static long alertUntil;

    private final BoolSetting notify =
            new BoolSetting("sendChatFeedback", "Send Chat Feedback: show gamemode changes in local chat", false);
    public GamemodeDetector() {
        super("GamemodeDetector",
                "Announces other players gamemode changes from player-info updates.",
                Category.MISC);
        addSetting(notify);
    }

    // ---- mixin entry point ----

    /**
     * Called from {@code PlayerInfoMixin} at the tail of
     * {@code handlePlayerInfoUpdate}, on the network thread's hand-off into the
     * client thread.
     *
     * <p>Deliberately cheap and non-throwing: it runs inside packet handling, so a
     * detector bug there would take the connection down. The work is delegated to
     * {@link #reconcile(String, boolean)} which reads client state that is only
     * safe on the client thread, so it is deferred to the next tick unless the
     * caller is already on it.
     */
    public static void onPlayerInfoPacket() {
        packetsSeen++;
        lastPacketMs = System.currentTimeMillis();

        GamemodeDetector detector = instance();
        if (detector == null || !detector.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.isSameThread()) {
            detector.reconcile("packet");
        }
        // Off-thread: the tick poll picks it up. PlayerInfo is not thread-safe and
        // reading it from the packet thread is how a detector becomes a crash.
    }

    // ---- tick ----

    @Override
    public void onTick() {
        reconcile("tick");
    }

    /**
     * Compares the tab list against the last reported mode for each player.
     *
     * @param origin "packet" or "tick", logged with every alert so a missing mixin
     *               is visible in the log rather than invisible in the game
     */
    private void reconcile(String origin) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.level == null) {
            // Leaving the world clears the baseline: the next server's tab list has
            // no relationship to this one, and a stale mode would fire on join.
            known.clear();
            trackedConnection=null;
            return;
        }
        if(trackedConnection!=connection){known.clear();trackedConnection=connection;}

        Set<UUID> present = new HashSet<>();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        Collection<PlayerInfo> listed = connection.getOnlinePlayers();
        if (listed == null) listed=java.util.List.of();

        for (PlayerInfo info : listed) {
            if (info == null) continue;

            UUID id;
            try {
                id = info.getProfile().id();
            } catch (Throwable ignored) {
                continue;
            }
            if (id == null) continue;
            present.add(id);

            GameType current = info.getGameMode();
            if (current == null) continue;

            boolean isSelf = id.equals(self);
            if(isSelf)continue;
            GameType previous = known.put(id, current);
            if (previous == null) {
                continue;
            }
            if (previous == current) continue;


            announce(displayName(info, id), current);

            UtilityClient.LOGGER.info("[Detector] {} {} -> {} ({})",
                    displayName(info, id), previous.getSerializedName(),
                    current.getSerializedName(), origin);
        }

        // Drop players who left, so a rejoin seeds instead of alerting.
        known.keySet().retainAll(present);
    }

    private void announce(String name, GameType mode) {
        alert="[Detector] "+name+" switched to "+pretty(mode);alertUntil=System.currentTimeMillis()+5000;
        if (!notify.get()) return;
        ChatCommands.say("[Detector] " + name + " switched to " + pretty(mode), ChatFormatting.YELLOW);
    }
    public static String currentAlert(){return System.currentTimeMillis()<alertUntil?alert:null;}
    public static void render(net.minecraft.client.gui.GuiGraphicsExtractor g,Minecraft mc){
        var detector=instance();String message=currentAlert();
        if(detector==null||!detector.isEnabled()||message==null||mc.font==null)return;
        int width=mc.font.width(message);g.fill(4,4,width+12,21,0xD0202028);g.text(mc.font,message,8,8,0xFFFFFF55,true);
    }

    private String displayName(PlayerInfo info, UUID id) {
        try {
            String name = info.getProfile().name();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
            // Fall through to the UUID.
        }
        return id.toString();
    }

    /** Tab-list spelling of the four modes, so the alert reads like the game does. */
    private String pretty(GameType mode) {
        return switch (mode) {
            case SURVIVAL -> "Survival";
            case CREATIVE -> "Creative";
            case ADVENTURE -> "Adventure";
            case SPECTATOR -> "Spectator";
            default -> mode.getSerializedName();
        };
    }

    private static GamemodeDetector instance() {
        if (UtilityClient.moduleManager == null) return null;
        Module module = UtilityClient.moduleManager.getByName("GamemodeDetector");
        return module instanceof GamemodeDetector detector ? detector : null;
    }

    /** One line for the terminal and the self-test sweep. */
    public static String diagnostic() {
        return "detector: " + known.size() + " tracked, " + packetsSeen
                + " packet checks, last " + lastPacketMs;
    }

    @Override
    protected void onEnable() {
        known.clear();
        packetsSeen = 0L;
        lastPacketMs = 0L;
        alert=null;alertUntil=0;trackedConnection=null;reconcile("enable");
        UtilityClient.LOGGER.info("[Detector] armed");
    }

    @Override
    protected void onDisable() {
        known.clear();
    }
}
