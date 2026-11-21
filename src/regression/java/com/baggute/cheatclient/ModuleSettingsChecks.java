package com.baggute.cheatclient;

import com.baggute.cheatclient.config.ConfigManager;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.module.apex.ApexModule;
import com.baggute.cheatclient.module.apex.ApexModules;
import com.baggute.cheatclient.module.apex.ApexSettings;
import com.baggute.cheatclient.settings.*;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** Config regressions exercised without starting Minecraft or writing live files. */
public final class ModuleSettingsChecks {
    public static void run() {
        int configured = 0, controls = 0;
        Set<String> actionOnly = Set.of("ClickGUI", "Humanization", "Backtrack");
        for (Module module : ApexModules.createAll()) {
            Set<String> names = new HashSet<>();
            for (Setting setting : module.getSettings()) {
                require(names.add(setting.getName()), "duplicate setting: " + module.getName());
                Object initial = setting.getValue();
                if (setting instanceof IntSetting value) {
                    value.set(Integer.MIN_VALUE);
                    require(value.get() == value.getMin(), "integer lower bound");
                    value.set(Integer.MAX_VALUE);
                    require(value.get() == value.getMax(), "integer upper bound");
                } else if (setting instanceof DoubleSetting value) {
                    value.set(-Double.MAX_VALUE);
                    require(value.get() == value.getMin(), "numeric lower bound");
                    value.set(Double.MAX_VALUE);
                    require(value.get() == value.getMax(), "numeric upper bound");
                    value.set(Double.NaN);
                    require(Double.isFinite(value.get()), "nonfinite value rejected");
                }
                setting.resetToDefault();
                require(initial.equals(setting.getValue()), "default restoration: " + setting.getName());
                setting.fromJson(setting.toJson());
                require(initial.equals(setting.getValue()), "setting JSON roundtrip");
                controls++;
            }
            if (ApexSettings.delegate(module.getName()) == null && !actionOnly.contains(module.getName())) {
                require(!module.getSettings().isEmpty(), "missing useful controls: " + module.getName());
                configured++;
            }
        }

        ModuleManager manager = new ModuleManager();
        Module owner = new Module("ESP", "Test settings owner", Category.RENDER) {};
        DoubleSetting range = new DoubleSetting("range", "Test range", 6, 1, 64);
        owner.addSetting(range);
        KeybindSetting secondary = new KeybindSetting("testAction", "Test action", 67, false);
        owner.addSetting(secondary);
        ApexModule alias = new ApexModule("ShaderESP", "Test shared controls");
        alias.addSetting(new KeybindSetting("keybind", "Alias toggle", -1, false));
        alias.bindDelegateSettings(owner);
        require(ApexSettings.find(alias, "range") == range, "alias edits the live owner setting");
        require(alias.getPersistentSettings().size() == 1, "alias only owns its toggle bind");
        manager.getModules().add(owner);
        manager.getModules().add(alias);
        require(manager.getKeyConflicts(67, alias).isEmpty(), "shared action is not a keybind conflict");
        ConfigManager config = new ConfigManager(manager);
        range.set(12);
        JsonObject snapshot = config.snapshot();
        JsonObject modules = snapshot.getAsJsonObject("modules");
        require(!modules.getAsJsonObject("ShaderESP").getAsJsonObject("settings").has("range"),
                "shared settings saved once under their owner");
        range.set(30);
        require(config.applySnapshot(snapshot), "snapshot loads");
        require(range.get() == 12, "canonical value restored");
        modules.getAsJsonObject("ShaderESP").getAsJsonObject("settings").addProperty("range", 50);
        require(config.applySnapshot(snapshot) && range.get() == 12, "stale alias value cannot override owner");
        require(ApexSettings.matches("stone, minecraft:diamond", "minecraft:diamond_sword"), "item filter matching");
        require(!ApexSettings.matches("stone, diamond", "minecraft:apple"), "item filter excludes nonmatches");
        require(ApexSettings.matches("", "minecraft:apple"), "empty filter permits all");
        System.out.println("PASS: " + configured + " direct Apex modules / " + controls
                + " controls, bounds, defaults, JSON roundtrip, shared ownership and legacy alias config");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
