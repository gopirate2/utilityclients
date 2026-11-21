// language: Java, file: PotionWidget.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders active potion effects as "Name Level Duration".
 *
 * <p>The old counter printed the potion's registry id, which is why it read
 * {@code strong_swiftness} and {@code long_fire_resistance}: those prefixes are
 * the potion variant, not the effect. Reading the effect instance instead gives
 * the real name, the amplifier as a roman numeral, and the remaining time —
 * {@code Strength II 1:30}.
 *
 * <p>Duration is formatted mm:ss (h:mm:ss past an hour) and "∞" for effects that
 * never expire, which is what beacons and conduit power report.
 */
public final class PotionWidget {

    private PotionWidget() {}

    /** One formatted row. */
    public record Row(String text, int color) {}

    /**
     * Active effects, strongest duration first so the row order does not jump
     * around while effect timers tick at different rates.
     */
    public static List<Row> rows(Minecraft mc, int color) {
        List<Row> rows = new ArrayList<>();
        if (mc.player == null) return rows;

        List<MobEffectInstance> effects = new ArrayList<>(mc.player.getActiveEffects());
        effects.sort(Comparator.comparingInt(MobEffectInstance::getDuration).reversed());

        for (MobEffectInstance effect : effects) {
            String name = effect.getEffect().value().getDisplayName().getString();
            int amplifier = effect.getAmplifier();
            String level = amplifier <= 0 ? "I" : roman(amplifier + 1);
            rows.add(new Row(name + " " + level + " " + clock(effect.getDuration()), color));
        }
        return rows;
    }

    /** Draws the effect rows at {@code x,y}, one per 10px line. */
    public static int draw(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int color) {
        List<Row> rows = rows(mc, color);
        if (rows.isEmpty()) {
            g.text(mc.font, "no effects", x, y, color, true);
            return y + 10;
        }
        for (Row row : rows) {
            g.text(mc.font, row.text(), x, y, row.color(), true);
            y += 10;
        }
        return y;
    }

    /**
     * Potion stacks in the inventory, drawn with their real item texture.
     * Returns the Y after the last row so a caller can stack widgets.
     */
    public static int drawStackIcons(GuiGraphicsExtractor g, Minecraft mc, int x, int y) {
        if (mc.player == null) return y;

        int slotCount = Math.min(41, mc.player.getInventory().getContainerSize());
        for (int slot = 0; slot < slotCount; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (!com.baggute.cheatclient.util.ModuleUtils.matches(stack, "potion")) continue;

            g.item(stack, x, y - 4);
            String count = "x" + stack.getCount();
            g.text(mc.font, count, x + 20, y, 0xFFFFFFFF, true);
            y += 12;
        }
        return y;
    }

    /** 1 -> "I", 2 -> "II", 4 -> "IV". Anything past 10 falls back to digits. */
    public static String roman(int value) {
        if (value <= 0) return "I";
        if (value > 10) return String.valueOf(value);
        String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return numerals[value];
    }

    /** Ticks to mm:ss, or h:mm:ss past an hour. -1 renders as infinity. */
    public static String clock(int ticks) {
        if (ticks < 0) return "\u221e";
        long totalSeconds = ticks / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds);
        return String.format("%d:%02d", minutes, seconds);
    }
}
