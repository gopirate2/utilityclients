// language: Java, file: ApexModule.java, target: Fabric 26.2
package com.baggute.cheatclient.module.apex;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.settings.Setting;
import java.util.ArrayList;
import java.util.List;

/**
 * One imported Apexx catalogue entry, wired to a real body in ApexImpl.
 *
 * Entries with a body tick; entries without one refuse to enable and say so,
 * rather than flipping to "on" and doing nothing.
 */
public class ApexModule extends Module {

    private final String entry;
    private List<Setting> sharedSettings;

    public ApexModule(String name, String description) {
        super(name, description, Category.APEX);
        this.entry = name;
        ApexSettings.configure(this);
    }

    public void bindDelegateSettings(Module target) {
        sharedSettings = null;
        if (target == null) return;
        sharedSettings = new ArrayList<>(super.getSettings());
        for (Setting setting : target.getSettings()) {
            if (!setting.getName().equals("keybind")) sharedSettings.add(setting);
        }
    }

    @Override public List<Setting> getSettings() {
        return sharedSettings == null ? super.getSettings() : sharedSettings;
    }

    @Override public List<Setting> getPersistentSettings() { return super.getSettings(); }

    public String entry() {
        return entry;
    }

    public boolean isImplemented() {
        return ApexImpl.supported(entry);
    }

    @Override
    public void onTick() {
        if (isEnabled() && isImplemented()) ApexImpl.tick(this);
    }

    @Override
    public void setEnabled(boolean state) {
        // Configs, presets and keybinds call setEnabled directly, so the "not
        // available" guard has to live here too; overriding only toggle() let an
        // unimplemented entry be flipped on by a loaded config and silently do
        // nothing.
        if (state && !isImplemented()) {
            Notifications.push(entry + ": not available", false);
            return;
        }
        super.setEnabled(state);
    }

    @Override
    public void setEnabledHeld(boolean state) {
        if (state && !isImplemented()) {
            Notifications.push(entry + ": not available", false);
            return;
        }
        super.setEnabledHeld(state);
    }

    @Override
    public void onEnable() {
        // Delegating entries hand off to the real module on the same tick.
        if (isImplemented()) ApexImpl.begin(entry);
    }
    @Override
    public void onDisable() { ApexImpl.cleanup(entry); }

    @Override
    public void onInputSuspended() { ApexImpl.cleanup(entry); }
}

