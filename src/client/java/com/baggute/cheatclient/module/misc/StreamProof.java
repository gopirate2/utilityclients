// language: Java, file: StreamProof.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.render.CaptureGuard;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.stealth.StealthConfig;
import net.minecraft.client.Minecraft;

/**
 * Screen-capture exclusion.
 *
 * <p>Two placements of the same Windows switch, chosen by what the driver accepts:
 *
 * <ul>
 *   <li><b>Whole window</b> - {@code WDA_EXCLUDEFROMCAPTURE} on the game's HWND.
 *       Discord, OBS and Teams record a black rectangle while the monitor still
 *       shows the game, the HUD and every menu.</li>
 *   <li><b>Overlay window</b> - the game window stays fully visible in the capture
 *       and the client's widgets move to a second, separately excluded top-level
 *       HWND floating over the client area ({@code HudOverlay}). This is the
 *       fallback the module requests when the game window's affinity is refused: a
 *       refused exclusion on one window does not mean a plain {@code WS_POPUP}
 *       window will be refused as well, and it is the only arrangement where the
 *       capture gets clean gameplay instead of a black frame.</li>
 * </ul>
 *
 * <p>Windows excludes windows, not layers, so a single process cannot hide only its
 * overlay - which is exactly why the second window exists.
 *
 * <p>The affinity is applied at {@code ClientLifecycleEvents.CLIENT_STARTED} by
 * {@code StealthInitializer}, the first moment an HWND exists, and this module owns
 * it afterwards: applied on enable if it is not already in force, verified by
 * read-back once a second, and re-applied when DWM drops it on a resize.
 */
public final class StreamProof extends Module {

    /** Ticks between read-back verifications of the applied affinity. */
    private static final int VERIFY_INTERVAL_TICKS = 20;

    private final BoolSetting excludeWindow =
            new BoolSetting("excludeWindow", "Hide the game window from captures", true);
    private final BoolSetting hideWhenUnsupported =
            new BoolSetting("hideHud", "Request the second, excluded overlay window when exclusion fails", true);
    private final BoolSetting compatibilityMode =
            new BoolSetting("compatibilityMode",
                    "Fall back to WDA_MONITOR when the driver refuses WDA_EXCLUDEFROMCAPTURE. "
                            + "The capture then shows a black window instead of gameplay.",
                    StealthConfig.get().compatibilityMode());

    /** True when the game window is excluded and the player asked for it. */
    private static volatile boolean hiding;

    /** True when the overlay window should carry the HUD instead of the game window. */
    private static volatile boolean overlayWanted;

    /**
     * Set by {@link StealthInitializer} when the startup application of the window
     * affinity was refused.
     *
     * <p>Held separately from {@link #overlayWanted} because that field is recomputed
     * from live state every tick: without a latch, a refusal decided once at startup
     * would be overwritten on the next tick by the module's own "is it excluded?"
     * logic and the fallback would never stand up.
     */
    private static volatile boolean fallbackForced;

    /**
     * Mirrors the module's enabled flag for code that holds no module instance.
     *
     * <p>{@link StealthInitializer} runs from lifecycle events, where there is no
     * module reference to ask, and reaching into the module manager from there would
     * tie the window hook to its initialisation order. Written on every enable and
     * every disable, so it is already correct on the first tick after a toggle and
     * the startup path reads the same answer the player sees in the menu.
     */
    private static volatile boolean moduleEnabled;
    private static volatile boolean fallbackAllowed;

    /** Which affinity is currently in force, for the HUD diagnostic line. */
    private static volatile CaptureGuard.Outcome mode = CaptureGuard.Outcome.REFUSED;

    private long appliedWindow = -1L;
    private boolean excluded;
    private boolean lastCompatibilityMode;
    private int verifyTicks;

    public StreamProof() {
        super("StreamProof", "Hides the client from screen capture and streaming.", Category.MISC);
        addSetting(excludeWindow);
        addSetting(hideWhenUnsupported);
        addSetting(compatibilityMode);
    }

    /**
     * True when the game window is hidden from capture and client drawing should be
     * treated as sensitive by the world-space layers.
     */
    public static boolean hidingFromCapture() {
        return hiding || (moduleEnabled && fallbackAllowed
                && !com.baggute.cheatclient.stealth.ScreenBypass.gameExclusionLive());
    }

    /**
     * True when the native exclusion on the game window was refused and the second,
     * separately excluded window is the only way to keep the HUD out of the capture.
     */
    public static boolean overlayFallbackRequested() {
        return overlayWanted || hidingFromCapture();
    }

    /** Which affinity the window is actually carrying. */
    public static CaptureGuard.Outcome appliedMode() {
        return mode;
    }

    /**
     * True while the module is switched on, readable without the module instance.
     *
     * <p>The single question the window hook asks before touching an HWND. A disabled
     * module means no affinity is applied and any leftover one is released - the
     * switch is the whole permission model for the call.
     */
    public static boolean moduleEnabled() {
        return moduleEnabled;
    }

    /**
     * Latches the overlay fallback on.
     *
     * <p>Called from {@link StealthInitializer} when {@code applyBest} came back
     * {@code REFUSED} at {@code CLIENT_STARTED}: the game window will be visible in
     * every capture, so the client's own drawing has to move to the second,
     * separately excluded top-level window or it ships straight to the recording.
     */
    public static void requestOverlayFallback() {
        if (fallbackForced) return;
        fallbackForced = true;
        overlayWanted = true;
        UtilityClient.LOGGER.warn("[StreamProof] window affinity refused at startup;"
                + " overlay fallback latched");
    }

    /** Drops the latch. Used when the affinity is re-applied and takes. */
    public static void clearOverlayFallback() {
        fallbackForced = false;
    }

    @Override
    public void onTick() {
        fallbackAllowed = excludeWindow.get() && hideWhenUnsupported.get();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;

        // The setting is the GUI-facing copy; stealth.json is what survives a
        // restart, so a change in one has to reach the other. Compare before
        // saving: setCompatibilityMode() writes the file on every call.
        boolean compat = compatibilityMode.get();
        if (compat != lastCompatibilityMode) {
            lastCompatibilityMode = compat;
            StealthConfig.get().setCompatibilityMode(compat);
            // A driver that accepted WDA_EXCLUDEFROMCAPTURE will not start refusing
            // it because the operator flipped a fallback switch, but the reverse
            // happens, so the cached outcome is dropped and re-measured.
            excluded = false;
            appliedWindow = -1L;
        }

        long window = CaptureGuard.glfwHandle(mc.getWindow());
        boolean canExclude = excludeWindow.get() && CaptureGuard.supported() && window != 0L;

        if (!canExclude) {
            // Hand the window back before reporting anything: a player who turned the
            // switch off, or whose driver lost the call, must not keep a window the
            // recorder cannot see.
            releaseOwned(window);
            hiding = false;
            overlayWanted = hideWhenUnsupported.get() && excludeWindow.get() && !CaptureGuard.supported();
            return;
        }

        if (!excluded || window != appliedWindow) {
            apply(window, compat);
        } else if (++verifyTicks >= VERIFY_INTERVAL_TICKS) {
            verifyTicks = 0;
            verify(window);
        }

        // Refused: the game window is fully visible in the capture, so the HUD can
        // only be kept out of it by a second window that is excluded on its own.
        // A startup refusal is latched and outranks the live recomputation.
        overlayWanted = fallbackForced || (!excluded && hideWhenUnsupported.get());
        // The world-space layers read this: with no exclusion in force they stop
        // drawing into the frame a recorder would see.
        hiding = overlayWanted;
    }

    /** Applies the strongest affinity the driver accepts and records the outcome. */
    private void apply(long window, boolean compat) {
        CaptureGuard.Applied applied = CaptureGuard.applyBest(CaptureGuard.hwndOf(window), compat);
        mode = applied.outcome();
        excluded = applied.hidden();
        appliedWindow = window;
        verifyTicks = 0;
        // Published immediately: the rest of the client asks this file whether the
        // window is carrying an affinity, and a stale answer here is what let a
        // disabled module keep an exclusion running.
        StealthConfig.get().setAffinityState(applied.hidden(),
                applied.hidden() ? CaptureGuard.hwndOf(window) : 0L);

        UtilityClient.LOGGER.info("[StreamProof] affinity {} on window 0x{}",
                applied.outcome(), Long.toHexString(window));
    }

    /**
     * Drops an affinity this module owns and clears every latch that could re-apply it.
     *
     * <p>Guarded by ownership rather than by {@code excluded}, because the startup
     * ladder can leave an affinity on the window that this module's own fields never
     * recorded. The check is {@code StealthConfig}'s live state plus both local
     * caches, and the release is unconditional once any of them says it is ours -
     * {@code WDA_NONE} on a window that is already clean is a no-op.
     */
    private void releaseOwned(long window) {
        boolean owned = StealthConfig.get().affinityActive() || excluded || appliedWindow != -1L;
        if (!owned) return;

        long glfw = window != 0L ? window : currentWindow();
        long hwnd = glfw == 0L ? 0L : CaptureGuard.hwndOf(glfw);
        boolean released = hwnd != 0L && (com.baggute.cheatclient.stealth.ScreenBypass.gameExclusionRequested()
                || CaptureGuard.release(hwnd));

        excluded = false;
        appliedWindow = -1L;
        mode = CaptureGuard.Outcome.REFUSED;
        StealthConfig.get().setAffinityState(false, 0L);

        if (released) {
            UtilityClient.LOGGER.info("[StreamProof] window exclusion released on 0x{}",
                    Long.toHexString(hwnd));
        }
    }

    /**
     * Read-back check. DWM can drop the affinity when the window is resized or
     * recomposed, and a dropped affinity is a client visible in a recording with no
     * error anywhere - so the applied state is compared against the live state and
     * re-applied when it has gone.
     */
    private void verify(long window) {
        long hwnd = CaptureGuard.hwndOf(window);
        if (hwnd == 0L) return;

        int live;
        int expected;
        switch (mode) {
            case EXCLUDED -> { live = CaptureGuard.readAffinity(hwnd); expected = CaptureGuard.WDA_EXCLUDEFROMCAPTURE; }
            case MONITORED -> { live = CaptureGuard.readAffinity(hwnd); expected = CaptureGuard.WDA_MONITOR; }
            default -> { return; }
        }
        // Unreadable protection is withdrawn until a matching read-back succeeds.
        if (live == expected) return;

        excluded = false;
        appliedWindow = -1L;
        StealthConfig.get().setAffinityState(false, 0L);
        UtilityClient.LOGGER.warn("[StreamProof] affinity lost (was {}, now {}); re-applying",
                expected, live);
    }

    @Override
    protected void onEnable() {
        // First statement: the window hook reads this before it resolves a handle, so
        // the flag has to be true before any application can be attempted.
        moduleEnabled = true;
        fallbackAllowed = excludeWindow.get() && hideWhenUnsupported.get();
        hiding = false;
        overlayWanted = false;
        excluded = false;
        appliedWindow = -1L;
        verifyTicks = 0;
        mode = CaptureGuard.Outcome.REFUSED;
        lastCompatibilityMode = StealthConfig.get().compatibilityMode();

        Minecraft mc = Minecraft.getInstance();
        long window = mc == null || mc.getWindow() == null
                ? 0L : CaptureGuard.glfwHandle(mc.getWindow());
        if (CaptureGuard.supported() && window != 0L && excludeWindow.get()) {
            // The lifecycle hook may already have applied it; applyBest is a
            // read-verified idempotent call, so this cannot undo a working state.
            apply(window, lastCompatibilityMode);
        } else {
            UtilityClient.LOGGER.warn("[StreamProof] window exclusion unavailable here;"
                    + " the overlay window carries the HUD instead");
            StealthConfig.get().setAffinityState(false, 0L);
            overlayWanted = hideWhenUnsupported.get() && excludeWindow.get();
        }
        UtilityClient.LOGGER.info("[StreamProof] enabled");
    }

    @Override
    protected void onDisable() {
        // Order is load-bearing. The flag drops first so the startup ladder cannot
        // re-apply an affinity between the release and the end of this method; the
        // latch goes next so a refused startup cannot keep the overlay path alive;
        // the window is handed back last.
        moduleEnabled = false;
        fallbackAllowed = false;
        fallbackForced = false;

        releaseOwned(currentWindow());

        hiding = false;
        overlayWanted = false;
        verifyTicks = 0;
        mode = CaptureGuard.Outcome.REFUSED;
        UtilityClient.LOGGER.info("[StreamProof] disabled");
    }

    /** The GLFW handle behind the game window, or 0 when there is no window yet. */
    private static long currentWindow() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 0L : CaptureGuard.glfwHandle(mc.getWindow());
    }
}
