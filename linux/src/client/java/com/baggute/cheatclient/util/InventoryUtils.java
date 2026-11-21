package com.baggute.cheatclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;

public final class InventoryUtils {
    private InventoryUtils() {}

    public static boolean swapPlayerSlots(Minecraft mc, int inventorySlot, int menuSlot) {
        return Stealth.swapSlot(mc, inventorySlot, menuSlot);
    }

    public static int hotbarMenuSlot(int hotbarIndex) { return hotbarIndex >= 0 && hotbarIndex < 9 ? 36 + hotbarIndex
            : hotbarIndex >= 9 && hotbarIndex < 36 ? hotbarIndex : -1; }
    public static int offhandMenuSlot() { return 45; }
}
