package com.baggute.cheatclient.injector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Fold the client into a mod the player already has.
 *
 * <p>Fabric reads exactly one {@code fabric.mod.json} per jar, so a merge produces
 * a single mod whose id, name and version stay the host's: the mod list shows the
 * host and nothing else. The client's classes, mixin configs, nested jars and
 * entrypoints ride along inside it, and the host keeps its own entrypoints, mixins
 * and dependencies untouched — the host mod keeps working exactly as before.
 *
 * <p>This edits a file the launcher owns and the player expects to find in
 * {@code mods/}, so the original is stored outside that folder, in
 * {@code depends/backups}, and indexed by {@code depends/backups/merges.json}.
 * {@link #remove} puts the original back where it came from and deletes the
 * backup, leaving the mods folder exactly as it was before the merge.
 */
public final class ModMerger {

    /** Only inside the merged jar: records which client went in and when. */
    private static final String MARKER = "utilityclient_merge";
    private static final String BACKUP_SUFFIX = ".uc-backup";
    private static final String MOD_JSON = "fabric.mod.json";

    /** Every backup and the index live here, never in the mods folder. */
    private static final String BACKUP_DIR = "backups";
    private static final String INDEX_NAME = "merges.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModMerger() {
    }

    public record Result(boolean ok, String message) {
    }

    // ---- paths ----

    /** {@code depends/backups} — created on demand. */
    public static Path backupDir() {
        return InstallPaths.prepare().resolve(BACKUP_DIR);
    }

    private static Path indexFile() {
        return backupDir().resolve(INDEX_NAME);
    }

    /**
     * Backup name for a host jar.
     *
     * <p>Hashed path prefix, because two instances can ship a mod of the same name
     * and one backup must never overwrite the other.
     */
    private static Path backupFile(Path hostJar) {
        String key = fingerprint(hostJar);
        return backupDir().resolve(key + "-" + hostJar.getFileName() + BACKUP_SUFFIX);
    }

    /**
     * Distinguishes same-named jars in different instances.
     *
     * <p>{@code getAbsolutePath} then lowercase on Windows: the same file reached
     * through different drive letter casing must resolve to one backup.
     */
    private static String fingerprint(Path hostJar) {
        String absolute = hostJar.toAbsolutePath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            absolute = absolute.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(absolute.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (Exception unreachable) {
            // SHA-256 is mandatory in every JRE; a hash of the path is enough here.
            return Integer.toHexString(absolute.hashCode());
        }
    }

    /** Pre-change location: beside the jar in the mods folder. */
    private static Path legacyBackup(Path hostJar) {
        return hostJar.resolveSibling(hostJar.getFileName() + BACKUP_SUFFIX);
    }

    // ---- index ----

    private static JsonArray loadIndex() {
        Path file = indexFile();
        if (!Files.isRegularFile(file)) return new JsonArray();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception unreadable) {
            // A corrupt index must not block a merge; the jar marker still tells the truth.
            return new JsonArray();
        }
    }

    private static void saveIndex(JsonArray index) throws IOException {
        Path file = indexFile();
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "merges-", ".json");
        try {
            Files.writeString(temp, GSON.toJson(index), StandardCharsets.UTF_8);
            replaceAtomically(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static JsonObject entryFor(JsonArray index, Path hostJar) {
        String wanted = hostJar.toAbsolutePath().normalize().toString();
        for (JsonElement element : index) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("host") && wanted.equalsIgnoreCase(entry.get("host").getAsString())) {
                return entry;
            }
        }
        return null;
    }

    private static void dropEntry(JsonArray index, JsonObject entry) {
        index.remove(entry);
    }

    // ---- install ----

    public static Result install(Path hostJar, Path clientJar) {
        try { ClientIntegrity.verify(clientJar); }
        catch (IOException invalid) { return new Result(false, invalid.getMessage()); }
        if (!Files.isRegularFile(hostJar)) return new Result(false, "no such jar: " + hostJar);
        if (!Files.isRegularFile(clientJar)) return new Result(false, "no such client jar: " + clientJar);
        if (hostJar.toAbsolutePath().equals(clientJar.toAbsolutePath())) {
            return new Result(false, "host and client are the same file");
        }

        // Older builds kept the original beside the jar. Adopt it, so the mods
        // folder stops carrying a file the player did not put there.
        Path legacy = legacyBackup(hostJar);
        if (Files.exists(legacy)) {
            try {
                return adoptLegacy(hostJar, legacy);
            } catch (IOException failure) {
                return new Result(false, "cannot move the old backup: " + describe(failure));
            }
        }

        JsonArray index = loadIndex();
        if (entryFor(index, hostJar) != null) {
            return new Result(false, "already injected into " + hostJar.getFileName()
                    + " — remove that injection first");
        }

        Path backup = backupFile(hostJar);
        if (Files.exists(backup)) {
            return new Result(false, "stale backup for " + hostJar.getFileName()
                    + " at " + backup + " — delete it, or remove the injection first");
        }

        Path temp = null;
        try {
            int added;
            // Both zips are closed before the host is replaced: Windows refuses to
            // overwrite a file that is still open.
            try (ZipFile host = new ZipFile(hostJar.toFile());
                 ZipFile client = new ZipFile(clientJar.toFile())) {

                JsonObject hostMeta = readModJson(host);
                if (hostMeta == null) {
                    return new Result(false, hostJar.getFileName() + " has no fabric.mod.json");
                }
                JsonObject clientMeta = readModJson(client);
                if (clientMeta == null) {
                    return new Result(false, clientJar.getFileName() + " has no fabric.mod.json");
                }

                Set<String> hostNames = new LinkedHashSet<>(names(host));
                for (String name : hostNames) {
                    String upper = name.toUpperCase(java.util.Locale.ROOT);
                    if (upper.startsWith("META-INF/") && (upper.endsWith(".SF")
                            || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                            || upper.endsWith(".EC") || upper.substring(9).startsWith("SIG-")))
                        throw new IOException("Signed host JAR cannot be merged without invalidating its signature");
                }
                Map<String, byte[]> additions = new LinkedHashMap<>();

                for (String name : names(client)) {
                    if (name.endsWith("/")) continue;
                    if (name.equals(MOD_JSON)) continue;
                    if (name.equals("META-INF/MANIFEST.MF")) continue;
                    if (name.endsWith(BACKUP_SUFFIX)) continue;
                    if (hostNames.contains(name)) continue;      // host wins on collisions
                    additions.put(name, bytes(client, name));
                }

                JsonObject merged = mergeMetadata(hostMeta, clientMeta, clientJar.getFileName().toString());
                if (hostMeta.has("accessWidener") && clientMeta.has("accessWidener")) {
                    String hostAw = hostMeta.get("accessWidener").getAsString();
                    String clientAw = clientMeta.get("accessWidener").getAsString();
                    String awName = "utilityclient-merged.aw";
                    if (hostNames.contains(awName)) throw new IOException("merged access widener path already exists");
                    additions.put(awName, mergeWideners(bytes(host, hostAw), bytes(client, clientAw)));
                    merged.addProperty("accessWidener", awName);
                }
                additions.put(MOD_JSON, GSON.toJson(merged).getBytes(StandardCharsets.UTF_8));

                temp = Files.createTempFile(hostJar.getParent(), "uc-merge-", ".jar");
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                    for (String name : hostNames) {
                        if (name.equals(MOD_JSON)) continue;
                        copyEntry(host, name, out);
                    }
                    for (Map.Entry<String, byte[]> entry : additions.entrySet()) {
                        out.putNextEntry(new ZipEntry(entry.getKey()));
                        out.write(entry.getValue());
                        out.closeEntry();
                    }
                }
                added = additions.size();
            }

            Files.createDirectories(backup.getParent());
            Files.copy(hostJar, backup, StandardCopyOption.REPLACE_EXISTING);

            JsonObject entry = new JsonObject();
            entry.addProperty("host", hostJar.toAbsolutePath().normalize().toString());
            entry.addProperty("hostName", hostJar.getFileName().toString());
            entry.addProperty("backup", backup.toAbsolutePath().normalize().toString());
            entry.addProperty("client", clientJar.getFileName().toString());
            entry.addProperty("merged", DateTimeFormatter.ISO_INSTANT
                    .withZone(ZoneOffset.UTC).format(Instant.now()));
            index.add(entry);
            saveIndex(index);

            try {
                replaceAtomically(temp, hostJar);
            } catch (IOException moveFailure) {
                // Keep the recovery copy and journal until an explicit restore succeeds.
                throw moveFailure;
            }
            temp = null;

            return new Result(true, "merged into " + hostJar.getFileName()
                    + " (" + added + " entries) — original kept in " + backup.getParent());
        } catch (Exception failure) {
            return new Result(false, "merge failed: " + describe(failure));
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // The temp file is disposable.
                }
            }
        }
    }

    /** Moves an old {@code .uc-backup} out of the mods folder and records it. */
    private static Result adoptLegacy(Path hostJar, Path legacy) throws IOException {
        JsonArray index = loadIndex();
        JsonObject existing = entryFor(index, hostJar);
        if (existing != null) return new Result(false, "already injected into " + hostJar.getFileName());

        Path backup = backupFile(hostJar);
        Files.createDirectories(backup.getParent());
        Files.move(legacy, backup, StandardCopyOption.REPLACE_EXISTING);

        JsonObject entry = new JsonObject();
        entry.addProperty("host", hostJar.toAbsolutePath().normalize().toString());
        entry.addProperty("hostName", hostJar.getFileName().toString());
        entry.addProperty("backup", backup.toAbsolutePath().normalize().toString());
        entry.addProperty("client", "(earlier build)");
        entry.addProperty("merged", DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC).format(Instant.now()));
        index.add(entry);
        saveIndex(index);

        return new Result(false, "already injected into " + hostJar.getFileName()
                + " — its original moved to " + backup.getParent() + "; remove the injection first");
    }

    // ---- remove ----

    /**
     * Puts the untouched original back and deletes the backup.
     *
     * <p>The jar is written back even when the merged one was deleted by hand: the
     * backup is the only copy of the player's mod, and leaving it stranded in
     * {@code depends} would lose it.
     */
    public static Result remove(Path hostJar) {
        JsonArray index = loadIndex();
        JsonObject entry = entryFor(index, hostJar);

        Path backup;
        if (entry != null && entry.has("backup")) {
            backup = Path.of(entry.get("backup").getAsString());
        } else {
            backup = legacyBackup(hostJar);
        }

        if (!Files.exists(backup)) {
            if (entry != null) {
                try {
                    dropEntry(index, entry);
                    saveIndex(index);
                } catch (IOException ignored) {
                    // Nothing to restore either way.
                }
            }
            return new Result(false, "no backup found for " + hostJar.getFileName()
                    + " (looked in " + backup.getParent() + ")");
        }

        try {
            // The mods folder may have been cleaned since the merge.
            if (hostJar.getParent() != null) Files.createDirectories(hostJar.getParent());
            Path restore = Files.createTempFile(hostJar.toAbsolutePath().getParent(), "uc-restore-", ".jar");
            try {
                Files.copy(backup, restore, StandardCopyOption.REPLACE_EXISTING);
                replaceAtomically(restore, hostJar);
            } finally { Files.deleteIfExists(restore); }
            if (entry != null) {
                dropEntry(index, entry);
                saveIndex(index);
            }
            Files.deleteIfExists(backup);
            return new Result(true, "restored " + hostJar.getFileName()
                    + " — backup removed from " + backup.getParent().getFileName());
        } catch (IOException failure) {
            return new Result(false, "restore failed: " + describe(failure));
        }
    }

    public static boolean isInjected(Path hostJar) {
        if (Files.exists(legacyBackup(hostJar))) return true;
        return entryFor(loadIndex(), hostJar) != null;
    }

    /** Where the original of this jar is stored, or null when it was never merged. */
    public static Path backupFor(Path hostJar) {
        JsonObject entry = entryFor(loadIndex(), hostJar);
        if (entry != null && entry.has("backup")) return Path.of(entry.get("backup").getAsString());
        Path legacy = legacyBackup(hostJar);
        return Files.exists(legacy) ? legacy : null;
    }

    // ---- metadata ----

    /**
     * Host identity is preserved on purpose: id, name and version all come from the
     * host so the loader lists one mod, not two. Only the arrays that make code run
     * are appended, and the host's own entries stay first and untouched — the host
     * initialises exactly as it did before, then the client's initialiser runs.
     */
    private static JsonObject mergeMetadata(JsonObject host, JsonObject client, String clientName) {
        JsonObject merged = host.deepCopy();

        appendArray(merged, client, "mixins");
        appendArray(merged, client, "jars");
        appendClientEntrypoints(merged, client);
        mergeDepends(merged, client);

        if (!merged.has("accessWidener") && client.has("accessWidener")) {
            merged.add("accessWidener", client.get("accessWidener"));
        }

        JsonObject marker = new JsonObject();
        marker.addProperty("client", clientName);
        marker.addProperty("merged", DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC).format(Instant.now()));
        merged.add(MARKER, marker);

        return merged;
    }

    private static byte[] mergeWideners(byte[] host, byte[] client) throws IOException {
        String[] a = new String(host, StandardCharsets.UTF_8).split("\\R");
        String[] b = new String(client, StandardCharsets.UTF_8).split("\\R");
        if (a.length == 0 || b.length == 0 || !a[0].trim().equals(b[0].trim()))
            throw new IOException("incompatible access widener versions or namespaces");
        Set<String> lines = new LinkedHashSet<>();
        for (String[] source : new String[][]{a, b}) {
            for (int i = 1; i < source.length; i++) {
                String line = source[i].trim();
                if (!line.isEmpty() && !line.startsWith("#")) lines.add(line);
            }
        }
        return (a[0].trim() + "\n" + String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void appendArray(JsonObject target, JsonObject source, String key) {
        if (!source.has(key) || !source.get(key).isJsonArray()) return;
        JsonArray array = target.has(key) && target.get(key).isJsonArray()
                ? target.getAsJsonArray(key)
                : new JsonArray();
        Set<String> present = new LinkedHashSet<>();
        for (JsonElement element : array) present.add(element.toString());
        for (JsonElement element : source.getAsJsonArray(key)) {
            if (present.add(element.toString())) array.add(element);
        }
        target.add(key, array);
    }

    private static void appendClientEntrypoints(JsonObject target, JsonObject source) {
        JsonObject sourcePoints = source.getAsJsonObject("entrypoints");
        if (sourcePoints == null) return;
        JsonObject targetPoints = target.has("entrypoints")
                ? target.getAsJsonObject("entrypoints")
                : new JsonObject();

        for (String key : sourcePoints.keySet()) {
            JsonArray sourceArray = sourcePoints.getAsJsonArray(key);
            if (sourceArray == null) continue;
            JsonArray array = targetPoints.has(key) && targetPoints.get(key).isJsonArray()
                ? targetPoints.getAsJsonArray(key)
                : new JsonArray();
            Set<String> present = new LinkedHashSet<>();
            for (JsonElement element : array) present.add(element.toString());
            for (JsonElement element : sourceArray) {
                if (present.add(element.toString())) array.add(element);
            }
            targetPoints.add(key, array);
        }
        target.add("entrypoints", targetPoints);
    }

    /** Fabric arrays are OR alternatives; predicates within one string are AND terms. */
    private static void mergeDepends(JsonObject target, JsonObject source) {
        JsonObject sourceDepends = source.getAsJsonObject("depends");
        if (sourceDepends == null) return;
        JsonObject depends = target.has("depends") ? target.getAsJsonObject("depends") : new JsonObject();
        for (String key : sourceDepends.keySet()) {
            if (!depends.has(key)) {
                depends.add(key, sourceDepends.get(key).deepCopy());
            } else {
                JsonElement host = depends.get(key);
                JsonElement client = sourceDepends.get(key);
                List<String> hostAlternatives = dependencyAlternatives(host);
                List<String> clientAlternatives = dependencyAlternatives(client);
                JsonArray intersection = new JsonArray();
                for (String left : hostAlternatives) for (String right : clientAlternatives) {
                    try {
                        var leftInterval = net.fabricmc.loader.api.metadata.version.VersionPredicate.parse(left).getInterval();
                        var rightInterval = net.fabricmc.loader.api.metadata.version.VersionPredicate.parse(right).getInterval();
                        if (leftInterval != null && rightInterval != null && leftInterval.and(rightInterval) != null)
                            intersection.add(left.equals("*") ? right : right.equals("*") ? left : left + " " + right);
                    } catch (net.fabricmc.loader.api.VersionParsingException invalid) {
                        throw new IllegalArgumentException("Invalid dependency " + key, invalid);
                    }
                }
                if (intersection.isEmpty()) throw new IllegalArgumentException("Incompatible dependency " + key);
                depends.add(key, intersection.size() == 1 ? intersection.get(0) : intersection);
            }
        }
        target.add("depends", depends);
    }

    private static List<String> dependencyAlternatives(JsonElement value) {
        List<String> result = new ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank())
                    throw new IllegalArgumentException("Invalid dependency predicate");
                result.add(item.getAsString().trim());
            }
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) result.add(value.getAsString());
        else throw new IllegalArgumentException("Invalid dependency predicate");
        if (result.isEmpty() || result.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Empty dependency predicate");
        return result;
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- zip plumbing ----

    private static JsonObject readModJson(ZipFile file) throws IOException {
        ZipEntry entry = file.getEntry(MOD_JSON);
        if (entry == null) return null;
        JsonElement parsed = JsonParser.parseString(new String(bytes(file, MOD_JSON), StandardCharsets.UTF_8));
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static List<String> names(ZipFile file) {
        List<String> out = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = file.entries();
        while (entries.hasMoreElements()) out.add(entries.nextElement().getName());
        return out;
    }

    private static byte[] bytes(ZipFile file, String name) throws IOException {
        try (InputStream in = file.getInputStream(file.getEntry(name))) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        }
    }

    private static void copyEntry(ZipFile source, String name, ZipOutputStream out) throws IOException {
        ZipEntry entry = source.getEntry(name);
        if (entry == null) return;
        if (entry.isDirectory()) {
            out.putNextEntry(new ZipEntry(name));
            out.closeEntry();
            return;
        }
        out.putNextEntry(new ZipEntry(name));
        try (InputStream in = source.getInputStream(entry)) {
            in.transferTo(out);
        }
        out.closeEntry();
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    // ---- cli ----

    /** {@code merge <host.jar> <client.jar>} or {@code remove <host.jar>}. */
    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("merge")) {
            emit(install(Path.of(args[1]), Path.of(args[2])));
        } else if (args.length == 2 && args[0].equals("remove")) {
            emit(remove(Path.of(args[1])));
        } else {
            System.err.println("usage: ModMerger merge <host.jar> <client.jar>");
            System.err.println("       ModMerger remove <host.jar>");
            System.exit(2);
        }
    }

    private static void emit(Result result) {
        OutputStream out = result.ok() ? System.out : System.err;
        try {
            out.write((result.ok() ? "ok: " : "fail: ").concat(result.message()).concat("\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // Nothing useful left to do if stdout is gone.
        }
        if (!result.ok()) System.exit(1);
    }
}
