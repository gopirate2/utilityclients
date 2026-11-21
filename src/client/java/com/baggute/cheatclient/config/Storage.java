// language: Java, file: Storage.java, target: Fabric 26.2
package com.baggute.cheatclient.config;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where this client keeps everything: settings, presets, and the jar the repo
 * fetch drops.
 *
 * <p>Deliberately not the instance's {@code config/} folder. Anything there is
 * one screenshot away from being found, and a mods folder plus a config name is
 * about as subtle as a banner. Both platforms get a path that looks like
 * ordinary Wine/roaming leftovers:
 *
 * <ul>
 *   <li>Windows: {@code %APPDATA%\depends}</li>
 *   <li>Linux: {@code $XDG_CONFIG_HOME/depends}, i.e. {@code ~/.config/depends}</li>
 * </ul>
 *
 * <p>The Linux path used to point into a Wine prefix. That is wrong for a native
 * launcher (Prism, ATLauncher, the vanilla launcher), where no prefix exists, and
 * it disagreed with {@code LogManager}, which has always written
 * {@code ~/.config/depends}. Settings, presets, the stealth config and the log
 * now live in the same directory on Linux.
 *
 * <p>The injector resolves the same two paths independently — it cannot see this
 * class — so a change here has to be mirrored in
 * {@code injector/.../InstallPaths.java}.
 */
public final class Storage {

    /** Folder name on Windows. */
    private static final String WINDOWS_DIR = "depends";

    /** Folder name under the XDG config directory on Linux. */
    private static final String LINUX_DIR = "depends";

    private Storage() {
    }

    public static Path root() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, WINDOWS_DIR);
            }
            // APPDATA is unset in some service contexts; the profile path still works.
            return Path.of(System.getProperty("user.home"), "AppData", "Roaming", WINDOWS_DIR);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve(LINUX_DIR);
    }

    public static Path file(String name) {
        return root().resolve(name);
    }

    /** Creates the folder tree; false when the filesystem refuses. */
    public static boolean ensure() {
        try {
            Files.createDirectories(root());
            return true;
        } catch (Exception failure) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    // Nothing here can be allowed to take the client down: a read-only
                    // profile just means settings do not persist.
                });
            }
            return false;
        }
    }
}
