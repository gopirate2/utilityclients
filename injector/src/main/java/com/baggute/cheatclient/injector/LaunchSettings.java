package com.baggute.cheatclient.injector;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Shared injector/bootstrapper preferences; changing them does not create startup registration. */
public final class LaunchSettings {
    public static final String FILE_NAME = "injector-settings.json";
    public boolean autoInject, startMinimized, allowMultiple;
    public boolean reinjectOnRestart = true;
    public int warmupSeconds = 8, scanIntervalMs = 1500;
    public String gameDirectory = "";

    public static LaunchSettings load(Path root) throws IOException {
        Path file = root.resolve(FILE_NAME);
        if (!Files.exists(file)) return new LaunchSettings();
        if (!Files.isRegularFile(file) || Files.size(file) > 65536) throw new IOException("Launch settings file is invalid or too large");
        try {
            JsonObject data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            LaunchSettings result = new LaunchSettings();
            for (String name : List.of("autoInject", "startMinimized", "allowMultiple", "reinjectOnRestart"))
                if (data.has(name) && (!data.get(name).isJsonPrimitive() || !data.get(name).getAsJsonPrimitive().isBoolean()))
                    throw new IllegalArgumentException("Invalid " + name);
            if (data.has("schema") && integer(data, "schema", 1, 1, 1) != 1) throw new IllegalArgumentException("Unsupported launch settings schema");
            result.autoInject = bool(data, "autoInject", false);
            result.startMinimized = bool(data, "startMinimized", false);
            result.allowMultiple = bool(data, "allowMultiple", false);
            result.reinjectOnRestart = bool(data, "reinjectOnRestart", true);
            result.warmupSeconds = integer(data, "warmupSeconds", 8, 0, 120);
            result.scanIntervalMs = integer(data, "scanIntervalMs", 1500, 500, 15000);
            if (data.has("gameDirectory")) {
                JsonElement value = data.get("gameDirectory");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid gameDirectory");
                result.gameDirectory = value.getAsString().strip();
            }
            if (result.gameDirectory.length() > 32768) throw new IllegalArgumentException("Game directory is too long");
            return result;
        } catch (RuntimeException malformed) { throw new IOException("Launch settings need repair: " + malformed.getMessage(), malformed); }
    }
    private static boolean bool(JsonObject data, String name, boolean fallback) { return data.has(name) ? data.get(name).getAsBoolean() : fallback; }
    private static int integer(JsonObject data, String name, int fallback, int min, int max) {
        if (!data.has(name)) return fallback;
        JsonElement value = data.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid " + name);
        int number = value.getAsBigDecimal().intValueExact();
        if (number < min || number > max) throw new IllegalArgumentException(name + " must be " + min + "–" + max);
        return number;
    }
    public void save(Path root) throws IOException {
        if (warmupSeconds < 0 || warmupSeconds > 120 || scanIntervalMs < 500 || scanIntervalMs > 15000
                || gameDirectory == null || gameDirectory.length() > 32768) throw new IOException("Launch options are outside their supported range");
        Path file = root.resolve(FILE_NAME);
        JsonObject data = new JsonObject();
        if (Files.isRegularFile(file) && Files.size(file) <= 65536) {
            try { data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException ignored) { }
        }
        data.addProperty("schema", 1); data.addProperty("autoInject", autoInject); data.addProperty("startMinimized", startMinimized);
        data.addProperty("allowMultiple", allowMultiple); data.addProperty("reinjectOnRestart", reinjectOnRestart);
        data.addProperty("warmupSeconds", warmupSeconds); data.addProperty("scanIntervalMs", scanIntervalMs);
        data.addProperty("gameDirectory", gameDirectory.strip());
        Path pending = Files.createTempFile(rootDirectory(root), "launch-settings-", ".tmp");
        try {
            Files.writeString(pending, new GsonBuilder().setPrettyPrinting().create().toJson(data) + "\n", StandardCharsets.UTF_8);
            try { Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(pending); }
    }
    private static Path rootDirectory(Path root) throws IOException { Files.createDirectories(root); return root; }
}
