// language: Java, file: BlockESP.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.StringSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.Set;

public class BlockESP extends Module {

    private final StringSetting blockFilter;
    private final DoubleSetting radius;
    private final IntSetting maxShown;
    private final BoolSetting fastScan;
    private final BoolSetting simpleRender;
    private final ColorSetting color;

    public BlockESP() {
        super("BlockESP", "Draws outlines around selected block types.", Category.RENDER);

        this.blockFilter = new StringSetting("blockFilter", "Enabled block IDs",
                "minecraft:diamond_ore,minecraft:deepslate_diamond_ore," +
                        "minecraft:ancient_debris,minecraft:emerald_ore," +
                        "minecraft:gold_ore,minecraft:deepslate_gold_ore");
        this.radius       = new DoubleSetting("radius", "Scan radius (blocks)", 64.0, 4.0, 256.0);
        this.maxShown     = new IntSetting("maxShown", "Max blocks rendered", 256, 64, 2000);
        this.fastScan     = new BoolSetting("fastScan", "Fast scan (may skip sections)", false);
        this.simpleRender = new BoolSetting("simpleRender", "Simple render (fewer lines)", false);
        this.color        = new ColorSetting("color", "Block outline color", 0xFF00E5FF);

        addSetting(blockFilter);
        addSetting(radius);
        addSetting(maxShown);
        addSetting(fastScan);
        addSetting(simpleRender);
        addSetting(color);
    }

    public Set<String> getBlockFilter() {
        Set<String> out = new HashSet<>();
        for (String part : blockFilter.get().split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public Set<Block> getBlockSet() {
        Set<Block> out = new HashSet<>();
        for (String id : getBlockFilter()) {
            try {
                Identifier ident = Identifier.parse(id);
                if (BuiltInRegistries.BLOCK.containsKey(ident)) {
                    out.add(BuiltInRegistries.BLOCK.getValue(ident));
                } else {
                    UtilityClient.LOGGER.warn("[BlockESP] unknown block id: {}", id);
                }
            } catch (Exception e) {
                UtilityClient.LOGGER.warn("[BlockESP] failed to parse id: {} ({})", id, e.toString());
            }
        }
        return out;
    }

    public StringSetting getBlockFilterSetting() { return blockFilter; }
    public double getRadius() { return radius.get(); }
    public int getMaxShown() { return maxShown.get(); }
    public boolean isFastScan() { return fastScan.get(); }
    public boolean isSimpleRender() { return simpleRender.get(); }
    public int getColor() { return color.get(); }

    @Override protected void onEnable() {
        UtilityClient.LOGGER.info("[BlockESP] enabled");
    }
    @Override protected void onDisable() {
        UtilityClient.LOGGER.info("[BlockESP] disabled");
    }
}