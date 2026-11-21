// language: Java, file: ESP.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.StringSetting;

import java.util.HashSet;
import java.util.Set;

public class ESP extends Module {

    private final StringSetting entityFilter;
    private final BoolSetting tracers;
    private final DoubleSetting range;
    private final ColorSetting playerColor;
    private final ColorSetting mobColor;
    private final ColorSetting tracerColor;

    public ESP() {
        super("ESP", "Highlights selected entity types through walls.", Category.RENDER);

        this.entityFilter = new StringSetting("entityFilter", "Enabled entity IDs",
                "minecraft:player,minecraft:zombie,minecraft:skeleton,minecraft:creeper,minecraft:enderman");
        this.tracers     = new BoolSetting("tracers", "Draw lines to crosshair", false);
        this.range       = new DoubleSetting("range", "Render distance in blocks", 256.0, 8.0, 256.0);
        this.playerColor = new ColorSetting("playerColor", "Player outline color", 0xFF3D7BFF);
        this.mobColor    = new ColorSetting("mobColor", "Mob outline color", 0xFFFF4040);
        this.tracerColor = new ColorSetting("tracerColor", "Tracer line color", 0xFF00E5FF);

        addSetting(entityFilter);
        addSetting(tracers);
        addSetting(range);
        addSetting(playerColor);
        addSetting(mobColor);
        addSetting(tracerColor);
    }

    public Set<String> getEntityFilter() {
        Set<String> out = new HashSet<>();
        for (String part : entityFilter.get().split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public StringSetting getEntityFilterSetting() { return entityFilter; }
    public boolean isTracers() { return tracers.get(); }
    public double getRange() { return range.get(); }
    public int getPlayerColor() { return playerColor.get(); }
    public int getMobColor() { return mobColor.get(); }
    public int getTracerColor() { return tracerColor.get(); }

    @Override protected void onEnable() {
        com.baggute.cheatclient.UtilityClient.LOGGER.info("[ESP] enabled");
    }
    @Override protected void onDisable() {
        com.baggute.cheatclient.UtilityClient.LOGGER.info("[ESP] disabled");
    }
}