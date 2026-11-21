// language: Java, file: Debug.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage board for the automation modules. Each module reports its current phase
 * plus the counters that decide the next transition, so a cycle that stalls is
 * visible instead of inferred.
 *
 * <p>Reports are dropped automatically once a module stops updating, so a disabled
 * module never leaves a stale line on screen.
 *
 * <p><b>Two views of the same writes.</b> {@link #snapshot()} is the overlay: the
 * latest line per module, pruned when it goes stale. {@link #history(String)} is the
 * audit's view: a bounded ring of every distinct stage the module has entered, plus a
 * {@link #transitions(String) transition count}. The distinction matters because a
 * module that reaches {@code IDLE}, {@code EQUIP}, {@code ATTACK} and back is
 * <em>working</em> even though its current line reads {@code IDLE}, and the overlay
 * alone cannot tell that apart from a module that never moved at all.
 *
 * <p>History is never pruned by staleness. A cycle that finished two seconds ago is
 * exactly the evidence the audit needs, and the ring bound keeps a long-running
 * module from growing without limit.
 */
public class Debug extends Module {

    private static final long STALE_MS = 1500L;

    /** Distinct stages kept per module. A cycle deeper than this is not a cycle. */
    private static final int HISTORY_LIMIT = 24;

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, Deque<Sample>> HISTORY = new LinkedHashMap<>();
    private static final Map<String, AtomicLong> TRANSITIONS = new LinkedHashMap<>();

    private final BoolSetting enabledOverlay;

    private record Entry(String text, long stamp) {}

    /** One stage transition, with the time it happened. */
    public record Sample(String text, long stamp) {}

    public Debug() {
        super("Debug", "Shows each automation module's live stage and counters.", Category.MISC);
        this.enabledOverlay = new BoolSetting("overlay", "Draw the stage board on screen", true);
        addSetting(enabledOverlay);
    }

    public boolean isOverlayOn() {
        return isEnabled() && enabledOverlay.get();
    }

    /**
     * Called by automation modules every tick. Cheap: two map writes.
     *
     * <p>Repeating the same stage is free — the history ring only takes a sample
     * when the text actually changes, and the transition counter only moves then
     * too. A module that reports {@code IDLE} every tick for an hour leaves one
     * sample and a counter at zero, which is the honest reading.
     */
    public static void stage(String module, String text) {
        if (module == null || text == null) return;
        long now = System.currentTimeMillis();

        synchronized (ENTRIES) {
            ENTRIES.put(module, new Entry(text, now));

            Deque<Sample> samples = HISTORY.computeIfAbsent(module, key -> new ArrayDeque<>());
            Sample last = samples.peekLast();
            if (last != null && last.text().equals(text)) return;

            samples.addLast(new Sample(text, now));
            while (samples.size() > HISTORY_LIMIT) samples.removeFirst();
            TRANSITIONS.computeIfAbsent(module, key -> new AtomicLong()).incrementAndGet();
        }
    }

    /** Live lines, oldest report first, with stale entries filtered out. */
    public static List<String> snapshot() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        synchronized (ENTRIES) {
            ENTRIES.entrySet().removeIf(e -> now - e.getValue().stamp() > STALE_MS);
            for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
                out.add(e.getKey() + ": " + e.getValue().text());
            }
        }
        return out;
    }

    /** The most recent stage a module reported, or null when it has never reported. */
    public static String currentStage(String module) {
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(module);
            return entry == null ? null : entry.text();
        }
    }

    /** Every distinct stage this module has entered, oldest first. */
    public static List<Sample> history(String module) {
        synchronized (ENTRIES) {
            Deque<Sample> samples = HISTORY.get(module);
            return samples == null ? List.of() : new ArrayList<>(samples);
        }
    }

    /** How many times the module's stage text has changed since the last clear. */
    public static long transitions(String module) {
        synchronized (ENTRIES) {
            AtomicLong counter = TRANSITIONS.get(module);
            return counter == null ? 0L : counter.get();
        }
    }

    /** Zeroes one module's history and transition count. Used before each audit test. */
    public static void clear(String module) {
        synchronized (ENTRIES) {
            HISTORY.remove(module);
            TRANSITIONS.remove(module);
            ENTRIES.remove(module);
        }
    }

    /** Every module that has ever reported, for the bridge's full dump. */
    public static List<String> trackedModules() {
        synchronized (ENTRIES) {
            return new ArrayList<>(HISTORY.keySet());
        }
    }

    @Override
    protected void onEnable() {
        clearAll();

        // Pure-logic checks over the math that has bitten us before. JUnit is not in
        // the offline cache, so the run reports through the log instead.
        List<String> failures = com.baggute.cheatclient.util.SelfTests.run();
        if (failures.isEmpty()) {
            com.baggute.cheatclient.UtilityClient.LOGGER.info("[SelfTests] all checks passed");
            Notifications.push("selftests: ok", true);
        } else {
            for (String failure : failures) {
                com.baggute.cheatclient.UtilityClient.LOGGER.error("[SelfTests] {}", failure);
            }
            Notifications.push("selftests: " + failures.size() + " failed", false);
        }
    }

    @Override
    protected void onDisable() {
        clearAll();
    }

    private static void clearAll() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            HISTORY.clear();
            TRANSITIONS.clear();
        }
    }
}
