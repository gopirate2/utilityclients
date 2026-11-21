package com.baggute.cheatclient.injector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Identity of a client jar: bytes on disk, declared mod version, SHA-256 prefix.
 *
 * <p>Two releases can both be called {@code cheatclient.jar} and both declare
 * version 1.0.0, so a filename or a version string cannot tell an old build from
 * a new one. The digest can, which is why every attach logs one.
 */
public record ClientJar(Path path, long bytes, String version, String digest) {

    public static ClientJar describe(Path path) {
        long size = 0;
        String version = "version unknown";
        String digest = "digest unavailable";

        try {
            size = Files.size(path);
        } catch (IOException ignored) {
            // A missing file still gets a line, with zeros.
        }
        try {
            version = readVersion(path);
        } catch (Exception ignored) {
            // Same: report what is known rather than failing.
        }
        try {
            digest = sha256(path).substring(0, 8);
        } catch (Exception ignored) {
            // ditto
        }
        return new ClientJar(path, size, version, digest);
    }

    /** One log line: name, bytes, declared version, digest prefix. */
    public String line() {
        return path.getFileName() + " | " + bytes + " B | " + version + " | sha256:" + digest;
    }

    /**
     * Version of a client jar, for display. Never throws.
     *
     * <p>{@code not set} and {@code not found} are different answers: one means no
     * jar was chosen, the other means the chosen path is gone. Both are more
     * useful in a column than an exception.
     */
    public static String versionOf(Path path) {
        if (path == null) return "not set";
        if (!Files.exists(path)) return "not found";
        if (!Files.isRegularFile(path)) return "not a file";
        try {
            return readVersion(path);
        } catch (Exception failure) {
            return "unreadable";
        }
    }

    private static String readVersion(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            String manifest = manifestVersion(zip);
            if (!manifest.isEmpty()) return manifest;

            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return "no fabric.mod.json";
            try (InputStream in = zip.getInputStream(entry)) {
                JsonObject root = JsonParser.parseString(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("version") && !root.get("version").isJsonNull()) {
                    return root.get("version").getAsString();
                }
            }
        }
        return "version unknown";
    }

    /**
     * {@code Implementation-Version} out of {@code META-INF/MANIFEST.MF}.
     *
     * <p>Read by hand rather than through {@link java.util.jar.Manifest}: the
     * manifest inside a jar is small, and a malformed one should cost a fallback
     * to {@code fabric.mod.json}, not an exception that turns the whole column
     * into "unreadable". 72-byte continuation lines are unwrapped because a long
     * version string can be folded.
     */
    private static String manifestVersion(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) return "";

        String text;
        try (InputStream in = zip.getInputStream(entry)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        StringBuilder unfolded = new StringBuilder(text.length());
        for (String raw : text.split("\r\n|\r|\n")) {
            if (!raw.isEmpty() && raw.charAt(0) == ' ') {
                unfolded.append(raw, 1, raw.length());
            } else {
                unfolded.append('\n').append(raw);
            }
        }

        for (String line : unfolded.toString().split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            if (!key.equalsIgnoreCase("Implementation-Version")) continue;
            String value = line.substring(colon + 1).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
