// language: Java, file: StealthConfig.java, target: Fabric 26.2
package com.baggute.cheatclient.stealth;

import com.baggute.cheatclient.config.Storage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the stealth layer, stored beside the module config.
 *
 * <p>Kept out of {@code utilityclient.json} on purpose: that file is rewritten
 * wholesale on every module toggle, and the capture/panic switches must survive a
 * crash that happens mid-save. One small file, atomic replace, no dependency on
 * the module system being initialised.
 */
public final class StealthConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = Storage.root()
            .resolve(StringDecryptor.decrypt("WhdG3zSKrGvEHKCdK0M="));
    private static volatile StealthConfig instance;

    /** Delete the log directory on JVM shutdown. */
    private boolean wipeOnExit;

    /** Refuse to draw the HUD on the primary display at all. */
    private boolean bypassMode;

    /**
     * GLFW key code that flips the whole layer on and off.
     *
     * <p>Right Ctrl, matching the only key the ClickGUI opens on: a tap opens the
     * menu and a hold panics, and both have to be the same key or one of them
     * fires from something the player never pressed.
     */
    private int panicKey = GLFW.GLFW_KEY_RIGHT_CONTROL;

    /**
     * GLFW key code that tears the layer down instead of hiding it.
     *
     * <p>Separate from the panic key on purpose. Panic is reversible and is what a
     * player reaches for when someone walks in; release is irreversible until the
     * game restarts and is what a player reaches for when the hook itself is the
     * problem. One key must never be both, because a panicked double-tap would
     * then unload the DLL at the worst possible moment.
     */
    private int releaseKey = GLFW.GLFW_KEY_END;

    /**
     * Accept {@code WDA_MONITOR} when {@code WDA_EXCLUDEFROMCAPTURE} is refused.
     *
     * <p>Some drivers - mostly older Intel and a few virtualised adapters - return
     * success from {@code SetWindowDisplayAffinity} and then drop the affinity on
     * the first compose, so the overlay ends up visible in the capture anyway.
     * With this on, the client falls back to {@code WDA_MONITOR}, which blacks the
     * window out in the recording. The recording then contains a black rectangle
     * instead of gameplay, which is worse than a clean hide but strictly better
     * than showing the overlay.
     */
    private boolean compatibilityMode;

    /** Master switch for the deferred-overlay path; false forces plain suppression. */
    private boolean screenBypass = true;

    /**
     * Hide the game window from every capture path.
     *
     * <p>Off by default: it blanks the whole game in a recording, which is only
     * wanted when nothing else works. The default path keeps the game fully
     * visible in the capture and moves the HUD into a second, separately excluded
     * overlay window — see {@code HudOverlay}. Turn this on for capture tools that
     * are not on the name list, or when a second window is not acceptable.
     */
    private boolean captureExclusion;

    /**
     * Hide the client's own menus from capture while one is open.
     *
     * <p>ClickGUI, the HUD editor and the terminal are Minecraft screens: they are
     * painted inside the game window, so there is no second window to mark. The
     * game window is excluded for as long as one of them is open and returned to
     * normal the moment it closes. On by default — a menu left readable in a
     * recording gives away the client faster than any HUD ever did.
     */
    private boolean hideClientScreens;

    /**
     * Treat capture as active even when no recorder process is recognised.
     *
     * <p>The process scan is a heuristic: a new Discord build, a browser tab, an
     * unrecognised tool or a hardware capture card all bypass it. The guard makes
     * the client behave as if a recorder is running regardless, which is what
     * "hide my HUD from every screen share" needs to mean when detection is the
     * only thing standing between the HUD and the capture.
     */
    private boolean captureGuard = true;

    /**
     * Mirror the HUD into a second, capture-excluded window instead of drawing it
     * in the game window.
     *
     * <p>Off by default. This was the implicit behaviour before it had a switch:
     * {@code captureGuard} forced {@link #exposure()} true, the overlay opened on
     * the first tick, and the in-window HUD was suppressed on the assumption the
     * overlay would replace it. When the overlay failed to come up - or came up
     * without a working affinity - the player had no HUD at all.
     *
     * <p>The overlay is still worth having for the case it was written for: the
     * game window is visible in a capture and the HUD must not be. Turn this on
     * only when {@link #captureExclusion()} cannot be used, because it is the
     * only configuration where the game window must stay clean.
     */
    private boolean hudOverlay;

    /**
     * Runtime state: an affinity this client applied is in force on the game window.
     *
     * <p>Never persisted and deliberately absent from {@link #save()}: it describes
     * what is on the window right now, not what the player asked for. A stored copy
     * would come back {@code true} after a crash that left the window excluded, and
     * the next launch would inherit a hide nobody requested.
     */
    private volatile boolean affinityActive;

    /** Runtime state: the HWND carrying {@link #affinityActive}, 0 when none. */
    private volatile long affinityWindow;

    private StealthConfig() {
    }

    public static StealthConfig get() {
        StealthConfig current = instance;
        if (current != null) return current;
        synchronized (StealthConfig.class) {
            if (instance == null) instance = load();
            return instance;
        }
    }

    private static StealthConfig load() {
        StealthConfig fresh = new StealthConfig();
        try {
            if (!Files.exists(FILE)) return fresh;
            JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            if (root.has("wipeOnExit")) fresh.wipeOnExit = root.get("wipeOnExit").getAsBoolean();
            if (root.has("bypassMode")) fresh.bypassMode = root.get("bypassMode").getAsBoolean();
            if (root.has("panicKey")) fresh.panicKey = root.get("panicKey").getAsInt();
            if (root.has("releaseKey")) fresh.releaseKey = root.get("releaseKey").getAsInt();
            if (root.has("compatibilityMode")) {
                fresh.compatibilityMode = root.get("compatibilityMode").getAsBoolean();
            }
            if (root.has("screenBypass")) fresh.screenBypass = root.get("screenBypass").getAsBoolean();
            // Deliberately NOT the old "captureExclusion" key: the previous build
            // wrote true there, which blanked the whole game in a recording. Reading
            // the old name would resurrect that on every existing install.
            if (root.has("blankWholeWindowInCapture")) {
                fresh.captureExclusion = root.get("blankWholeWindowInCapture").getAsBoolean();
            }
            if (root.has("hideClientScreens")) {
                fresh.hideClientScreens = root.get("hideClientScreens").getAsBoolean();
            }
            if (root.has("captureGuard")) {
                fresh.captureGuard = root.get("captureGuard").getAsBoolean();
            }
            if (root.has("hudOverlay")) {
                fresh.hudOverlay = root.get("hudOverlay").getAsBoolean();
            }
            if (fresh.panicKey < 0) fresh.panicKey = GLFW.GLFW_KEY_RIGHT_CONTROL;
            // Old files carry Right Shift here. The GUI no longer opens on it, so a
            // panic key left on it would never be reachable through the hold path.
            if (fresh.panicKey == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                fresh.panicKey = GLFW.GLFW_KEY_RIGHT_CONTROL;
            }
            if (fresh.releaseKey < 0) fresh.releaseKey = GLFW.GLFW_KEY_END;
        } catch (Exception malformed) {
            // A corrupt stealth file must not take the client down; defaults are safe.
        }
        return fresh;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("wipeOnExit", wipeOnExit);
            root.addProperty("bypassMode", bypassMode);
            root.addProperty("panicKey", panicKey);
            root.addProperty("releaseKey", releaseKey);
            root.addProperty("compatibilityMode", compatibilityMode);
            root.addProperty("screenBypass", screenBypass);
            root.addProperty("blankWholeWindowInCapture", captureExclusion);
            root.addProperty("hideClientScreens", hideClientScreens);
            root.addProperty("captureGuard", captureGuard);
            root.addProperty("hudOverlay", hudOverlay);
            com.baggute.cheatclient.util.AtomicFiles.writeString(FILE, GSON.toJson(root));
            LogManager.hide(FILE);
        } catch (Exception failed) {
            LogManager.log("WARN", "stealth settings could not be written", failed);
        }
    }

    public boolean wipeOnExit() {
        return wipeOnExit;
    }

    public void setWipeOnExit(boolean value) {
        this.wipeOnExit = value;
        save();
    }

    public boolean bypassMode() {
        return bypassMode;
    }

    public void setBypassMode(boolean value) {
        this.bypassMode = value;
        save();
    }

    public int panicKey() {
        return panicKey;
    }

    public void setPanicKey(int key) {
        this.panicKey = key;
        save();
    }

    public int releaseKey() {
        return releaseKey;
    }

    public void setReleaseKey(int key) {
        this.releaseKey = key;
        save();
    }

    public boolean compatibilityMode() {
        return compatibilityMode;
    }

    public void setCompatibilityMode(boolean value) {
        this.compatibilityMode = value;
        save();
    }

    public boolean screenBypass() {
        return screenBypass;
    }

    public void setScreenBypass(boolean value) {
        this.screenBypass = value;
        save();
    }

    public boolean captureExclusion() {
        return captureExclusion;
    }

    public void setCaptureExclusion(boolean value) {
        this.captureExclusion = value;
        save();
    }

    public boolean captureGuard() {
        return captureGuard;
    }

    public void setCaptureGuard(boolean value) {
        captureGuard = value;
        save();
    }

    public boolean hudOverlay() {
        return hudOverlay;
    }

    public void setHudOverlay(boolean value) {
        hudOverlay = value;
        save();
    }

    public boolean hideClientScreens() {
        return hideClientScreens;
    }

    public void setHideClientScreens(boolean value) {
        hideClientScreens = value;
        save();
    }

    /** True while a window affinity applied by this client is in force. */
    public boolean affinityActive() {
        return affinityActive;
    }

    /** The HWND carrying {@link #affinityActive}, or 0. */
    public long affinityWindow() {
        return affinityWindow;
    }

    /**
     * Publishes the live affinity state.
     *
     * <p>Called by {@code StreamProof} and {@code StealthInitializer} on every apply
     * and every release, so nothing that reads this file sees a stale "excluded"
     * after the window was handed back. Synchronized because the release path runs
     * on the module tick and the startup ladder runs on the lifecycle callback, and a
     * reader must never observe {@code true} with a zero window or the reverse.
     */
    public synchronized void setAffinityState(boolean active, long hwnd) {
        affinityActive = active;
        affinityWindow = active ? hwnd : 0L;
    }
}
