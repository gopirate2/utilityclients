// language: Java, file: StepUp.java, target: Fabric 26.2
package com.baggute.cheatclient.module.movement;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.DoubleSetting;

public class StepUp extends Module {

    public static volatile double stepHeight = 0.6;

    private final DoubleSetting height;

    public StepUp() {
        super("StepUp", "Walk up blocks without jumping.", Category.MOVEMENT);
        this.height = new DoubleSetting("height", "Step height (blocks)", 1.0, 1.0, 10.0);
        addSetting(height);
    }

    @Override
    public void onTick() {
        stepHeight = height.get();
    }

    @Override
    protected void onEnable() {
        stepHeight = height.get();
        UtilityClient.LOGGER.info("[StepUp] enabled (height={})", stepHeight);
    }

    @Override
    protected void onDisable() {
        stepHeight = 0.6;
        UtilityClient.LOGGER.info("[StepUp] disabled");
    }
}