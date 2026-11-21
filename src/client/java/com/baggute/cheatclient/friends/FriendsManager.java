// language: Java, file: FriendsManager.java, target: Fabric 26.2
package com.baggute.cheatclient.friends;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.config.Storage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The friends list: who to leave alone and who to draw in green.
 *
 * <p>Two keys per entry, because neither alone is enough. A UUID is the only
 * stable identity (names change, and two accounts can share a skin), but the
 * client is routinely asked to add a name for someone it has never seen. An
 * entry therefore keeps whichever keys are available:
 *
 * <ul>
 *   <li>{@code uuid} - always present when the name could be resolved from the
 *       level or when the input was a UUID.</li>
 *   <li>{@code name} - always present, lowercased. Used as a fallback match and
 *       as the row label in the GUI.</li>
 * </ul>
 *
 * <p>Stored at {@code %APPDATA%\depends\friends.json} (or the XDG equivalent) via
 * {@link Storage}, next to the config, so a config share carries the list with it.
 */
public final class FriendsManager {

    /** Colour used for a friend in ESP, nametags and the HUD. */
    public static final int FRIEND_COLOR = 0xFF3DFF7A;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<UUID> UUIDS = new LinkedHashSet<>();
    private static final Set<String> NAMES = new LinkedHashSet<>();

    private static final java.util.Map<String, UUID> ASSOCIATIONS = new java.util.LinkedHashMap<>();

    private FriendsManager() {}

    // ---- queries ----

    public static boolean isFriend(UUID uuid) {
        return uuid != null && uuids().contains(uuid);
    }

    public static boolean isFriendName(String name) {
        return name != null && !name.isBlank() && names().contains(key(name));
    }

    /** UUID first, name second: covers both an online friend and a stored name. */
    public static boolean isFriend(Player player) {
        if (player == null) return false;
        return isFriend(player.getUUID()) || isFriendName(player.getName().getString());
    }

    public static boolean isFriend(Entity entity) {
        return entity instanceof Player player && isFriend(player);
    }

    /** Live UUID set, kept in sync with the file on every mutation. */
    public static synchronized Set<UUID> uuids() {
        return new LinkedHashSet<>(UUIDS);
    }

    public static synchronized Set<String> names() {
        return new LinkedHashSet<>(NAMES);
    }

    public static synchronized int size() {
        return Math.max(UUIDS.size(), NAMES.size());
    }

    /** Row labels for the GUI, sorted. */
    public static synchronized List<String> entries() {
        List<String> out = new ArrayList<>(NAMES);
        for (UUID uuid : UUIDS) {
            boolean covered = false;
            for (String name : out) {
                UUID resolved = ASSOCIATIONS.getOrDefault(name, resolveUuid(name));
                if (uuid.equals(resolved)) { covered = true; break; }
            }
            if (!covered) out.add(uuid.toString());
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    // ---- mutations ----

    /**
     * Adds a friend from a name or a UUID.
     *
     * @return false when the input is blank or already listed
     */
    public static synchronized boolean add(String raw) {
        if (raw == null) return false;
        String input = raw.trim();
        if (input.isEmpty()) return false;

        UUID parsed = parseUuid(input);
        String name = parsed != null ? nameOf(parsed) : input;
        UUID resolved = parsed != null ? parsed : resolveUuid(input);

        if (resolved != null && name != null && !name.isBlank()) ASSOCIATIONS.put(key(name), resolved);
        boolean changed = false;
        if (resolved != null && UUIDS.add(resolved)) changed = true;
        if (name != null && !name.isBlank() && NAMES.add(key(name))) changed = true;
        if (changed) save();
        return changed;
    }

    /** Removes by name or UUID; returns true when something was actually removed. */
    public static synchronized boolean remove(String raw) {
        if (raw == null) return false;
        String input = raw.trim();
        if (input.isEmpty()) return false;

        UUID parsed = parseUuid(input);
        UUID resolved = parsed != null ? parsed : ASSOCIATIONS.get(key(input));
        if (resolved == null) resolved = resolveUuid(input);
        boolean changed = NAMES.remove(key(input));
        if (resolved != null) changed |= UUIDS.remove(resolved);
        for (String name : new ArrayList<>(NAMES)) {
            UUID id = ASSOCIATIONS.getOrDefault(name, resolveUuid(name));
            if (resolved != null && resolved.equals(id)) changed |= NAMES.remove(name);
        }
        UUID removed = resolved;
        ASSOCIATIONS.entrySet().removeIf(e -> e.getKey().equals(key(input))
                || (removed != null && removed.equals(e.getValue())));
        if (changed) save();
        return changed;
    }

    public static synchronized void clear() {
        UUIDS.clear();
        NAMES.clear();
        ASSOCIATIONS.clear();
        save();
    }

    // ---- persistence ----

    private static Path file() {
        return Storage.file("friends.json");
    }

    public static synchronized void load() {
        UUIDS.clear();
        NAMES.clear();
        ASSOCIATIONS.clear();
        Path path = file();
        if (!Files.exists(path)) return;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            fromJson(parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject());
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("[Friends] failed to read {}", path, exception);
        }
    }

    public static synchronized void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            com.baggute.cheatclient.util.AtomicFiles.writeString(path, GSON.toJson(toJson()));
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("[Friends] failed to write {}", path, exception);
        }
    }

    /** Serialised form, shared by the file and the config share code. */
    public static synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray uuids = new JsonArray();
        for (UUID uuid : UUIDS) uuids.add(uuid.toString());
        JsonArray names = new JsonArray();
        for (String name : NAMES) names.add(name);
        root.add("uuids", uuids);
        root.add("names", names);
        JsonObject associations = new JsonObject();
        ASSOCIATIONS.forEach((name, uuid) -> associations.addProperty(name, uuid.toString()));
        root.add("associations", associations);
        return root;
    }

    public static void validateJson(JsonObject root) {
        if (root == null) return;
        for (String key : new String[]{"uuids", "names"}) {
            if (!root.has(key)) continue;
            if (!root.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
            for (JsonElement element : root.getAsJsonArray(key)) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException(key + " entries must be strings");
                if (key.equals("uuids") && parseUuid(element.getAsString()) == null)
                    throw new IllegalArgumentException("Invalid friend UUID");
            }
        }
        if (root.has("associations")) {
            if (!root.get("associations").isJsonObject()) throw new IllegalArgumentException("associations must be an object");
            for (var entry : root.getAsJsonObject("associations").entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()
                        || parseUuid(entry.getValue().getAsString()) == null)
                    throw new IllegalArgumentException("Invalid friend association");
            }
        }
    }

    public static synchronized void fromJson(JsonObject root) {
        validateJson(root);
        java.util.Set<UUID> nextUuids = new java.util.HashSet<>();
        java.util.Set<String> nextNames = new java.util.HashSet<>();
        java.util.Map<String, UUID> nextAssociations = new java.util.HashMap<>();
        if (root != null) {
            JsonArray uuids = root.getAsJsonArray("uuids");
            if (uuids != null) for (JsonElement element : uuids) nextUuids.add(parseUuid(element.getAsString()));
            JsonObject associations = root.getAsJsonObject("associations");
            if (associations != null) associations.entrySet().forEach(entry ->
                    nextAssociations.put(key(entry.getKey()), parseUuid(entry.getValue().getAsString())));
            JsonArray names = root.getAsJsonArray("names");
            if (names != null) for (JsonElement element : names) {
                String name = element.getAsString();
                if (!name.isBlank()) {
                    nextNames.add(key(name));
                    UUID id = resolveUuid(name);
                    if (id != null) nextAssociations.putIfAbsent(key(name), id);
                }
            }
        }
        UUIDS.clear(); UUIDS.addAll(nextUuids);
        NAMES.clear(); NAMES.addAll(nextNames);
        ASSOCIATIONS.clear(); ASSOCIATIONS.putAll(nextAssociations);
    }

    // ---- resolution ----

    /** Matches a name against players currently in the level. Never does network IO. */
    public static UUID resolveUuid(String name) {
        if (name == null || name.isBlank()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        String wanted = key(name);
        for (Player player : mc.level.players()) {
            if (key(player.getName().getString()).equals(wanted)) return player.getUUID();
        }
        return null;
    }

    /** Display name for a known UUID, when the player is loaded. */
    public static String nameOf(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(uuid)) return player.getName().getString();
        }
        return null;
    }

    /** Accepts 8-4-4-4-12 and the dashless 32-hex form. */
    public static UUID parseUuid(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.length() == 32 && value.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                    + value.substring(12, 16) + "-" + value.substring(16, 20) + "-"
                    + value.substring(20);
        }
        if (value.length() != 36) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
