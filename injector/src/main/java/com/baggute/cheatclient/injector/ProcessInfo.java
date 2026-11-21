package com.baggute.cheatclient.injector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only facts about one target process, for the selected-process panel.
 *
 * <p>Everything here is best-effort and never throws: the panel is a view of what
 * is knowable, and a value the OS will not hand over is reported as {@code —}
 * rather than as a guessed number. The two expensive reads - the attach probe for
 * the JVM string and {@code jcmd} for the heap - both run on the injector's worker
 * thread, never on the EDT.
 *
 * <p>The version shown in the table is the <em>game</em> version, parsed from the
 * command line the launcher passed. It is not the client jar's manifest version:
 * the table lists processes and the right panel names the jar that would be
 * attached, and collapsing the two made a 1.20.1 instance look like a 1.21.4 one.
 */
public final class ProcessInfo {

    /** Shown wherever a value could not be read. */
    public static final String UNKNOWN = "\u2014";

    private static final Pattern FML_VERSION =
            Pattern.compile("--fml\\.mcVersion\\s+(\\S+)");
    private static final Pattern VERSION_FLAG =
            Pattern.compile("--version\\s+(\\S+)");
    private static final Pattern VERSIONS_DIR =
            Pattern.compile("versions[\\\\/]([^\\\\/\\s\"]+)");
    /** Maven layout for the game jar: {@code com/mojang/minecraft/1.21.4/…}. */
    private static final Pattern MOJANG_VERSION =
            Pattern.compile("mojang[\\\\/]minecraft[\\\\/]([^\\\\/\\s\"]+)");
    private static final Pattern USED_K =
            Pattern.compile("used\\s+(\\d+)K");
    private static final Pattern COMMITTED_K =
            Pattern.compile("committed\\s+(\\d+)K");
    private static final Pattern RESERVED_K =
            Pattern.compile("total reserved\\s+(\\d+)K");
    private static final Pattern TOTAL_K =
            Pattern.compile("total\\s+(\\d+)K");

    private ProcessInfo() {
    }

    /** Everything the right panel prints, in one value. */
    public record Snapshot(String version, String jvm, String uptime, String heap, String gameDir) {
    }

    /** Full read. Safe to call off the EDT; can block for a few hundred ms. */
    public static Snapshot snapshot(JvmScanner.Target target) {
        Map<String, String> properties = properties(target.pid());
        return new Snapshot(
                version(target, properties.getOrDefault("java.class.path", "")),
                jvmText(properties),
                uptime(target.pid()),
                heap(target.pid()),
                gameDirectory(target, properties.getOrDefault("user.dir", "")));
    }

    /**
     * One attach, every property the panel needs.
     *
     * <p>The command line {@link ProcessHandle} reports is empty for processes
     * started by another user or through a launcher that rewrote its arguments, and
     * then both the version and the game directory would be a dash. The live
     * {@code java.class.path} and {@code user.dir} carry the same facts from inside
     * the target, so one probe covers what the header could not.
     */
    private static Map<String, String> properties(String pid) {
        try {
            return JvmScanner.probe(pid,
                    List.of("java.vm.name", "java.version", "os.arch",
                            "java.class.path", "user.dir"));
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }

    private static String gameDirectory(JvmScanner.Target target, String userDir) {
        String fromLine = target.gameDirectory();
        if (fromLine != null && !fromLine.isBlank()) return fromLine;
        return userDir == null || userDir.isBlank() ? UNKNOWN : userDir;
    }

    /**
     * The Minecraft version this process was launched with, or {@link #UNKNOWN}.
     *
     * <p>Sources, in order of authority: the Forge/Fabric version property, the
     * launcher's {@code --version} argument, a {@code versions/<name>} path segment,
     * and the Maven coordinates of the game jar ({@code com/mojang/minecraft/<ver>}),
     * which is the only form Prism and CurseForge leave behind. A Gradle daemon
     * carries none of them and correctly shows a dash.
     */
    public static String version(JvmScanner.Target target) {
        return version(target, "");
    }

    /**
     * The same read, with the target's live {@code java.class.path} as a last
     * resort. The table calls the one-argument form for every row, so it never
     * attaches; the panel has already paid for a probe and hands the class path in.
     */
    public static String version(JvmScanner.Target target, String classpath) {
        String line = target.commandLine() == null ? "" : target.commandLine();

        String found = first(FML_VERSION, line);
        if (found == null) found = first(VERSION_FLAG, line);
        if (found == null) found = first(VERSIONS_DIR, line);
        if (found == null) found = first(MOJANG_VERSION, line);
        if (found == null && classpath != null && !classpath.isBlank()) {
            found = first(MOJANG_VERSION, classpath);
            if (found == null) found = first(VERSIONS_DIR, classpath);
        }
        if (found == null || found.isBlank() || found.startsWith("-")) return UNKNOWN;
        return cleanup(found);
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Strips quotes and a trailing separator a launcher left on the token. */
    private static String cleanup(String value) {
        String text = value.replace("\"", "").trim();
        while (text.length() > 1 && (text.endsWith("\\") || text.endsWith("/"))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** Wall-clock age of the process as {@code HH:MM:SS}. */
    public static String uptime(String pid) {
        long id = parse(pid);
        if (id <= 0L) return UNKNOWN;
        try {
            Instant started = ProcessHandle.of(id)
                    .flatMap(handle -> handle.info().startInstant())
                    .orElse(null);
            if (started == null) return UNKNOWN;

            long seconds = Math.max(0L, Duration.between(started, Instant.now()).getSeconds());
            long hours = seconds / 3600L;
            long minutes = (seconds % 3600L) / 60L;
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds % 60L);
        } catch (RuntimeException failure) {
            return UNKNOWN;
        }
    }

    /**
     * "OpenJDK 21.0.4 amd64" from one already-read property map.
     *
     * <p>The values come through the attach API, which is the only way to read a
     * property out of another process. A process that refuses the attach - a
     * different user, a hardened runtime, a dead pid - hands back an empty map and
     * the panel shows a dash instead of breaking.
     */
    private static String jvmText(Map<String, String> properties) {
        String name = properties.getOrDefault("java.vm.name", "");
        String version = properties.getOrDefault("java.version", "");
        String arch = properties.getOrDefault("os.arch", "");
        if (name.isBlank() && version.isBlank()) return UNKNOWN;

        StringBuilder text = new StringBuilder();
        if (!name.isBlank()) text.append(shortVmName(name));
        if (!version.isBlank()) {
            if (text.length() > 0) text.append(' ');
            text.append(version);
        }
        if (!arch.isBlank()) {
            if (text.length() > 0) text.append(' ');
            text.append(arch);
        }
        return text.toString();
    }

    /** "OpenJDK 64-Bit Server VM" -> "OpenJDK". Keeps the vendor, drops the boilerplate. */
    private static String shortVmName(String name) {
        String text = name.trim();
        int cut = text.indexOf(" 64-Bit");
        if (cut > 0) text = text.substring(0, cut);
        int server = text.indexOf(" Server VM");
        if (server > 0) text = text.substring(0, server);
        return text.trim();
    }

    /**
     * "0.7 GB / 4.0 GB" used over the heap ceiling, as the target's own collector
     * reports it.
     *
     * <p>Read with the JDK's {@code jcmd} against the same pid. The shape differs by
     * collector: G1 and ZGC print {@code total reserved …K, committed …K, used …K},
     * while the generational collectors print {@code total …K, used …K} once per
     * generation. Used is summed across every line, and the ceiling is the reserved
     * figure when present, else the committed one, else the summed totals. A machine
     * without {@code jcmd} on the injector's path gets a dash.
     */
    public static String heap(String pid) {
        long id = parse(pid);
        if (id <= 0L) return UNKNOWN;

        String output = jcmd(id, "GC.heap_info");
        if (output.isEmpty()) return UNKNOWN;

        long usedK = sum(USED_K, output);
        long totalK = sum(RESERVED_K, output);
        if (totalK <= 0L) totalK = sum(COMMITTED_K, output);
        if (totalK <= 0L) totalK = sum(TOTAL_K, output);
        if (usedK <= 0L || totalK <= 0L) return UNKNOWN;
        return bytes(usedK * 1024L) + " / " + bytes(totalK * 1024L);
    }

    private static long sum(Pattern pattern, String text) {
        long total = 0L;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                total += Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // A value too large for a long is not a heap size; skip the line.
            }
        }
        return total;
    }

    private static String bytes(long value) {
        double gigabytes = value / (1024.0 * 1024.0 * 1024.0);
        if (gigabytes >= 1.0) return String.format(Locale.ROOT, "%.1f GB", gigabytes);
        double megabytes = value / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.0f MB", megabytes);
    }

    /** Runs one jcmd subcommand against the pid, empty string on any failure. */
    private static String jcmd(long pid, String command) {
        Path tool = jcmdPath();
        if (tool == null) return "";

        Process process = null;
        try {
            process = new ProcessBuilder(tool.toString(), Long.toString(pid), command)
                    .redirectErrorStream(true)
                    .start();
            return ProcessOutput.read(process, 2500);
        } catch (IOException | RuntimeException failure) {
            if (process != null) process.destroyForcibly();
            return "";
        } catch (InterruptedException interrupted) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * The {@code jcmd} that belongs to this runtime.
     *
     * <p>{@code java.home} is a JRE directory inside some launchers, where
     * {@code jcmd} is absent; a JDK on {@code PATH} is the fallback.
     */
    private static Path jcmdPath() {
        String home = System.getProperty("java.home", "");
        if (!home.isBlank()) {
            Path direct = Path.of(home, "bin", "jcmd.exe");
            if (Files.isRegularFile(direct)) return direct;
            Path plain = Path.of(home, "bin", "jcmd");
            if (Files.isRegularFile(plain)) return plain;
        }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry, "jcmd.exe");
            if (Files.isRegularFile(candidate)) return candidate;
            Path unix = Path.of(entry, "jcmd");
            if (Files.isRegularFile(unix)) return unix;
        }
        return null;
    }

    private static long parse(String pid) {
        try {
            return Long.parseLong(pid == null ? "" : pid.trim());
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }
}
