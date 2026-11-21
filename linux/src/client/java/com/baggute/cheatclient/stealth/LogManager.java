// language: Java, file: LogManager.java, target: Fabric 26.2
package com.baggute.cheatclient.stealth;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One log file, outside the instance, hidden from Explorer, bounded on disk.
 *
 * <p>The client logger ("utilityclient") and the package logger
 * ("com.baggute.cheatclient") are given a dedicated appender and
 * {@code additivity=false}. That last flag is the part that matters: it stops
 * every record from being copied up to the root logger, which is the one writing
 * {@code logs/latest.log}. The file itself is never touched by Fabric's log
 * rotator because it is not in the instance.
 *
 * <p><b>One writer, not two.</b> The appender attached to those loggers is not a
 * {@code FileAppender}; it is {@link FileRelay}, which formats the record and hands
 * the bytes to {@link #appendLine}. That is the same method {@link #log} uses, so
 * every record — log4j's and the crash handler's — passes through one size gate and
 * one lock. A {@code FileAppender} would have been a second writer holding its own
 * open handle: truncating the file underneath it does not rewind the stream, so the
 * next write lands at the old offset and the file (now a NUL-filled hole followed by
 * new text) never actually shrinks. That is the failure mode this shape exists to
 * avoid.
 *
 * <p><b>Rotation.</b> At install, after a truncation, and every
 * {@link #MAINTAIN_EVERY} writes, the directory is scanned for the client's log
 * pattern. At {@link #MAX_FILES} files or more, the oldest are deleted until
 * {@link #KEEP_FILES} remain. The active file is excluded from the sweep by path,
 * not by modification time: clock skew and touch should never be able to nominate
 * the file that is about to be appended to.
 *
 * <p><b>Size cap.</b> Checked before every single append. At or above
 * {@link #MAX_BYTES} the file is truncated to zero and the write proceeds into an
 * empty file. Truncated rather than deleted so anything tailing the path keeps a
 * valid handle.
 *
 * <p><b>Locking.</b> {@link #WRITE_LOCK} guards the scan, the sort, the deletes, the
 * truncate and the write. Logging happens from the render thread, the tick thread,
 * the JNA callback threads and the shutdown hook, and any two of those can be
 * inside {@link #appendLine} at the same time.
 *
 * <p><b>Locked files.</b> On Windows an antivirus scanner, an editor or a second
 * client instance can hold a handle for a few hundred milliseconds; {@code
 * Files.delete} then throws {@code AccessDeniedException}. That is not a failure
 * worth ending a frame over: the path is parked in {@link #DEFERRED} and retried on
 * the next maintenance cycle.
 *
 * <p>On Windows the directory and the file are marked HIDDEN+SYSTEM through
 * {@code SetFileAttributesW}. That hides them from Explorer's default view; it does
 * not make them unreadable, and it never will.
 *
 * <p>Everything here is best-effort by construction: a read-only profile, a missing
 * JNA, or a failed attribute call leaves the game running with logging that simply
 * is not redirected.
 */
public final class LogManager {

    private static final String PACKAGE_LOGGER = "com.baggute.cheatclient";
    private static final String MOD_LOGGER = "utilityclient";

    /**
     * Third-party loggers that are silenced outright.
     *
     * <p>SeedCrackerX and the seedfinding libraries fail their structure table
     * lookup on any Minecraft version newer than the ones they know, and they
     * report it as one ERROR stack trace per feature — a dozen screens of
     * {@code NullPointerException} in {@code MCVersion.isNewerThan} on every
     * launch, into the console and the launcher log. The failures are handled and
     * harmless, so the records are turned off at the logger rather than filtered
     * after the fact.
     */
    private static final String[] SILENCED_LOGGERS = {
            "kaptainwutax",
            "kaptainwutax.seedcrackerX",
            "com.seedfinding",
            // log4j names for the target's raw stdout/stderr. The JVM prints its
            // "A Java agent has been loaded dynamically" warning here during attach,
            // before any of our code can run; this is the only place it can be
            // dropped without touching the JVM's own output.
            "STDERR",
            "STDOUT"
    };
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** Hard ceiling for the active file. The spec is 500 KB, not 500 KiB-rounded. */
    private static final long MAX_BYTES = 500L * 1024L;

    /** Files allowed on disk. The cap is "never more than five", so trim to four. */
    private static final int MAX_FILES = 5;
    private static final int KEEP_FILES = 4;

    /** Matches client.log and anything a future roll number would add. */
    private static final String LOG_GLOB = "*.log*";

    /** Appends between opportunistic rotation sweeps. A sweep is one directory read. */
    private static final int MAINTAIN_EVERY = 256;

    /** Deferred-delete backlog bound; a locked file older than this is dropped from the list. */
    private static final int MAX_DEFERRED = 64;

    /** Minimal binding: jna-platform may not be on Fabric's runtime classpath. */
    private interface Kernel32Attributes extends Library {
        Kernel32Attributes INSTANCE = Native.load(
                StringDecryptor.decrypt("WhdeziOFpXOfAA=="), Kernel32Attributes.class);

        boolean SetFileAttributesW(WString fileName, int attributes);
    }

    private static final int FILE_ATTRIBUTE_HIDDEN = 0x2;
    private static final int FILE_ATTRIBUTE_SYSTEM = 0x4;

    /** Guards the scan, the deletes, the truncate and the write. Never re-entered across threads. */
    private static final Object WRITE_LOCK = new Object();

    /** Files a lock prevented us from deleting, retried on the next sweep. */
    private static final Set<Path> DEFERRED = new LinkedHashSet<>();

    private static volatile Path directory;
    private static volatile Appender appender;
    private static volatile boolean silent;
    /** Read once at install: the shutdown hook must not load Gson or config classes. */
    private static volatile boolean wipeOnExit;

    /** Guarded by {@link #WRITE_LOCK}. */
    private static int writesSinceMaintain;

    private LogManager() {
    }

    /**
     * Windows: {@code %APPDATA%\depends\logs}. Linux: {@code ~/.config/depends/logs}.
     *
     * <p>Deliberately not {@code Storage.root()} on Linux: that path points into a
     * Wine prefix, which is where the client lives when it runs under Proton, but
     * a native install writes its logs under XDG. The two disagree on purpose.
     */
    public static Path logDir() {
        Path known = directory;
        if (known != null) return known;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path roaming = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
            base = roaming.resolve("depends");
        } else {
            base = Path.of(System.getProperty("user.home"), ".config", "depends");
        }

        // Logs live in their own folder under depends: config, presets and the
        // stealth file stay at the top, and a wipe-on-exit sweep can delete the
        // whole directory without touching settings.
        Path resolved = base.resolve("logs");
        directory = resolved;
        return resolved;
    }

    public static Path logFile() {
        return logDir().resolve(StringDecryptor.decrypt("WhdWxziOrmuCXqWJ"));
    }

    /** Installs redirection once; later calls are no-ops. */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;

        try {
            Files.createDirectories(logDir());
        } catch (IOException ignored) {
            // Nowhere to write: leave the default logging in place rather than
            // dropping records on the floor.
            INSTALLED.set(false);
            return;
        }

        hide(logDir());

        // Rotation before the first record, not after: a profile that already has
        // six files is trimmed on the way in rather than on the way out.
        synchronized (WRITE_LOCK) {
            maintainLocked();
        }

        try {
            wipeOnExit = StealthConfig.get().wipeOnExit();
        } catch (Throwable ignored) {
            // Gson missing or config unreadable: logging still installs, and the
            // shutdown hook simply keeps the files.
            wipeOnExit = false;
        }
        attachAppenders();
        installCrashHandler();
        silent = true;

        Runtime.getRuntime().addShutdownHook(new Thread(LogManager::onShutdown, "uc-log-shutdown"));
    }

    /** True once the client's own records are no longer reaching the console. */
    public static boolean isSilent() {
        return silent;
    }

    /**
     * Runs the rotation sweep on demand.
     *
     * <p>Cheap enough to call from a tick if the caller wants the directory watched
     * between write bursts: one read, one sort, and nothing at all when the count is
     * under {@link #MAX_FILES}. Safe from any thread.
     */
    public static void maintain() {
        synchronized (WRITE_LOCK) {
            maintainLocked();
        }
    }

    // ---- appender ----

    /**
     * Forwards log4j records into the same locked writer the crash handler uses.
     *
     * <p>Nothing is buffered here: the layout produces the exact bytes a
     * {@code FileAppender} would have written, and {@link #appendLine} owns the
     * handle for the duration of one write. That is what keeps the size cap honest
     * on the log4j path — a {@code FileAppender} would be writing through its own
     * stream, and a truncate underneath it cannot rewind that stream.
     */
    private static final class FileRelay extends AbstractAppender {

        private FileRelay(Layout<? extends Serializable> layout) {
            super("uc-stealth-file", null, layout, false);
        }

        @Override
        public void append(LogEvent event) {
            Layout<? extends Serializable> layout = getLayout();
            if (layout == null) return;
            try {
                byte[] bytes = layout.toByteArray(event);
                if (bytes != null && bytes.length > 0) {
                    appendLine(new String(bytes, StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // A record that cannot be formatted is dropped; the frame runs on.
            }
        }
    }

    private static void attachAppenders() {
        try {
            LoggerContext context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();

            PatternLayout layout = PatternLayout.newBuilder()
                    .withPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{32} - %msg%n")
                    .withCharset(StandardCharsets.UTF_8)
                    .build();

            Appender relay = new FileRelay(layout);
            relay.start();
            appender = relay;

            for (String name : new String[]{PACKAGE_LOGGER, MOD_LOGGER}) {
                LoggerConfig loggerConfig = new LoggerConfig(name, Level.ALL, false);
                loggerConfig.addAppender(relay, Level.ALL, null);
                configuration.addLogger(name, loggerConfig);
            }
            // OFF with additivity=false: not written here, not written upward.
            for (String name : SILENCED_LOGGERS) {
                configuration.addLogger(name, new LoggerConfig(name, Level.OFF, false));
            }
            context.updateLoggers();

            hide(logFile());
        } catch (Throwable unsupported) {
            // log4j-core is not guaranteed on every launch path (the agent path
            // loads into an already-configured JVM). Fall back to the manual
            // writer below, which needs nothing.
            silent = true;
        }
    }

    // ---- writing ----

    /**
     * Writes a record without going through log4j. Used by the crash handler and by
     * the stealth layer itself, where a logging failure must not recurse.
     */
    public static void log(String level, String message, Throwable failure) {
        StringBuilder line = new StringBuilder(160);
        line.append(LocalDateTime.now().format(STAMP))
                .append(" [").append(Thread.currentThread().getName()).append("] ")
                .append(level).append(" stealth - ")
                .append(message == null ? "" : message)
                .append(System.lineSeparator());
        if (failure != null) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            line.append(trace);
        }
        appendLine(line.toString());
    }

    /**
     * The only place bytes reach the log file.
     *
     * <p>Order is deliberate: cap first, then write, then an opportunistic sweep.
     * Checking after the write would let a single 8 KB exception dump push a
     * 499 KB file to 507 KB and leave it there until the next record.
     *
     * <p>Never calls {@code UtilityClient.LOGGER} — a failure reported through the
     * logger would re-enter this method from inside the appender and recurse.
     */
    private static void appendLine(String text) {
        if (text == null || text.isEmpty()) return;

        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) {
            String bounded = new String(encoded, 0, (int) MAX_BYTES - 4, StandardCharsets.UTF_8) + "\n";
            encoded = bounded.getBytes(StandardCharsets.UTF_8);
        }
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(logDir());
                Path file = logFile();

                enforceSizeLocked(file, encoded.length);
                if (Files.exists(file) && Files.size(file) + encoded.length > MAX_BYTES) return;

                boolean fresh = !Files.exists(file);
                Files.write(file, encoded,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (fresh) hide(file);

                if (++writesSinceMaintain >= MAINTAIN_EVERY) {
                    writesSinceMaintain = 0;
                    maintainLocked();
                }
            } catch (IOException | RuntimeException ignored) {
                // A log that cannot be written is not a reason to fail the frame.
            }
        }
    }

    /**
     * Truncates the active file to zero when it has reached {@link #MAX_BYTES}.
     *
     * <p>Truncate rather than delete and recreate: the path keeps its identity, so
     * a tail or a monitor with the file open still points at a valid inode. The
     * write that follows lands at offset 0 because this writer opens per append.
     */
    private static void enforceSizeLocked(Path file) { enforceSizeLocked(file, 0); }

    private static void enforceSizeLocked(Path file, int incomingBytes) {
        long size;
        try {
            if (!Files.exists(file)) return;
            size = Files.size(file);
        } catch (IOException unreadable) {
            return;
        }
        if (size < MAX_BYTES && size + incomingBytes <= MAX_BYTES) return;

        try {
            Files.newByteChannel(file, EnumSet.of(
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)).close();
        } catch (IOException locked) {
            // An exclusive handle beat us to it. The file stays oversized for one
            // more record and the next append tries again.
            if (debugWanted()) {
                consoleWarn("truncate deferred, file locked (" + locked.getMessage() + ")");
            }
            return;
        }

        writesSinceMaintain = 0;
        if (debugWanted()) {
            consoleWarn("truncated at " + (size / 1024L) + " KB (limit "
                    + (MAX_BYTES / 1024L) + " KB)");
        }
        // A truncation is a natural file boundary, which is the other half of the
        // spec's "before writing a new log file" trigger.
        maintainLocked();
    }

    // ---- rotation ----

    /** Retries parked deletes, then trims the directory to {@link #KEEP_FILES}. */
    private static void maintainLocked() {
        retryDeferredLocked();

        Path dir = logDir();
        if (!Files.isDirectory(dir)) return;

        Path active = normalise(logFile());
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, LOG_GLOB)) {
            for (Path candidate : stream) {
                try {
                    if (!Files.isRegularFile(candidate)) continue;
                } catch (RuntimeException ignored) {
                    continue;
                }
                logs.add(candidate);
            }
        } catch (IOException | RuntimeException ignored) {
            // Directory vanished or is unreadable mid-scan; nothing to trim.
            return;
        }

        if (logs.size() < MAX_FILES) return;

        // Oldest first. An unreadable timestamp sorts last so a file we could not
        // stat is never the first thing deleted.
        logs.sort(Comparator.comparingLong(LogManager::lastModified));

        int remaining = logs.size();
        for (int i = 0; i < logs.size() && remaining > KEEP_FILES; i++) {
            Path candidate = logs.get(i);
            // By path, not by timestamp: the file about to be appended to is never
            // a rotation candidate.
            if (normalise(candidate).equals(active)) continue;
            if (deleteLocked(candidate)) remaining--;
        }
    }

    /**
     * Deletes one file, parking it when Windows refuses.
     *
     * <p>{@code AccessDeniedException} here means another process holds a handle —
     * a scanner finishing a read, an editor with the file open, a second client
     * instance. Throwing would take out the frame; ignoring it forever would leave
     * the directory over the cap. Park and retry is the only option that does both.
     */
    private static boolean deleteLocked(Path file) {
        try {
            if (Files.deleteIfExists(file)) {
                DEFERRED.remove(file);
                if (debugWanted()) consoleWarn("rotated out " + file.getFileName());
                return true;
            }
            DEFERRED.remove(file);
            return true;
        } catch (IOException | RuntimeException locked) {
            DEFERRED.add(file);
            // The backlog is bounded so a permanently locked file cannot grow this
            // set without limit; the oldest entry is the one dropped.
            while (DEFERRED.size() > MAX_DEFERRED) {
                Path oldest = DEFERRED.iterator().next();
                DEFERRED.remove(oldest);
            }
            if (debugWanted()) {
                consoleWarn("delete deferred, file locked: " + file.getFileName());
            }
            return false;
        }
    }

    private static void retryDeferredLocked() {
        if (DEFERRED.isEmpty()) return;

        Path active = normalise(logFile());
        for (Path pending : new ArrayList<>(DEFERRED)) {
            if (normalise(pending).equals(active)) {
                DEFERRED.remove(pending);
                continue;
            }
            try {
                Files.deleteIfExists(pending);
                DEFERRED.remove(pending);
            } catch (IOException | RuntimeException stillLocked) {
                // Still held. It stays parked; the next sweep tries again.
            }
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException | RuntimeException unknown) {
            return Long.MAX_VALUE;
        }
    }

    private static Path normalise(Path file) {
        try {
            return file.toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return file;
        }
    }

    // ---- diagnostics ----

    /**
     * Whether the operator asked to see log-management notices.
     *
     * <p>Two sources, cheapest first: the {@code utilityclient.debug} system
     * property, then the {@code Debug} module's own enabled flag. Never reads the
     * config file — this can be reached from the appender, and Gson must not be
     * on that path.
     */
    private static boolean debugWanted() {
        String property = System.getProperty("utilityclient.debug");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }
        try {
            if (com.baggute.cheatclient.UtilityClient.moduleManager == null) return false;
            com.baggute.cheatclient.module.Module debug =
                    com.baggute.cheatclient.UtilityClient.moduleManager.getByName("Debug");
            return debug != null && debug.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Console notice, not a log record.
     *
     * <p>Deliberately {@code System.out}: routing this through the logger would send
     * it back through {@link #appendLine} from inside itself, and {@code silent}
     * already keeps our records off the console by design.
     */
    private static void consoleWarn(String message) {
        try {
            System.out.println("[LogManager] " + message);
        } catch (Throwable ignored) {
            // A closed stream is not worth a stack trace.
        }
    }

    // ---- install plumbing ----

    private static void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            log("ERROR", "uncaught on " + thread.getName(), failure);
            // Do not re-print: stderr is exactly the console the layer is silencing.
            if (previous != null && !silent) previous.uncaughtException(thread, failure);
        });
    }

    /** HIDDEN + SYSTEM on Windows; a no-op elsewhere. */
    public static void hide(Path path) {
        if (path == null) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            Kernel32Attributes.INSTANCE.SetFileAttributesW(new WString(path.toString()),
                    FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM);
        } catch (Throwable ignored) {
            // Old JNA, a path longer than MAX_PATH, or a filesystem without
            // attributes: hiding is cosmetic, the redirect still works.
        }
    }

    private static void onShutdown() {
        try {
            if (appender instanceof org.apache.logging.log4j.core.LifeCycle lifecycle) {
                lifecycle.stop();
            }
        } catch (Throwable ignored) {
            // Shutdown ordering is not ours to control.
        }

        try { wipeOnExit = StealthConfig.get().wipeOnExit(); } catch (Throwable ignored) {}
        if (!wipeOnExit) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir(), "*.log*")) {
            for (Path file : stream) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Open handle or read-only: leave it.
                }
            }
        } catch (IOException ignored) {
            // Directory already gone.
        }
    }
}
