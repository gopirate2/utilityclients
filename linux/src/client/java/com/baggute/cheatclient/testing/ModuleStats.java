// language: Java, file: ModuleStats.java, target: Fabric 26.2
package com.baggute.cheatclient.testing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-module liveness counters for the automated audit.
 *
 * <p>The audit asks one question per module: did enabling it produce an observable
 * effect? Answering that needs a clock that keeps running whether or not a module
 * does anything, and a counter per module that only moves when the module's own
 * code runs. {@link #clientTicks()} and {@link #clientFrames()} are that clock;
 * {@link #tick(String)} and {@link #render(String)} are the per-module answer.
 *
 * <p>Kept out of {@code Module} on purpose. A counter field on the base class is
 * state every module carries forever to answer a question only the audit asks,
 * and it would have to be reset from four call sites. Here it is a map that is
 * empty until a module is enabled, and clearing it is one call.
 *
 * <p>All counters are {@link AtomicLong} because the HTTP bridge reads them from
 * its own thread pool while the client thread writes them.
 */
public final class ModuleStats {

    private ModuleStats() {}

    /** One module's counters. Read by the bridge, written by the tick loop. */
    public static final class Counters {
        private final AtomicLong ticks = new AtomicLong();
        private final AtomicLong renders = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong enabledAt = new AtomicLong();
        private volatile String lastError;
        private volatile long lastErrorAt;

        public long ticks() { return ticks.get(); }
        public long renders() { return renders.get(); }
        public long errors() { return errors.get(); }
        public long enabledAt() { return enabledAt.get(); }
        public String lastError() { return lastError; }
        public long lastErrorAt() { return lastErrorAt; }
    }

    private static final Map<String, Counters> STATS = new ConcurrentHashMap<>();

    private static final AtomicLong CLIENT_TICKS = new AtomicLong();
    private static final AtomicLong CLIENT_FRAMES = new AtomicLong();
    private static final long STARTED_AT = System.currentTimeMillis();

    /** Advanced once per client tick, before any module runs. */
    public static void clientTick() { CLIENT_TICKS.incrementAndGet(); }

    /** Advanced once per rendered frame. */
    public static void clientFrame() { CLIENT_FRAMES.incrementAndGet(); }

    public static long clientTicks() { return CLIENT_TICKS.get(); }
    public static long clientFrames() { return CLIENT_FRAMES.get(); }
    public static long startedAt() { return STARTED_AT; }

    /** The counters for one module, created on first use. */
    public static Counters of(String module) {
        return STATS.computeIfAbsent(module, key -> new Counters());
    }

    /** A module's {@code onTick} returned without throwing. */
    public static void tick(String module) { of(module).ticks.incrementAndGet(); }

    /** A module's {@code onRenderFrame} returned without throwing. */
    public static void render(String module) { of(module).renders.incrementAndGet(); }

    /** A module's hook threw. The message is kept because the audit reports it. */
    public static void error(String module, Throwable failure) {
        Counters counters = of(module);
        counters.errors.incrementAndGet();
        counters.lastErrorAt = System.currentTimeMillis();
        if (failure == null) {
            counters.lastError = "unknown";
            return;
        }
        String message = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            message += ": " + failure.getMessage();
        }
        counters.lastError = message;
    }

    /** Marks the moment a module was switched on, so the audit can bound a window. */
    public static void markEnabled(String module, boolean enabled) {
        Counters counters = of(module);
        if (enabled) counters.enabledAt.set(System.currentTimeMillis());
    }

    /**
     * Zeroes one module's counters.
     *
     * <p>The audit calls this immediately before enabling a module so a delta is
     * "since this test started" rather than "since the client booted" — a module
     * that ticked once during config load would otherwise look alive forever.
     */
    public static void reset(String module) {
        Counters counters = of(module);
        counters.ticks.set(0L);
        counters.renders.set(0L);
        counters.errors.set(0L);
        counters.lastError = null;
        counters.lastErrorAt = 0L;
        counters.enabledAt.set(0L);
    }

    /** Every module name that has counters, for the bridge's full dump. */
    public static Map<String, Counters> all() { return STATS; }
}
