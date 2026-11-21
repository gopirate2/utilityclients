// language: Java, file: PlayerSnapshots.java, target: Fabric 26.2
package com.baggute.cheatclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Equipment cache for every player seen inside render distance.
 *
 * The game only sends another player's equipment while they are being tracked, so
 * anything outside that window has to be remembered here. Each tick refreshes the
 * players currently visible; their last known kit stays available after they walk
 * out of range or log, which is what `.lookup` falls back to.
 */
public final class PlayerSnapshots {

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** One gear slot: label, item, durability left and its enchantment list. */
    public record Piece(String slot, String item, int remaining, int max, String enchants) {
        public String line() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-6s ", slot)).append(item);
            if (max > 0) sb.append("  ").append(remaining).append("/").append(max);
            if (!enchants.isEmpty()) sb.append("  [").append(enchants).append("]");
            return sb.toString();
        }

        public boolean isEmpty() {
            return item.isEmpty();
        }
    }

    public record Snapshot(String name, float health, float maxHealth,
                           List<Piece> armor, List<Piece> tools, long stamp) {}

    private static final Map<String, Snapshot> BY_NAME = new LinkedHashMap<>();

    private PlayerSnapshots() {}

    /** Refresh everyone currently visible. Cheap enough to run once per client tick. */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player p) || p == mc.player) continue;
            if (p.getHealth() <= 0) continue;
            BY_NAME.put(key(p.getName().getString()), capture(p));
        }
    }

    private static Snapshot capture(Player p) {
        List<Piece> armor = new ArrayList<>(4);
        for (EquipmentSlot slot : ARMOR) {
            armor.add(piece(shortSlot(slot), p.getItemBySlot(slot)));
        }

        List<Piece> tools = new ArrayList<>(2);
        tools.add(piece("hand", p.getMainHandItem()));
        tools.add(piece("off", p.getOffhandItem()));

        return new Snapshot(p.getName().getString(), p.getHealth(), p.getMaxHealth(),
                armor, tools, System.currentTimeMillis());
    }

    private static Piece piece(String label, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new Piece(label, "", 0, 0, "");
        int max = stack.getMaxDamage();
        int remaining = max > 0 ? max - stack.getDamageValue() : 0;
        return new Piece(label, stack.getHoverName().getString(), remaining, max, enchants(stack));
    }

    private static String enchants(ItemStack stack) {
        try {
            var component = stack.getEnchantments();
            if (component == null || component.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (var entry : component.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(entry.getKey().value().description().getString())
                        .append(' ').append(entry.getValue());
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String shortSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            default -> slot.getName();
        };
    }

    /** Exact match first, then unique prefix, then substring. Case insensitive. */
    public static Snapshot find(String query) {
        if (query == null || query.isBlank()) return null;
        String q = key(query);

        Snapshot exact = BY_NAME.get(q);
        if (exact != null) return exact;

        Snapshot prefixHit = null;
        int prefixCount = 0;
        for (var entry : BY_NAME.entrySet()) {
            if (entry.getKey().startsWith(q)) { prefixHit = entry.getValue(); prefixCount++; }
        }
        if (prefixCount == 1) return prefixHit;

        Snapshot containsHit = null;
        int containsCount = 0;
        for (var entry : BY_NAME.entrySet()) {
            if (entry.getKey().contains(q)) { containsHit = entry.getValue(); containsCount++; }
        }
        return containsCount == 1 ? containsHit : null;
    }

    /** Every cached name, for the "no match" hint. */
    public static List<String> names() {
        return new ArrayList<>(BY_NAME.values().stream().map(Snapshot::name).toList());
    }

    public static boolean isOnline(Minecraft mc, String name) {
        if (mc.level == null) return false;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity le)) continue;
            if (le.getName().getString().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static void clear() {
        BY_NAME.clear();
    }

    private static String key(String name) {
        return name.toLowerCase().trim();
    }
}
