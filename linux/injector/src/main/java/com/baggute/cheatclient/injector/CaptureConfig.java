// language: Java, file: CaptureConfig.java, injector UI
package com.baggute.cheatclient.injector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Remembers whether the injector hides its own window from screen capture.
 *
 * <p>A file rather than {@link java.util.prefs.Preferences} because the setting is
 * about the machine, not the user profile: it sits beside the fetched jar in
 * {@code %APPDATA%\depends}, so a support session can read it, a copied install can
 * carry it, and the same "delete the folder and start clean" rule works for both.
 *
 * <p>Every failure path returns {@code true} - hidden. A file that cannot be read,
 * cannot be parsed, or was written by a newer build leaves the window out of the
 * capture. That is the safe direction: the wrong answer costs a visible window in a
 * screen share, and the wrong answer in the other direction costs the session.
 */
public final class CaptureConfig {

    /** The one file this class owns. */
    public static final String FILE_NAME = "stealth_injector.json";

    private static final String KEY = "hideInInjector";

    private CaptureConfig() {
    }

    /** The config file, whether or not it exists yet. */
    public static Path file() {
        return InstallPaths.prepare().resolve(FILE_NAME);
    }

    /**
     * The saved preference.
     *
     * @return true when the window should be excluded from capture - also the
     *         answer when there is no file yet or the file is unreadable
     */
    public static boolean load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return true;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            if (!json.has(KEY) || json.get(KEY).isJsonNull()) return true;
            return json.get(KEY).getAsBoolean();
        } catch (Exception malformed) {
            // A truncated write or a hand edit gone wrong. Hidden, not exposed.
            return true;
        }
    }

    /**
     * Writes the preference.
     *
     * <p>Through a temp file and a rename: the window is closing on the same tick
     * this is written, and a half-written JSON would be read back as "corrupt" next
     * launch - which reads as hidden, so the toggle would silently spring back on
     * after the user turned it off.
     *
     * @return true when the file on disk now says what the caller asked for
     */
    public static boolean save(boolean hidden) {
        Path path = file();
        JsonObject json = new JsonObject();
        json.addProperty(KEY, hidden);
        json.addProperty("version", 1);

        Path temp = null;
        try {
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), "stealth-", ".tmp");
            Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception failure) {
            // The toggle still works for this session; only the memory is lost.
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // Nothing to do about a temp file the OS will clean anyway.
                }
            }
            return false;
        }
    }
}
