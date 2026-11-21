package com.baggute.cheatclient.module.apex;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.*;

/** Controls for the bodies in ApexImpl and ApexRender. */
public final class ApexSettings {
    private ApexSettings() {}

    public static String delegate(String entry) {
        return switch (entry) {
            case "AutoShieldBreak" -> "ShieldBreaker";
            case "SprintReset" -> "SmartWTap";
            case "ItemESP", "MobESP", "ShaderESP" -> "ESP";
            case "Xray" -> "BlockESP";
            case "ConfigBind" -> "PresetSwitch";
            default -> null;
        };
    }

    public static void configure(Module m) {
        switch (m.getName()) {
            case "Backtrack" -> integer(m, "delayTicks", "Delay target movement updates (ticks)", 3, 1, 10);
            case "AlienWC" -> interval(m, 6);
            case "AutoAlienWC" -> {
                interval(m, 2);
                number(m, "minFallSpeed", "Minimum downward speed (blocks/tick)", 0.2, 0.05, 2);
                number(m, "pitch", "Wind-charge pitch (degrees)", 88, 45, 90);
            }
            case "AutoElytra" -> range(m, 4);
            case "BreachSwap" -> { range(m, 3.5); integer(m, "minArmor", "Minimum target armor points", 10, 0, 20); }
            case "ChompzaInstaCatch" -> {
                integer(m, "cycleTicks", "Pearl/charge cycle length (ticks)", 4, 4, 40);
                number(m, "pitch", "Wind-charge pitch (degrees)", 85, -90, 90);
            }
            case "MaceDoubleTap" -> { range(m, 4); interval(m, 4); }
            case "PearlDisrupter" -> { range(m, 10); interval(m, 2); }
            case "PerfectWindcharge" -> { range(m, 3); interval(m, 2); }
            case "SpearMaceDtap" -> {
                range(m, 3.6);
                integer(m, "cycleTicks", "Spear/mace cycle length (ticks)", 4, 4, 40);
            }
            case "AutoHitCrystal" -> {
                range(m, 4);
                number(m, "placementRadius", "Search radius around target (blocks)", 2, 1, 4);
            }
            case "SafeAnchor" -> {
                range(m, 4);
                integer(m, "searchRadius", "Anchor search radius (blocks)", 2, 1, 4);
                integer(m, "chargeTicks", "Ticks between charge attempts", 8, 1, 40);
                integer(m, "charges", "Charges before detonation", 4, 1, 4);
            }
            case "FastCart" -> interval(m, 2);
            case "PearlCatch" -> {
                integer(m, "timeoutTicks", "Abort an unfinished sequence after this many ticks", 80, 20, 200);
                number(m, "pitch", "Wind-charge pitch (degrees)", 30, -90, 90);
            }
            case "PearlChase" -> { range(m, 30); interval(m, 3); }
            case "PearlGrapple" -> { range(m, 25); interval(m, 4); }
            case "SwordHit" -> interval(m, 2);
            case "AutoGapple" -> flag(m, "enchantedFirst", "Prefer enchanted golden apples", false);
            case "AutoPearl" -> interval(m, 10);
            case "AutoShield" -> flag(m, "offhandFirst", "Use an offhand shield before swapping slots", false);
            case "AutoLunge" -> { range(m, 3.8); interval(m, 3); }
            case "AutoSprint" -> {
                flag(m, "forwardOnly", "Sprint only while moving forward", false);
                flag(m, "pauseUsingItem", "Pause while eating, drinking or blocking", false);
            }
            case "Snaptap" -> {
                flag(m, "forwardBack", "Resolve forward/backward key conflicts", true);
                flag(m, "leftRight", "Resolve left/right key conflicts", true);
            }
            case "SpearAssist" -> range(m, 20);
            case "AntiBot" -> {
                flag(m, "blankNames", "Filter players with blank names", true);
                flag(m, "duplicateNames", "Filter duplicate player names", true);
                flag(m, "suspiciousPrefixes", "Filter CIT- and formatting prefixes", true);
            }
            case "AutoDrain" -> interval(m, 5);
            case "AutoExtinguish" -> interval(m, 4);
            case "AutoTool" -> {
                flag(m, "pickaxes", "Select pickaxes for stone and ores", true);
                flag(m, "axes", "Select axes for wood", true);
                flag(m, "shovels", "Select shovels for dirt, sand and gravel", true);
            }
            case "WebEscape" -> interval(m, 3);
            case "AutoChorus" -> {
                number(m, "health", "Eat at or below this health (2 HP = 1 heart)", 6, 1, 20);
                interval(m, 8);
            }
            case "AutoHeal" -> {
                number(m, "healthPercent", "Heal below this percentage of maximum health", 50, 5, 100);
                flag(m, "preferApple", "Try a golden apple before a potion", true);
                interval(m, 6);
            }
            case "BowSpam" -> integer(m, "chargeTicks", "Release after this many bow-use ticks", 3, 1, 20);
            case "LavaMacro" -> {
                integer(m, "cycleTicks", "Placement cycle length (ticks)", 10, 8, 60);
                integer(m, "scoopTicks", "Ticks before scooping placed lava", 6, 1, 30);
                integer(m, "distance", "Placement distance in front (blocks)", 2, 1, 3);
            }
            case "PlateWeb" -> { range(m, 4); interval(m, 10); }
            case "MaceShieldDrain" -> {
                range(m, 4.2);
                flag(m, "requireFalling", "Act only while descending", true);
            }
            case "FastPlace", "NoBreakDelay", "FastExp" -> interval(m, 1);
            case "CartRefill", "PotRefill" -> {
                interval(m, 20);
                integer(m, "threshold", "Refill stacks at or below this count", 16, 1, 64);
                flag(m, "fillEmpty", "Fill empty hotbar slots", true);
            }
            case "Replenish" -> {
                interval(m, 20);
                integer(m, "threshold", "Refill stacks at or below this count", 8, 1, 64);
            }
            case "ElytraSwap" -> interval(m, 10);
            case "ChestSteal" -> {
                interval(m, 4);
                text(m, "itemFilter", "Comma-separated item IDs or fragments; empty loots all", "");
                flag(m, "reverseOrder", "Loot from the last container slot first", false);
            }
            case "InvCleaner" -> {
                interval(m, 6);
                text(m, "junkItems", "Comma-separated item IDs or fragments to discard",
                        "rotten_flesh,poisonous_potato,spider_eye,seeds,flint,bowl");
                flag(m, "cleanHotbar", "Include hotbar slots when discarding junk", true);
            }
            case "DashTrails" -> {
                integer(m, "length", "Maximum number of trail points", 28, 2, 120);
                number(m, "spacing", "Minimum distance between trail points (blocks)", 0.2, 0.05, 2);
                number(m, "width", "Trail line width", 2, 0.5, 5);
                addColor(m, 0xE07C9CFF);
            }
            case "ObsidianGlow" -> {
                integer(m, "radius", "Horizontal scan radius (blocks)", 8, 1, 16);
                integer(m, "verticalRadius", "Vertical scan radius (blocks)", 4, 1, 8);
                integer(m, "maxBlocks", "Maximum outlined blocks", 24, 1, 200);
                addColor(m, 0xC0A78BFA);
            }
            case "MaceImpact" -> {
                range(m, 6);
                number(m, "radius", "Maximum ring radius (blocks)", 2, 0.6, 5);
                integer(m, "segments", "Ring segments", 20, 8, 64);
                number(m, "width", "Ring line width", 2.5, 0.5, 5);
                addColor(m, 0xD0F87171);
            }
            default -> { }
        }
    }

    private static void interval(Module m, int value) {
        integer(m, "intervalTicks", "Ticks between action attempts (20 ticks = 1 second)", value, 1, 100);
    }
    private static void range(Module m, double value) {
        number(m, "range", "Target scan range (blocks); interaction limits still apply", value, 1, 64);
    }
    private static void integer(Module m, String n, String d, int v, int min, int max) { m.addSetting(new IntSetting(n,d,v,min,max)); }
    private static void number(Module m, String n, String d, double v, double min, double max) { m.addSetting(new DoubleSetting(n,d,v,min,max)); }
    private static void flag(Module m, String n, String d, boolean v) { m.addSetting(new BoolSetting(n,d,v)); }
    private static void text(Module m, String n, String d, String v) { m.addSetting(new StringSetting(n,d,v)); }
    private static void addColor(Module m, int v) { m.addSetting(new ColorSetting("color", "Overlay colour and opacity", v)); }

    public static Setting find(Module module, String key) {
        if (module != null) for (Setting setting : module.getSettings()) if (setting.getName().equals(key)) return setting;
        return null;
    }
    public static Module module(String name) {
        return UtilityClient.moduleManager == null ? null : UtilityClient.moduleManager.getByName(name);
    }
    public static int integer(Module m, String key, int fallback) {
        return find(m,key) instanceof IntSetting s ? s.get() : fallback;
    }
    public static double number(Module m, String key, double fallback) {
        return find(m,key) instanceof DoubleSetting s ? s.get() : fallback;
    }
    public static boolean flag(Module m, String key, boolean fallback) {
        return find(m,key) instanceof BoolSetting s ? s.get() : fallback;
    }
    public static String text(Module m, String key, String fallback) {
        return find(m,key) instanceof StringSetting s ? s.get() : fallback;
    }
    public static int color(Module m, int fallback) {
        return find(m,"color") instanceof ColorSetting s ? s.get() : fallback;
    }
    public static boolean matches(String filter, String item) {
        if (filter == null || filter.isBlank()) return true;
        for (String token : filter.toLowerCase(java.util.Locale.ROOT).split(",")) {
            String key = token.trim();
            if (!key.isEmpty() && item.toLowerCase(java.util.Locale.ROOT).contains(key)) return true;
        }
        return false;
    }
}
