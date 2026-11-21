// language: Java, file: Panic.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.KeybindSetting;
import net.minecraft.client.Minecraft;

/**
 * One key, everything off. Walks the module list, disables anything running,
 * closes whatever screen is open and saves the config so a restart starts clean.
 */
public class Panic extends Module {

    private final KeybindSetting trigger;

    public Panic() {
        super("Panic", "One key disables every module and saves the config.", Category.MISC);
        this.trigger = new KeybindSetting("panic", "Emergency kill switch", -1, false);
        addSetting(trigger);
    }

    @Override
    public void onRawKey(int keyCode, int action) {
        tryTrigger(keyCode, action);
    }

    /**
     * Fires even when a screen is open; the keyboard mixin calls this before its
     * screen guard so the kill switch works in chat, inventories and the GUI.
     *
     * @return true when this key was the panic bind
     */
    public boolean tryTrigger(int keyCode, int action) {
        if (action != 1) return false;
        if (trigger.getKey() == -1 || keyCode != trigger.getKey()) return false;
        fire();
        return true;
    }

    private void fire() {
        if (UtilityClient.moduleManager == null) return;
        int disabled = 0;
        for (Module m : UtilityClient.moduleManager.getModules()) {
            if (m == this || !m.isEnabled()) continue;
            try { m.setEnabled(false); }
            catch (Throwable failure) { com.baggute.cheatclient.util.ClientErrors.report(m.getName() + "/panic cleanup", failure); }
            disabled++;
        }
        try { UtilityClient.saveConfig(); }
        catch (Throwable failure) { com.baggute.cheatclient.util.ClientErrors.report("panic save", failure); }

        Minecraft mc = Minecraft.getInstance();
        // Modules that hold keys down (AutoWalk, Flight, ShieldBreaker, ...) would
        // leave them latched after being switched off, so drop the input state too.
        if (mc != null && mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.keyAttack.setDown(false);
            mc.options.keyUse.setDown(false);
        }
        if (mc != null && mc.player != null && mc.gameMode != null && mc.player.isUsingItem()) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
        if (mc != null && mc.gui != null && mc.gui.screen() != null) mc.gui.setScreen(null);
        UtilityClient.LOGGER.info("[Panic] disabled {} modules", disabled);
        Notifications.push("panic: " + disabled + " off", false);
    }
}
