// language: Java, file: Ignition.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;

/**
 * TNT minecart ignition.
 *
 * <p>A minecart primes from fire or explosion damage, and from nothing else: flint
 * and steel only has {@code useOn} (blocks), so it cannot light a cart. The two
 * methods a player actually has are a Flame bow (the arrow is lit at release) and
 * a Fire Aspect sword (the hit itself is fire damage). Both land here.
 */
final class Ignition {

    /** Degrees of view error that still counts as aimed. */
    private static final float AIM_TOLERANCE = 2.5f;

    /** Per-tick rotation ceiling. */
    private static final float MAX_DEGREES_PER_TICK = 20.0f;

    private Ignition() {}

    /** Hotbar slot with a bow (not a crossbow) carrying Flame, or -1. */
    static int flameBowSlot(Minecraft mc) {
        if (mc.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!com.baggute.cheatclient.util.ModuleUtils.isPlainBow(stack)) continue;
            if (hasEnchantment(stack, Enchantments.FLAME)) return slot;
        }
        return -1;
    }

    /** Hotbar slot with a Fire Aspect sword, or -1. */
    static int fireAspectSlot(Minecraft mc) {
        if (mc.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (hasEnchantment(stack, Enchantments.FIRE_ASPECT)) return slot;
        }
        return -1;
    }

    /** Any method in the hotbar that can actually prime a cart. */
    static boolean canPrime(Minecraft mc) {
        return flameBowSlot(mc) >= 0 || fireAspectSlot(mc) >= 0;
    }

    static boolean hasEnchantment(ItemStack stack,
                                  net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> enchantment) {
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null) return false;
        for (var entry : enchants.entrySet()) {
            if (entry.getKey().is(enchantment) && entry.getIntValue() > 0) return true;
        }
        return false;
    }

    /**
     * Steers the view toward {@code point} for a projectile release or a priming hit.
     *
     * <p>Returns true when the view already covers the point, which is the only tick
     * the caller may send the use or attack packet on. Snapping and interacting in
     * the same tick meant the launch direction the server resolved was the copy of
     * the rotation it held before the packet arrived.
     */
    static boolean aimServerSide(Minecraft mc, Vec3 point) {
        if (mc.player == null) return false;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 to = point.subtract(eye);
        double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(to.y, horizontal));

        float yawError = Math.abs(net.minecraft.util.Mth.wrapDegrees(wantYaw - mc.player.getYRot()));
        float pitchError = Math.abs(wantPitch - mc.player.getXRot());
        if (yawError <= AIM_TOLERANCE && pitchError <= AIM_TOLERANCE) return true;

        AimPipeline.tickRotate(mc, point, MAX_DEGREES_PER_TICK, true,
                AIM_TOLERANCE, AimPipeline.DEFAULT_DRIFT_PIXELS);
        return false;
    }
}
