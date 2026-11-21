// language: Java, file: Module.java, target: Fabric 26.2
package com.baggute.cheatclient.module;

import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.settings.Setting;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {

    private final String name;
    private final String description;
    private final Category category;
    private boolean enabled;
    private final List<Setting> settings = new ArrayList<>();

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    /** Command services and theme preferences are not module-list entries. */
    public boolean isListed() { return !name.equals("Appearance") && !name.equals("SeedCrackerX"); }
    public boolean isEnabled() { return enabled; }
    public List<Setting> getSettings() { return settings; }

    /** Settings owned by this module; shared controls are saved by their owner. */
    public List<Setting> getPersistentSettings() { return settings; }

    /**
     * Append a setting to the end of the list.
     *
     * <p>The default is captured here rather than in the Setting constructor:
     * registration is the first moment the value is final, and every module
     * builds its settings before anything can mutate them.
     */
    public void addSetting(Setting s) {
        settings.add(s);
        s.captureDefault();
    }

    /** Insert a setting at a specific index. Used for pinning keybind at the top. */
    public void insertSetting(int index, Setting s) {
        if (index < 0) index = 0;
        if (index > settings.size()) index = settings.size();
        settings.add(index, s);
        s.captureDefault();
    }

    /** Restores every setting on this module to its registered default. */
    public int resetSettings() {
        int restored = 0;
        for (Setting s : getSettings()) {
            if (s.resetToDefault()) restored++;
        }
        return restored;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean state) {
        changeState(state, true);
    }

    /**
     * Held binds: the module is active only while the key is down, so the state
     * follows the key silently — no toast spam on every tap.
     */
    public void setEnabledHeld(boolean state) {
        changeState(state, false);
    }

    private void changeState(boolean state, boolean notify) {
        if (this.enabled == state) return;
        this.enabled = state;
        try {
            if (state) onEnable();
            else onDisable();
        } catch (Throwable failure) {
            // A failed enable must never leave an apparently active module.
            // Cleanup may itself fail; keep the original cause and stay off.
            this.enabled = false;
            if (state) {
                try { onDisable(); }
                catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        if (notify && enabled == state) Notifications.push(name, state);
        if (!enabled) com.baggute.cheatclient.module.misc.ActionTiming.forget(this);
    }

    // ---- hooks ----

    protected void onEnable() {}
    protected void onDisable() {}

    /** Runs once after the saved config has been applied to this module. */
    public void onConfigLoaded() {}

    public void onTick() {}

    public void onKey(int keyCode, int action) {}

    /** Receives configured secondary action keys without toggling the module. */
    public void onRawKey(int keyCode, int action) {}

    /** Cancel transient input actions when a screen, pause or focus loss takes over. */
    public void onInputSuspended() {}

    public void onRender3D(PoseStack poseStack, float partialTick) {}

    public void onRenderFrame(float partialTick) {}
}
