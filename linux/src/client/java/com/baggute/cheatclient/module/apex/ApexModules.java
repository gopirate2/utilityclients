// language: Java, file: ApexModules.java, target: Fabric 26.2
package com.baggute.cheatclient.module.apex;

import com.baggute.cheatclient.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * Apexx Client catalogue (apexxclient.com/modules), imported as labels into the
 * "skidded off apex" tab.
 *
 * Entries whose name already exists as a working module in this client were
 * removed from the table outright — the tab keeps only what this client does not
 * already implement. ModuleManager also refuses duplicate names, so nothing can
 * shadow a real module even if one slips back in.
 */
public final class ApexModules {

    private ApexModules() {}

    private static final String[][] ENTRIES = {
        // ---- combat ----
        {"AlienWC", "Elytra + wind charge launch combo: sprint, jump, equip, glide, dive and boost off the floor.", "combat"},
        {"AutoAlienWC", "AlienWC but automatic: while gliding toward the floor, throws a wind charge down and rides the blast back up.", "combat"},
        {"AutoElytra", "Takes the elytra off right before an AutoMace hit lands, as late as the smash still allows.", "combat"},
        {"BreachSwap", "Switches to a Breach-enchanted mace for a hit on well-armoured enemies, then returns to your weapon.", "combat"},
        {"ChompzaInstaCatch", "Throws pearl and catches it near-instantly with wind charge, with optional look-up aim.", "combat"},
        {"MaceDoubleTap", "While gliding: aims at the target, breach mace hit, elytra drop, then a mace smash once the server ends the glide.", "combat"},
        {"PearlDisrupter", "Intercepts an enemy ender pearl mid-flight with an aimed wind charge.", "combat"},
        {"PerfectWindcharge", "Automatically jumps when a wind charge is thrown at your feet.", "combat"},
        {"SpearMaceDtap", "Charged spear hit, then a mace hit the moment the server has registered the spear.", "combat"},
        {"AutoHitCrystal", "Sword right click places obsidian then cycles crystals; auto place can pick terrain after a sword attack.", "combat"},
        {"SafeAnchor", "Charges, shields and explodes your anchors with configurable rotation.", "combat"},
        {"CartRefill", "Refills an empty hotbar slot with TNT minecarts or rails once the cart modules are done.", "combat"},
        {"FastCart", "Instantly places a TNT minecart after you place a rail and swaps back to your weapon.", "combat"},
        {"Humanization", "Uses slower capped aim with mouse-pixel drift for Apex actions.", "combat"},
        {"PearlCatch", "Throws a pearl where you look and catches it mid-air with an aimed wind charge, before or after the pearl.", "combat"},
        {"PearlChase", "Automatically throws a counter-pearl towards enemy pearl trajectories.", "combat"},
        {"PearlGrapple", "One key press throws an ender pearl that intercepts a player in range and teleports you onto them.", "combat"},
        {"SwordHit", "Automatically swaps to a sword when attacking an entity with specified held items.", "combat"},

        // ---- automations ----
        {"AutoGapple", "Hold the key to swap to a golden apple and keep eating, swap back on release.", "automation"},
        {"AutoPearl", "One-tap pearl throw with swap-back.", "automation"},
        {"AutoShield", "Hold the key to swap to your shield and block, swap back on release.", "automation"},

        // ---- movement ----
        {"AutoLunge", "Lunge macro: swaps to the lunge spear, attacks, and swaps back, with optional aim assist.", "movement"},
        {"AutoSprint", "Keeps you sprinting without holding the key.", "movement"},
        {"Snaptap", "Newest key wins, cancelling the opposite direction.", "movement"},
        {"SpearAssist", "Aims at targets while charging a spear.", "movement"},

        // ---- render ----
        {"ShaderESP", "GPU model chams, 2D full/corner ESP with health bars, and 3D wave-gradient boxes.", "render"},

        // ---- player ----
        {"AntiBot", "Filters server-spawned bot entities from targeting.", "player"},
        {"AutoDrain", "Scoops up nearby fluids with rotated bucket clicks.", "player"},
        {"AutoExtinguish", "Puts you out when on fire: looks down, places water at your feet, scoops it back, looks back.", "player"},
        {"AutoTool", "Snaps to the fastest tool while breaking blocks.", "player"},
        {"ChestSteal", "Container looting with screen-scoped click pacing.", "player"},
        {"ElytraSwap", "Flips instantly between elytra and chestplate.", "player"},
        {"FastExp", "Faster experience collection.", "player"},
        {"InvCleaner", "Cleans junk items and keeps superior equipment with human delays.", "player"},
        {"Replenish", "Refills a low hotbar stack from your inventory before it runs out.", "player"},
        {"WebEscape", "Gets you out of a cobweb: breaks it with a water bucket or throws an ender pearl, in the order you choose.", "player"},

        // ---- smp ----
        {"AutoChorus", "Automatically consumes chorus fruit to escape combos.", "smp"},
        {"AutoHeal", "Splashes a healing potion, checking the hotbar then the inventory.", "smp"},
        {"AutoShieldBreak", "Answers a raised enemy shield by briefly swapping to an axe to knock it down.", "smp"},
        {"BowSpam", "Rapidly fires bow shots at minimum charge for maximum arrow spam.", "smp"},
        {"ItemESP", "Shows dropped items through walls.", "smp"},
        {"LavaMacro", "Drops a lava bucket and scoops it back up.", "smp"},
        {"MobESP", "Shows mobs through walls, coloured by kind.", "smp"},
        {"PlateWeb", "Webs the head of a player standing on a pressure plate on every hit, and again when they break it.", "smp"},
        {"PotRefill", "Restocks chosen hotbar slots with fresh potions.", "smp"},
        {"SprintReset", "Resets sprint for one tick after a sprint hit so the next hit has sprint knockback.", "smp"},
        {"Xray", "Shows ores and chosen blocks through the terrain.", "smp"},

        // ---- blatant ----
        {"MaceShieldDrain", "Hold keybind while falling to obliterate an enemy shield with the mace at huge CPS and extended reach.", "blatant"},
        {"FastPlace", "Places blocks faster while right-click is held.", "blatant"},
        {"NoBreakDelay", "Strips the cooldown between block breaks.", "blatant"},

        // ---- now implemented (packet + gizmo layer) ----
        {"Backtrack", "Briefly delays the current target's movement updates, then replays them in order.", "combat"},
        {"ObsidianGlow", "Outlines obsidian around you so anchor and crystal spots read at a glance.", "render"},
        {"MaceImpact", "Expanding ground rings on every mace hit that lands nearby.", "render"},
        {"DashTrails", "Fading ribbon behind you while you move.", "render"},

        // ---- client ----
        {"ClickGUI", "Theme and styling controls for the menu.", "client"},
        {"ConfigBind", "Bind hotkeys to swap configs on the fly.", "client"},
    };

    public static List<Module> createAll() {
        List<Module> out = new ArrayList<>(ENTRIES.length);
        for (String[] entry : ENTRIES) {
            out.add(new ApexModule(entry[0], entry[1]));
        }
        return out;
    }
}
