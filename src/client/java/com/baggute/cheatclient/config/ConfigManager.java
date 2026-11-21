// language: Java, file: ConfigManager.java, target: Fabric 26.2
package com.baggute.cheatclient.config;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.settings.Setting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConfigManager {
    private final ModuleManager moduleManager;
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ConfigManager(ModuleManager moduleManager) {
        this(moduleManager, root().resolve("utilityclient.json"));
    }

    public ConfigManager(ModuleManager moduleManager, Path path) {
        this.moduleManager = moduleManager;
        this.path = path;
    }

    /**
     * Where settings live: the hidden folder in {@link Storage}, not the instance's
     * config directory.
     *
     * <p>The game's config folder is the first place anyone looks after a
     * screenshare, and a file named after the client there is a confession. Stealth
     * mode used to move the file to a temp directory instead; that location died on
     * the next reboot, so both modes now share one persistent, unobtrusive path.
     */
    private static Path root() {
        return Storage.root();
    }

    private static Path presetDir() {
        return root().resolve("utilityclient").resolve("presets");
    }

    public void save() {
        writeTo(path);
    }

    public void load() {
        readFrom(path);
    }

    /** Preset names on disk, sorted, without the .json suffix. */
    public static List<String> listPresets() {
        List<String> out = new ArrayList<>();
        Path dir = presetDir();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                String file = p.getFileName().toString();
                out.add(file.substring(0, file.length() - 5));
            }
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("Failed to list presets", exception);
        }
        Collections.sort(out);
        return out;
    }

    public boolean savePreset(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return false;
        Path target = presetDir().resolve(safe + ".json");
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("Failed to create preset directory", exception);
            return false;
        }
        return writeTo(target);
    }

    public boolean loadPreset(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return false;
        Path target = presetDir().resolve(safe + ".json");
        if (!Files.exists(target)) return false;
        return readFrom(target);
    }

    /** Reads a preset for review without applying settings or lifecycle callbacks. */
    public JsonObject readPreset(String name) throws IOException {
        String safe = sanitize(name);
        if (safe.isEmpty()) throw new IOException("Preset name is empty");
        Path file = presetDir().resolve(safe + ".json");
        if (Files.size(file) > 2 * 1024 * 1024) throw new IOException("Preset exceeds 2 MiB");
        JsonElement parsed = JsonParser.parseString(Files.readString(file));
        if (!parsed.isJsonObject() || !validateSnapshot(parsed.getAsJsonObject()))
            throw new IOException("Preset contains invalid settings");
        return parsed.getAsJsonObject().deepCopy();
    }

    public List<ProfileComparison.Change> compareSnapshot(JsonObject candidate) {
        if (!validateSnapshot(candidate)) throw new IllegalArgumentException("Invalid profile settings");
        return ProfileComparison.compare(moduleManager, candidate);
    }

    public boolean deletePreset(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return false;
        try {
            return Files.deleteIfExists(presetDir().resolve(safe + ".json"));
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("Failed to delete preset {}", safe, exception);
            return false;
        }
    }

    /** "12 on" from the preset's own metadata, or "" when it has none. */
    public static String describePreset(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return "";
        Path file = presetDir().resolve(safe + ".json");
        if (!Files.exists(file)) return "";
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return "";
            JsonObject meta = parsed.getAsJsonObject().getAsJsonObject("__meta");
            if (meta == null || !meta.has("enabled")) return "";
            return meta.get("enabled").getAsInt() + " on";
        } catch (Exception exception) {
            return "";
        }
    }

    /** Strips anything that could escape the preset directory. */
    private static String sanitize(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("[^a-zA-Z0-9 _-]", "").replace(' ', '_');
    }

    private boolean writeTo(Path target) {
        try {
            Files.createDirectories(target.getParent());
            com.baggute.cheatclient.util.AtomicFiles.writeString(target, gson.toJson(snapshot()));
            return true;
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("Failed to save config to {}", target, exception);
            return false;
        }
    }

    /**
     * Every module's enabled flag and settings as one JSON object.
     *
     * <p>Public because the share code needs exactly the same shape the file has:
     * a preset, a share string and a {@code .txt} dump that disagree about keys
     * would each need their own loader.
     */
    public JsonObject snapshot() {
        JsonObject root = new JsonObject();
        JsonObject modulesObject = new JsonObject();
        int enabledCount = 0;
        for (Module module : moduleManager.getModules()) {
            JsonObject moduleObject = new JsonObject();
            moduleObject.addProperty("enabled", module.isEnabled());
            if (module.isEnabled()) enabledCount++;
            JsonObject settingsObject = new JsonObject();
            for (Setting setting : module.getPersistentSettings()) {
                settingsObject.add(setting.getName(), setting.toJson());
            }
            moduleObject.add("settings", settingsObject);
            modulesObject.add(module.getName(), moduleObject);
        }
        root.add("modules", modulesObject);

        JsonObject meta = new JsonObject();
        meta.addProperty("enabled", enabledCount);
        meta.addProperty("saved", System.currentTimeMillis());
        root.add("__meta", meta);
        return root;
    }

    /** Validate all supplied values before a module's lifecycle can run. */
    public boolean validateSnapshot(JsonObject root) {
        if (root == null || !root.has("modules") || !root.get("modules").isJsonObject()) return false;
        try {
            JsonObject modules = root.getAsJsonObject("modules");
            for (var entry : modules.entrySet()) {
                if (!entry.getValue().isJsonObject()) return false;
                JsonObject item = entry.getValue().getAsJsonObject();
                if (item.has("enabled") && !isBoolean(item.get("enabled"))) return false;
                if (item.has("settings") && !item.get("settings").isJsonObject()) return false;
            }
            for (Module module : moduleManager.getModules()) {
                if (!modules.has(module.getName())) continue;
                JsonObject settings = modules.getAsJsonObject(module.getName()).getAsJsonObject("settings");
                if (settings == null) continue;
                for (Setting setting : module.getPersistentSettings()) {
                    if (!settings.has(setting.getName())) continue;
                    JsonElement value = settings.get(setting.getName());
                    if (setting instanceof com.baggute.cheatclient.settings.BoolSetting) {
                        if (!isBoolean(value)) return false;
                    } else if (setting instanceof com.baggute.cheatclient.settings.IntSetting
                            || setting instanceof com.baggute.cheatclient.settings.ColorSetting) {
                        if (!isInteger(value)) return false;
                    } else if (setting instanceof com.baggute.cheatclient.settings.DoubleSetting) {
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                                || !Double.isFinite(value.getAsDouble())) return false;
                    } else if (setting instanceof com.baggute.cheatclient.settings.ChoiceSetting choice) {
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !choice.accepts(value.getAsString())) return false;
                    } else if (setting instanceof com.baggute.cheatclient.settings.StringSetting) {
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
                    } else if (setting instanceof com.baggute.cheatclient.settings.KeybindSetting) {
                        if (value.isJsonObject()) {
                            JsonObject bind = value.getAsJsonObject();
                            if (bind.has("key") && !isInteger(bind.get("key"))) return false;
                            if (bind.has("held") && !isBoolean(bind.get("held"))) return false;
                        } else if (!isInteger(value)) return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException malformed) { return false; }
    }

    private static boolean isBoolean(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isInteger(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try { value.getAsBigDecimal().intValueExact(); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    /** Reject malformed snapshots without mutation; restore state if application fails. */
    public boolean applySnapshot(JsonObject root) {
        if (!validateSnapshot(root)) return false;
        JsonObject previous = snapshot();
        Notifications.setMuted(true);
        try {
            applyValidatedSnapshot(root);
            return true;
        } catch (RuntimeException failure) {
            try { applyValidatedSnapshot(previous); }
            catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            UtilityClient.LOGGER.warn("Config application failed; restored the previous snapshot", failure);
            return false;
        } finally { Notifications.setMuted(false); }
    }

    private void applyValidatedSnapshot(JsonObject root) {
        JsonObject modules = root.getAsJsonObject("modules");
        for (Module module : moduleManager.getModules()) {
            if (!modules.has(module.getName())) continue;
            JsonObject item = modules.getAsJsonObject(module.getName());
            JsonObject settings = item.getAsJsonObject("settings");
            if (settings != null) {
                for (Setting setting : module.getPersistentSettings()) {
                    JsonElement value = settings.get(setting.getName());
                    if (value != null) setting.fromJson(value);
                }
            }
            if (item.has("enabled")) module.setEnabled(item.get("enabled").getAsBoolean());
            module.onConfigLoaded();
        }
    }

    private boolean readFrom(Path source) {
        if (!Files.exists(source)) return false;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(source));
            if (!parsed.isJsonObject()) return false;
            return applySnapshot(parsed.getAsJsonObject());
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("Failed to load config from {}", source, exception);
            return false;
        }
    }
}
