// language: Java, file: AutoEat.java, target: Fabric 26.2
package com.baggute.cheatclient.module.automation;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.StringSetting;
import com.baggute.cheatclient.module.apex.ApexSettings;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Eats the best hotbar food while hunger is at or below the threshold, then puts
 * the previous slot back.
 *
 * <p>The old version re-selected the food slot every tick and never checked what
 * it was selecting: an item with a {@code FOOD} component that restores nothing
 * (a cake, a stack of rotten flesh already used) would be held forever, and the
 * selection was re-sent after the meal was over. Selection now happens once, the
 * meal is allowed to finish, and the slot is restored exactly once.
 */
public final class AutoEat extends Module {

    private final IntSetting hunger =
            new IntSetting("hunger", "Start eating at hunger or below", 14, 1, 20);
    private final IntSetting minNutrition =
            new IntSetting("minNutrition", "Ignore food that restores less hunger than this", 1, 0, 20);
    private final BoolSetting preferNutrition = new BoolSetting("preferNutrition", "Prefer the food restoring the most hunger", false);
    private final StringSetting excludedFoods = new StringSetting("excludedFoods", "Comma-separated food IDs or fragments to avoid", "");

    private int oldSlot = -1;
    private ItemStack ownedUse;
    private int ownedSlot = -1;

    public AutoEat() {
        super("AutoEat", "Automatically eats food below the configured hunger level.", Category.AUTOMATION);
        addSetting(hunger);
        addSetting(minNutrition);
        addSetting(preferNutrition);
        addSetting(excludedFoods);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            oldSlot = -1;
            ownedUse = null;
            ownedSlot = -1;
            return;
        }
        if (mc.gui.screen() != null || mc.isPaused()) {
            stop(mc);
            return;
        }
        if (mc.player.getFoodData().getFoodLevel() > hunger.get()) {
            stop(mc);
            return;
        }

        if (mc.player.isUsingItem() && mc.player.getUseItem() != ownedUse) return;
        if (mc.player.isUsingItem() && mc.player.getUseItem() == ownedUse) return;
        if (oldSlot >= 0 && ownedSlot >= 0 && mc.player.getInventory().getSelectedSlot() != ownedSlot) {
            oldSlot = -1; ownedUse = null; ownedSlot = -1; return;
        }
        int slot = findFoodSlot(mc);
        if (slot < 0) {
            stop(mc);
            return;
        }

        if (oldSlot < 0) oldSlot = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            // One selection, then eat on the next tick with the food actually held.
            if (com.baggute.cheatclient.util.Stealth.swapSlot(mc, slot)) ownedSlot = slot;
            return;
        }

        if (!mc.player.isUsingItem()) {
            if (!com.baggute.cheatclient.util.Stealth.canInteract(mc)) return;
            com.baggute.cheatclient.util.Stealth.useItem(mc, InteractionHand.MAIN_HAND);
            com.baggute.cheatclient.util.Stealth.markInteraction();
            if (mc.player.isUsingItem()) { ownedUse = mc.player.getUseItem(); ownedSlot = slot; }
        }
    }

    private int findFoodSlot(Minecraft mc) {
        int needed = Math.max(1, minNutrition.get());
        int best = -1;
        int nutrition = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (!excludedFoods.get().isBlank() && ApexSettings.matches(excludedFoods.get(), stack.getItem().toString())) continue;
            if (food != null && food.nutrition() >= needed) {
                if (!preferNutrition.get()) return i;
                if (food.nutrition() > nutrition) { best = i; nutrition = food.nutrition(); }
            }
        }
        return best;
    }

    /** Puts the previous slot back and drops the eating state. */
    private void stop(Minecraft mc) {
        if (mc.player != null && mc.gameMode != null && ownedUse != null && mc.player.isUsingItem()
                && mc.player.getUseItem() == ownedUse) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
        if (oldSlot >= 0 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() != oldSlot) {
            if (ownedSlot < 0 || mc.player.getInventory().getSelectedSlot() == ownedSlot)
                com.baggute.cheatclient.util.Stealth.restoreSlot(mc, oldSlot);
        }
        oldSlot = -1;
        ownedUse = null;
        ownedSlot = -1;
    }

    @Override
    protected void onDisable() {
        stop(Minecraft.getInstance());
    }
}
