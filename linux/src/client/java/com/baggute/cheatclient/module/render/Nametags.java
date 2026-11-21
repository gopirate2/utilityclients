// language: Java, file: Nametags.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;

/**
 * Floating labels over dropped items and mobs.
 *
 * <p>Rendering lives in {@code NametagRenderer} so this class holds only the
 * filters. The labels are emitted through the game's gizmo pass, the same one the
 * ESP boxes use, which is why they track the camera correctly at any distance
 * without a hand-rolled billboard matrix.
 */
public class Nametags extends Module {

    private final DoubleSetting range;
    private final BoolSetting showItems;
    private final BoolSetting showMobs;
    private final BoolSetting showHealth;
    private final DoubleSetting itemScale;
    private final DoubleSetting mobScale;
    private final ColorSetting itemColor;
    private final ColorSetting mobColor;
    private final IntSetting maxEntries;

    public Nametags() {
        super("Nametags", "Labels dropped items and mobs through walls.", Category.RENDER);

        this.range = new DoubleSetting("range", "Scan radius in blocks", 64.0, 8.0, 256.0);
        this.showItems = new BoolSetting("Items", "Label dropped items", true);
        this.showMobs = new BoolSetting("Mobs", "Label mobs", true);
        this.showHealth = new BoolSetting("showHealth", "Append health to mob labels", true);
        this.itemScale = new DoubleSetting("itemsScale", "Scale for item labels", 1.0, 0.4, 3.0);
        this.mobScale = new DoubleSetting("mobsScale", "Scale for mob labels", 1.0, 0.4, 3.0);
        this.itemColor = new ColorSetting("itemsColor", "Item label color", 0xFFFFD54F);
        this.mobColor = new ColorSetting("mobsColor", "Mob label color", 0xFFFF6E6E);
        this.maxEntries = new IntSetting("maxEntries", "Maximum labels drawn", 48, 1, 200);

        addSetting(range);
        addSetting(showItems);
        addSetting(showMobs);
        addSetting(showHealth);
        addSetting(itemScale);
        addSetting(mobScale);
        addSetting(itemColor);
        addSetting(mobColor);
        addSetting(maxEntries);
    }

    public double getRange() { return range.get(); }
    public boolean showItems() { return showItems.get(); }
    public boolean showMobs() { return showMobs.get(); }
    public boolean showHealth() { return showHealth.get(); }
    public double itemsScale() { return itemScale.get(); }
    public double mobsScale() { return mobScale.get(); }
    public int itemsColor() { return itemColor.get(); }
    public int mobsColor() { return mobColor.get(); }
    public int maxEntries() { return maxEntries.get(); }
}
