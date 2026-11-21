// language: Java, file: PresetSwitch.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.config.ConfigManager;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.settings.StringSetting;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Hot-swaps config presets without opening the GUI: one key cycles forward, one
 * back. The current preset name is kept so the binds stay in sequence.
 */
public class PresetSwitch extends Module {

    private final KeybindSetting next = new KeybindSetting("nextPreset", "Load the next preset", -1, false);
    private final KeybindSetting previous = new KeybindSetting("prevPreset", "Load the previous preset", -1, false);
    private final StringSetting current = new StringSetting("current", "Active preset", "");

    public PresetSwitch() {
        super("PresetSwitch", "Cycles config presets with hotkeys.", Category.MISC);
        addSetting(next);
        addSetting(previous);
        addSetting(current);
    }

    @Override
    public void onRawKey(int keyCode, int action) {
        // Deliberately not gated on isEnabled(): ModuleManager routes secondary
        // binds to every module so PresetSwitch can swap presets while the rest of
        // the client (including this module's own toggle) is off.
        if (action != 1) return;
        if (keyCode == next.getKey()) cycle(1);
        else if (keyCode == previous.getKey()) cycle(-1);
    }

    private void cycle(int direction) {
        // ConfigManager is owned by UtilityClient; it is created once at startup.
        ConfigManager manager = UtilityClient.configManager();
        if (manager == null) return;

        List<String> presets = ConfigManager.listPresets();
        if (presets.isEmpty()) {
            Notifications.push("no presets saved", false);
            return;
        }

        int index = presets.indexOf(current.get());
        index = index < 0 ? (direction > 0 ? 0 : presets.size() - 1)
                          : Math.floorMod(index + direction, presets.size());

        String name = presets.get(index);
        if (manager.loadPreset(name)) {
            current.set(name);
            Notifications.push("preset: " + name, true);
        } else {
            Notifications.push("preset load failed: " + name, false);
        }
    }

    @Override
    protected void onEnable() {
        if (Minecraft.getInstance().player != null) {
            UtilityClient.LOGGER.info("[PresetSwitch] enabled");
        }
    }
}
