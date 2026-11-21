// language: Java, file: StealthInitializer.java, target: Fabric 26.2
package com.baggute.cheatclient.stealth;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.misc.StreamProof;
import com.baggute.cheatclient.render.CaptureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies the window exclusion at the first moment it can actually succeed, and
 * keeps trying until it does - but only while the player has asked for it.
 *
 * <p><b>Ownership.</b> The affinity belongs to the {@code StreamProof} module, not to
 * this class. This hook exists because the module cannot run early enough: the first
 * frames are presented before the first tick, and an affinity applied after that
 * window has closed is dropped by DWM on the next compose. So the startup path
 * applies an affinity <em>on the module's behalf</em>, and every entry point here
 * begins by asking the module whether it is on. A disabled module means no
 * affinity, in both directions: nothing is applied, and anything left over from a
 * previous session is handed back.
 *
 * <p><b>Why {@code CLIENT_STARTED} and not the mod initialiser.</b>
 * {@code UtilityClient.onInitializeClient} runs from inside the {@code Minecraft}
 * constructor, before {@code Window} exists, so {@code mc.getWindow()} is null and
 * every handle resolution returns 0. The affinity call then fails closed on a zero
 * handle and, because it fails silently, {@code StreamProof} reads as enabled while
 * doing nothing.
 *
 * <p><b>Why the retry ladder.</b> There is a second, less obvious way the same call
 * fails: {@code SetWindowDisplayAffinity} returns success before DWM has composed
 * the window once, and the affinity is dropped at the first compose. Startup runs
 * before the first frame, which is exactly the window of time where that happens.
 * So a single attempt is not enough - the outcome is re-measured over the first few
 * ticks, after the first frames have been presented, and re-applied until the
 * read-back agrees. Every attempt ends in {@code GetWindowDisplayAffinity}, so
 * "success" here means the window is actually excluded, not that a call returned.
 *
 * <p><b>Why the overlay fallback.</b> If no affinity sticks - an unsupported driver,
 * a locked-down desktop, a remote session - the game window will be visible in
 * every capture. The client's own drawing then has to move to a second, separately
 * excluded window, or the overlay ships straight into the recording. The refusal is
 * latched into {@link StreamProof} so the render path knows before the next frame.
 */
public final class StealthInitializer {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    /**
     * Ticks after {@code CLIENT_STARTED} at which a refused application is retried.
     *
     * <p>Spread out rather than every tick: the first compose happens within a frame
     * or two, the first few frames are the useful window, and hammering the call on
     * a driver that has genuinely said no just spams the log. Past the last entry the
     * overlay fallback owns the job.
     */
    private static final int[] RETRY_TICKS = {2, 10, 40};

    /**
     * Ticks between visibility sweeps while the module is off.
     *
     * <p>Once a second is enough for a watchdog: it is not re-measuring a fresh
     * application, it is catching an affinity that outlived the module - a leftover
     * from an earlier session, or a release that failed while the window was being
     * recomposed. A per-tick sweep would be twenty syscalls a second for an answer
     * that changes at most once.
     */
    private static final int WATCHDOG_TICKS = 20;

    private static int retryIndex;
    private static int tickCounter;
    private static boolean applied;
    private static int watchdogTicks;
    private static boolean restoreLogged;

    private StealthInitializer() {
    }

    /** Registers the lifecycle hook. Safe to call more than once. */
    public static void init() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        ClientLifecycleEvents.CLIENT_STARTED.register(StealthInitializer::onClientStarted);
        if (com.baggute.cheatclient.UtilityClient.LATE_INIT) {
            onClientStarted(Minecraft.getInstance());
        }
        LogManager.log("INFO", "stealth initializer armed (CLIENT_STARTED + retry ladder,"
                + " gated on StreamProof)", null);
    }

    private static void onClientStarted(Minecraft mc) {
        // The module owns the affinity. Off means nothing is applied here, and any
        // affinity inherited from a previous session is released below.
        if (!StreamProof.moduleEnabled()) {
            retryIndex = RETRY_TICKS.length;
            applied = false;
            restoreVisibility(mc, true);
            LogManager.log("INFO", "StreamProof disabled; startup window exclusion skipped", null);
            return;
        }

        // A released client has already torn the layer down; re-applying an
        // affinity here would undo the release key in the same session.
        if (UtilityClient.isUnloaded() || ScreenBypass.released()) return;

        // Idempotent: loads the swap hook if stealth.json wants it. Runs before
        // the first frame, so the hook is in place before any buffer is swapped.
        ScreenBypass.init();

        if (mc == null || mc.getWindow() == null) {
            LogManager.log("WARN", "no window at CLIENT_STARTED; exclusion deferred to tick", null);
            return;
        }

        retryIndex = 0;
        tickCounter = 0;
        watchdogTicks = 0;
        applied = false;
        restoreLogged = false;

        if (!CaptureGuard.isWindows()) {
            LogManager.log("INFO", "exclusion unavailable: not windows", null);
            StreamProof.requestOverlayFallback();
            return;
        }
        if (!CaptureGuard.supported()) {
            LogManager.log("INFO", "exclusion unavailable: SetWindowDisplayAffinity missing ("
                    + CaptureGuard.lastError() + ")", null);
            StreamProof.requestOverlayFallback();
            return;
        }

        attempt(mc, "startup");
    }

    /**
     * Retry ladder, plus the watchdog that runs when the module is off.
     *
     * <p>The ladder stops the moment the module is disabled: a retry exists to make
     * an affinity the player asked for stick, and once the ask is withdrawn a retry
     * is the bug. The same branch hands back anything still excluded, so turning the
     * switch off restores the window on the next tick even if the module's own
     * {@code onDisable} could not reach a handle.
     */
    public static void onTick(Minecraft mc) {
        if (UtilityClient.isUnloaded() || ScreenBypass.released()) return;
        if (mc == null || mc.getWindow() == null) return;

        if (!StreamProof.moduleEnabled()) {
            retryIndex = RETRY_TICKS.length;
            applied = false;
            // Cheap gate first, then a sweep once a second for an affinity that some
            // other path left behind, so a disabled module cannot stay hidden.
            if (StealthConfig.get().affinityActive() || watchdogTicks++ % WATCHDOG_TICKS == 0) {
                restoreVisibility(mc, false);
            }
            return;
        }

        if (applied || retryIndex >= RETRY_TICKS.length) return;

        tickCounter++;
        if (tickCounter < RETRY_TICKS[retryIndex]) return;
        retryIndex++;

        attempt(mc, "retry " + retryIndex);
    }

    /**
     * One full application attempt: resolve the handle, ask for the strongest
     * affinity the driver accepts, and verify by read-back.
     *
     * @param phase logged with the outcome so the log says which attempt stuck
     */
    private static void attempt(Minecraft mc, String phase) {
        // Guarded here as well as at the two call sites: this is the only method
        // that touches the window, and a future caller must not be able to apply an
        // exclusion the module never asked for.
        if (!StreamProof.moduleEnabled()) return;

        long glfw = glfwWindow(mc);
        long hwnd = hwnd(glfw);

        if (hwnd == 0L) {
            LogManager.log("WARN", "no win32 handle at " + phase + " (glfw=0x"
                    + Long.toHexString(glfw) + "); " + CaptureGuard.lastError(), null);
            // A zero handle is not a refusal: the window may simply not be created
            // yet. Leave the ladder running, and only fall back when it is spent.
            if (retryIndex >= RETRY_TICKS.length) StreamProof.requestOverlayFallback();
            return;
        }

        // Strict first: WDA_EXCLUDEFROMCAPTURE leaves the window fully visible on the
        // physical monitor and black in the capture, which is the only outcome where
        // nothing changes for the player.
        CaptureGuard.Applied result = CaptureGuard.applyBest(hwnd, false);

        if (!result.hidden() && StealthConfig.get().compatibilityMode()) {
            // Compatibility mode is opt-in and it means "accept a black window in the
            // capture rather than nothing at all". It is a second attempt, not the
            // default, so a driver that supports the modern switch never sees it.
            CaptureGuard.Applied compat = CaptureGuard.applyBest(hwnd, true);
            if (compat.hidden()) {
                result = compat;
                LogManager.log("INFO", "compatibility fallback accepted: "
                        + compat.outcome() + " (capture shows a black window)", null);
            }
        }

        int affinity = CaptureGuard.readAffinity(hwnd);
        String detail = switch (result.outcome()) {
            case EXCLUDED -> "window excluded from capture";
            case MONITORED -> "WDA_MONITOR in force: window black in capture";
            case REFUSED -> "SetWindowDisplayAffinity refused";
        };
        LogManager.log(result.hidden() ? "INFO" : "WARN",
                phase + ": " + detail + " (hwnd=0x" + Long.toHexString(hwnd)
                        + ", affinity read-back=" + affinity + ")", null);

        if (result.hidden()) {
            applied = true;
            restoreLogged = false;
            StealthConfig.get().setAffinityState(true, hwnd);
            StreamProof.clearOverlayFallback();
            return;
        }

        StealthConfig.get().setAffinityState(false, 0L);

        // Ladder spent and still refused: the game window is visible in capture, so
        // the client's drawing moves to the second, separately excluded window.
        if (retryIndex >= RETRY_TICKS.length) {
            StreamProof.requestOverlayFallback();
            LogManager.log("WARN", "exclusion refused after " + (retryIndex + 1)
                    + " attempts; overlay window carries the HUD instead", null);
        }
    }

    /**
     * Hands the window back to capture when the module is off.
     *
     * <p>Only an affinity this layer owns is released. A menu-open exclusion belongs
     * to {@link ScreenBypass}: it applies and drops its own affinity around the
     * screen, and tearing that down here would flash a client menu into a recording
     * once a second.
     *
     * @param startup true at {@code CLIENT_STARTED}, where the log line explains why
     *                no exclusion was applied rather than reporting a repair
     */
    private static void restoreVisibility(Minecraft mc, boolean startup) {
        if (!CaptureGuard.isWindows()) return;

        long hwnd = CaptureGuard.hwndOf(mc == null ? null : mc.getWindow());
        if (hwnd == 0L) return;

        boolean ours = StealthConfig.get().affinityActive();
        int live = CaptureGuard.readAffinity(hwnd);

        if (!ours) {
            // Nothing of ours is in force. The read is only used to report the state;
            // an exclusion that belongs to another path is left alone.
            if (live != -1 && live != CaptureGuard.WDA_NONE && !ScreenBypass.gameExclusionRequested()
                    && !startup) {
                // Not ours and not ScreenBypass: a leftover from a previous run. It is
                // still a window the recorder cannot see, and the module is off, so it
                // goes. Read-back decides, not a cached flag.
                if (CaptureGuard.release(hwnd) && !restoreLogged) {
                    restoreLogged = true;
                    LogManager.log("INFO", "released a stale window exclusion"
                            + " (affinity read-back=" + live + ", StreamProof disabled)", null);
                }
            }
            return;
        }

        boolean released = ScreenBypass.gameExclusionRequested()
                || CaptureGuard.release(hwnd);
        StealthConfig.get().setAffinityState(false, 0L);
        applied = false;
        if (released && !restoreLogged) {
            restoreLogged = true;
            LogManager.log("INFO", startup
                    ? "StreamProof off at startup; window exclusion released"
                    : "StreamProof disabled; window exclusion released (affinity "
                            + live + " cleared)", null);
        }
    }

    /** The GLFW window behind {@code mc.getWindow()}, or 0. */
    private static long glfwWindow(Minecraft mc) {
        if (mc == null || mc.getWindow() == null) return 0L;
        long handle = CaptureGuard.glfwHandle(mc.getWindow());
        if (handle != 0L) return handle;
        LogManager.log("WARN", "GLFW window handle unresolved; " + CaptureGuard.lastError(), null);
        return 0L;
    }

    /**
     * The Win32 handle for a GLFW window.
     *
     * <p>Named explicitly rather than left to a reflective lookup: this is the whole
     * failing link in the chain. {@code glfwGetWin32Window} is shipped by LWJGL, so
     * there is no extra native, and a throw here is logged instead of swallowed.
     */
    private static long hwnd(long glfw) {
        if (glfw == 0L) return 0L;
        try {
            return GLFWNativeWin32.glfwGetWin32Window(glfw);
        } catch (Throwable failure) {
            LogManager.log("WARN", "glfwGetWin32Window threw "
                    + failure.getClass().getSimpleName(), null);
            // Last resort: the guarded path inside CaptureGuard, which fails closed.
            return CaptureGuard.hwndOf(glfw);
        }
    }

    /** Diagnostic for the HUD and the terminal. */
    public static String diagnostic() {
        if (!StreamProof.moduleEnabled()) {
            return "affinity: off (StreamProof disabled)";
        }
        StealthConfig config = StealthConfig.get();
        if (config.affinityActive()) {
            return "affinity: active on hwnd=0x" + Long.toHexString(config.affinityWindow());
        }
        return "affinity: not applied (retries " + retryIndex + "/" + RETRY_TICKS.length + ")";
    }
}
