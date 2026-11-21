package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.settings.ChoiceSetting;
import com.baggute.cheatclient.settings.ColorSetting;

/** Persistent appearance preferences apply immediately, independent of the toggle. */
public final class Appearance extends Module {
    public final ChoiceSetting style = new ChoiceSetting("Style", "Panel finish; left/right click changes the selection", "Flat", "Flat", "Rounded", "Outlined", "Glass");
    public final ChoiceSetting palette = new ChoiceSetting("Palette", "Choose a preset or Custom to use the color pickers", "Graphite", "Graphite", "Midnight", "Rose", "Forest", "Light", "Custom");
    public final ColorSetting accent = new ColorSetting("Accent", "Custom accent color", 0xFF4EC9C9);
    public final ColorSetting background = new ColorSetting("Background", "Custom window background", 0xFF121212);
    public final ColorSetting text = new ColorSetting("Text", "Custom text color", 0xFFFFFFFF);
    public Appearance() {
        super("Appearance", "Themes apply immediately. Choose Style and Palette, or edit Custom colors.", Category.MISC);
        addSetting(style); addSetting(palette); addSetting(accent); addSetting(background); addSetting(text);
    }
}
