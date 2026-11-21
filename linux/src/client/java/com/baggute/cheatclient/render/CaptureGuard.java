// language: Java, file: CaptureGuard.java, target: Fabric 26.2 (JDK 25, JNA)
package com.baggute.cheatclient.render;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Hides a window from screen capture: OBS, Discord, Teams, and anything else
 * built on Windows Graphics Capture or BitBlt.
 *
 * <p>{@code SetWindowDisplayAffinity} with {@code WDA_EXCLUDEFROMCAPTURE} tells DWM
 * to substitute black for this window in every capture path while still drawing it
 * normally for the user. Nothing hooks Discord and nothing is patched in the
 * renderer: it is the same switch Microsoft exposes for protected video.
 *
 * <p>Reached through JNA, which Fabric Loader already ships to every mod, so there
 * is no DLL to compile, no CMake, and no toolchain on the build machine. Every
 * entry point fails closed: on a non-Windows platform, a locked-down runtime, or a
 * rejected handle the caller gets {@code false} and the module falls back to
 * hiding its own drawing.
 *
 * <p>{@link User32Ext} exists because JNA's bundled {@code User32} mapping predates
 * {@code SetWindowDisplayAffinity} and still omits it. Extending the interface
 * keeps the {@code User32.INSTANCE} shape while declaring the two exports Win32
 * actually has; JNA resolves unmapped method names by their literal spelling, and
 * neither function has an A/W pair.
 */
public final class CaptureGuard {

    /** Show the window to capture again (default). */
    public static final int WDA_NONE = 0x0;

    /**
     * Paint this window black in every screen capture.
     *
     * <p>The pre-2004 switch, and the only one some drivers honour. It is a
     * strictly worse deal than {@link #WDA_EXCLUDEFROMCAPTURE}: the capture gets a
     * black rectangle where the game should be rather than the game without its
     * overlay, and on a few driver builds the black also reaches the physical
     * display. That is why it is the fallback and not the default.
     */
    public static final int WDA_MONITOR = 0x1;

    /** Replace this window with black in every screen capture. */
    public static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    /** What a click on {@link #applyBest} actually achieved. */
    public enum Outcome {
        /** The window is hidden from capture but visible on the monitor. */
        EXCLUDED,
        /** The window is black in capture; possibly also on the monitor. */
        MONITORED,
        /** Nothing stuck. Callers must hide their own drawing. */
        REFUSED
    }

    /** Result of {@link #applyBest}, carrying the affinity that is actually set. */
    public record Applied(Outcome outcome, int affinity) {
        public boolean hidden() {
            return outcome != Outcome.REFUSED;
        }
    }

    /**
     * user32 with the display-affinity pair bolted on.
     *
     * <p>Both are declared {@code int} rather than {@code boolean}: the real
     * signature returns {@code BOOL}, and JNA's implicit int-to-boolean mapping
     * would turn any nonzero value into true - including a future non-error status
     * code. Comparing the raw {@code BOOL} against zero is the only reading that
     * stays correct whatever Windows starts returning there.
     */
    public interface User32Ext extends User32 {
        User32Ext INSTANCE =
                Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        int SetWindowDisplayAffinity(Pointer hwnd, int affinity);

        int GetWindowDisplayAffinity(Pointer hwnd, IntByReference affinity);
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    /** Cached accessor for the raw GLFW handle inside {@code net.minecraft.client.Window}. */
    private static java.lang.reflect.Method handleAccessor;

    private static volatile User32Ext user32;
    private static volatile boolean resolved;
    private static volatile String lastError = "";

    private CaptureGuard() {
    }

    /**
     * The loaded user32 mapping, or null when JNA cannot reach it.
     *
     * <p>Resolved once and cached, including the failure: a runtime that refuses the
     * native load will refuse it on every frame, and retrying per tick would just
     * flood the log. {@code resolved} is set after the attempt rather than inside
     * the try so that a throw cannot leave the class retrying forever.
     */
    private static User32Ext user32() {
        User32Ext current = user32;
        if (resolved) return current;
        synchronized (CaptureGuard.class) {
            if (resolved) return user32;
            try {
                user32 = User32Ext.INSTANCE;
                lastError = "";
            } catch (Throwable failure) {
                user32 = null;
                lastError = "user32 unavailable: " + failure.getClass().getSimpleName();
            }
            resolved = true;
            return user32;
        }
    }

    /** True when the call could plausibly work; false short-circuits before use. */
    public static boolean supported() {
        return WINDOWS && user32() != null;
    }

    /** True on a Windows host, regardless of whether the call is available. */
    public static boolean isWindows() {
        return WINDOWS;
    }

    /** Why the last resolution attempt failed, or an empty string. */
    public static String lastError() {
        return lastError;
    }

    /** The Win32 HWND behind a GLFW window, or 0 when it cannot be resolved. */
    public static long hwndOf(long glfwWindow) {
        if (!WINDOWS || glfwWindow == 0L) return 0L;
        try {
            return GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        } catch (Throwable failure) {
            return 0L;
        }
    }

    /** The GLFW window handle behind Minecraft's window, or 0 when unavailable. */
    public static long hwndOf(com.mojang.blaze3d.platform.Window window) {
        if (window == null) return 0L;
        return hwndOf(glfwHandle(window));
    }

    /**
     * Resolves {@code Window.getWindow()} reflectively: the accessor has been
     * renamed between Minecraft versions, and a failed lookup is just "no
     * exclusion" rather than a compile error.
     */
    public static long glfwHandle(com.mojang.blaze3d.platform.Window window) {
        if (window == null) return 0L;
        try {
            java.lang.reflect.Method accessor = handleAccessor;
            if (accessor == null) {
                for (String name : new String[]{"getWindow", "handle", "getHandle"}) {
                    try {
                        java.lang.reflect.Method candidate = window.getClass().getMethod(name);
                        if (candidate.getReturnType() == long.class) {
                            accessor = candidate;
                            break;
                        }
                    } catch (NoSuchMethodException ignored) {
                        // Try the next spelling.
                    }
                }
                handleAccessor = accessor;
            }
            if (accessor == null) return 0L;
            return (long) accessor.invoke(window);
        } catch (Throwable failure) {
            return 0L;
        }
    }

    /**
     * Sets the strongest affinity this driver will accept, verifying by read-back.
     *
     * <p>{@code SetWindowDisplayAffinity} returns success before DWM has composed
     * the window once, and the affinity is silently dropped at that point. A bare
     * return code therefore proves nothing, so every attempt here ends in
     * {@link #readAffinity} and only a matching read counts as applied.
     *
     * @param compatibilityMode when true, accept {@code WDA_MONITOR} as a
     *                          successful outcome instead of only reporting it.
     *                          A monitor-blanked capture is ugly but it does keep
     *                          the overlay out of the recording, and on drivers
     *                          without {@code WDA_EXCLUDEFROMCAPTURE} it is the
     *                          only thing that works at all.
     */
    public static Applied applyBest(long hwnd, boolean compatibilityMode) {
        if (!WINDOWS || hwnd == 0L) return new Applied(Outcome.REFUSED, WDA_NONE);

        if (applyToHwnd(hwnd, WDA_EXCLUDEFROMCAPTURE) && confirms(hwnd, WDA_EXCLUDEFROMCAPTURE)) {
            return new Applied(Outcome.EXCLUDED, WDA_EXCLUDEFROMCAPTURE);
        }

        // Either the call was refused outright, or it returned success and DWM
        // dropped it on the first compose. Both land here and both need the older
        // switch or nothing.
        if (compatibilityMode && applyToHwnd(hwnd, WDA_MONITOR) && confirms(hwnd, WDA_MONITOR)) {
            return new Applied(Outcome.MONITORED, WDA_MONITOR);
        }

        // Nothing stuck. Clear the half-applied state so the window does not sit
        // in a mode the caller has decided not to trust.
        applyToHwnd(hwnd, WDA_NONE);
        return new Applied(Outcome.REFUSED, WDA_NONE);
    }

    /**
     * Protection is confirmed only by a matching live read-back.
     */
    private static boolean confirms(long hwnd, int expected) {
        int actual = readAffinity(hwnd);
        return actual == expected;
    }

    /** Clears any affinity: the window shows up in capture again. */
    public static boolean release(long hwnd) {
        return applyToHwnd(hwnd, WDA_NONE);
    }

    /** Applies {@code affinity} to the GLFW window handle. */
    public static boolean apply(long glfwWindow, int affinity) {
        if (!WINDOWS || glfwWindow == 0L) return false;

        long hwnd;
        try {
            // The Win32 handle behind a GLFW window; LWJGL ships this, no extra native.
            hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        } catch (Throwable failure) {
            return false;
        }
        if (hwnd == 0L) return false;
        return applyToHwnd(hwnd, affinity);
    }

    /** Applies {@code affinity} to a raw HWND. */
    public static boolean applyToHwnd(long hwnd, int affinity) {
        if (!WINDOWS || hwnd == 0L) return false;
        User32Ext api = user32();
        if (api == null) return false;
        try {
            return api.SetWindowDisplayAffinity(Pointer.createConstant(hwnd), affinity) != 0;
        } catch (Throwable failure) {
            // Unsigned/no-desktop contexts refuse the call; the caller falls back.
            lastError = "SetWindowDisplayAffinity threw: "
                    + failure.getClass().getSimpleName();
            return false;
        }
    }

    /**
     * Current display affinity of a window, or -1 when it cannot be read.
     *
     * <p>Needed because {@code SetWindowDisplayAffinity} returns success before the
     * window is first composed and the affinity is dropped when it is. Reading the
     * value back is the only way to know the window is actually hidden.
     */
    public static int readAffinity(long hwnd) {
        if (!WINDOWS || hwnd == 0L) return -1;
        User32Ext api = user32();
        if (api == null) return -1;
        try {
            IntByReference out = new IntByReference();
            if (api.GetWindowDisplayAffinity(Pointer.createConstant(hwnd), out) == 0) {
                return -1;
            }
            return out.getValue();
        } catch (Throwable ignored) {
            // Fall through to the "unreadable" answer.
            return -1;
        }
    }
}
