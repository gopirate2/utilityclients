// language: Java, file: TargetHud.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;

/**
 * Everything the target card needs: who, how much health, how far. Rendering is
 * the TARGET_HUD widget so the card can be dragged and themed like the rest.
 */
public class TargetHud extends Module {

    private final DoubleSetting range;
    private final BoolSetting showArmor;
    private final BoolSetting showDistance;

    public TargetHud() {
        super("TargetHud", "Card for the player you are fighting.", Category.RENDER);

        this.range = new DoubleSetting("range", "Max distance to display", 8.0, 2.0, 32.0);
        this.showArmor = new BoolSetting("showArmor", "Show armour points", true);
        this.showDistance = new BoolSetting("showDistance", "Show distance", true);

        addSetting(range);
        addSetting(showArmor);
        addSetting(showDistance);
    }

    public double getRange() { return range.get(); }
    public boolean showArmor() { return showArmor.get(); }
    public boolean showDistance() { return showDistance.get(); }
}
