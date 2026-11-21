// language: Java, file: Watermark.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.StringSetting;

/**
 * Client watermark: name, fps, ping.
 *
 * <p>Drawn by the HUD renderer rather than by a widget, so it is on screen in the
 * corner even when every widget is switched off. Position, scale and colour are
 * settings, so a preset carries them like anything else.
 */
public class Watermark extends Module {

    private final StringSetting text;
    private final BoolSetting showFps;
    private final BoolSetting showPing;
    private final IntSetting x;
    private final IntSetting y;
    private final DoubleSetting scale;
    private final ColorSetting color;
    private final BoolSetting background;

    public Watermark() {
        super("Watermark", "Client name, fps and ping in the corner.", Category.MISC);

        this.text = new StringSetting("text", "Watermark text", "UtilityClient");
        this.showFps = new BoolSetting("showFps", "Show fps", true);
        this.showPing = new BoolSetting("showPing", "Show ping", true);
        this.x = new IntSetting("x", "Screen X", 4, 0, 4000);
        this.y = new IntSetting("y", "Screen Y", 4, 0, 4000);
        this.scale = new DoubleSetting("scale", "Scale", 1.0, 0.5, 3.0);
        this.color = new ColorSetting("color", "Text color", 0xFF00E5FF);
        this.background = new BoolSetting("background", "Draw background", true);

        addSetting(text);
        addSetting(showFps);
        addSetting(showPing);
        addSetting(x);
        addSetting(y);
        addSetting(scale);
        addSetting(color);
        addSetting(background);
    }

    public String label() { return text.get(); }
    public boolean showFps() { return showFps.get(); }
    public boolean showPing() { return showPing.get(); }
    public int x() { return x.get(); }
    public int y() { return y.get(); }
    public double scale() { return scale.get(); }
    public int color() { return color.get(); }
    public boolean background() { return background.get(); }
}
