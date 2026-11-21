// language: Java, file: InstallPaths.java, injector, target: JDK 21+
package com.baggute.cheatclient.injector;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The hidden folder the client and this injector share.
 *
 * <p>Mirrors {@code com.baggute.cheatclient.config.Storage} in the client jar: the
 * agent cannot see that class, and the client cannot see this one, so the two
 * resolvers are kept deliberately identical rather than coupled.
 *
 * <ul>
 *   <li>Windows: {@code %APPDATA%\depends}</li>
 *   <li>Linux: {@code $XDG_CONFIG_HOME/depends}, i.e. {@code ~/.config/depends}</li>
 * </ul>
 */
public final class InstallPaths {

    private InstallPaths() {
    }

    public static Path root() {
        String supplied = System.getProperty("utilityclient.injectorRoot", "");
        if (!supplied.isBlank()) return Path.of(supplied).toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) return Path.of(appData, "depends");
            return Path.of(System.getProperty("user.home"), "AppData", "Roaming", "depends");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("depends");
    }

    /** Creates the folder and pulls the fetched jar out of the old {@code ~/.uc}. */
    public static Path prepare() {
        Path root = root();
        try {
            Files.createDirectories(root);
        } catch (Exception ignored) {
            // Every caller reports its own failure; a read-only profile still gets a path.
        }

        // Migration: earlier builds kept their files in ~/.uc. Only the fetched jar
        // still matters; the token is gone from the design entirely.
        Path legacy = Path.of(System.getProperty("user.home"), ".uc");
        if (!System.getProperty("utilityclient.injectorRoot", "").isBlank()) return root;
        for (String name : new String[]{"cheatclient.jar"}) {
            Path from = legacy.resolve(name);
            Path to = root.resolve(name);
            try {
                if (Files.isRegularFile(from) && !Files.exists(to)) {
                    Files.copy(from, to);
                }
            } catch (Exception ignored) {
                // A half-migrated folder is not worth failing a launch over.
            }
        }
        return root;
    }
}
