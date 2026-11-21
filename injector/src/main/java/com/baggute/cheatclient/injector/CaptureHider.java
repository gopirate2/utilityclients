// language: Java, file: CaptureHider.java, target: injector UI (Windows)
package com.baggute.cheatclient.injector;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.Window;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides the injector window from every screen capture, sharing app and remote
 * desktop session on this machine.
 *
 * <p>Windows 10 2004 and later honour {@code SetWindowDisplayAffinity} with
 * {@code WDA_EXCLUDEFROMCAPTURE}: the window keeps rendering on the physical
 * display, and every capture path that goes through DWM (Discord, OBS with display
 * or window capture, Teams, Zoom, the Snipping Tool, Print Screen) receives black
 * where the window is. That is stronger than "mostly invisible" — there is no
 * window in the captured frame to see.
 *
 * <p>Two limits worth knowing:
 * <ul>
 *   <li>Layered windows are refused by this API, silently, with
 *       {@code GetLastError() == 0}. The injector window is a plain AWT window, so
 *       it is accepted; a future transparent/layered window would not be.</li>
 *   <li>A capture that reads the framebuffer directly, bypassing DWM, is outside
 *       what any user-mode API can hide from. Nothing here can cover that.</li>
 * </ul>
 *
 * <p><b>Two paths, one switch.</b> {@link #applyTo(Pointer)} takes an exact handle
 * and is what the header toggle calls, so flipping it does something on the same
 * event rather than on the next sweep. The sweep exists for the windows the handle
 * cannot name: the file chooser, a tooltip, a modal created later. Both are gated
 * by {@link #setEnabled(boolean)}, and turning it off walks the marked handles and
 * puts each back to {@code WDA_NONE} - which is what makes the window screenshot-able
 * again for a support post.
 *
 * <p>Nothing here touches the game. This is the injector's own window, and the
 * client's StreamProof module is a separate mechanism in a separate process.
 */
public final class CaptureHider {

    /** Keep the window out of every DWM capture. Windows 10 2004+. */
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    /** Capture sees the window normally. */
    private static final int WDA_NONE = 0x00;

    /**
     * Handles this process has marked, so a disabled toggle can put them back.
     *
     * <p>{@link Pointer} rather than the raw {@code long}: a handle has to be handed
     * back to {@code SetWindowDisplayAffinity} to be released, and a long would need
     * a fabricated pointer to get there.
     */
    private static final Set<Pointer> MARKED = ConcurrentHashMap.newKeySet();

    private static volatile boolean enabled = true;
    private static volatile boolean running;
    private static volatile Thread sweepThread;
    private static volatile int excluded;
    private static volatile String lastError = "not started";

    private CaptureHider() {
    }

    /**
     * Turns the exclusion on or off for this window and every window it owns.
     *
     * <p>Off releases every handle that was marked, including ones the sweep found,
     * and stops the sweep. On marks immediately and restarts it. Safe to call with
     * the same value twice.
     */
    public static void setEnabled(boolean on) {
        enabled = on;
        if (!isWindows()) return;
        if (on) {
            startSweep();
            hideNow();
        } else {
            stopSweep();
            revealNow();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Windows actually excluded on the last pass. */
    public static int excludedWindows() {
        return excluded;
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * The native handle behind a Swing window, or null when there is none.
     *
     * <p>{@code Native.getWindowPointer} asks AWT for its {@code ComponentPeer}, so
     * it throws before the window is displayable and on a non-Windows desktop.
     * Both are ordinary states here, not errors: the caller applies the affinity
     * after {@code setVisible(true)}, and a Linux build simply has no handle.
     */
    public static Pointer pointerOf(Window window) {
        if (!isWindows() || window == null || !window.isDisplayable()) return null;
        try {
            return Native.getWindowPointer(window);
        } catch (Throwable unavailable) {
            lastError = "no window handle (" + unavailable.getClass().getSimpleName() + ")";
            return null;
        }
    }

    /**
     * Marks one exact window. This is the call the toggle makes.
     *
     * @return true when Windows accepted the affinity for that handle
     */
    public static boolean applyTo(Pointer hwnd) {
        if (!isWindows() || hwnd == null || !enabled) return false;
        try {
            int[] live = new int[1];
            if (User32.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)
                    && User32.INSTANCE.GetWindowDisplayAffinity(hwnd, live)
                    && live[0] == WDA_EXCLUDEFROMCAPTURE) {
                MARKED.add(hwnd);
                lastError = "excluded";
                return true;
            }
            // A refusal is silent in this API: layered windows and windows owned by
            // another process both come back false with no extended error.
            lastError = "hwnd " + Long.toHexString(Pointer.nativeValue(hwnd))
                    + " refused (" + Native.getLastError() + ")";
            return false;
        } catch (Throwable failure) {
            lastError = failure.getClass().getSimpleName();
            return false;
        }
    }

    /** Puts one window back into capture. */
    public static boolean releaseFrom(Pointer hwnd) {
        if (!isWindows() || hwnd == null) return false;
        try {
            MARKED.remove(hwnd);
            return User32.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_NONE);
        } catch (Throwable failure) {
            lastError = failure.getClass().getSimpleName();
            return false;
        }
    }

    private static synchronized void startSweep() {
        if (running) return;
        running = true;
        Thread thread = new Thread(CaptureHider::sweep, "capture-hider");
        sweepThread = thread;
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static synchronized void stopSweep() {
        running = false;
        Thread old = sweepThread;
        sweepThread = null;
        if (old != null) old.interrupt();
    }

    private static void sweep() {
        // The first window does not exist yet at enable time; the first pass after
        // the EDT shows it is what matters.
        while (running && sweepThread == Thread.currentThread()) {
            if (enabled) {
                try {
                    hideNow();
                } catch (Throwable failure) {
                    lastError = failure.getClass().getSimpleName();
                }
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Marks every visible top-level window of this process. Returns how many. */
    public static synchronized int hideNow() {
        if (!isWindows() || !enabled) return 0;

        int pid = Kernel32.INSTANCE.GetCurrentProcessId();
        int[] count = {0};
        String[] error = {null};

        User32.EnumWindowsCallback callback = (hwnd, data) -> {
            try {
                int[] owner = new int[1];
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
                if (owner[0] != pid) return true;
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;

                if (MARKED.contains(hwnd)) {
                    int[] live = new int[1];
                    if (User32.INSTANCE.GetWindowDisplayAffinity(hwnd, live)
                            && live[0] == WDA_EXCLUDEFROMCAPTURE) {
                        count[0]++;
                        return true;
                    }
                    MARKED.remove(hwnd); // window recreated its surface
                }

                if (applyTo(hwnd)) {
                    MARKED.add(hwnd);
                    count[0]++;
                } else if (error[0] == null) {
                    error[0] = "hwnd " + Long.toHexString(Pointer.nativeValue(hwnd))
                            + " refused (" + Native.getLastError() + ")";
                }
            } catch (Throwable ignored) {
                // A window that vanishes mid-enumeration must not stop the sweep.
            }
            return true;
        };

        User32.INSTANCE.EnumWindows(callback, null);
        excluded = count[0];
        lastError = count[0] > 0 ? "excluded" : (error[0] == null ? "no window yet" : error[0]);
        return count[0];
    }

    /**
     * Restores capture for every window this process marked. Returns how many.
     *
     * <p>Driven by the marked set rather than by a fresh enumeration: a window that
     * was excluded and has since been disposed cannot be found by handle, and the
     * ones that still exist are exactly the ones in here.
     */
    public static synchronized int revealNow() {
        int released = 0;
        for (Pointer hwnd : MARKED.toArray(new Pointer[0])) {
            try {
                if (User32.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_NONE)) released++;
            } catch (Throwable ignored) {
                // A disposed window needs no restoring.
            }
            MARKED.remove(hwnd);
        }
        excluded = 0;
        lastError = isWindows() ? "visible to capture" : "not windows";
        return released;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        interface EnumWindowsCallback extends StdCallLibrary.StdCallCallback {
            boolean callback(Pointer hwnd, Pointer data);
        }

        boolean EnumWindows(EnumWindowsCallback callback, Pointer data);

        int GetWindowThreadProcessId(Pointer hwnd, int[] processId);

        boolean IsWindowVisible(Pointer hwnd);

        boolean SetWindowDisplayAffinity(Pointer hwnd, int affinity);

        boolean GetWindowDisplayAffinity(Pointer hwnd, int[] affinity);
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int GetCurrentProcessId();
    }
}
