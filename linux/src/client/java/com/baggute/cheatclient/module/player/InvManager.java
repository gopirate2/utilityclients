// language: Java, file: InvManager.java, target: Fabric 26.2
package com.baggute.cheatclient.module.player;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.InventoryUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Inventory housekeeping: throw the junk out, put the useful things where the
 * hand expects them.
 *
 * <p>Both halves move one line per {@code delayTicks} rather than doing the whole
 * inventory in a single tick. Sixteen click packets in one tick is the obvious
 * signature of a client; two per second is what a person looks like.
 *
 * <p>Every action goes through {@code handleContainerInput} on the player's own
 * inventory menu, so it is the same code path a mouse click takes - no packet
 * crafting, no desync, and it works while the inventory screen is open.
 */
public class InvManager extends Module {

    /**
     * Items with no PvP value that a player would clear out by hand anyway.
     *
     * <p>Deliberately a fixed list rather than "anything not in the hotbar": an
     * automatic drop that guesses wrong costs the player their gear, and a curated
     * set is one everybody recognises on sight.
     */
    private static final Set<Item> GARBAGE = Set.of(
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.EGG,
            Items.BONE,
            Items.STRING,
            Items.FLINT,
            Items.BOWL,
            Items.STICK
    );

    /** Hotbar layout, lowest first: weapons, blocks, ranged, food, potions, tools. */
    private static final int RANK_WEAPON = 0;
    private static final int RANK_BLOCK = 1;
    private static final int RANK_RANGED = 2;
    private static final int RANK_FOOD = 3;
    private static final int RANK_POTION = 4;
    private static final int RANK_TOOL = 5;
    private static final int RANK_OTHER = 6;

    private final BoolSetting autoDrop;
    private final BoolSetting autoSort;
    private final IntSetting delayTicks;

    private int cooldown;

    public InvManager() {
        super("InvManager", "Drops junk items and sorts the inventory into a fixed hotbar layout.",
                Category.PLAYER);

        this.autoDrop = new BoolSetting("AutoDrop", "Drop rotten flesh, eggs and other junk", true);
        this.autoSort = new BoolSetting("AutoSort", "Swords to slot 1, blocks to 2, food behind them", true);
        this.delayTicks = new IntSetting("delayTicks", "Ticks between inventory actions", 4, 1, 40);

        addSetting(autoDrop);
        addSetting(autoSort);
        addSetting(delayTicks);
    }

    @Override
    protected void onEnable() {
        cooldown = 0;
    }

    @Override
    public void onTick() {
        if (!autoDrop.get() && !autoSort.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        // A chest, a crafting table or the creative screen owns the menu; moving
        // items then would fight whatever the player is doing.
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        if (mc.isPaused()) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // Menu clicks on the same tick as a rotation, an attack or a carried-item
        // packet put two container mutations either side of an interaction the server
        // resolves against the pre-click inventory. Housekeeping waits for a clean tick.
        if (Stealth.rotatedThisTick() || Stealth.attackedThisTick() || Stealth.swapBlocked()) return;

        boolean acted = false;
        if (autoDrop.get()) acted = dropOneGarbage(mc);
        if (!acted && autoSort.get()) acted = sortStep(mc);
        if (acted) cooldown = delayTicks.get();
    }

    // ---- auto drop ----

    /**
     * Drops the first junk stack in the main inventory.
     *
     * <p>Slots 0-8 (the hotbar) are skipped on purpose: whatever is in the hand is
     * the player's decision, and a drop that eats the item somebody just selected
     * is worse than a slightly dirty inventory.
     */
    private boolean dropOneGarbage(Minecraft mc) {
        var inventory = mc.player.getInventory();
        int size = Math.min(36, inventory.getContainerSize());
        for (int slot = 9; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!GARBAGE.contains(stack.getItem())) continue;
            // THROW with button 1 takes the whole stack; button 0 drops a single
            // item. Verified against AbstractContainerMenu#doClick in 26.2.
            return com.baggute.cheatclient.util.Stealth.swapSlot(mc, slot, 1, ContainerInput.THROW);
        }
        return false;
    }

    // ---- auto sort ----

    /** Performs at most one swap so the layout converges over several ticks. */
    private boolean sortStep(Minecraft mc) {
        var inventory = mc.player.getInventory();
        int size = Math.min(36, inventory.getContainerSize());
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) items.add(inventory.getItem(i));
        int[] swap = nextSortSwap(items);
        return swap != null && InventoryUtils.swapPlayerSlots(mc,
                InventoryUtils.hotbarMenuSlot(swap[0]), InventoryUtils.hotbarMenuSlot(swap[1]));
    }

    /** Returns the next source/destination pair without mutating the inventory. */
    public static int[] nextSortSwap(List<ItemStack> items) {
        int size = Math.min(36, items.size());
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) if (!items.get(i).isEmpty()) stacks.add(items.get(i));
        stacks.sort(Comparator.comparingInt(InvManager::rank).thenComparing(stack -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? "" : id.toString();
        }));
        int desired = Math.min(9, stacks.size());
        for (int destination = 0; destination < desired; destination++) {
            ItemStack want = stacks.get(destination);
            if (ItemStack.isSameItemSameComponents(items.get(destination), want)) continue;
            // The already sorted prefix owns its duplicate occurrences.
            for (int source = destination + 1; source < size; source++)
                if (ItemStack.isSameItemSameComponents(items.get(source), want)) return new int[]{source, destination};
        }
        return null;
    }

    /** Hotbar priority for an item, lowest first. */
    private static int rank(ItemStack stack) {
        Item item = stack.getItem();
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        String path = id == null ? "" : id.getPath();

        if (path.endsWith("_sword") || path.endsWith("_axe") || path.equals("mace")
                || path.equals("trident")) {
            return RANK_WEAPON;
        }
        if (path.equals("bow") || path.equals("crossbow") || path.equals("arrow")) {
            return RANK_RANGED;
        }
        if (path.endsWith("potion") || path.endsWith("splash_potion")
                || path.endsWith("lingering_potion")) {
            return RANK_POTION;
        }
        if (item.components().has(DataComponents.FOOD)) return RANK_FOOD;
        if (item instanceof BlockItem) return RANK_BLOCK;
        if (path.endsWith("_pickaxe") || path.endsWith("_shovel") || path.endsWith("_hoe")
                || path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")
                || path.equals("shears") || path.equals("fishing_rod")
                || path.equals("shield") || path.equals("totem_of_undying")) {
            return RANK_TOOL;
        }
        return RANK_OTHER;
    }
}
