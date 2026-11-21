// language: Java, file: AutoCrossbow.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Charges and fires a crossbow without input.
 *
 * <p>A crossbow is a two-step item: holding use builds the charge, and once the
 * charge lands the bow stays loaded until use is pressed *again*. Releasing alone
 * fires nothing, so the module runs both halves — hold until
 * {@link CrossbowItem#getChargeDuration} elapses, release to load, then use once
 * more to loose the bolt. Quick Charge shortens that duration through the game's
 * own calculation, so the timing follows the enchantment for free.
 *
 * <p>Charge progress is read from {@code getTicksUsingItem()} against the game's
 * charge duration rather than a hardcoded tick count; a 32-tick constant fires
 * early on Quick Charge III and late on a lagging tick.
 */
public final class AutoCrossbow extends Module {

    private final KeybindSetting xbow =
            new KeybindSetting("Xbow", "Fire a loaded crossbow, or start the draw", -1, false);
    private final IntSetting missChance =
            new IntSetting("misschance", "Chance to aim off and miss (%)", 0, 0, 100);
    private final IntSetting delayMs =
            new IntSetting("delay", "Milliseconds between shots", 250, 0, 5000);
    private final BoolSetting onlyWhenHeld =
            new BoolSetting("onlyWhenHeld", "Only fire a crossbow already in hand", true);

    private long lastShotMs;
    private float savedYaw;
    private float savedPitch;
    private boolean rotationDirty;

    /** Degrees of view error that still counts as back on the pre-miss aim. */
    private static final float RESTORE_TOLERANCE = 1.0f;

    /** Per-tick ceiling for the step back to the pre-miss aim. */
    private static final float RESTORE_DEGREES_PER_TICK = 18.0f;

    /** Set when a miss offset went out this tick; the shot follows on the next one. */
    private boolean pendingFire;
    private boolean requested;
    private float missYaw, missPitch;
    private ItemStack ownedUse;

    public AutoCrossbow() {
        super("AutoCrossbow", "Charges a crossbow and fires it the moment it is loaded.", Category.COMBAT);
        addSetting(xbow);
        addSetting(missChance);
        addSetting(delayMs);
        addSetting(onlyWhenHeld);
    }

    /**
     * Action key. One press looses a loaded bolt, or starts drawing one; it never
     * releases on hold, so a held key cannot spam the charge cycle.
     */
    @Override
    public void onRawKey(int keyCode, int action) {
        if (action != org.lwjgl.glfw.GLFW.GLFW_PRESS) return;
        if (!isEnabled()) return;

        if (keyCode == xbow.getKey()) requested = true;
    }

    @Override public void onInputSuspended() { onDisable(); }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            pendingFire = rotationDirty = requested = false;
            ownedUse = null;
            return;
        }
        if (mc.gui.screen() != null || mc.isPaused()) return;
        if (pendingFire) {
            ItemStack held = mc.player.getMainHandItem();
            if (!ModuleUtils.matches(held, "crossbow") || !CrossbowItem.isCharged(held)) {
                pendingFire = false;
                rotationDirty = true;
                return;
            }
            float tolerance = Math.max(0.2f, AimPipeline.mouseGcd(mc) * 0.51f);
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(missYaw - mc.player.getYRot())) > tolerance
                    || Math.abs(missPitch - mc.player.getXRot()) > tolerance) {
                AimPipeline.steer(mc, missYaw, missPitch, 18.0f, 0.2, 0.0f);
                return;
            }
            if (!com.baggute.cheatclient.util.Stealth.canInteract(mc)) return;
            if (!shoot(mc)) return;
            pendingFire = false;
            rotationDirty = true;
            return;
        }
        if (rotationDirty) {
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(savedYaw - mc.player.getYRot())) > RESTORE_TOLERANCE
                    || Math.abs(savedPitch - mc.player.getXRot()) > RESTORE_TOLERANCE) {
                AimPipeline.steer(mc, savedYaw, savedPitch, RESTORE_DEGREES_PER_TICK,
                        RESTORE_TOLERANCE, 0.0f);
                return;
            }
            rotationDirty = false;
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!ModuleUtils.matches(held, "crossbow")) {
            if (onlyWhenHeld.get() && !requested) return;
            int slot = ModuleUtils.findHotbarItem(mc, "crossbow");
            if (slot >= 0) com.baggute.cheatclient.util.Stealth.swapSlot(mc, slot);
            return;
        }
        if (!requested && System.currentTimeMillis() - lastShotMs < delayMs.get()) return;
        if (mc.player.isUsingItem()) {
            if (mc.player.getUseItem() != ownedUse) return;
            if (mc.player.getTicksUsingItem() >= CrossbowItem.getChargeDuration(held, mc.player)) {
                mc.gameMode.releaseUsingItem(mc.player);
                com.baggute.cheatclient.util.Stealth.markInteraction();
                ownedUse = null;
            }
            return;
        }
        if (!com.baggute.cheatclient.util.Stealth.canInteract(mc)) return;
        if (CrossbowItem.isCharged(held)) {
            if (rollMiss()) {
                savedYaw = mc.player.getYRot();
                savedPitch = mc.player.getXRot();
                float offset = AimPipeline.quantize((ThreadLocalRandom.current().nextFloat() - 0.5f)
                        * 12.0f, AimPipeline.mouseGcd(mc));
                missYaw = savedYaw + offset;
                missPitch = net.minecraft.util.Mth.clamp(savedPitch + offset, -89.0f, 89.0f);
                pendingFire = true;
                return;
            }
            shoot(mc);
        } else {
            com.baggute.cheatclient.util.Stealth.useItem(mc, InteractionHand.MAIN_HAND);
            com.baggute.cheatclient.util.Stealth.markInteraction();
            if (mc.player.isUsingItem()) ownedUse = mc.player.getUseItem();
        }
    }

    private boolean shoot(Minecraft mc) {
        var result = com.baggute.cheatclient.util.Stealth.useItem(mc, InteractionHand.MAIN_HAND);
        if (result == null || !result.consumesAction()) return false;
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastShotMs = System.currentTimeMillis();
        requested = false;
        return true;
    }

    private boolean rollMiss() {
        int chance = missChance.get();
        if (chance <= 0) return false;
        if (chance >= 100) return true;
        return ThreadLocalRandom.current().nextInt(100) < chance;
    }

    @Override
    protected void onEnable() {
        lastShotMs = 0;
        rotationDirty = false;
        pendingFire = false;
        requested = false;
        ownedUse = null;
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameMode != null && ownedUse != null
                && mc.player.isUsingItem() && mc.player.getUseItem() == ownedUse)
            mc.gameMode.releaseUsingItem(mc.player);
        pendingFire = requested = false;
        ownedUse = null;
        rotationDirty = false;
    }
}
