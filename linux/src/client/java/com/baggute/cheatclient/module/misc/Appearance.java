package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.settings.ChoiceSetting;
import com.baggute.cheatclient.settings.ColorSetting;

/** Persistent appearance preferences apply immediately, independent of the toggle. */
public final class Appearance extends Module {
    public final ChoiceSetting style = new ChoiceSetting("Style", "Panel finish; left/right click changes the selection", "Flat", "Flat", "Rounded", "Outlined", "Glass");
    public final ChoiceSetting palette = new ChoiceSetting("Palette", "Choose a preset or Custom to use the color pickers", "Graphite", "Graphite", "Midnight", "Rose", "Forest", "Light", "Ocean", "Sunset", "Amethyst", "Nord", "Cyberpunk", "Cherry", "Sandstone", "Ice", "Monochrome", "Solarized", "Coffee", "Custom");
    public final ColorSetting accent = new ColorSetting("Accent", "Custom accent color", 0xFF4EC9C9);
    public final ColorSetting background = new ColorSetting("Background", "Custom window background", 0xFF121212);
    public final ColorSetting text = new ColorSetting("Text", "Custom text color", 0xFFFFFFFF);
    public final ColorSetting panel = new ColorSetting("Panel", "Custom panel color", 0xFF1E1E1E);
    public final ColorSetting dim = new ColorSetting("Muted Text", "Custom secondary text color", 0xFF9A9A9A);
    public final ColorSetting border = new ColorSetting("Border", "Custom border color", 0xFF2A2A2A);
    public final com.baggute.cheatclient.settings.BoolSetting customSurfaceColors = new com.baggute.cheatclient.settings.BoolSetting("Custom Surface Colors", "Use explicitly edited panel/muted/border colors; older profiles retain their derived colors", false);
    public final com.baggute.cheatclient.settings.DoubleSetting opacity = new com.baggute.cheatclient.settings.DoubleSetting("Opacity", "Panel opacity", 1, .55, 1);
    public final com.baggute.cheatclient.settings.IntSetting radius = new com.baggute.cheatclient.settings.IntSetting("Corner Radius", "Rounded/Glass corner radius", 6, 0, 16);
    public final com.baggute.cheatclient.settings.IntSetting borderWidth = new com.baggute.cheatclient.settings.IntSetting("Border Width", "Panel outline thickness", 1, 0, 3);
    public final com.baggute.cheatclient.settings.DoubleSetting hover = new com.baggute.cheatclient.settings.DoubleSetting("Hover Strength", "Accent wash on hovered rows", .1, 0, .5);
    public final com.baggute.cheatclient.settings.StringSetting themeName = new com.baggute.cheatclient.settings.StringSetting("Theme Name", "Name shown on shared themes", "My Theme");
    public final com.baggute.cheatclient.settings.StringSetting author = new com.baggute.cheatclient.settings.StringSetting("Author", "Optional shared theme author", "");
    public Appearance() {
        super("Appearance", "Themes apply immediately. Choose Style and Palette, or edit Custom colors.", Category.MISC);
        addSetting(style); addSetting(palette); addSetting(accent); addSetting(background); addSetting(text);
        addSetting(panel); addSetting(dim); addSetting(border); addSetting(customSurfaceColors); addSetting(opacity); addSetting(radius); addSetting(borderWidth); addSetting(hover); addSetting(themeName); addSetting(author);
    }
}
