// language: Java, file: VersionInfo.java, target: Fabric 26.2
package com.baggute.cheatclient.util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.CodeSource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Single source of truth for every string the client reports about itself.
 *
 * <p>Everything is read from the loaded mod metadata rather than hard-coded, so a
 * build that bumps {@code gradle.properties} and forgets a constant still reports
 * the version it actually shipped as. The one value that is not metadata is the
 * build stamp: it comes from the mtime of the code source, which is the only way
 * to answer the question people actually type {@code .version} to answer - "is the
 * jar I am running the jar I just built".
 *
 * <p>Returns plain strings. Chat, the terminal and Brigadier style them
 * differently, so no formatting lives here.
 */
public final class VersionInfo {

    /**
     * Fabric mod id. This is the {@code id} field in {@code fabric.mod.json}, not
     * the package name and not the product name - {@code getModContainer} is an
     * exact key lookup and returns empty for anything else.
     */
    public static final String MOD_ID = "utilityclient";

    private static final String UNKNOWN = "unknown";
    private static final int LABEL_WIDTH = 12;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    private VersionInfo() {
    }

    /** The client's own version, as declared in {@code fabric.mod.json}. */
    public static String client() {
        String loaded=modVersion(MOD_ID);
        if (!UNKNOWN.equals(loaded)) return loaded;
        // Late injection has no Fabric mod container; read this JAR's own manifest.
        try {
            var source=VersionInfo.class.getProtectionDomain().getCodeSource();
            if(source!=null && Files.isRegularFile(Path.of(source.getLocation().toURI()))) {
                try(var jar=new java.util.jar.JarFile(Path.of(source.getLocation().toURI()).toFile())) {
                    var entry=jar.getJarEntry("fabric.mod.json");
                    if(entry!=null) try(var input=jar.getInputStream(entry)) {
                        return com.google.gson.JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("version").getAsString();
                    }
                }
            }
        } catch(Exception ignored) { }
        return loaded;
    }

    public static String minecraft() {
        return modVersion("minecraft");
    }

    public static String loader() {
        return modVersion("fabricloader");
    }

    public static String fabricApi() {
        return modVersion("fabric-api");
    }

    /** The display name from {@code fabric.mod.json}. */
    public static String displayName() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(container -> container.getMetadata().getName())
                    .orElse(UNKNOWN);
        } catch (Throwable failure) {
            return UNKNOWN;
        }
    }

    /**
     * True when the version carries no qualifier of its own.
     *
     * <p>A bare {@code x.y.z} is a tagged release build. Anything with a {@code -}
     * qualifier ({@code 1.0.0-gh}, {@code 1.0.0-dev}) or {@code +} build metadata
     * is a variant that already says which one it is, so it is shown as-is instead
     * of being labelled on top of its own label.
     */
    public static boolean isBareRelease() {
        return isBareRelease(client());
    }

    static boolean isBareRelease(String version) {
        if (version == null || version.isEmpty() || UNKNOWN.equals(version)) return false;
        String core = version;
        int plus = core.indexOf('+');
        if (plus >= 0) core = core.substring(0, plus);
        return core.indexOf('-') < 0;
    }

    /** One-line identity: {@code UtilityClient v1.0.0 (GitHub Release)}. */
    public static String headline() {
        String version = client();
        StringBuilder line = new StringBuilder(displayName())
                .append(" v").append(version);
        if (isBareRelease(version)) line.append(" (GitHub Release)");
        return line.toString();
    }

    /** Formatted detail block for the chat and terminal front-ends. */
    public static List<String> lines() {
        List<String> out = new ArrayList<>(7);
        out.add(headline() + (isDev() ? "  [dev]" : ""));
        out.add(label("minecraft") + minecraft());
        out.add(label("loader") + loader());
        out.add(label("fabric api") + fabricApi());
        out.add(label("java") + System.getProperty("java.version", UNKNOWN)
                + "  (" + System.getProperty("os.name", UNKNOWN) + ")");
        out.add(label("build") + buildStamp());
        return out;
    }

    /**
     * File name and mtime of whatever the class was loaded out of.
     *
     * <p>In a production install that is the jar. In a dev run it is the
     * {@code build/classes/java/client} directory, which is reported as such
     * instead of pretending to be a jar.
     */
    public static String buildStamp() {
        File source = codeSource();
        if (source == null) return UNKNOWN;
        String name = source.getName();
        if (name.isEmpty()) name = source.getAbsolutePath();
        long modified = source.lastModified();
        if (modified <= 0) return name;
        return name + "  " + STAMP.format(Instant.ofEpochMilli(modified));
    }

    public static File codeSource() {
        try {
            CodeSource cs = VersionInfo.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            return new File(cs.getLocation().toURI());
        } catch (Exception | LinkageError failure) {
            // A security manager or a launcher that serves classes from a custom
            // loader can refuse the code source. The rest of the block is still
            // useful, so this stays a null rather than a throw.
            return null;
        }
    }

    public static boolean isDev() {
        try {
            return FabricLoader.getInstance().isDevelopmentEnvironment();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Lookup by exact mod id, guarded.
     *
     * <p>{@code getModContainer(id).get()} throws {@link java.util.NoSuchElementException}
     * when the id is wrong or the mod is not loaded, and a version command is not
     * worth crashing the client for.
     */
    private static String modVersion(String id) {
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(id);
            if (container.isEmpty()) return UNKNOWN;
            return container.get().getMetadata().getVersion().getFriendlyString();
        } catch (Throwable failure) {
            return UNKNOWN;
        }
    }

    private static String label(String text) {
        StringBuilder pad = new StringBuilder(text);
        while (pad.length() < LABEL_WIDTH) pad.append(' ');
        return pad.toString();
    }
}
