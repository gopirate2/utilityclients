// language: Java, file: PanicHandler.java, target: Fabric 26.2
package com.baggute.cheatclient.stealth;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Turns the stealth layer off the instant the panic key is used.
 *
 * <p>The default key is Right Shift, which this client also uses to open the
 * ClickGui. A tap therefore keeps its old meaning and a <em>hold</em> of
 * {@value #HOLD_MS} ms or more flips the layer instead: the GUI that opened on
 * press is closed again when the hold fires, so the player never ends up staring
 * at a cheat menu on stream. Binding {@code panicKey} to a free key gets an
 * immediate, tap-triggered panic instead.
 *
 * <p>Edge-triggered by design. A held key must not flip the layer every tick, so
 * the press is remembered until its release.
 */
public final class PanicHandler {

    /** How long the default key must be held before it counts as a panic. */
    private static final long HOLD_MS = 500L;

    private static boolean armed;
    private static long pressedAt;
    private static boolean holdFired;

    private PanicHandler() {
    }

    /**
     * Called before every other key handler, including the GUI key and the screen
     * guard, because a panic that requires closing chat is not a panic.
     *
     * @return true when the key belonged to this handler and must not propagate
     */
    public static boolean handle(int key, int action) {
        StealthConfig config = StealthConfig.get();
        int panicKey = config.panicKey();
        int releaseKey = config.releaseKey();

        // Release is checked first and is unconditional: once the layer is gone the
        // panic key has nothing left to toggle, and a release must not be missed
        // because some earlier state machine decided the press was a GUI open.
        if (action == GLFW.GLFW_PRESS && key == releaseKey && key != panicKey) {
            fireRelease();
            return true;
        }

        if (action == GLFW.GLFW_PRESS) {
            if (key != panicKey) return false;
            // A free key panics on press, instantly. The GUI key cannot: it has
            // to reach KeyMapping or the ClickGui never opens, so it arms a hold
            // and decides on release.
            if (!isGuiKey(panicKey)) {
                fire();
                return true;
            }
            armed = true;
            holdFired = false;
            pressedAt = System.currentTimeMillis();
            return false;
        }

        if (action == GLFW.GLFW_RELEASE) {
            if (!armed || key != panicKey) return false;
            armed = false;
            if (holdFired) return false;
            if (System.currentTimeMillis() - pressedAt >= HOLD_MS) {
                fire();
                return true;
            }
            return false;
        }

        return false;
    }

    /** Called from the tick loop: a hold fires without waiting for the release. */
    public static void tick() {
        if (!armed || holdFired) return;
        if (System.currentTimeMillis() - pressedAt < HOLD_MS) return;
        if (!isGuiKey(StealthConfig.get().panicKey())) return;
        holdFired = true;
        fire();
    }

    private static boolean isGuiKey(int key) {
        // Right Ctrl only: it is the single ClickGUI bind, so it is the only key a
        // hold can mean "hide the menu and everything with it".
        return key == GLFW.GLFW_KEY_RIGHT_CONTROL;
    }

    private static void fire() {
        ScreenBypass.togglePanic();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            // The GUI opened on press; a panic frame must not show it.
            mc.gui.setScreen(null);
        }
        if (com.baggute.cheatclient.UtilityClient.moduleManager != null) {
            com.baggute.cheatclient.module.misc.Notifications.push(
                    ScreenBypass.panicHidden() ? "stealth: hidden" : "stealth: visible", false);
        }
    }

    /**
     * Tears the layer down and restarts the client out of it.
     *
     * <p>The restart is deliberate. Once {@code uc_uninstall} has run, the swap
     * hook is gone and the overlay window is destroyed; a client that carried on
     * rendering modules would then be drawing a HUD straight into the captured
     * back buffer, which is the exact state the release key exists to escape.
     * Reloading would need the DLL mapped again, which Windows will not reliably
     * do in-process, so a clean restart is both simpler and safer than pretending
     * the module system still has somewhere safe to draw.
     */
    private static void fireRelease() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) mc.gui.setScreen(null);

        // Modules first, so nothing is drawing while the hook and the overlay come
        // down. The reverse order leaves one frame where a module paints into a
        // back buffer whose swap hook has already been removed.
        com.baggute.cheatclient.UtilityClient.unload();
        ScreenBypass.releaseStealth();
    }
}
