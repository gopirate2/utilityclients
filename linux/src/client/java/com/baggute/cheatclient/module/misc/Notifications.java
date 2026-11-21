// language: Java, file: Notifications.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Toggle banners. Module.setEnabled pushes here; the HUD draws them top-centre
 * with a fade so a toggle is visible without opening the GUI.
 */
public class Notifications extends Module {

    private static final Deque<Toast> QUEUE = new ArrayDeque<>();
    private static final int MAX_TOASTS = 5;
    private static boolean muted = false;

    private final BoolSetting show = new BoolSetting("show", "Show toggle banners", true);
    private final IntSetting durationMs = new IntSetting("durationMs", "Banner lifetime (ms)", 2200, 500, 8000);
    private final ColorSetting onColor = new ColorSetting("onColor", "Enable banner color", 0xFF4CAF50);
    private final ColorSetting offColor = new ColorSetting("offColor", "Disable banner color", 0xFFE05555);

    private record Toast(String text, long bornMs, boolean enabled) {}

    public Notifications() {
        super("Notifications", "Banners for module toggles.", Category.RENDER);
        addSetting(show);
        addSetting(durationMs);
        addSetting(onColor);
        addSetting(offColor);
    }

    /** Silences banners while a config or preset is being applied wholesale. */
    public static void setMuted(boolean value) {
        muted = value;
    }

    public static void push(String text, boolean on) {
        if (muted) return;
        synchronized (QUEUE) {
            QUEUE.addLast(new Toast(text, System.currentTimeMillis(), on));
            while (QUEUE.size() > MAX_TOASTS) QUEUE.removeFirst();
        }
    }

    public static void render(GuiGraphicsExtractor g, Minecraft mc) {
        if (mc.font == null) return;

        Notifications module = null;
        if (com.baggute.cheatclient.UtilityClient.moduleManager != null) {
            Module m = com.baggute.cheatclient.UtilityClient.moduleManager.getByName("Notifications");
            if (m instanceof Notifications n) module = n;
        }
        if (module == null || !module.isEnabled() || !module.show.get()) {
            synchronized (QUEUE) { QUEUE.clear(); }
            return;
        }

        long now = System.currentTimeMillis();
        long lifetime = module.durationMs.get();

        synchronized (QUEUE) {
            QUEUE.removeIf(t -> now - t.bornMs > lifetime);
            if (QUEUE.isEmpty()) return;

            int y = 8;
            for (Toast toast : QUEUE) {
                long age = now - toast.bornMs;
                int alpha = (int) (255 * Math.max(0.0, 1.0 - (double) age / lifetime));
                int color = ((toast.enabled ? module.onColor.get() : module.offColor.get()) & 0xFFFFFF)
                        | (alpha << 24);
                int w = mc.font.width(toast.text);
                int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
                g.fill(x - 4, y - 2, x + w + 4, y + 10, (alpha / 3) << 24);
                g.text(mc.font, toast.text, x, y, color, true);
                y += 14;
            }
        }
    }

    @Override
    protected void onDisable() {
        synchronized (QUEUE) { QUEUE.clear(); }
    }
}
