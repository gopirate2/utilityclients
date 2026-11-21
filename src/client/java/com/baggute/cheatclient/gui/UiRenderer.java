// language: Java, file: UiRenderer.java, target: Fabric 26.2
package com.baggute.cheatclient.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Flat chrome for the click GUI.
 *
 * <p>Deliberately separate from {@link Ui}: that one is the rounded, gradient
 * HUD theme shared with the position editor, while this one is a hard-edged
 * graphite sheet — square corners, one pixel of border, one accent. Keeping the
 * two apart means a change to the click GUI palette cannot drift the HUD.
 *
 * <p>Every geometry call takes a top-left corner plus a size, and every colour
 * is ARGB. Nothing here allocates.
 */
final class UiRenderer {

    private UiRenderer() {}
    private static ThemePalette.Palette activeTheme = ThemePalette.resolve(null);

    // ---- palette ----

    /** Window background: the darkest layer, behind every panel. */
    static int GRAPHITE = 0xFF121212;
    /** Panel body: sidebar, module list, settings column. */
    static int PANEL = 0xFF1E1E1E;
    /** Slightly recessed panel used for input fields and empty states. */
    static int RECESS = 0xFF171717;
    /** Header and footer strips: one step above the panels. */
    static int STRIP = 0xFF1A1A1A;

    /** The single hairline that separates every region. */
    static int BORDER = 0xFF2A2A2A;
    /** Softer hairline for inner dividers that should not compete. */
    static int BORDER_SOFT = 0xFF262626;

    /** Muted cyan. The only saturated colour in the sheet. */
    static int ACCENT = 0xFF4EC9C9;
    /** Accent lifted toward white, for text that must stay legible. */
    static int ACCENT_TEXT = 0xFF74DCDC;
    /** Accent at 1/6 for washes behind selected rows. */
    static int ACCENT_WASH = 0x1F4EC9C9;
    /** Accent at 1/3, for borders that want to read as active. */
    static int ACCENT_EDGE = 0x554EC9C9;

    static int TEXT = 0xFFFFFFFF;
    static int TEXT_DIM = 0xFF9A9A9A;
    static int TEXT_MUTED = 0xFF606060;
    static int TEXT_GHOST = 0xFF444444;

    /** Row hover wash over a panel. */
    static int HOVER = 0xFF262626;
    /** Input field body. */
    static int INPUT = 0xFF161616;
    /** Empty slider track / scrollbar groove. */
    static int TRACK = 0xFF2E2E2E;
    /** Swatch outline. */
    static int SWATCH = 0xFF3A3A3A;

    /** Friend highlight, shared with the ESP so the two agree. */
    static int FRIEND = 0xFF3DFF7A;
    /** Destructive action, used only on an active hover. */
    static int DANGER = 0xFFE05A5A;

    static void refreshTheme() {
        ThemePalette.Palette p = ThemePalette.current();
        activeTheme = p;
        GRAPHITE = p.background(); PANEL = p.panel(); ACCENT = p.accent(); TEXT = p.text();
        RECESS = INPUT = ThemePalette.blend(GRAPHITE, PANEL, .35f);
        STRIP = ThemePalette.blend(GRAPHITE, PANEL, .7f);
        BORDER = p.border(); BORDER_SOFT = ThemePalette.blend(PANEL, TEXT, .10f);
        ACCENT_TEXT = ThemePalette.blend(ACCENT, TEXT, .25f);
        ACCENT_WASH = 0x1F000000 | (ACCENT & 0xFFFFFF); ACCENT_EDGE = 0x55000000 | (ACCENT & 0xFFFFFF);
        TEXT_DIM = p.dim(); TEXT_MUTED = ThemePalette.blend(PANEL, TEXT, .46f); TEXT_GHOST = ThemePalette.blend(PANEL, TEXT, .33f);
        HOVER = ThemePalette.blend(PANEL, ACCENT, .10f); TRACK = ThemePalette.blend(PANEL, TEXT, .12f); SWATCH = BORDER;
        Ui.applyTheme(p);
    }

    // ---- primitives ----

    static void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        ThemePalette.Palette theme = activeTheme;
        boolean surface = w > 20 && h > 12 && (argb == GRAPHITE || argb == PANEL || argb == STRIP || argb == RECESS || argb == INPUT);
        if (surface && theme.style().equals("Outlined")) argb = GRAPHITE;
        if (surface && theme.style().equals("Glass")) argb = 0xC0000000 | (argb & 0xFFFFFF);
        if (surface && (theme.style().equals("Rounded") || theme.style().equals("Glass")))
            Ui.roundedFill(g, x, y, w, h, 6, argb);
        else g.fill(x, y, x + w, y + h, argb);
    }

    /** One pixel outline, drawn inside the given box. */
    static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        if (w > 40 && h > 14 && activeTheme.style().equals("Outlined") && argb == BORDER) argb = ACCENT;
        if (w > 40 && h > 14 && (activeTheme.style().equals("Rounded") || activeTheme.style().equals("Glass"))) {
            int r = Math.min(6, Math.min(w, h) / 2);
            fill(g, x + r, y, w - 2*r, 1, argb); fill(g, x + r, y + h - 1, w - 2*r, 1, argb);
            fill(g, x, y + r, 1, h - 2*r, argb); fill(g, x + w - 1, y + r, 1, h - 2*r, argb);
            for (int row = 0; row < r; row++) {
                double dy = r - row - .5;
                int inset = Math.max(0, r - (int)Math.sqrt(r*r - dy*dy));
                fill(g, x + inset, y + row, 1, 1, argb); fill(g, x + w - 1 - inset, y + row, 1, 1, argb);
                fill(g, x + inset, y + h - 1 - row, 1, 1, argb); fill(g, x + w - 1 - inset, y + h - 1 - row, 1, 1, argb);
            }
            return;
        }
        fill(g, x, y, w, 1, argb);
        fill(g, x, y + h - 1, w, 1, argb);
        fill(g, x, y, 1, h, argb);
        fill(g, x + w - 1, y, 1, h, argb);
    }

    /** Border + body in one call: the standard panel. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int body) {
        fill(g, x, y, w, h, BORDER);
        fill(g, x + 1, y + 1, w - 2, h - 2, body);
    }

    static void hLine(GuiGraphicsExtractor g, int x, int y, int w, int argb) {
        fill(g, x, y, w, 1, argb);
    }

    static void vLine(GuiGraphicsExtractor g, int x, int y, int h, int argb) {
        fill(g, x, y, 1, h, argb);
    }

    // ---- text ----

    static void textLeft(GuiGraphicsExtractor g, Font font, String text, int x, int y, int argb) {
        if (text == null || text.isEmpty()) return;
        g.text(font, text, x, y, argb, false);
    }

    static void textRight(GuiGraphicsExtractor g, Font font, String text, int rightX, int y, int argb) {
        if (text == null || text.isEmpty()) return;
        g.text(font, text, rightX - font.width(text), y, argb, false);
    }

    static void textCentre(GuiGraphicsExtractor g, Font font, String text, int centreX, int y, int argb) {
        if (text == null || text.isEmpty()) return;
        g.text(font, text, centreX - font.width(text) / 2, y, argb, false);
    }

    /**
     * Shortens to fit {@code maxWidth}, ending in ".." .
     *
     * <p>Measures the cut result rather than the source, because the ellipsis
     * itself costs width and a naive trim overflows the row it was meant to save.
     */
    static String trim(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (font.width(text) <= maxWidth) return text;
        if (maxWidth <= font.width("..")) return "";
        int keep = text.length();
        while (keep > 0 && font.width(text.substring(0, keep) + "..") > maxWidth) keep--;
        return text.substring(0, keep) + "..";
    }

    /** Uppercase label helper: the sheet's headers are all caps at 1 px spacing. */
    static String caps(String text) {
        return text == null ? "" : text.toUpperCase(java.util.Locale.ROOT);
    }

    // ---- widgets ----

    /**
     * 10x10 square toggle.
     *
     * <p>Outlined when off, filled accent with a dark tick when on. The box is
     * fixed size on purpose: the sheet has no rounded geometry, so a square that
     * grows with its row would read as a different control.
     */
    static void checkbox(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hover) {
        int edge = on ? ACCENT : (hover ? SWATCH : BORDER);
        fill(g, x, y, 10, 10, on ? ACCENT : RECESS);
        outline(g, x, y, 10, 10, edge);
        if (on) {
            // Two strokes of a tick, punched out in the window colour.
            fill(g, x + 2, y + 4, 2, 4, GRAPHITE);
            fill(g, x + 4, y + 6, 5, 2, GRAPHITE);
        }
    }

    /**
     * Thin track with a square handle.
     *
     * <p>The handle is 4x10 and straddles the track so it stays grabbable at any
     * scale; {@code fraction} is clamped so a NaN never reaches {@code fill}.
     */
    static void slider(GuiGraphicsExtractor g, int x, int y, int w, double fraction, boolean active) {
        int trackH = 2;
        int ty = y + 4;
        double f = Double.isFinite(fraction) ? Math.max(0.0, Math.min(1.0, fraction)) : 0.0;
        fill(g, x, ty, w, trackH, TRACK);
        int filled = (int) Math.round((w - 1) * f);
        if (filled > 0) fill(g, x, ty, filled, trackH, ACCENT);
        int hx = x + filled - 2;
        if (hx < x - 1) hx = x - 1;
        if (hx > x + w - 3) hx = x + w - 3;
        fill(g, hx, y, 4, 10, active ? ACCENT_TEXT : ACCENT);
    }

    /**
     * Vertical scrollbar for a pixel-scrolled region.
     *
     * <p>{@code viewH} is the visible height and {@code contentH} the full content
     * height; when the content fits, only the groove is drawn so the layout does
     * not jump as rows are added.
     */
    static void scrollbar(GuiGraphicsExtractor g, int x, int y, int viewH, double scroll, int contentH) {
        if (viewH <= 0) return;
        fill(g, x, y, 2, viewH, TRACK);
        if (contentH <= viewH) return;
        int thumb = Math.max(16, (int) ((long) viewH * viewH / contentH));
        int travel = viewH - thumb;
        double max = contentH - viewH;
        double t = max <= 0 ? 0 : Math.max(0.0, Math.min(1.0, scroll / max));
        int ty = y + (int) Math.round(travel * t);
        fill(g, x, ty, 2, thumb, ACCENT);
    }

    /** Colour swatch with a 1 px outline; the alpha channel is drawn as-is. */
    static void swatch(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        fill(g, x, y, w, h, SWATCH);
        fill(g, x + 1, y + 1, w - 2, h - 2, argb);
    }

    /** Small outlined label, used for keybinds and counts. */
    static int badgeWidth(Font font, String text) {
        return font.width(text) + 10;
    }

    static void badge(GuiGraphicsExtractor g, Font font, String text, int x, int y, int fg, int bg, int edge) {
        int w = badgeWidth(font, text);
        fill(g, x, y, w, 12, edge);
        fill(g, x + 1, y + 1, w - 2, 10, bg);
        g.text(font, text, x + 5, y + 2, fg, false);
    }

    /** Flat chip used for the footer actions. Returns its width so callers can lay out right-to-left. */
    static int chipWidth(Font font, String text) {
        return font.width(text) + 16;
    }

    static void chip(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int h,
                     boolean active, boolean hover) {
        fill(g, x, y, w, h, active ? ACCENT : (hover ? SWATCH : BORDER));
        fill(g, x + 1, y + 1, w - 2, h - 2, active ? ACCENT : (hover ? HOVER : PANEL));
        int fg = active ? GRAPHITE : (hover ? TEXT : TEXT_DIM);
        g.text(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2 + 1, fg, false);
    }
}
