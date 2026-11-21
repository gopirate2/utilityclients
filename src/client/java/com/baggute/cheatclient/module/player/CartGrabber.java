package com.baggute.cheatclient.module.player;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.util.InventoryUtils;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;

/**
 * Pulls a TNT minecart out of the pockets and into a chosen hotbar slot.
 *
 * <p>The scan is main-inventory only. Indices 36-39 are the armour slots in the
 * player inventory, and the old loop walked to 40, so a chestplate or a helmet
 * could be clicked into the hotbar and the cart stayed where it was.
 */
public final class CartGrabber extends Module {

    /** First main-inventory index; 0-8 are the hotbar itself. */
    private static final int MAIN_INV_START = 9;
    /** One past the last main-inventory index; 36-39 are armour. */
    private static final int MAIN_INV_END = 36;

    private final IntSetting targetSlot =
            new IntSetting("targetSlot", "TNT minecart hotbar slot", 1, 1, 9);
    private int cooldown;
    private final IntSetting refillTicks = new IntSetting("refillTicks", "Ticks between refill attempts", 2, 1, 40);
    private final BoolSetting replaceOccupied = new BoolSetting("replaceOccupied", "Replace other items in the target slot", true);

    public CartGrabber() {
        super("CartGrabber", "Moves a TNT minecart from inventory into a chosen hotbar slot.", Category.PLAYER);
        addSetting(targetSlot);
        addSetting(refillTicks);
        addSetting(replaceOccupied);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (cooldown > 0) cooldown--;
        if (mc.player == null || mc.gameMode == null) return;
        if (cooldown > 0) return;
        // Same guards AutoTotem uses: no clicks over a screen, and only while the
        // player's own inventory menu is the open container.
        if (mc.gui.screen() != null || mc.isPaused()) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        // A container click on the same tick as a rotation, an attack or a carried-item
        // packet resolves against the inventory the server had before the click.
        if (Stealth.rotatedThisTick() || Stealth.attackedThisTick() || Stealth.swapBlocked()) return;

        int target = targetSlot.get() - 1;
        Inventory inv = mc.player.getInventory();
        if (ModuleUtils.matches(inv.getItem(target), "tnt_minecart")) return;
        if (!replaceOccupied.get() && !inv.getItem(target).isEmpty()) return;

        for (int i = MAIN_INV_START; i < MAIN_INV_END; i++) {
            if (!ModuleUtils.matches(inv.getItem(i), "tnt_minecart")) continue;
            // Main-inventory indices sit at the same menu slot as their index, so the
            // source stays as-is; only the destination needs the hotbar offset.
            if (InventoryUtils.swapPlayerSlots(mc, i, InventoryUtils.hotbarMenuSlot(target))) {
                cooldown = refillTicks.get();
            }
            return;
        }
    }
}
