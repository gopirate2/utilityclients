// language: Java, file: SmartWTap.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * WTap: the sprint flag is what decides whether a hit knocks back. After each
 * landed hit this drops sprint for a tick and puts it back, so the next hit
 * arrives with sprint knockback again instead of the weaker follow-up.
 */
public class SmartWTap extends Module {

    private final IntSetting holdTicks;
    private final DoubleSetting range;
    private final BoolSetting playersOnly;

    private int restoreTicks = -1;
    private net.minecraft.client.player.LocalPlayer owner;
    private boolean priorSprint;

    public SmartWTap() {
        super("SmartWTap", "Breaks sprint after a landed hit so the next hit knocks back.", Category.COMBAT);

        this.holdTicks = new IntSetting("holdTicks", "Ticks to stay unsprinted", 1, 1, 5);
        this.range = new DoubleSetting("range", "Hit detection distance", 3.2, 1.0, 6.0);
        this.playersOnly = new BoolSetting("playersOnly", "Only react to players", true);

        addSetting(holdTicks);
        addSetting(range);
        addSetting(playersOnly);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (owner != mc.player) { restoreTicks = -1; owner = mc.player; }
        if (mc.player == null || mc.level == null) return;
        if (mc.player.connection == null) return;

        if (restoreTicks >= 0) {
            if (--restoreTicks <= 0) {
                restoreTicks = -1;
                // STOP_SPRINTING cleared the flag above, so restore when it is off.
                if (priorSprint && !mc.player.isSprinting() && mc.gui.screen() == null
                        && mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown() && !mc.player.isUsingItem()) sendSprint(mc, true);
            }
            return;
        }


    }
    public void afterAttack(net.minecraft.world.entity.player.Player attacker, Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || attacker != mc.player || target == null || !target.isAlive()
                || (playersOnly.get() && !(target instanceof Player))
                || com.baggute.cheatclient.util.Stealth.eyeToHitbox(mc.player.getEyePosition(), target.getBoundingBox()) > range.get()
                || restoreTicks >= 0 || mc.gui.screen() != null || mc.isPaused()) return;
        priorSprint = mc.player.isSprinting() || com.baggute.cheatclient.util.Stealth.serverSprinting(mc.player);
        if (!priorSprint) return;
        owner = mc.player; sendSprint(mc, false); restoreTicks = holdTicks.get() + 1;
    }

    private void sendSprint(Minecraft mc, boolean sprinting) {
        com.baggute.cheatclient.util.Stealth.setSprinting(mc, sprinting);
    }

    @Override
    protected void onEnable() {
        restoreTicks = -1;
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (restoreTicks >= 0 && owner == mc.player && mc.player != null
                && mc.player.connection != null && priorSprint && !mc.player.isSprinting()
                &&mc.options!=null&&mc.options.keyUp.isDown()&&!mc.player.isShiftKeyDown()) sendSprint(mc, true);
        owner = null;
        restoreTicks = -1;
    }
}
