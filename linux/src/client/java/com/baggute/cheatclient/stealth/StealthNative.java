// language: Java, file: StealthNative.java, target: Fabric 26.2
package com.baggute.cheatclient.stealth;

import com.baggute.cheatclient.config.Storage;
import com.sun.jna.Library;
import com.sun.jna.Native;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JNA bridge to {@code ucstealth.dll}.
 *
 * <p>The DLL owns the swap hook and the front-buffer draw; this class only loads
 * it and forwards the overlay texture id. No Java callback is registered: calling
 * back into the JVM from inside {@code wglSwapBuffers} needs a cached JavaVM, an
 * attached thread and a global reference, and every one of those is a crash
 * waiting for a class-loader change. The DLL draws with its own GL entry points
 * instead.
 */
public final class StealthNative {

    /** Mirrors the exports in {@code native/dllmain.cpp}. */
    public interface Lib extends Library {
        int uc_install();

        void uc_uninstall();

        void uc_set_overlay(int texture, int width, int height);

        void uc_set_enabled(int enabled);

        int uc_is_installed();

        String uc_last_error();
    }

    private static volatile Lib lib;
    private static volatile boolean attempted;
    private static volatile String lastError = "not loaded";

    private StealthNative() {
    }

    public static Path dllPath() {
        return Storage.root()
                .resolve(StringDecryptor.decrypt("WhdbyiWCtno="))
                .resolve(StringDecryptor.decrypt("WhdAyCKfpX7ARqLAIEHx"));
    }

    /** Jar resource the DLL is shipped under; neutral name, see build.gradle. */
    private static final String RESOURCE = StringDecryptor.decrypt("WhdU2CKOtGyDSuWcak/0hQ==");

    /**
     * Writes the bundled DLL to {@code target} and hides it.
     *
     * <p>Staged to a {@code .part} sibling and moved into place, never written
     * straight to the destination. {@code LoadLibrary} maps whatever bytes are at
     * the path it is given, so a JVM killed halfway through an extraction would
     * leave a truncated PE that every later launch tries to map and fails on. A
     * rename is atomic on the same volume, and this is the same volume by
     * construction.
     */
    private static boolean extractBundled(Path target) {
        try (InputStream in = StealthNative.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) return false;

            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);

            Path staging = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(in, staging, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            LogManager.hide(target);
            return Files.isRegularFile(target);
        } catch (Throwable failure) {
            lastError = "extract failed: " + failure.getClass().getSimpleName();
            LogManager.log("WARN", lastError, failure);
            return false;
        }
    }

    /** Loads and installs the hook once; false when the DLL is missing or refused. */
    public static synchronized boolean ensureLoaded() {
        if (lib != null) return true;
        if (attempted) return false;
        attempted = true;

        Path dll = dllPath();
        if (!Files.isRegularFile(dll) && !extractBundled(dll)) {
            lastError = "missing: " + dll;
            LogManager.log("WARN", "stealth native library not present", null);
            return false;
        }

        try {
            // System.load first, purely for the error. A bitness or dependency
            // mismatch fails here with a specific reason (a 32-bit DLL in a 64-bit
            // JVM, a missing VC++ runtime); JNA's own load reports the same thing
            // as a bare UnsatisfiedLinkError with no detail.
            System.load(dll.toAbsolutePath().toString());

            Lib loaded = Native.load(dll.toAbsolutePath().toString(), Lib.class);
            int result = loaded.uc_install();
            if (result != 0) {
                lastError = "install failed: " + loaded.uc_last_error();
                LogManager.log("WARN", lastError, null);
                return false;
            }
            lib = loaded;
            lastError = "ok";
            LogManager.log("INFO", "stealth native installed", null);
            return true;
        } catch (Throwable failure) {
            lastError = "load failed: " + failure.getClass().getSimpleName();
            LogManager.log("WARN", lastError, failure);
            return false;
        }
    }

    public static boolean ready() {
        return lib != null;
    }

    public static String lastError() {
        return lastError;
    }

    /** Hands the off-screen HUD texture to the swap hook. */
    public static void setOverlay(int texture, int width, int height) {
        Lib current = lib;
        if (current == null) return;
        try {
            current.uc_set_overlay(texture, width, height);
        } catch (Throwable failure) {
            lastError = "overlay failed: " + failure.getClass().getSimpleName();
        }
    }

    /** Enables or disables the post-swap draw without unloading the hook. */
    public static void setEnabled(boolean enabled) {
        Lib current = lib;
        if (current == null) return;
        try {
            current.uc_set_enabled(enabled ? 1 : 0);
        } catch (Throwable failure) {
            lastError = "toggle failed: " + failure.getClass().getSimpleName();
        }
    }

    /**
     * Removes the swap hook and lets the library go.
     *
     * <p>{@code uc_uninstall} patches {@code wglSwapBuffers} back to its original
     * first; only then is the module released. Unloading a DLL whose hook is still
     * installed jumps straight into freed code on the next buffer swap, which is
     * an immediate hard crash and looks exactly like a driver fault.
     *
     * <p>{@code attempted} is reset so a later {@link #ensureLoaded} can install
     * again. The removed module cannot be re-loaded reliably on Windows - a
     * module that stayed mapped after a failed uninstall is still there - so the
     * reload only succeeds when the first uninstall actually completed.
     */
    public static synchronized void uninstall() {
        Lib current = lib;
        lib = null;
        attempted = false;
        if (current == null) {
            lastError = "not loaded";
            return;
        }
        try {
            current.uc_uninstall();
            lastError = "uninstalled";
            LogManager.log("INFO", "stealth native removed", null);
        } catch (Throwable failure) {
            lastError = "uninstall failed: " + failure.getClass().getSimpleName();
            LogManager.log("WARN", lastError, failure);
        }
    }
}
