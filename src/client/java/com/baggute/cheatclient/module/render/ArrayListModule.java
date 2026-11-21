// language: Java, file: ArrayListModule.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The classic right-edge module list: every enabled module, sorted by width so the
 * edge stays a clean staircase, with an optional dimmed background bar.
 *
 * <p>The order is decided once, in {@link #lines(Minecraft)}, and the renderer
 * walks that same list. The two used to sort differently (name length vs. pixel
 * width), so the capture-excluded overlay drew a different order than the game and
 * the staircase broke on the second line.
 */
public class ArrayListModule extends Module {

    private static final double RAINBOW_STEP_PER_TICK = 0.001;
    private static final float RAINBOW_HUE_SPREAD = 0.06f;

    private final BoolSetting background = new BoolSetting("background", "Draw a background bar", true);
    private final BoolSetting rainbow = new BoolSetting("rainbow", "Cycle colors", false);
    private final ColorSetting textColor = new ColorSetting("textColor", "Text color", 0xFF00E5FF);
    private final ColorSetting bgColor = new ColorSetting("bgColor", "Background color", 0x90101010);
    private final DoubleSetting scale = new DoubleSetting("scale", "Scale", 1.0, 0.5, 2.0);
    private final BoolSetting lowercase = new BoolSetting("lowercase", "Lowercase names", false);

    private float hue = 0f;

    public ArrayListModule() {
        super("ArrayList", "Displays enabled modules down the right edge.", Category.RENDER);
        addSetting(background);
        addSetting(rainbow);
        addSetting(textColor);
        addSetting(bgColor);
        addSetting(scale);
        addSetting(lowercase);
    }

    @Override
    public void onRenderFrame(float partialTick) {
        if (!rainbow.get()) return;
        hue += (float) RAINBOW_STEP_PER_TICK;
        if (hue > 1f) hue -= 1f;
    }

    /**
     * Names the list would draw, in draw order — longest first so the right edge
     * stays flush. Empty when the module is off.
     */
    public List<String> lines(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (!isEnabled() || UtilityClient.moduleManager == null) return out;
        boolean lower = lowercase.get();
        for (Module m : UtilityClient.moduleManager.getModules()) {
            if (!m.isEnabled() || m == this) continue;
            out.add(lower ? m.getName().toLowerCase() : m.getName());
        }
        out.sort(Comparator.comparingInt(String::length).reversed());
        return out;
    }

    public double listScale() { return scale.get(); }

    public int listColor() { return textColor.get(); }

    public void renderList(GuiGraphicsExtractor g, Minecraft mc) {
        List<String> lines = lines(mc);
        if (lines.isEmpty()) return;

        double s = scale.get();
        g.pose().pushMatrix();
        g.pose().scale((float) s, (float) s);

        int screenW = (int) (mc.getWindow().getGuiScaledWidth() / s);
        int y = 4;
        for (int i = 0; i < lines.size(); i++) {
            String name = lines.get(i);
            int w = mc.font.width(name);
            int color = listColor(i);
            if (background.get()) {
                g.fill(screenW - w - 6, y - 1, screenW, y + 9, bgColor.get());
            }
            g.text(mc.font, name, screenW - w - 3, y, color, true);
            y += 10;
        }

        g.pose().popMatrix();
    }

    /** Text colour for the entry at {@code index}; rainbow when cycling. */
    private int listColor(int index) {
        if (!rainbow.get()) return textColor.get();
        float entryHue = (hue + index * RAINBOW_HUE_SPREAD) % 1f;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(entryHue, 0.55f, 1f) & 0xFFFFFF);
    }

    @Override
    protected void onEnable() {
        hue = 0f;
    }
}
