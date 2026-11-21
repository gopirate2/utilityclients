package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.misc.Appearance;

/** Pure theme resolution allows previews and settings checks without a live renderer. */
public final class ThemePalette {
    public record Palette(int background, int panel, int accent, int text, int dim, int border, String style) {}
    private ThemePalette() {}
    public static Palette current() {
        var manager = UtilityClient.moduleManager;
        return resolve(manager != null && manager.getByName("Appearance") instanceof Appearance a ? a : null);
    }
    public static Palette resolve(Appearance a) {
        String name = a == null ? "Graphite" : a.palette.get();
        int bg = 0xFF121212, panel = 0xFF1E1E1E, accent = 0xFF4EC9C9, text = 0xFFFFFFFF;
        switch (name) {
            case "Midnight" -> { bg = 0xFF0C1020; panel = 0xFF171D34; accent = 0xFF9C8CFF; }
            case "Rose" -> { bg = 0xFF1B1019; panel = 0xFF2C1C29; accent = 0xFFFF89BC; }
            case "Forest" -> { bg = 0xFF0D1915; panel = 0xFF192B22; accent = 0xFF76DDA4; }
            case "Light" -> { bg = 0xFFF2F4F8; panel = 0xFFFFFFFF; accent = 0xFF2563B8; text = 0xFF172238; }
            case "Custom" -> { bg = a.background.get() | 0xFF000000; accent = a.accent.get() | 0xFF000000; text = a.text.get() | 0xFF000000; panel = blend(bg, text, 0.06f); }
        }
        return new Palette(bg, panel, accent, text, blend(panel, text, 0.62f), blend(panel, text, 0.16f), a == null ? "Flat" : a.style.get());
    }
    public static int blend(int a, int b, float fraction) {
        float t = Math.max(0, Math.min(1, fraction));
        int result = 0xFF000000;
        for (int shift : new int[]{16,8,0}) result |= Math.round(((a >> shift) & 255) * (1-t) + ((b >> shift) & 255) * t) << shift;
        return result;
    }
}
