// language: Java, file: Ui.java, target: Fabric 26.2
package com.baggute.cheatclient.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared dark-theme chrome for the client's screens.
 *
 * <p>One palette plus a handful of primitives (gradient, shadow, corner ticks,
 * chips) so the click GUI and the HUD editor look like the same product. All
 * colours are ARGB and the accent is animated between cyan and violet.
 */
final class Ui {

    static int BG      = 0xF507080A;
    static int HEADER  = 0xF80D0F14;
    static int SIDEBAR = 0xFA090B0F;
    static int BORDER  = 0xFF252B35;
    static int SOFT    = 0xFF171B22;
    static int ROW_SEL = 0xFF141920;

    static int TEXT    = 0xFFEDF1F8;
    static int DIM     = 0xFF9AA4B5;
    static int MUTED   = 0xFF5F6775;

    static int ACCENT  = 0xFF29E0FF;
    static int ACCENT2 = 0xFF8B7BFF;
    static int GREEN   = 0xFF3DDC97;
    static int RED     = 0xFFFF6B81;

    static int HOVER   = 0x14FFFFFF;
    static int INPUT   = 0xFF10141A;
    static int TRACK   = 0x22FFFFFF;
    static int SWATCH  = 0xFF2B3240;
    static int WASH    = 0x1429E0FF;

    private Ui() {}
    static void applyTheme(ThemePalette.Palette p) {
        BG = p.background(); HEADER = p.panel(); SIDEBAR = p.background(); BORDER = p.border();
        SOFT = ROW_SEL = ThemePalette.blend(p.panel(), p.accent(), .08f);
        TEXT = p.text(); DIM = p.dim(); MUTED = ThemePalette.blend(p.panel(), p.text(), .46f);
        ACCENT = p.accent(); ACCENT2 = ThemePalette.blend(p.accent(), p.text(), .25f);
        INPUT = p.background(); SWATCH = p.border(); WASH = 0x14000000 | (p.accent() & 0xFFFFFF);
    }

    // ---- colour ----

    static int mix(int a, int b, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int aa = lerpChannel(a >>> 24, b >>> 24, f);
        int ar = lerpChannel((a >> 16) & 0xFF, (b >> 16) & 0xFF, f);
        int ag = lerpChannel((a >> 8) & 0xFF, (b >> 8) & 0xFF, f);
        int ab = lerpChannel(a & 0xFF, b & 0xFF, f);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    private static int lerpChannel(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }

    static int alpha(int argb, int alpha) {
        return ((alpha & 0xFF) << 24) | (argb & 0xFFFFFF);
    }

    static int fade(int argb, float factor) {
        float f = Math.max(0f, Math.min(1f, factor));
        return alpha(argb, (int) (((argb >>> 24) & 0xFF) * f));
    }

    /** Slow 0..1 sine, used to breathe the accent colour. */
    static float pulse() {
        double phase = (System.currentTimeMillis() % 6000L) / 6000.0;
        return (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0));
    }

    /** Accent colour of the moment (cyan drifting toward violet). */
    static int accent() {
        return mix(ACCENT, ACCENT2, 0.42f * pulse());
    }

    static int accentFaded(float factor) {
        return fade(accent(), factor);
    }

    // ---- primitives ----

    static void hGradient(GuiGraphicsExtractor g, int x1, int x2, int y1, int y2, int from, int to) {
        int steps = Math.max(1, x2 - x1);
        for (int i = 0; i < steps; i++) {
            g.fill(x1 + i, y1, x1 + i + 1, y2, mix(from, to, (float) i / steps));
        }
    }

    static void vGradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int from, int to) {
        int steps = Math.max(1, y2 - y1);
        for (int i = 0; i < steps; i++) {
            g.fill(x1, y1 + i, x2, y1 + i + 1, mix(from, to, (float) i / steps));
        }
    }

    /** Feathered drop shadow under a panel. */
    static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        for (int i = 8; i >= 1; i--) {
            int a = 0x0A + (8 - i) * 0x03;
            g.fill(x - i, y - i, x + w + i, y + h + i, a << 24);
        }
        g.fill(x - 2, y + h, x + w + 2, y + h + 4, 0x30000000);
    }

    /** Four short L-shaped accent strokes on the corners of a panel. */
    static void corners(GuiGraphicsExtractor g, int x, int y, int w, int h, int len, int color) {
        g.fill(x, y, x + len, y + 1, color);
        g.fill(x, y, x + 1, y + len, color);
        g.fill(x + w - len, y, x + w, y + 1, color);
        g.fill(x + w - 1, y, x + w, y + len, color);
        g.fill(x, y + h - 1, x + len, y + h, color);
        g.fill(x, y + h - len, x + 1, y + h, color);
        g.fill(x + w - len, y + h - 1, x + w, y + h, color);
        g.fill(x + w - 1, y + h - len, x + w, y + h, color);
    }

    /** Panel body with a hairline border, a top highlight and corner ticks. */
    static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x + 1, y, x + w - 1, y + 1, 0x18FFFFFF);
        corners(g, x - 1, y - 1, w + 2, h + 2, 9, fade(accent(), 0.55f));
    }

    /** Small outlined label, e.g. "ON", "v1.0", "FPS 240". */
    static void chip(GuiGraphicsExtractor g, Font font, String text,
                     int x, int y, int fg, int bg, int border) {
        int w = font.width(text) + 8;
        g.fill(x, y, x + w, y + 11, border);
        g.fill(x + 1, y + 1, x + w - 1, y + 10, bg);
        g.text(font, text, x + 4, y + 2, fg, false);
    }

    static int chipWidth(Font font, String text) {
        return font.width(text) + 8;
    }

    // ---- rounded geometry ----

    /**
     * Filled rectangle with circular corners.
     *
     * <p>Row spans rather than a shader: the GUI renderer batches {@code fill}
     * calls into one buffer, so a 6 px radius costs twelve spans and no state
     * change. Anything larger than half the smaller side is clamped, which keeps
     * a short row from turning into a lozenge.
     */
    static void roundedFill(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        if (((color >>> 24) & 0xFF) == 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i;
            double halfWidth = Math.sqrt(Math.max(0.0, (double) r * r - dy * dy));
            int inset = (int) Math.ceil(r - halfWidth);
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    /** Border + body in one call: the click GUI shell and its panels both use it. */
    static void roundedFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius,
                             int border, int fill) {
        roundedFill(g, x, y, w, h, radius, border);
        roundedFill(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
    }

    /** Thin separator with rounded ends so it does not look cut with scissors. */
    static void roundedBar(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        roundedFill(g, x, y, w, h, Math.min(h / 2, 3), color);
    }
}
