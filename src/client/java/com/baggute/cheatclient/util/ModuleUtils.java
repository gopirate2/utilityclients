package com.baggute.cheatclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Item lookup shared by every automation module.
 *
 * <p>Matching goes through the registry key rather than {@code Item#toString()}.
 * Both happen to agree today ({@code toString} resolves to {@code minecraft:rail}),
 * but the registry key is the stable identity: it survives renames, it never picks
 * up a class name, and exact matches can be preferred over substring ones.
 */
public final class ModuleUtils {

    private ModuleUtils() {}

    /** Registry path of the held stack, e.g. {@code "rail"} — empty for an empty stack. */
    public static String path(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /**
     * True when the stack's registry path is one of {@code names}.
     *
     * <p>An exact path match wins over a substring match, so asking for
     * {@code "rail"} picks {@code minecraft:rail} over {@code minecraft:powered_rail}
     * when both are in the hotbar.
     */
    public static boolean matches(ItemStack stack, String... names) {
        String path = path(stack);
        if (path.isEmpty()) return false;
        for (String name : names) if (path.equals(name)) return true;
        for (String name : names) if (path.contains(name)) return true;
        return false;
    }

    /** True for a bow that is not a crossbow. */
    public static boolean isPlainBow(ItemStack stack) {
        return matches(stack, "bow") && !matches(stack, "crossbow");
    }

    public static boolean is(ItemStack stack, Item item) {
        return stack != null && !stack.isEmpty() && stack.getItem() == item;
    }

    /**
     * First hotbar slot matching {@code names}, or -1.
     *
     * <p>Exact registry paths are searched across the whole hotbar before any
     * substring match, so a {@code powered_rail} in slot 1 never shadows the plain
     * {@code rail} in slot 4.
     */
    public static int findHotbarItem(Minecraft mc, String... names) {
        if (mc.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            String path = path(mc.player.getInventory().getItem(slot));
            for (String name : names) if (path.equals(name)) return slot;
        }
        return findHotbarItem(mc, stack -> matches(stack, names));
    }

    /** First hotbar slot matching {@code test}, or -1. */
    public static int findHotbarItem(Minecraft mc, Predicate<ItemStack> test) {
        if (mc.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && test.test(stack)) return slot;
        }
        return -1;
    }

    /** True when the item in the main hand matches. */
    public static boolean holding(Minecraft mc, String... names) {
        return mc.player != null && matches(mc.player.getMainHandItem(), names);
    }

    public static Player nearestPlayer(Minecraft mc, double range) {
        if (mc.level == null || mc.player == null) return null;
        Player best = null;
        double bestDist = range * range;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Player p && p != mc.player && p.isAlive()
                    && !com.baggute.cheatclient.friends.FriendsManager.isFriend(p)) {
                double d = p.distanceToSqr(mc.player);
                if (d <= bestDist) { bestDist = d; best = p; }
            }
        }
        return best;
    }

    /**
     * Switches the held hotbar slot and tells the server about it.
     *
     * <p>{@code Inventory.setSelectedSlot} is client-side only: vanilla sends the
     * carried-item packet from the key handler, so a module that just set the slot
     * left the server resolving uses and attacks against the previously held item.
     * Every placement that "did nothing" was this — the packet was never on the
     * wire.
     */
    public static void select(Minecraft mc, int slot) {
        if (slot < 0 || slot >= 9 || mc.player == null) return;
        if (mc.player.getInventory().getSelectedSlot() == slot) return;
        // One choke point for every hotbar switch in the client. The arbiter refuses a
        // switch on a tick that already rotated or attacked, so no module can put a
        // carried-item packet and an interaction in the same 50 ms slice.
        Stealth.swapSlot(mc, slot);
    }
}
