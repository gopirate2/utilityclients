// language: Java, file: ScreenBypass.java, target: Fabric 26.2
package com.baggute.cheatclient.stealth;

import com.baggute.cheatclient.render.CaptureGuard;
import com.baggute.cheatclient.render.HudOverlay;
import com.baggute.cheatclient.render.HudRenderer;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps client drawing out of screen captures.
 *
 * <p>Mechanisms, strongest first:
 *
 * <ol>
 *   <li><b>Window exclusion</b> ({@code SetWindowDisplayAffinity} with
 *       {@code WDA_EXCLUDEFROMCAPTURE}). Applied by {@link #tick} whenever it is
 *       supported, and it covers every capture path that exists — Discord, Medal,
 *       OBS, Game Bar, a phone pointed at a VM. The window renders normally for
 *       the player and as black for the capture. While it is in force nothing
 *       else in this class has to do anything, because no capture can see the
 *       window at all.</li>
 *   <li><b>Suppression.</b> When exclusion is unavailable or switched off and a
 *       capture process is running, draw nothing of ours: no HUD, no widgets, no
 *       ESP, no watermarks. This is the only leak-free fallback available in
 *       pure Java.</li>
 * </ol>
 *
 * <p><b>Why the off-screen framebuffer is not used any more.</b> It was written
 * on the assumption that binding a framebuffer inside
 * {@code Hud.extractRenderState} would capture the HUD draws. It does not:
 * 26.2's extractor only records draw commands there, and the pipeline submits
 * them after this class has restored the framebuffer binding. The HUD therefore
 * went to the real back buffer, the overlay texture stayed empty, and the
 * presented frame — the one a capture reads — contained the HUD. A deferral that
 * silently does not defer is worse than no deferral, so the path is now gated on
 * a read-back self-test: if the texture is empty, the deferral disables itself
 * for the rest of the session and suppression takes over. Deferral still needs
 * {@code ucstealth.dll}; nothing else here does.
 *
 * <p>Fail-closed is deliberate: if the overlay path cannot be proven to work, the
 * HUD is not drawn at all. A missing HUD is a bad session; a leaked HUD is a ban.
 */
public final class ScreenBypass {

    /** Executable names that mean someone is recording or streaming. */
    private static final Set<String> CAPTURE_PROCESSES = Set.of(
            "obs64.exe", "obs32.exe", "obs.exe", "obs-browser-page.exe",
            "streamlabs obs.exe", "slobsclient.exe", "slobs.exe", "streamlabs.exe",
            "xsplit.core.exe", "xsplitbroadcaster.exe",
            "discord.exe", "discordcanary.exe", "discordptb.exe", "discorddevelopment.exe",
            "medal.exe", "overwolf.exe", "outplayed.exe", "medalencoder.exe",
            "nvidia share.exe", "nvsphelper64.exe", "share.exe",
            "gamebar.exe", "gamebarft.exe", "screensketch.exe", "xboxgamebar.exe",
            "bandicam.exe", "fraps.exe", "action.exe", "camstudio.exe", "dxtory.exe",
            "sharex.exe", "screenrecorder.exe", "flashback.exe", "captura.exe",
            "recordit.exe", "loom.exe", "loomdesktop.exe", "vokoscreen.exe",
            "prismlivestudio.exe", "vmix64.exe", "melstudio.exe",
            // Remote-viewing tools, same exposure.
            "teamviewer.exe", "anydesk.exe", "rustdesk.exe", "parsec.exe",
            "sunshine.exe", "mstsc.exe", "vncviewer.exe",
            // Linux recorder names, matched without the .exe suffix below.
            "obs", "obs-studio", "kazam", "peek", "simplescreenrecorder",
            "gpu-screen-recorder", "wf-recorder", "vokoscreen", "vokoscreenng",
            "recordmydesktop", "deepin-screen-recorder", "kooha", "gnome-screenshot",
            "discord", "vesktop", "equibop", "obsproject", "teamviewer", "anydesk",
            "rustdesk", "remmina", "vinagre", "krfb"
    );

    /**
     * The same names with the Windows suffix removed.
     *
     * <p>Linux processes report {@code obs} and {@code discord}, never
     * {@code obs64.exe}, so matching the raw set there could only ever fail. Both
     * sides are normalised through this set instead of duplicating every entry.
     */
    private static final Set<String> CAPTURE_NAMES = CAPTURE_PROCESSES.stream()
            .map(ScreenBypass::stripExtension)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static String stripExtension(String name) {
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static final long SCAN_INTERVAL_MS = 1_000L;

    private static final AtomicBoolean READY = new AtomicBoolean();
    private static volatile boolean captureActive;
    private static volatile long lastScan;
    private static volatile boolean panicHidden;

    /** True while the OS is hiding this window from capture. */
    private static volatile boolean exclusionApplied;
    private static volatile long lastExclusionAttempt;

    /** Which affinity actually stuck: excluded, monitor-blanked, or refused. */
    private static volatile CaptureGuard.Outcome exclusionMode = CaptureGuard.Outcome.REFUSED;

    /** Set by the release keybind; stops {@link #tick} from re-applying anything. */
    private static volatile boolean released;

    /** True while the game window is excluded only because a client menu is open. */
    private static volatile boolean screenExcluded;
    private static volatile long lastScreenAttempt;
    private static volatile String screenDiagnostic = "not needed";
    private static volatile long lastOverlayAssert;

    /** Set once the deferral fails its own read-back test. */
    private static volatile boolean deferralBroken;
    private static volatile boolean deferralVerified;
    private static volatile String lastDiagnostic = "none";
    private static volatile String lastCaptureName = "";

    private static int framebuffer;
    private static int colourTexture;
    private static int targetWidth;
    private static int targetHeight;
    private static int previousFramebuffer;

    private ScreenBypass() {
    }

    /** Loads the native hook. Safe to call before any GL context exists. */
    public static void init() {
        if (!READY.compareAndSet(false, true)) return;
        StealthConfig.get();
        if (StealthConfig.get().screenBypass() && StealthNative.ensureLoaded()) {
            StealthNative.setEnabled(true);
        }
        LogManager.log("INFO", "capture layer up: exclusion="
                + (CaptureGuard.supported() ? "available" : "unavailable")
                + " native=" + (StealthNative.ready() ? "loaded" : "absent")
                + " log=" + LogManager.logFile(), null);
    }

    /**
     * Per-tick upkeep: keeps the window exclusion in step with the config and the
     * GLFW handle, and re-runs the deferral self-test if it never passed.
     */
    public static void tick(Minecraft mc) {
        if (!READY.get()) return;
        if (released) return;
        if (mc == null || mc.getWindow() == null) return;

        long now = System.currentTimeMillis();

        // Two modes, and the default one does NOT touch the game window:
        //
        //   overlay   the HUD lives in a second window that is itself excluded,
        //             so the capture records the game and only the game. This is
        //             the default; it needs Windows and a working exclusion call.
        //   whole     the game window is excluded and the capture is black. Opt-in
        //             through stealth.json, for capture tools that are not on the
        //             process list.
        boolean wholeWindow = StealthConfig.get().captureExclusion();
        // The guard forces the capture treatment without waiting for a recognised
        // recorder process; detection is a heuristic and it is the single point that
        // made the HUD leak in captures from tools it did not know.
        //
        // StreamProof is the third source. When the module is enabled but the driver
        // refused the exclusion, it sets hidingFromCapture() and relies on something
        // still showing the player a HUD. The overlay window is that something: the
        // game window draws nothing of ours, and the HUD lives in its own excluded
        // HWND. Without this the module hides the HUD and provides no replacement.
        // The overlay is opt-in. With it on, the game window must stay clean, so
        // the HUD lives in the second window only. With it off - the default -
        // the game window draws the HUD directly and opening the overlay as well
        // would paint everything twice.
        //
        // StreamProof joins here when its native exclusion was refused: that is the
        // fallback path, a second window excluded on its own while the game window
        // stays clean. It is not gated on recording-process detection because the
        // player already asked for it by enabling the module.
        boolean overlayFallback = com.baggute.cheatclient.module.misc.StreamProof.overlayFallbackRequested();
        boolean capture = (wholeWindow || StealthConfig.get().hudOverlay() || overlayFallback)
                && (captureActive()
                    || StealthConfig.get().captureGuard()
                    || overlayFallback);

        if (wholeWindow) {
            clearOverlay();
            if (!capture) {
                if (exclusionApplied) applyExclusion(mc, false);
                return;
            }
            if (!exclusionApplied || now - lastExclusionAttempt >= SCAN_INTERVAL_MS) {
                lastExclusionAttempt = now;
                applyExclusion(mc, true);
            }
            return;
        }

        if (exclusionApplied) applyExclusion(mc, false);

        // ClickGUI, the HUD editor and the terminal are Minecraft screens: they are
        // drawn inside the game window, so there is no second HWND to mark. The game
        // window is excluded while one is open and returned the moment it closes.
        updateScreenExclusion(mc, now);

        // The overlay is the player's only view of the HUD while a capture runs,
        // so it is opened on the first tick a recorder appears and torn down the
        // first tick it is gone.
        if (capture) {
            // A menu is open: the HUD window would float on top of it, and the menu
            // already has the game window excluded.
            if (screenExcluded) {
                clearOverlay();
                return;
            }
            if (!HudOverlay.visible() && !HudOverlay.show()) {
                // No overlay means no way to show the HUD to the player without
                // also showing it to the capture. The old fallback excluded the
                // whole game window, which is the one thing this mode exists to
                // avoid: the share went black. Suppress the HUD instead and leave
                // the game visible; HudRenderer draws nothing while failed().
                lastDiagnostic = "overlay unavailable (" + HudOverlay.lastError()
                        + "); HUD hidden, game still visible in capture";
            }
            if (HudOverlay.visible()) {
                // Re-verify once a second: a recomposed window silently loses its
                // affinity, and that is a HUD visible in a recording.
                if (now - lastOverlayAssert >= SCAN_INTERVAL_MS) {
                    lastOverlayAssert = now;
                    HudOverlay.reassert();
                }
                HudOverlay.render(mc.getWindow());
            }
        } else {
            clearOverlay();
            if (exclusionApplied) applyExclusion(mc, false);
        }

        // A half-second of capture is enough to prove or disprove the deferral.
        if (!deferralVerified && StealthNative.ready() && captureActive()) {
            verifyDeferral();
        }

        // Menus are deliberately NOT closed here. They used to be, and with a
        // capture process running permanently (Discord in the tray is enough) that
        // made the click GUI and the terminal close themselves the instant they
        // opened — unusable, and indistinguishable from a broken keybind. The HUD
        // and the 3D overlays are still suppressed inside the game window; the
        // menus are left to the player, who is the only one who knows whether a
        // recorder is actually recording.
    }

    /**
     * Excludes the game window for as long as a client-owned menu is open.
     *
     * <p>The exclusion is applied on the transition and re-asserted once a second,
     * because anything else that touches the window affinity (the whole-window
     * path, a resize, a driver reset) would otherwise drop it silently and leave a
     * menu visible in a recording.
     */
    private static void updateScreenExclusion(Minecraft mc, long now) {
        if (released) return;
        boolean wanted = StealthConfig.get().hideClientScreens() && clientScreenOpen(mc);

        if (!wanted) {
            if (screenExcluded) {
                long hwnd = CaptureGuard.hwndOf(mc.getWindow());
                if (hwnd != 0L && (exclusionApplied || StealthConfig.get().affinityActive()
                        || CaptureGuard.applyToHwnd(hwnd, CaptureGuard.WDA_NONE))) {
                    screenExcluded = false;
                    screenDiagnostic = "menu closed, window visible again";
                }
            }
            return;
        }

        if (!CaptureGuard.supported()) {
            screenDiagnostic = "window exclusion unavailable on this platform";
            return;
        }
        if (screenExcluded && now - lastScreenAttempt < SCAN_INTERVAL_MS) return;

        long hwnd = CaptureGuard.hwndOf(mc.getWindow());
        if (hwnd == 0L) {
            screenDiagnostic = "no win32 handle for the game window";
            return;
        }
        lastScreenAttempt = now;
        CaptureGuard.Applied applied =
                CaptureGuard.applyBest(hwnd, StealthConfig.get().compatibilityMode());
        screenExcluded = applied.hidden();
        screenDiagnostic = switch (applied.outcome()) {
            case EXCLUDED -> "menu open, game window excluded (capture sees black until it closes)";
            case MONITORED -> "menu open, WDA_MONITOR fallback (capture sees black until it closes)";
            case REFUSED -> "SetWindowDisplayAffinity refused (needs Windows 10 2004+)";
        };
    }

    /** True while one of this client's own screens is on screen. */
    public static boolean clientScreenOpen(Minecraft mc) {
        if (mc == null || mc.gui == null || mc.gui.screen() == null) return false;
        return mc.gui.screen().getClass().getName().startsWith("com.baggute.cheatclient.gui.");
    }

    /** True while the game window is excluded because a client menu is open. */
    public static boolean screenExcluded() {
        return screenExcluded;
    }

    public static String screenDiagnostic() {
        return screenDiagnostic;
    }

    private static void applyExclusion(Minecraft mc, boolean wanted) {
        if (released) {
            exclusionApplied = false;
            lastDiagnostic = "released";
            return;
        }
        if (wanted && !CaptureGuard.supported()) {
            exclusionApplied = false;
            lastDiagnostic = "exclusion unsupported on this platform";
            return;
        }
        long hwnd = CaptureGuard.hwndOf(mc.getWindow());
        if (hwnd == 0L) {
            exclusionApplied = false;
            lastDiagnostic = "no win32 handle";
            return;
        }
        if (!wanted) {
            if (!screenExcluded && !StealthConfig.get().affinityActive()) CaptureGuard.release(hwnd);
            exclusionApplied = false;
            exclusionMode = CaptureGuard.Outcome.REFUSED;
            lastDiagnostic = "exclusion off";
            return;
        }

        CaptureGuard.Applied applied =
                CaptureGuard.applyBest(hwnd, StealthConfig.get().compatibilityMode());
        exclusionMode = applied.outcome();
        exclusionApplied = applied.hidden();
        lastDiagnostic = switch (applied.outcome()) {
            case EXCLUDED -> "window excluded from capture";
            case MONITORED -> "WDA_MONITOR fallback: window black in capture";
            case REFUSED -> "SetWindowDisplayAffinity refused (needs Windows 10 2004+)";
        };
    }

    /**
     * Entry point for the HUD mixin. Draws the client HUD into the game window.
     *
     * <p>There is no branch between the call and the draw. Every previous version
     * of this method had at least one rung that could reach the end without
     * painting, and in the shipping configuration they all fired:
     *
     * <ul>
     *   <li>{@code StealthConfig.captureGuard} defaults to {@code true}, so
     *       {@link #exposure()} is permanently true and every "something is
     *       watching" branch was always taken;</li>
     *   <li>the overlay window was then opened, and its branch returned without
     *       drawing into the game window - so the HUD existed only in a second
     *       HWND with its own creation and affinity failure modes;</li>
     *   <li>{@code StreamProof.hideHud} defaults to {@code true} and set
     *       {@code hiding_from_capture}, which the renderer honoured as "draw
     *       nothing anywhere";</li>
     *   <li>{@code bypassMode} and {@code panicHidden} added two more silent
     *       exits, and a panic state that never cleared because the key that
     *       cleared it was itself gated.</li>
     * </ul>
     *
     * <p>Hiding the HUD from a recorder is the window affinity's job
     * ({@link StealthConfig#captureExclusion()}), not the render path's: a HUD
     * the player cannot see protects nothing and tells the player nothing.
     *
     * <p>The null check stays because there is nothing to draw on. It is not a
     * policy gate; it cannot be true in a live frame.
     */
    public static void renderHud(GuiGraphicsExtractor graphics) {
        if (graphics == null) return;
        HudRenderer.render(graphics);
    }

    private static boolean canDefer() {
        return StealthConfig.get().screenBypass()
                && StealthNative.ready()
                && !deferralBroken;
    }

    private static void clearOverlay() {
        HudOverlay.hide();
    }

    /**
     * True while client drawing must be suppressed entirely in the game window.
     *
     * <p>That is the whole point of the overlay: while a capture runs, the game
     * draws none of our layers, so the recording is vanilla-clean; the widgets the
     * player still wants are in the separate window instead. Closing the game
     * overlay without the second window would leave the player with nothing, so the
     * overlay check is part of the condition — if the second window is not up, the
     * layers are simply hidden from everyone, which leaks nothing.
     */
    public static boolean hidingFromCapture() {
        // Three states, chosen so nothing leaks in any of them:
        //
        //   overlay up        draw there only; the game window stays clean for the
        //                     capture and the player still sees every widget
        //   overlay failed    no excluded surface exists: draw nothing at all
        //                     rather than paint the HUD where a recorder can read it
        //   game excluded     the window itself is black in the capture, so the game
        //                     may draw normally
        if (released || !exposure()) return false;
        boolean requested = StealthConfig.get().hudOverlay() || StealthConfig.get().captureExclusion()
                || com.baggute.cheatclient.module.misc.StreamProof.overlayFallbackRequested();
        return requested && !gameExclusionLive();
    }

    /** Live protection and requester ownership are separate checks. */
    public static boolean gameExclusionLive() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        int live = CaptureGuard.readAffinity(CaptureGuard.hwndOf(mc.getWindow()));
        return live == CaptureGuard.WDA_EXCLUDEFROMCAPTURE || live == CaptureGuard.WDA_MONITOR;
    }

    public static boolean gameExclusionRequested() {
        if (released) return false;
        Minecraft mc = Minecraft.getInstance();
        return (StealthConfig.get().hideClientScreens() && mc != null && clientScreenOpen(mc))
                || (StealthConfig.get().captureExclusion() && exposure());
    }

    /** True while 3D client drawing (ESP, nametags, tracers) must stay off screen. */
    public static boolean worldHidden() {
        return panicHidden || StealthConfig.get().bypassMode()
                || com.baggute.cheatclient.UtilityClient.isUnloaded()
                || hidingFromCapture();
    }

    public static boolean panicHidden() {
        return panicHidden;
    }

    public static boolean exclusionApplied() {
        return exclusionApplied;
    }

    /** True while the separate HUD window is carrying the widgets. */
    public static boolean overlayVisible() {
        return HudOverlay.visible();
    }

    public static boolean deferralUsable() {
        return StealthNative.ready() && !deferralBroken;
    }

    public static String diagnostic() {
        return lastDiagnostic;
    }

    public static String captureName() {
        return lastCaptureName;
    }

    /** Flips the whole layer: every client overlay disappears on the next frame. */
    public static void togglePanic() {
        panicHidden = !panicHidden;
        StealthNative.setEnabled(!panicHidden);
        if (panicHidden) clearOverlay();
        LogManager.log("INFO", "panic " + (panicHidden ? "engaged" : "released"), null);
    }

    /**
     * Tears the layer down for good: unhooks the swap, clears both window
     * affinities, drops the off-screen overlay.
     *
     * <p>Irreversible by design. The point of a release key is that the machine
     * stops looking like a modded client, and re-arming it on the next tick would
     * undo that in the same frame the player turned away. A reload needs a game
     * restart, which is the correct amount of friction.
     *
     * <p>Order matters: the hook comes out before the overlay window is destroyed,
     * because the hook draws from that window's GL context.
     */
    public static synchronized void releaseStealth() {
        if (released) return;
        released = true;

        StealthNative.uninstall();

        // Both windows, because the overlay is a separate HWND with its own
        // affinity. Leaving it set would keep a stale exclusion on a window that
        // no longer exists.
        long overlay = 0L;
        try {
            overlay = com.baggute.cheatclient.render.HudOverlay.hwndValue();
        } catch (Throwable ignored) {
            // Overlay never came up: nothing to clear.
        }
        try {
            com.baggute.cheatclient.render.HudOverlay.shutdown();
        } catch (Throwable failure) {
            LogManager.log("WARN", "overlay shutdown failed during release", failure);
        }
        if (overlay != 0L) CaptureGuard.release(overlay);

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) {
            long hwnd = CaptureGuard.hwndOf(mc.getWindow());
            if (hwnd != 0L) CaptureGuard.release(hwnd);
        }

        exclusionApplied = false;
        screenExcluded = false;
        exclusionMode = CaptureGuard.Outcome.REFUSED;
        panicHidden = true;
        destroyTarget();
        clearOverlay();
        lastDiagnostic = "released";
        LogManager.log("INFO", "stealth layer released, native removed", null);
    }

    /** True once the release keybind has fired. */
    public static boolean released() {
        return released;
    }

    /** Which affinity stuck, for the HUD diagnostic line. */
    public static CaptureGuard.Outcome exclusionMode() {
        return exclusionMode;
    }

    /** True when the client must behave as if a capture is reading the screen. */
    public static boolean exposure() {
        // StreamProof counts: when it has enabled itself and the exclusion was
        // refused, the HUD has to move to the overlay window rather than paint into
        // the game window that a capture is already reading.
        return captureActive()
                || StealthConfig.get().captureGuard()
                || com.baggute.cheatclient.module.misc.StreamProof.hidingFromCapture();
    }

    public static boolean captureActive() {
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_INTERVAL_MS) return captureActive;
        lastScan = now;
        String hit = scan();
        lastCaptureName = hit == null ? "" : hit;
        captureActive = hit != null;
        return captureActive;
    }

    // ---- process scan ----

    /** One entry from a Toolhelp process snapshot. */
    public static final class ProcessEntry extends Structure {
        public int dwSize;
        public int cntUsage;
        public int th32ProcessID;
        public Pointer th32DefaultHeapID;
        public int th32ModuleID;
        public int cntThreads;
        public int th32ParentProcessID;
        public int pcPriClassBase;
        public int dwFlags;
        public byte[] szExeFile = new byte[260];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID",
                    "th32ModuleID", "cntThreads", "th32ParentProcessID", "pcPriClassBase",
                    "dwFlags", "szExeFile");
        }

        String name() {
            int end = 0;
            while (end < szExeFile.length && szExeFile[end] != 0) end++;
            return new String(szExeFile, 0, end, StandardCharsets.ISO_8859_1)
                    .toLowerCase(Locale.ROOT);
        }
    }

    /** Minimal kernel32 binding for the snapshot walk; log4j's JNA already needs it. */
    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int TH32CS_SNAPPROCESS = 0x00000002;

        Pointer CreateToolhelp32Snapshot(int flags, int processId);

        boolean Process32First(Pointer snapshot, ProcessEntry entry);

        boolean Process32Next(Pointer snapshot, ProcessEntry entry);

        boolean CloseHandle(Pointer handle);
    }

    /**
     * First capture process found, or null.
     *
     * <p>A Toolhelp snapshot, not {@code ProcessHandle.info().command()}: that call
     * returns an empty string for plenty of live processes on Windows (the JDK
     * resolves the image path through a handle right that is not always granted),
     * which silently turned this check into "no capture" — the reason a running
     * recorder was not detected. The snapshot needs no privileges at all and
     * always reports the file name.
     */
    private static String scan() {
        if (CaptureGuard.isWindows()) {
            String hit = scanToolhelp();
            if (hit != null) return hit;
            return null;
        }
        return scanFallback();
    }

    private static String scanToolhelp() {
        Pointer snapshot = null;
        try {
            snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
                    Kernel32.TH32CS_SNAPPROCESS, 0);
            // The failure sentinel is (HANDLE)-1, not null.
            if (snapshot == null || Pointer.nativeValue(snapshot) == -1L) return null;

            ProcessEntry entry = new ProcessEntry();
            entry.dwSize = entry.size();
            if (!Kernel32.INSTANCE.Process32First(snapshot, entry)) return null;
            do {
                String name = entry.name();
                if (CAPTURE_PROCESSES.contains(name)) return name;
                if (CAPTURE_NAMES.contains(stripExtension(name))) return name;
                entry.dwSize = entry.size();
            } while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
            return null;
        } catch (Throwable unavailable) {
            lastDiagnostic = "process scan failed: " + unavailable.getClass().getSimpleName();
            return null;
        } finally {
            if (snapshot != null && Pointer.nativeValue(snapshot) != -1L) {
                try {
                    Kernel32.INSTANCE.CloseHandle(snapshot);
                } catch (Throwable ignored) {
                    // Snapshot handle is released by the OS at worst.
                }
            }
        }
    }

    /** Non-Windows: the JDK walk is the only portable option, empty paths included. */
    private static String scanFallback() {
        try {
            return ProcessHandle.allProcesses()
                    .map(ProcessHandle::info)
                    .map(info -> info.command().orElse(""))
                    .map(ScreenBypass::executableName)
                    .filter(name -> CAPTURE_PROCESSES.contains(name)
                            || CAPTURE_NAMES.contains(stripExtension(name)))
                    .findFirst()
                    .orElse(null);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static String executableName(String command) {
        if (command == null || command.isEmpty()) return "";
        int cut = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        String name = cut >= 0 ? command.substring(cut + 1) : command;
        return name.toLowerCase(Locale.ROOT);
    }

    // ---- deferred overlay (native front-buffer draw) ----

    /**
     * Binds a fresh colour-only framebuffer sized to the window.
     *
     * @return false when the GL call failed; the caller then draws nothing
     */
    private static boolean beginOffscreen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getWindow() == null) return false;

            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            if (width <= 0 || height <= 0) return false;

            if (!ensureTarget(width, height)) return false;

            previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, width, height);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            return true;
        } catch (Throwable failure) {
            LogManager.log("WARN", "offscreen bind failed", failure);
            return false;
        }
    }

    private static void endOffscreen() {
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            Minecraft mc = Minecraft.getInstance();
            if (mc.getWindow() != null) {
                GL11.glViewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            }
            StealthNative.setOverlay(colourTexture, targetWidth, targetHeight);
        } catch (Throwable failure) {
            LogManager.log("WARN", "offscreen release failed", failure);
        }
    }

    /**
     * Reads a small patch of the overlay texture back.
     *
     * <p>Empty means the GUI pipeline submitted the HUD to the real back buffer
     * after this class had already restored the binding — the deferral cannot
     * work in this build, and drawing normally during a capture would leak the
     * HUD. In that case the deferral is disabled and suppression takes over.
     */
    private static void verifyDeferral() {
        if (framebuffer == 0 || colourTexture == 0) return;
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            int size = 32;
            ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
            GL11.glReadPixels(Math.max(0, targetWidth / 2 - size / 2),
                    Math.max(0, targetHeight / 2 - size / 2),
                    size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);

            boolean wrote = false;
            for (int i = 3; i < pixels.capacity(); i += 4) {
                if ((pixels.get(i) & 0xFF) != 0) {
                    wrote = true;
                    break;
                }
            }
            deferralVerified = true;
            if (!wrote) {
                deferralBroken = true;
                lastDiagnostic = "deferral produced an empty texture; suppressed instead";
                LogManager.log("WARN", lastDiagnostic, null);
            } else {
                lastDiagnostic = "deferral verified";
            }
        } catch (Throwable failure) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            lastDiagnostic = "deferral read-back failed";
            deferralBroken = true;
            LogManager.log("WARN", lastDiagnostic, failure);
        }
    }

    private static boolean ensureTarget(int width, int height) {
        if (framebuffer != 0 && width == targetWidth && height == targetHeight) return true;
        destroyTarget();

        int fbo = GL30.glGenFramebuffers();
        int texture = GL11.glGenTextures();
        if (fbo == 0 || texture == 0) return false;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(texture);
            LogManager.log("WARN", "offscreen framebuffer incomplete: 0x"
                    + Integer.toHexString(status), null);
            return false;
        }

        framebuffer = fbo;
        colourTexture = texture;
        targetWidth = width;
        targetHeight = height;
        return true;
    }

    private static void destroyTarget() {
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (colourTexture != 0) {
            GL11.glDeleteTextures(colourTexture);
            colourTexture = 0;
        }
    }
}
