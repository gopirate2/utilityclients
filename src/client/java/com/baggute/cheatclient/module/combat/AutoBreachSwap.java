package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.util.Stealth;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.EntityHitResult;

/**
 * One breach-mace hit, then back to the old item.
 *
 * <p>Equip and swing are split over two ticks (the swing has to happen with the
 * mace actually selected), so the target is resolved again on the swing tick and
 * falls back to the nearest entity in front. The swap-back is armed as soon as
 * the mace is equipped, so a drift in the crosshair can never leave the player
 * holding the mace with no way back.
 */
public final class AutoBreachSwap extends Module {

    private final BoolSetting onlyWhileClicking =
            new BoolSetting("onlyWhileClicking", "Attack only while left-click is held", true);

    private int oldSlot = -1;
    private int ownedSlot = -1;
    private int restoreTicks;
    private int pendingTicks;
    private boolean pendingSwing;

    public AutoBreachSwap() {
        super("AutoBreachSwap", "Attacks once with a BREACH mace, then returns.", Category.COMBAT);
        addSetting(onlyWhileClicking);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.level == null) return;
        if (ownedSlot >= 0 && mc.player.getInventory().getSelectedSlot() != ownedSlot) {
            oldSlot = ownedSlot = -1; pendingSwing = false; restoreTicks = pendingTicks = 0;
        }

        if (restoreTicks > 0) {
            restoreTicks--;
            if (restoreTicks == 0 && oldSlot >= 0) {
                Stealth.restoreSlot(mc, oldSlot);
                oldSlot = -1;
                ownedSlot = -1;
            }
            return;
        }

        if (pendingSwing) {
            if (mc.gui.screen() != null || mc.isPaused()
                    || (onlyWhileClicking.get() && !mc.options.keyAttack.isDown()) || ++pendingTicks > 20) {
                pendingSwing = false; restoreTicks = oldSlot >= 0 ? 1 : 0; return;
            }
            LivingEntity target = resolveTarget(mc);
            if (target != null) {
                if (!Stealth.attackUncharged(mc, target, true)) return;
            }
            // Armed even on a whiff so the hotbar always comes back.
            pendingSwing = false;
            if (oldSlot >= 0) restoreTicks = 2;
            return;
        }

        if (onlyWhileClicking.get() && !mc.options.keyAttack.isDown()) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;

        LivingEntity target = resolveTarget(mc);
        if (target == null) return;

        int slot = findBreachMaceSlot(mc);
        if (slot < 0) return;

        if (!hasBreachMace(mc.player.getMainHandItem())) {
            int original = mc.player.getInventory().getSelectedSlot();
            if (!Stealth.swapSlot(mc, slot)) return;
            oldSlot = original; ownedSlot = slot; pendingTicks = 0;
            pendingSwing = true;
            return;
        }

        Stealth.attackUncharged(mc, target, true);
        if (oldSlot >= 0) restoreTicks = 2;
    }

    /** Crosshair entity first, then the nearest living entity in front of us. */
    private LivingEntity resolveTarget(Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity living
                && Stealth.validTarget(mc.player, living)) {
            return living;
        }
        LivingEntity best = null; double nearest = Stealth.reachLimit(mc.player);
        var eye = mc.player.getEyePosition(); var look = mc.player.getViewVector(1);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !Stealth.validTarget(mc.player, living)) continue;
            var point = AimPipeline.closestPoint(entity, eye, 0);
            if (point.subtract(eye).normalize().dot(look) < .5) continue;
            double distance = Stealth.eyeToHitbox(eye, entity.getBoundingBox());
            if (distance <= nearest) { nearest = distance; best = living; }
        }
        return best;
    }

    private int findBreachMaceSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (hasBreachMace(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }
    public boolean ownsClick(Minecraft mc) {
        return isEnabled() && mc != null && mc.player != null && mc.level != null
                && (pendingSwing || restoreTicks > 0 || (findBreachMaceSlot(mc) >= 0 && resolveTarget(mc) != null));
    }

    private boolean hasBreachMace(ItemStack s) {
        if (!ModuleUtils.matches(s, "mace")) return false;
        ItemEnchantments e = s.get(DataComponents.ENCHANTMENTS);
        if (e == null) return false;
        for (var x : e.entrySet()) {
            if (x.getKey().is(Enchantments.BREACH) && x.getIntValue() > 0) return true;
        }
        return false;
    }

    @Override
    protected void onEnable() {
        oldSlot = -1;
        ownedSlot = -1; pendingTicks = 0;
        restoreTicks = 0;
        pendingSwing = false;
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (oldSlot >= 0) {
            if (mc.player != null && mc.player.getInventory().getSelectedSlot() == ownedSlot) Stealth.restoreSlot(mc, oldSlot);
            oldSlot = -1;
        }
        restoreTicks = 0;
        pendingSwing = false;
        ownedSlot = -1; pendingTicks = 0;
    }
}
