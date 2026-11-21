package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.IntSetting;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Non-blocking timing for automatic interactions; rendering and input callbacks keep running. */
public final class ActionTiming extends Module {
    private final IntSetting minDelayMs = new IntSetting("minDelayMs", "Minimum extra delay for an automatic action (milliseconds)", 0, 0, 250);
    private final IntSetting maxDelayMs = new IntSetting("maxDelayMs", "Maximum extra delay; AutoPearlCatch always uses its own timing", 35, 0, 250);
    private static final ThreadLocal<Module> ACTIVE = new ThreadLocal<>();
    private static final Map<Module, Window> WINDOWS = new IdentityHashMap<>();
    private static LongSupplier clock = System::nanoTime;
    private static final class Window { long due; boolean armed; }

    public ActionTiming() {
        super("ActionTiming", "Adds a bounded random delay to automatic interactions, with no extra pearl-catch delay.", Category.MISC);
        addSetting(minDelayMs); addSetting(maxDelayMs);
        setEnabledHeld(true);
    }
    public static void run(Module module, Runnable callback) {
        Module previous = ACTIVE.get(); ACTIVE.set(module);
        try { callback.run(); }
        finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    public static synchronized boolean ready() {
        Module active = ACTIVE.get();
        if (active == null || !active.isEnabled() || active.getName().equals("AutoPearlCatch")
                || UtilityClient.moduleManager == null) return true;
        var setting = UtilityClient.moduleManager.getByName("ActionTiming");
        if (!(setting instanceof ActionTiming timing) || !timing.isEnabled()) return true;
        int low = Math.min(timing.minDelayMs.get(), timing.maxDelayMs.get());
        int high = Math.max(timing.minDelayMs.get(), timing.maxDelayMs.get());
        if (high == 0) { WINDOWS.remove(active); return true; }
        Window window = WINDOWS.computeIfAbsent(active, key -> new Window());
        long now = clock.getAsLong();
        if (!window.armed) {
            window.due = now + ThreadLocalRandom.current().nextLong(low, (long) high + 1) * 1_000_000L;
            window.armed = true;
        }
        return now - window.due >= 0;
    }
    /** Sample again for the next actual action, never for a blocked retry. */
    public static synchronized void consumed() {
        Window window = WINDOWS.get(ACTIVE.get()); if (window != null) window.armed = false;
    }
    public static synchronized void forget(Module module) { WINDOWS.remove(module); }
    public static synchronized void reset() { WINDOWS.clear(); }
    @Override protected void onDisable() { reset(); }
}
