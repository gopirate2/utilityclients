package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.misc.Appearance;

/** Pure theme resolution allows previews and settings checks without a live renderer. */
public final class ThemePalette {
    public record Palette(int background, int panel, int accent, int text, int dim, int border, String style, double opacity, int radius, int borderWidth, double hover) {}
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
            case "Ocean" -> { bg=0xFF071D2A;panel=0xFF103346;accent=0xFF49D3E9; }
            case "Sunset" -> { bg=0xFF28141E;panel=0xFF442235;accent=0xFFFFAB6B; }
            case "Amethyst" -> { bg=0xFF1C122D;panel=0xFF322044;accent=0xFFC79AFF; }
            case "Nord" -> { bg=0xFF2E3440;panel=0xFF3B4252;accent=0xFF88C0D0;text=0xFFECEFF4; }
            case "Cyberpunk" -> { bg=0xFF0C081B;panel=0xFF201137;accent=0xFFFF47C7;text=0xFFF8F46C; }
            case "Cherry" -> { bg=0xFF250D16;panel=0xFF421A27;accent=0xFFFF557E; }
            case "Sandstone" -> { bg=0xFFF5E8CC;panel=0xFFFFF5DF;accent=0xFFA15A25;text=0xFF382E22; }
            case "Ice" -> { bg=0xFFE5F4FA;panel=0xFFF5FBFF;accent=0xFF1678A6;text=0xFF163448; }
            case "Monochrome" -> { bg=0xFF060606;panel=0xFF242424;accent=0xFFE8E8E8; }
            case "Solarized" -> { bg=0xFF002B36;panel=0xFF073642;accent=0xFF2AA198;text=0xFFEEE8D5; }
            case "Coffee" -> { bg=0xFF211B18;panel=0xFF382D27;accent=0xFFD4A77C;text=0xFFF4EADF; }
            case "Custom" -> { bg = a.background.get() | 0xFF000000; accent = a.accent.get() | 0xFF000000; text = a.text.get() | 0xFF000000; panel = a.customSurfaceColors.get() ? a.panel.get() | 0xFF000000 : blend(bg,text,.06f); }
        }
        boolean explicit=name.equals("Custom")&&a.customSurfaceColors.get();
        return new Palette(bg, panel, accent, text, explicit ? a.dim.get()|0xFF000000 : blend(panel, text, 0.62f),
                explicit ? a.border.get()|0xFF000000 : blend(panel, text, 0.16f), a == null ? "Flat" : a.style.get(),
                a==null?1:a.opacity.get(), a==null?6:a.radius.get(), a==null?1:a.borderWidth.get(), a==null?.1:a.hover.get());
    }
    public static int blend(int a, int b, float fraction) {
        float t = Math.max(0, Math.min(1, fraction));
        int result = 0xFF000000;
        for (int shift : new int[]{16,8,0}) result |= Math.round(((a >> shift) & 255) * (1-t) + ((b >> shift) & 255) * t) << shift;
        return result;
    }
}
