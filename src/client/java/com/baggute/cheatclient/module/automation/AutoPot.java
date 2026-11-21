package com.baggute.cheatclient.module.automation;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.combat.AimPipeline;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Splashes a hotbar instant-health potion on the player.
 *
 * <p>Four ticks, one packet class each:
 *
 * <pre>
 *   tick 1  SELECT  carried-item packet for the potion slot
 *   tick 2  AIM     one quantised pitch step toward straight down
 *   tick 3  THROW   use-item packet, no rotation
 *   tick 4  RETURN  carried-item packet for the slot that was held
 * </pre>
 *
 * <p>The old version wrote pitch 90, threw, and restored the pitch inside a single
 * tick. Three packets in one 50 ms slice is not a shape a hand produces, and the
 * throw resolved against whatever the server's copy of the view happened to be when
 * the use packet was handled - sometimes the restored pitch, which sent the potion
 * forward instead of down. Splitting the phases means the server holds the downward
 * view before the throw is authored and holds the restored view only after it.
 */
public final class AutoPot extends Module {

    /** Degrees of pitch error that still counts as "looking at the floor". */
    private static final float AIM_TOLERANCE = 2.0f;

    /** Per-tick pitch ceiling: a 90 degree correction lands in six ticks. */
    private static final float MAX_DEGREES_PER_TICK = 16.0f;

    /** Ticks a phase may hold before the sequence gives up and restores. */
    private static final int PHASE_TIMEOUT = 20;

    private enum Phase { IDLE, SELECT, AIM, THROW, RETURN }

    private final IntSetting health =
            new IntSetting("health", "Splash at or below this health (2 HP = 1 heart)", 6, 1, 20);
    private final IntSetting cooldownTicks = new IntSetting("cooldownTicks", "Ticks between healing sequences", 4, 1, 100);
    private final BoolSetting includeAbsorption = new BoolSetting("includeAbsorption", "Count absorption hearts in the health threshold", false);

    private net.minecraft.client.player.LocalPlayer owner;
    private Phase phase = Phase.IDLE;
    private int oldSlot = -1;
    private int potionSlot = -1;
    private int phaseTicks;
    private int cooldown;

    public AutoPot() {
        super("AutoPot", "Splashes a hotbar instant health potion on yourself when health is low.",
                Category.AUTOMATION);
        addSetting(health);
        addSetting(cooldownTicks);
        addSetting(includeAbsorption);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (owner != null && mc.player != owner) { abort(mc); cooldown = 0; return; }
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null || mc.isPaused()) {
            abort(mc);
            return;
        }

        if (phase != Phase.IDLE) {
            int expected = phase == Phase.SELECT ? oldSlot : potionSlot;
            if (mc.player.getInventory().getSelectedSlot() != expected) { abort(mc); return; }
            phaseTicks++;
            if (phaseTicks > PHASE_TIMEOUT) {
                abort(mc);
                return;
            }
            tick(mc);
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (mc.player.isUsingItem()) return; // don't interrupt eating/drinking
        if (mc.player.getHealth() + (includeAbsorption.get() ? mc.player.getAbsorptionAmount() : 0) > health.get()) return;

        int slot = findInstantHealthSplash(mc);
        if (slot < 0) return;

        if (Stealth.swapBlocked() || Stealth.attackedThisTick()) return;
        owner = mc.player;
        oldSlot = mc.player.getInventory().getSelectedSlot();
        potionSlot = slot;
        // Selection begins on the next tick.
        enter(Phase.SELECT);
    }

    private void tick(Minecraft mc) {
        switch (phase) {
            case SELECT -> tickSelect(mc);
            case AIM -> tickAim(mc);
            case THROW -> tickThrow(mc);
            case RETURN -> tickReturn(mc);
            default -> { }
        }
    }

    /** Tick 1: put the potion in the selected slot and tell the server. */
    private void tickSelect(Minecraft mc) {
        if (potionSlot < 0 || !Stealth.swapSlot(mc, potionSlot)) return;
        if (!isInstantHealthSplash(mc.player.getMainHandItem())) {
            abort(mc);
            return;
        }
        enter(Phase.AIM);
    }

    /** Tick 2 onward: turn the pitch down in quantised steps until the floor is covered. */
    private void tickAim(Minecraft mc) {
        if (!isInstantHealthSplash(mc.player.getMainHandItem())) {
            abort(mc);
            return;
        }
        if (Math.abs(90.0f - mc.player.getXRot()) <= AIM_TOLERANCE) {
            enter(Phase.THROW);
            return;
        }
        AimPipeline.steer(mc, mc.player.getYRot(), 90.0f, MAX_DEGREES_PER_TICK,
                AIM_TOLERANCE, AimPipeline.DEFAULT_DRIFT_PIXELS);
    }

    /** Tick 3: the view is already down, so this tick owns the use packet alone. */
    private void tickThrow(Minecraft mc) {
        if (!isInstantHealthSplash(mc.player.getMainHandItem())) {
            abort(mc);
            return;
        }
        if (Stealth.rotatedThisTick() || Stealth.swappedThisTick()) return;
        var result = Stealth.useItem(mc, InteractionHand.MAIN_HAND);
        if (result == null || !result.consumesAction()) return;
        mc.player.swing(InteractionHand.MAIN_HAND);
        cooldown = cooldownTicks.get();
        enter(Phase.RETURN);
    }

    /** Tick 4: back to the slot that was held, once the throw is on the wire. */
    private void tickReturn(Minecraft mc) {
        if (oldSlot < 0 || !Stealth.swapSlot(mc, oldSlot)) return;
        oldSlot = -1;
        potionSlot = -1;
        owner = null;
        enter(Phase.IDLE);
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    /** Drops the sequence and puts the original slot back when it is safe to swap. */
    private void abort(Minecraft mc) {
        if (oldSlot >= 0 && mc.player == owner && owner != null && phase != Phase.SELECT
                && owner.getInventory().getSelectedSlot() == potionSlot) Stealth.restoreSlot(mc, oldSlot);
        owner = null;
        oldSlot = -1;
        phase = Phase.IDLE;
        phaseTicks = 0;
        potionSlot = -1;
    }

    @Override
    protected void onDisable() {
        abort(Minecraft.getInstance());
        cooldown = 0;
    }

    private int findInstantHealthSplash(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (isInstantHealthSplash(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean isInstantHealthSplash(ItemStack stack) {
        if (stack == null || !stack.is(Items.SPLASH_POTION)) return false;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().value() == MobEffects.INSTANT_HEALTH.value()) return true;
        }
        return false;
    }
}
