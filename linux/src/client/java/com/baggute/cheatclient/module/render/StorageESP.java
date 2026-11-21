// language: Java, file: StorageESP.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public class StorageESP extends Module {

    private final DoubleSetting radius;
    private final IntSetting scanIntervalMs;
    private final BoolSetting chests;
    private final BoolSetting trappedChests;
    private final BoolSetting enderChests;
    private final BoolSetting shulkers;
    private final BoolSetting barrels;
    private final BoolSetting furnaces;
    private final BoolSetting hoppers;
    private final BoolSetting dispensers;

    private final ColorSetting chestColor;
    private final ColorSetting shulkerColor;
    private final ColorSetting barrelColor;
    private final ColorSetting furnaceColor;
    private final ColorSetting otherColor;

    public StorageESP() {
        super("StorageESP", "Highlights storage blocks through walls.", Category.RENDER);

        this.radius         = new DoubleSetting("radius", "Scan radius (blocks)", 128.0, 4.0, 256.0);
        this.scanIntervalMs = new IntSetting("scanIntervalMs", "Scan interval (ms)", 1000, 250, 5000);
        this.chests         = new BoolSetting("chests", "Chests", true);
        this.trappedChests  = new BoolSetting("trappedChests", "Trapped chests", true);
        this.enderChests    = new BoolSetting("enderChests", "Ender chests", true);
        this.shulkers       = new BoolSetting("shulkers", "Shulker boxes", true);
        this.barrels        = new BoolSetting("barrels", "Barrels", true);
        this.furnaces       = new BoolSetting("furnaces", "Furnaces", false);
        this.hoppers        = new BoolSetting("hoppers", "Hoppers", false);
        this.dispensers     = new BoolSetting("dispensers", "Dispensers and droppers", false);

        this.chestColor     = new ColorSetting("chestColor", "Chest color", 0xFFFFD93D);
        this.shulkerColor   = new ColorSetting("shulkerColor", "Shulker color", 0xFFB040FF);
        this.barrelColor    = new ColorSetting("barrelColor", "Barrel color", 0xFFFFA040);
        this.furnaceColor   = new ColorSetting("furnaceColor", "Furnace color", 0xFF808080);
        this.otherColor     = new ColorSetting("otherColor", "Other storage color", 0xFFFFFFFF);

        addSetting(radius);
        addSetting(scanIntervalMs);
        addSetting(chests);
        addSetting(trappedChests);
        addSetting(enderChests);
        addSetting(shulkers);
        addSetting(barrels);
        addSetting(furnaces);
        addSetting(hoppers);
        addSetting(dispensers);
        addSetting(chestColor);
        addSetting(shulkerColor);
        addSetting(barrelColor);
        addSetting(furnaceColor);
        addSetting(otherColor);
    }

    public double getRadius() { return radius.get(); }
    public int getScanIntervalMs() { return scanIntervalMs.get(); }

    /** Direct block comparison — no registry lookup per entity. */
    public int getColorFor(Block block) {
        if (block == Blocks.ENDER_CHEST) return enderChests.get() ? chestColor.get() : 0;
        if (block instanceof ShulkerBoxBlock) return shulkers.get() ? shulkerColor.get() : 0;
        if (block instanceof BarrelBlock) return barrels.get() ? barrelColor.get() : 0;
        if (block instanceof AbstractFurnaceBlock) return furnaces.get() ? furnaceColor.get() : 0;
        if (block instanceof HopperBlock) return hoppers.get() ? otherColor.get() : 0;
        if (block instanceof DispenserBlock) return dispensers.get() ? otherColor.get() : 0;

        // Chests: chests / trapped chests handled via class, but need distinct flags
        if (block instanceof ChestBlock) {
            if (block == Blocks.TRAPPED_CHEST) return trappedChests.get() ? chestColor.get() : 0;
            return chests.get() ? chestColor.get() : 0;
        }
        return 0;
    }

    @Override protected void onEnable() {
        UtilityClient.LOGGER.info("[StorageESP] enabled");
    }
    @Override protected void onDisable() {
        UtilityClient.LOGGER.info("[StorageESP] disabled");
    }
}