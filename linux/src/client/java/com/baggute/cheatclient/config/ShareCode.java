// language: Java, file: ShareCode.java, target: Fabric 26.2
package com.baggute.cheatclient.config;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.friends.FriendsManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One-string config transfer: modules + friends, gzipped, base64'd.
 *
 * <p>Why gzip first: a full snapshot is around 40 KB of pretty-printed JSON and a
 * chat window, a Discord message and a clipboard all punish anything longer than
 * a couple of lines. Deflated, the same snapshot is roughly 3 KB.
 *
 * <p>The plain form is still accepted on import - a string with no gzip header is
 * decoded as raw UTF-8 JSON - so a hand-edited share never becomes unreadable.
 */
public final class ShareCode {

    /** Written by the "save to disk" button, in {@code %APPDATA%\depends\}. */
    public static final String FILE_NAME = "share.txt";

    private static final int FORMAT = 1;
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ShareCode() {}

    /** Current client state as a share string, or "" when there is nothing to send. */
    public static String exportCode() {
        ConfigManager manager = UtilityClient.configManager();
        if (manager == null) return "";
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("client", UtilityClient.MOD_ID);
            payload.addProperty("format", FORMAT);
            payload.addProperty("saved", System.currentTimeMillis());
            payload.add("modules", manager.snapshot());
            payload.add("friends", FriendsManager.toJson());
            return encode(payload.toString());
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("[Share] export failed", exception);
            return "";
        }
    }

    /** Applies a share string. Returns false when it is not one of ours. */
    public static boolean applyCode(String code) {
        try {
            return applyPrepared(prepareCode(code));
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("[Share] import failed", exception);
            return false;
        }
    }

    /** Decodes and validates a share for comparison; never applies or saves it. */
    public static JsonObject prepareCode(String code) throws IOException {
        ConfigManager manager = UtilityClient.configManager();
        if (manager == null || code == null || code.isBlank()) throw new IOException("No profile to import");
        if (code.length() > 4 * 1024 * 1024) throw new IOException("Profile exceeds import size limit");
        String body = code.stripLeading().startsWith("{") ? code : decode(code);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) throw new IOException("Profile exceeds 2 MiB");
        try {
            JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
            JsonObject supplied = parsed.getAsJsonObject("modules");
            if (supplied == null) throw new IOException("Profile is missing modules");
            boolean envelope = parsed.has("client") || parsed.has("format") || parsed.has("friends")
                    || supplied.has("modules");
            JsonObject payload = new JsonObject();
            if (envelope) {
                if (parsed.has("client") && (!parsed.get("client").isJsonPrimitive()
                        || !parsed.get("client").getAsJsonPrimitive().isString()
                        || !UtilityClient.MOD_ID.equals(parsed.get("client").getAsString())))
                    throw new IOException("Profile belongs to another client");
                if (parsed.has("format") && (!parsed.get("format").isJsonPrimitive()
                        || !parsed.get("format").getAsJsonPrimitive().isNumber()
                        || parsed.get("format").getAsBigDecimal().intValueExact() != FORMAT))
                    throw new IOException("Unsupported profile format");
                payload.add("modules", supplied.deepCopy());
                if (parsed.has("friends")) payload.add("friends", parsed.get("friends").deepCopy());
            } else payload.add("modules", parsed.deepCopy());
            if (!manager.validateSnapshot(payload.getAsJsonObject("modules"))) throw new IOException("Profile contains invalid settings");
            FriendsManager.validateJson(payload.getAsJsonObject("friends"));
            return payload;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid profile: " + invalid.getMessage(), invalid);
        }
    }

    public static JsonObject prepareFile() throws IOException {
        if (Files.size(file()) > 4 * 1024 * 1024) throw new IOException("Profile file is too large");
        return prepareCode(Files.readString(file(), StandardCharsets.UTF_8));
    }

    /** Applies the exact reviewed payload, rather than reading a changed clipboard/file. */
    public static boolean applyPrepared(JsonObject payload) {
        ConfigManager manager = UtilityClient.configManager();
        if (manager == null || payload == null) return false;
        try {
            JsonObject friends = payload.getAsJsonObject("friends");
            FriendsManager.validateJson(friends);
            if (!manager.applySnapshot(payload.getAsJsonObject("modules"))) return false;
            if (friends != null) { FriendsManager.fromJson(friends); FriendsManager.save(); }
            manager.save();
            return true;
        } catch (RuntimeException invalid) {
            UtilityClient.LOGGER.error("[Share] reviewed import failed", invalid);
            return false;
        }
    }

    // ---- clipboard ----

    public static boolean copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.keyboardHandler == null) return false;
        mc.keyboardHandler.setClipboard(text);
        return true;
    }

    public static String readClipboard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.keyboardHandler == null) return "";
        String text = mc.keyboardHandler.getClipboard();
        return text == null ? "" : text.trim();
    }

    // ---- file ----

    public static Path file() {
        return Storage.file(FILE_NAME);
    }

    public static boolean saveToFile() {
        String code = exportCode();
        if (code.isEmpty()) return false;
        try {
            Path target = file();
            Files.createDirectories(target.getParent());
            com.baggute.cheatclient.util.AtomicFiles.writeString(target, code);
            return true;
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("[Share] failed to write {}", file(), exception);
            return false;
        }
    }

    public static boolean loadFromFile() {
        Path source = file();
        if (!Files.exists(source)) return false;
        try {
            return applyCode(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("[Share] failed to read {}", source, exception);
            return false;
        }
    }

    // ---- codec ----

    private static String encode(String json) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    private static String decode(String code) throws IOException {
        if (code == null || code.length() > 4 * 1024 * 1024) throw new IOException("share code too large");
        byte[] raw = Base64.getMimeDecoder().decode(code.replaceAll("\\s", ""));
        if (raw.length > MAX_PAYLOAD_BYTES) throw new IOException("share payload too large");
        // 0x1F 0x8B is the gzip magic; anything else is a plain JSON body.
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = gzip.read(chunk)) > 0) {
                    if (out.size() + read > MAX_PAYLOAD_BYTES) throw new IOException("expanded share payload too large");
                    out.write(chunk, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /** Round-trips a single kit through the same codec. */
    public static String encodeKit(JsonElement kit) throws IOException {
        return encode(kit.toString());
    }

    public static JsonElement decodeKit(String code) throws IOException {
        return JsonParser.parseString(decode(code));
    }
}
