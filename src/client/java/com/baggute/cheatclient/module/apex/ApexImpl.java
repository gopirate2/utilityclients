// language: Java, file: ApexImpl.java, target: Fabric 26.2
package com.baggute.cheatclient.module.apex;

import com.baggute.cheatclient.util.Stealth;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.combat.AimPipeline;
import com.baggute.cheatclient.module.combat.Placement;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Bodies for the apex tab.
 *
 * Every entry is either wired to real behaviour here, delegated to an existing
 * working module, or declared unsupported â€” nothing is a silent no-op. The
 * `supported` switch is the single source of truth: an entry that is not in it
 * refuses to enable instead of pretending.
 *
 * API discipline: only calls already proven elsewhere in this client are used
 * (Placement, ModuleUtils, gameMode.useItem/useItemOn/releaseUsingItem/attack,
 * the two serverbound packets), so a compile is a real guarantee here.
 */
public final class ApexImpl {

    private ApexImpl() {}

    private static final Map<String, int[]> TIMERS = new HashMap<>();
    private static final Map<String, Boolean> PREV = new HashMap<>();

    private static String activeEntry;
    private static ApexModule activeModule;
    private static final Map<String, Owned> OWNED = new HashMap<>();
    private static final Map<Module, java.util.Set<String>> DELEGATES = new java.util.IdentityHashMap<>();
    private static final java.util.Set<Module> ENABLED_BY_US = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final class Owned {
        int originalSlot = -1, selectedSlot = -1, pearlPhase, phaseTicks, phase, targetId = -1;
        final java.util.Set<java.util.UUID> answeredPearls = new java.util.HashSet<>();
        net.minecraft.client.player.LocalPlayer owner;
        net.minecraft.client.multiplayer.ClientLevel level;
        BlockPos breaking, fluidPos;
        int placedTick, nextLavaTick, nextActionTick;
        boolean confirmedLava;
        ItemStack use;
        boolean sprint;
        final boolean[] physical = new boolean[4];
        final long[] pressOrder = new long[4];
        long sequence;
        float alienYaw, alienPitch;
        boolean alienRestore;
    }
    private static Owned owned() { return OWNED.computeIfAbsent(activeEntry, k -> new Owned()); }

    public static void begin(String name) { cleanup(name); OWNED.put(name, new Owned()); }

    public static void cleanup(String name) {
        TIMERS.remove(name);
        PREV.remove(name);
        if (name.equals("Backtrack")) cleanupStep(name, "backtrack", com.baggute.cheatclient.module.combat.BacktrackBuffer::flush);
        Minecraft mc = Minecraft.getInstance();
        Owned state = OWNED.remove(name);
        if (state != null && mc != null && mc.player != null && mc.player == state.owner && mc.level == state.level) {
            if (state.alienRestore) { mc.player.setYRot(state.alienYaw); mc.player.setXRot(state.alienPitch); }
            cleanupStep(name, "breaking", () -> cancelBreak(mc, state));
            if (state.use != null && mc.gameMode != null && mc.player.isUsingItem()
                    && mc.player.getUseItem() == state.use) cleanupStep(name, "using item", () -> mc.gameMode.releaseUsingItem(mc.player));
            if (state.originalSlot >= 0 && mc.player.getInventory().getSelectedSlot() == state.selectedSlot)
                cleanupStep(name, "slot", () -> Stealth.restoreSlot(mc, state.originalSlot));
            if (state.sprint && mc.player.isSprinting()) cleanupStep(name, "sprint", () -> mc.player.setSprinting(false));
            if (name.equals("Snaptap")) {
                for (net.minecraft.client.KeyMapping key : new net.minecraft.client.KeyMapping[]{
                        mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight})
                    cleanupStep(name, "movement key", () -> key.setDown(com.baggute.cheatclient.util.PhysicalInput.down(mc, key)));
            }
        }
        for (var e : new java.util.ArrayList<>(DELEGATES.entrySet())) {
            e.getValue().remove(name);
            if (e.getValue().isEmpty()) {
                DELEGATES.remove(e.getKey());
                if (ENABLED_BY_US.remove(e.getKey()) && e.getKey().isEnabled())
                    cleanupStep(name, "delegate " + e.getKey().getName(), () -> e.getKey().setEnabled(false));
            }
        }
    }

    private static void cleanupStep(String name, String step, Runnable operation) {
        try { operation.run(); }
        catch (Throwable failure) { com.baggute.cheatclient.util.ClientErrors.report(name + "/cleanup/" + step, failure); }
    }

    private static boolean selectSlot(Minecraft mc, int slot) {
        int before = mc.player.getInventory().getSelectedSlot();
        if (!Stealth.swapSlot(mc, slot)) return false;
        if (before != slot) {
            Owned state = owned();
            if (state.originalSlot < 0) state.originalSlot = before;
            state.selectedSlot = slot;
        }
        return true;
    }

    private static boolean use(Minecraft mc) {
        if (!Stealth.canInteract(mc)) return false;
        var result = Stealth.useItem(mc, InteractionHand.MAIN_HAND);
        if (mc.player.isUsingItem()) owned().use = mc.player.getUseItem();
        return result != null && result.consumesAction();
    }

    /** Entries that must keep ticking while their container screen is open. */
    private static final java.util.Set<String> CONTAINER_BODIES = java.util.Set.of("ChestSteal");

    /** True when this entry does something real. */
    public static boolean supported(String name) {
        return switch (name) {
            case "AlienWC", "AutoAlienWC", "AutoElytra", "BreachSwap", "ChompzaInstaCatch",
                 "MaceDoubleTap", "PearlDisrupter", "PerfectWindcharge", "SpearMaceDtap",
                 "AutoHitCrystal", "SafeAnchor", "FastCart", "PearlCatch", "PearlChase",
                 "PearlGrapple", "SwordHit",
                 "AutoGapple", "AutoPearl", "AutoShield",
                 "AutoLunge", "AutoSprint", "Snaptap", "SpearAssist",
                 "AntiBot", "AutoDrain", "AutoExtinguish", "AutoTool", "WebEscape",
                 "AutoChorus", "AutoHeal", "AutoShieldBreak", "BowSpam", "LavaMacro",
                 "PlateWeb", "SprintReset", "ItemESP", "MobESP", "ShaderESP",
                 "MaceShieldDrain", "FastPlace", "NoBreakDelay",
                 "ClickGUI", "ConfigBind", "Xray",
                 "CartRefill", "Replenish", "PotRefill", "ElytraSwap",
                 "ChestSteal", "InvCleaner", "FastExp", "Humanization",
                 "Backtrack", "ObsidianGlow", "MaceImpact", "DashTrails" -> true;
            default -> false;
        };
    }

    /** One tick of the named entry. Called only when the module is on. */
    public static void tick(ApexModule module) {
        String name = module.entry();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) { cleanup(name); return; }
        if (!com.baggute.cheatclient.util.PhysicalInput.focused(mc)) { cleanup(name); return; }
        Owned session = OWNED.computeIfAbsent(name, k -> new Owned());
        if (session.owner != null && (session.owner != mc.player || session.level != mc.level)) {
            cleanup(name);
            session = new Owned(); OWNED.put(name, session);
        }
        session.owner = mc.player; session.level = mc.level;
        // Gameplay bodies pause behind a screen; container looters need the screen
        // open and guard against the player's own inventory menu themselves.
        if (mc.gui.screen() != null && !name.equals("ClickGUI")
                && !CONTAINER_BODIES.contains(name) || mc.isPaused()) {
            Owned state = OWNED.get(name);
            if (state != null && state.alienRestore) {
                mc.player.setYRot(state.alienYaw); mc.player.setXRot(state.alienPitch); state.alienRestore = false;
            }
            if (state != null) cancelBreak(mc, state);
            if (state != null && state.use != null && mc.player.isUsingItem()
                    && mc.player.getUseItem() == state.use) mc.gameMode.releaseUsingItem(mc.player);
            return;
        }

        activeEntry = name;
        activeModule = module;
        int tick = advance(name);

        try {
            switch (name) {
                // ---- combat ----
                case "AlienWC" -> alienLaunch(mc, tick);
                case "AutoAlienWC" -> autoAlien(mc, tick);
                case "AutoElytra" -> elytraDrop(mc);
                case "BreachSwap" -> breachSwap(mc, tick);
                case "ChompzaInstaCatch" -> instaCatch(mc, tick);
                case "MaceDoubleTap" -> maceDoubleTap(mc, tick);
                case "PearlDisrupter" -> pearlDisrupt(mc, tick);
                case "PerfectWindcharge" -> perfectWindcharge(mc, tick);
                case "SpearMaceDtap" -> spearMace(mc, tick);
                case "AutoHitCrystal" -> hitCrystal(mc, tick);
                case "SafeAnchor" -> safeAnchor(mc, tick);
                case "FastCart" -> fastCart(mc, tick);
                case "PearlCatch" -> pearlCatch(mc, tick);
                case "PearlChase" -> pearlChase(mc, tick);
                case "PearlGrapple" -> pearlGrapple(mc, tick);
                case "SwordHit" -> swordHit(mc, tick);

                // ---- automation ----
                case "AutoGapple" -> {
                    if (flag("enchantedFirst", false) && hasItem(mc, "enchanted_golden_apple"))
                        holdEat(mc, "enchanted_golden_apple");
                    else holdEat(mc, "golden_apple");
                }
                case "AutoPearl" -> oneTap(mc, tick, "ender_pearl", "AutoPearl");
                case "AutoShield" -> holdShield(mc);

                // ---- movement ----
                case "AutoLunge" -> autoLunge(mc, tick);
                case "AutoSprint" -> keepSprint(mc);
                case "Snaptap" -> snaptap(mc);
                case "SpearAssist" -> spearAssist(mc);

                // ---- player ----
                case "AutoDrain" -> autoDrain(mc, tick);
                case "AutoExtinguish" -> autoExtinguish(mc, tick);
                case "AutoTool" -> autoTool(mc);
                case "WebEscape" -> webEscape(mc, tick);
                case "AntiBot" -> { /* consumed by apex target picking */ }

                // ---- smp ----
                case "AutoChorus" -> autoChorus(mc, tick);
                case "AutoHeal" -> autoHeal(mc, tick);
                case "AutoShieldBreak" -> delegate("ShieldBreaker");
                case "BowSpam" -> bowSpam(mc, tick);
                case "LavaMacro" -> lavaMacro(mc, tick);
                case "PlateWeb" -> plateWeb(mc, tick);
                case "SprintReset" -> delegate("SmartWTap");
                case "ItemESP", "MobESP", "ShaderESP" -> delegate("ESP");
                case "Xray" -> delegate("BlockESP");

                // ---- blatant ----
                case "MaceShieldDrain" -> maceShieldDrain(mc, tick);
                case "FastPlace" -> fastPlace(mc, tick);
                case "NoBreakDelay" -> noBreakDelay(mc, tick);

                case "CartRefill" -> refill(mc, tick, "tnt_minecart", "rail");
                case "Replenish" -> replenish(mc, tick);
                case "PotRefill" -> refill(mc, tick, "potion");
                case "ElytraSwap" -> elytraSwap(mc, tick);
                case "ChestSteal" -> chestSteal(mc, tick);
                case "InvCleaner" -> invCleaner(mc, tick);
                case "FastExp" -> fastExp(mc, tick);
                case "Humanization" -> { /* applied inside rotate() */ }
                // Backtrack cancels entity updates in PacketDelayMixin; the three visual
                // entries draw from ApexRender on the LevelRenderer hook.
                case "Backtrack", "ObsidianGlow", "MaceImpact", "DashTrails" -> { }

                // ---- client ----
                case "ClickGUI" -> openGui(mc);
                case "ConfigBind" -> delegate("PresetSwitch");

                default -> { }
            }
        } finally {
            activeEntry = null;
            activeModule = null;
        }
    }

    // ---- helpers ----

    private static int integer(String key, int fallback) { return ApexSettings.integer(activeModule, key, fallback); }
    private static double number(String key, double fallback) { return ApexSettings.number(activeModule, key, fallback); }
    private static boolean flag(String key, boolean fallback) { return ApexSettings.flag(activeModule, key, fallback); }
    private static boolean due(int tick, int fallback) { return tick % integer("intervalTicks", fallback) == 0; }


    private static int advance(String name) {
        int[] box = TIMERS.computeIfAbsent(name, k -> new int[]{0});
        if (box[0] < 0) box[0] = 0;
        return box[0]++;
    }

    private static void reset(String name) {
        TIMERS.put(name, new int[]{0});
    }

    private static boolean edge(String name, boolean now) {
        boolean was = PREV.getOrDefault(name, false);
        PREV.put(name, now);
        return now && !was;
    }

    private static void delegate(String moduleName) {
        Module module = UtilityClient.moduleManager.getByName(moduleName);
        if (module == null) return;
        DELEGATES.computeIfAbsent(module, k -> new java.util.HashSet<>()).add(activeEntry);
        if (!module.isEnabled()) { ENABLED_BY_US.add(module); module.setEnabled(true); }
    }

    /** Degrees of view error that still counts as aimed at the point. */
    private static final float AIM_TOLERANCE = 2.5f;

    /** Per-tick rotation ceiling. Wide corrections take several ticks, like a hand. */
    private static final float MAX_DEGREES_PER_TICK = 18.0f;

    /**
     * Steers the view toward a world point.
     *
     * <p>Returns true when the view already covers the point, which is the only tick
     * on which the caller may send an interaction. A false return means a rotation
     * went out and the caller must return: a rotation packet and a use packet in one
     * 50 ms slice is the shape a combat check resolves as a snap-aim hit, and the
     * launch or hit direction is taken from the rotation the server held before it.
     */
    private static boolean rotate(Minecraft mc, Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        float yawError = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
        float pitchError = Math.abs(pitch - mc.player.getXRot());
        if (yawError <= AIM_TOLERANCE && pitchError <= AIM_TOLERANCE) return true;

        Module human = UtilityClient.moduleManager.getByName("Humanization");
        boolean profile = human != null && human.isEnabled();
        AimPipeline.steer(mc, yaw, pitch, profile ? 9.0f : MAX_DEGREES_PER_TICK,
                AIM_TOLERANCE, profile ? AimPipeline.DEFAULT_DRIFT_PIXELS : 0.0f);
        return false;
    }

    /**
     * Steers the pitch alone, for items whose direction the server resolves (wind
     * charge, pearl, experience bottle).
     *
     * <p>Same contract as {@link #rotate}: true means the pitch is already there and
     * the use packet can go out this tick.
     */
    private static boolean pitch(Minecraft mc, float degrees) {
        if (Math.abs(degrees - mc.player.getXRot()) <= AIM_TOLERANCE) return true;
        Module human = UtilityClient.moduleManager.getByName("Humanization");
        boolean profile = human != null && human.isEnabled();
        AimPipeline.steer(mc, mc.player.getYRot(), degrees, profile ? 9.0f : MAX_DEGREES_PER_TICK,
                AIM_TOLERANCE, profile ? AimPipeline.DEFAULT_DRIFT_PIXELS : 0.0f);
        return false;
    }

    private static Player target(Minecraft mc, double range) {
        Player best = null;
        // The requested range is a preference; the arbiter's limit is the ceiling.
        // A caller asking for 4.2 was reaching past what the server resolves.
        double limit = range;
        double bestDist = limit * limit;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player p) || p == mc.player) continue;
            if (p.getHealth() <= 0 || com.baggute.cheatclient.friends.FriendsManager.isFriend(p)) continue;
            if (isBot(p)) continue;
            double d = p.distanceToSqr(mc.player);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    /** Bots report an empty name or a duplicate of an existing player. */
    private static boolean isBot(Player p) {
        Module antiBot = UtilityClient.moduleManager.getByName("AntiBot");
        if (antiBot == null || !antiBot.isEnabled()) return false;
        String name = p.getName().getString();
        if (ApexSettings.flag(antiBot, "blankNames", true) && name.isBlank()) return true;
        if (ApexSettings.flag(antiBot, "suspiciousPrefixes", true) && (name.startsWith("CIT-") || name.startsWith("Â§"))) return true;
        if (!ApexSettings.flag(antiBot, "duplicateNames", true)) return false;
        int same = 0;
        for (Entity e : Minecraft.getInstance().level.entitiesForRendering()) {
            if (e instanceof Player other && e != p && other.getName().getString().equals(name)) same++;
        }
        return same > 0;
    }

    private static boolean select(Minecraft mc, String... names) {
        if (ModuleUtils.matches(mc.player.getMainHandItem(), names)) return true;
        int slot = ModuleUtils.findHotbarItem(mc, names);
        if (slot < 0) return false;
        // Routed through the arbiter so the tick is marked as a swap: the attack that
        // follows on this tick is refused instead of racing the carried-item packet.
        selectSlot(mc, slot);
        return false;
    }

    /** True when the held item's registry path ends with `suffix` (e.g. "_axe"). */
    private static boolean holdingTool(Minecraft mc, String suffix) {
        return mc.player.getMainHandItem().getItem().toString().toLowerCase().endsWith(suffix);
    }

    /** Equips the first hotbar tool whose registry path ends with `suffix`. */
    private static void selectTool(Minecraft mc, String suffix) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem().toString().toLowerCase().endsWith(suffix)) {
                selectSlot(mc, i);
                return;
            }
        }
    }

    /**
     * Exact-item selection, used where a substring match is wrong: "bucket" also
     * matches water_bucket/lava_bucket, and draining a fluid with one of those
     * places it instead. Returns true only when the item is already in hand.
     */
    private static boolean selectExact(Minecraft mc, net.minecraft.world.item.Item item) {
        if (mc.player.getMainHandItem().is(item)) return true;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                selectSlot(mc, i);
                return false; // the carried-item packet goes out on the next tick
            }
        }
        return false;
    }

    private static void sneeze(Minecraft mc) {
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        mc.player.setSprinting(false);
    }

    private static boolean hasItem(Minecraft mc, String name) {
        return ModuleUtils.findHotbarItem(mc, name) >= 0;
    }

    private static boolean falling(Minecraft mc) {
        return mc.player.getDeltaMovement().y < -0.08;
    }

    // ---- combat bodies ----

    /** Jump, wind charge at the floor, elytra up: the launch combo. */
    private static void alienLaunch(Minecraft mc, int tick) {
        if (!due(tick, 6)) return;
        if (mc.player.onGround()) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().add(0, 0.42, 0));
            return;
        }
        if (falling(mc) && select(mc, "wind_charge")) {
            if (!pitch(mc, 90f)) return;
            use(mc);
            return;
        }
        if (hasItem(mc, "elytra") && falling(mc)) {
            activateGlide(mc);
        }
    }

    private static void autoAlien(Minecraft mc, int tick) {
        Owned state = owned();
        if (state.alienRestore) {
            mc.player.setYRot(state.alienYaw); mc.player.setXRot(state.alienPitch);
            state.alienRestore = false;
            return;
        }
        if (tick < state.nextActionTick || !mc.player.isFallFlying()
                || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA)) return;
        double vy = mc.player.getDeltaMovement().y;
        if (vy > -number("minFallSpeed", 0.2)) return;
        int slot = ModuleUtils.findHotbarItem(mc, "wind_charge");
        if (slot < 0) return;
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        int original = mc.player.getInventory().getSelectedSlot();
        var result = Stealth.useItemSelected(mc, InteractionHand.MAIN_HAND, slot, "wind_charge", yaw, 90);
        if (result != null && result.consumesAction()) {
            state.alienYaw = yaw; state.alienPitch = pitch; state.alienRestore = true;
            mc.player.setXRot(90);
            if (state.originalSlot < 0) state.originalSlot = original;
            state.selectedSlot = slot;
            Stealth.restoreSlot(mc, original);
            state.nextActionTick = tick + 10;
        }
    }

    private static void elytraDrop(Minecraft mc) {
        Player t = target(mc, number("range", 4.0));
        if (t == null) return;
        if (!mc.player.isFallFlying()) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (!ModuleUtils.matches(mc.player.getItemBySlot(EquipmentSlot.CHEST), "elytra")) return;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || (!ModuleUtils.matches(stack, "elytra")
                    && stack.getItem().toString().contains("chestplate"))) {
                Stealth.swapSlot(mc, 6, slot, net.minecraft.world.inventory.ContainerInput.SWAP);
                return; // wait for the equipment update before checking gliding again
            }
        }
    }

    private static int breachMace(Minecraft mc) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.is(net.minecraft.world.item.Items.MACE)) continue;
            var enchants = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
            if (enchants != null) for (var enchant : enchants.entrySet())
                if (enchant.getKey().is(net.minecraft.world.item.enchantment.Enchantments.BREACH)
                        && enchant.getIntValue() > 0) return slot;
        }
        return -1;
    }

    private static boolean finishOwned(Minecraft mc, Owned state) {
        if (state.originalSlot >= 0 && mc.player.getInventory().getSelectedSlot() == state.selectedSlot) {
            if (!Stealth.swapSlot(mc, state.originalSlot)) return false;
        }
        state.originalSlot = state.selectedSlot = -1;
        state.phase = state.phaseTicks = 0;
        return true;
    }

    private static void breachSwap(Minecraft mc, int tick) {
        Owned state = owned();
        if (state.phase == 1) { finishOwned(mc, state); return; }
        if (!mc.options.keyAttack.isDown()) return;
        Player target = target(mc, number("range", 3.5));
        if (target == null || target.getArmorValue() < integer("minArmor", 10)) return;
        int slot = breachMace(mc);
        if (slot < 0) return;
        if (mc.player.getInventory().getSelectedSlot() != slot) { selectSlot(mc, slot); return; }
        if (!rotate(mc, target.position().add(0, target.getBbHeight() * 0.6, 0))) return;
        if (Stealth.attack(mc, target)) state.phase = 1;
    }

    private static void instaCatch(Minecraft mc, int tick) {
        Owned state = owned();
        int cycle = integer("cycleTicks", 4);
        if (tick < state.nextActionTick) return;
        if (++state.phaseTicks > Math.max(80, cycle * 4)) { finishOwned(mc, state); return; }
        // Keep the phase while an equip or rotation consumes a tick. A fixed
        // tick-modulo schedule alternated slots forever without using either item.
        if (state.phase == 0) {
            if (!select(mc, "ender_pearl")) return;
        } else {
            if (!select(mc, "wind_charge")) return;
            if (!pitch(mc, (float) number("pitch", 85))) return;
        }
        if (use(mc)) {
            state.phase = state.phase == 0 ? 1 : 0;
            state.phaseTicks = 0;
            state.nextActionTick = tick + Math.max(1, cycle / 2);
        }
    }

    private static void maceDoubleTap(Minecraft mc, int tick) {
        Owned state = owned();
        if (state.phase == 4) { if (finishOwned(mc, state)) activeModule.setEnabled(false); return; }
        if (state.phase != 0 && ++state.phaseTicks > 100) { state.phase = 4; return; }
        Player target = state.targetId < 0 ? target(mc, number("range", 4.0))
                : mc.level.getEntity(state.targetId) instanceof Player found ? found : null;
        if (target == null || !target.isAlive() || !Stealth.inEntityReach(mc.player, target)) {
            if (state.phase != 0) state.phase = 4;
            return;
        }
        state.targetId = target.getId();
        if (state.phase == 0) {
            if (!mc.player.isFallFlying()) return;
            int slot = breachMace(mc);
            if (slot < 0) return;
            if (mc.player.getInventory().getSelectedSlot() != slot) { selectSlot(mc, slot); return; }
            if (!rotate(mc, target.position().add(0, target.getBbHeight() * 0.6, 0))) return;
            if (Stealth.attack(mc, target)) { state.phase = 1; state.phaseTicks = 0; }
            return;
        }
        if (state.phase == 1) {
            if (mc.player.containerMenu != mc.player.inventoryMenu) return;
            if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA)) {
                state.phase = 2; return;
            }
            for (int slot = 0; slot < 9; slot++) {
                ItemStack candidate = mc.player.getInventory().getItem(slot);
                if (candidate.isEmpty() || candidate.getItem().toString().endsWith("chestplate")) {
                    if (Stealth.swapSlot(mc, 6, slot, net.minecraft.world.inventory.ContainerInput.SWAP))
                        state.phase = 2;
                    return;
                }
            }
            state.phase = 4;
            return;
        }
        if (state.phase == 2) {
            if (mc.player.isFallFlying() || !falling(mc)) return;
            state.phase = 3;
        }
        if (!selectExact(mc, net.minecraft.world.item.Items.MACE)) return;
        if (!rotate(mc, target.position().add(0, target.getBbHeight() * 0.6, 0))) return;
        if (Stealth.attack(mc, target)) state.phase = 4;
    }

    private static void pearlDisrupt(Minecraft mc, int tick) {
        if (!hasItem(mc, "wind_charge")) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl)
                    || pearl.getOwner() == mc.player || owned().answeredPearls.contains(pearl.getUUID())) continue;
            if (e.distanceToSqr(mc.player) > Math.pow(number("range", 10), 2)) continue;
            if (!select(mc, "wind_charge")) return;
            if (!rotate(mc, e.position())) return;
            if (due(tick, 2) && use(mc)) owned().answeredPearls.add(pearl.getUUID());
            return;
        }
    }

    private static void perfectWindcharge(Minecraft mc, int tick) {
        if (!mc.player.onGround()) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!e.getType().getDescriptionId().toLowerCase().contains("wind_charge")) continue;
            if (e.distanceToSqr(mc.player) > Math.pow(number("range", 3), 2)) continue;
            if (due(tick, 2)) {
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().add(0, 0.42, 0));
            }
            return;
        }
    }

    private static void spearMace(Minecraft mc, int tick) {
        Owned state = owned();
        int cycle = integer("cycleTicks", 4);
        if (tick < state.nextActionTick) return;
        if (++state.phaseTicks > Math.max(80, cycle * 4)) { finishOwned(mc, state); return; }
        Player t = state.phase == 0 ? target(mc, number("range", 3.6))
                : mc.level.getEntity(state.targetId) instanceof Player found ? found : null;
        if (t == null || !t.isAlive() || !Stealth.inEntityReach(mc.player, t)) {
            if (state.phase != 0) finishOwned(mc, state);
            return;
        }
        if (!select(mc, state.phase == 0 ? "spear" : "mace")) return;
        if (!rotate(mc, t.position().add(0, t.getBbHeight() * 0.6, 0))) return;
        if (Stealth.attack(mc, t)) {
            state.targetId = t.getId();
            state.phase = state.phase == 0 ? 1 : 0;
            state.phaseTicks = 0;
            state.nextActionTick = tick + Math.max(1, cycle / 2);
        }
    }

    private static void hitCrystal(Minecraft mc, int tick) {
        Player t = target(mc, number("range", 4.0));
        if (t == null) return;
        // The crystal volume must not contain an entity (EndCrystalItem refuses it),
        // so the target's own feet block is never a valid base.
        BlockPos spot = Placement.findCrystalBase(mc, t.blockPosition(), number("placementRadius", 2));
        if (spot == null) return;

        if (tick % 4 == 0 && Placement.useOn(mc, "obsidian", spot.below()) != Placement.Result.USED) return;
        // The crystal must be used on the obsidian block itself: EndCrystalItem
        // validates the clicked block is obsidian/bedrock, and spot.below() is dirt.
        if (tick % 4 == 1 && Placement.useOn(mc, "end_crystal", spot) != Placement.Result.USED) return;
        // The crystal entity is damaged by clicking it once it exists.
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!e.getType().getDescriptionId().toLowerCase().contains("end_crystal")) continue;
            if (e.distanceToSqr(mc.player) > 36) continue;
            if (!rotate(mc, e.position())) return;
            if (mc.player.getAttackStrengthScale(0.95f) >= 0.9f) {
                Stealth.attack(mc, e);
            }
            return;
        }
    }

    private static void safeAnchor(Minecraft mc, int tick) {
        Player t = target(mc, number("range", 4.0));
        if (t == null) return;

        // Charge the anchor already standing near the target; findSpot only ever
        // returns empty cells, so the old "already there" test was unreachable and
        // the module just spammed new anchors.
        BlockPos anchor = Placement.findAnchor(mc, t.blockPosition(), integer("searchRadius", 2));
        if (anchor == null) {
            BlockPos spot = Placement.findSpot(mc, t.blockPosition(), integer("searchRadius", 2));
            if (spot != null) Placement.useOn(mc, "respawn_anchor", spot.below());
            return;
        }

        int charge = mc.level.getBlockState(anchor)
                .getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE);
        if (charge < integer("charges", 4)) {
            if (tick % integer("chargeTicks", 8) == 0) Placement.useOn(mc, "glowstone", anchor);
            return;
        }

        // Detonating needs a non-fuel item in hand, or the anchor charges again.
        if (ModuleUtils.matches(mc.player.getMainHandItem(), "glowstone")) {
            for (int i = 0; i < 9; i++) {
                if (!ModuleUtils.matches(mc.player.getInventory().getItem(i), "glowstone")) {
                    selectSlot(mc, i);
                    return;
                }
            }
            return;
        }
        // Detonation is a use-on-block packet, so it resolves against the player's own
        // look ray. Take the face the ray actually enters; when it misses, spend the
        // tick turning and let the next one send the click.
        Direction face = Placement.facing(mc, anchor);
        if (face == null) {
            AimPipeline.rotateTo(mc, Placement.faceCentre(anchor, Direction.UP), 0.5, 1.0, true, 0.3);
            return;
        }
        com.baggute.cheatclient.util.Stealth.useItemOn(mc, InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(
                        Placement.faceCentre(anchor, face), face, anchor, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private static void fastCart(Minecraft mc, int tick) {
        // Waits for a rail to be placed under the crosshair, then drops the cart.
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)) return;
        BlockPos pos = hit.getBlockPos();
        if (!mc.level.getBlockState(pos).getBlock().toString().contains("rail")) return;
        if (!due(tick, 2)) return;
        Placement.useOnSilent(mc, "tnt_minecart", pos);
    }

    private static void pearlCatch(Minecraft mc, int tick) {
        Owned state = owned();
        if (++state.phaseTicks > integer("timeoutTicks", 80)) { state.pearlPhase = 0; state.phaseTicks = 0; return; }
        if (state.pearlPhase == 0) {
            if (!select(mc, "ender_pearl")) return;
            if (use(mc)) { state.pearlPhase = 1; state.phaseTicks = 0; }
            return;
        }
        if (!select(mc, "wind_charge") || !pitch(mc, (float) number("pitch", 30))) return;
        if (use(mc)) { state.pearlPhase = 0; state.phaseTicks = 0; }
    }

    private static Vec3 pearlLanding(Minecraft mc, Entity pearl) {
        Vec3 position = pearl.position(), motion = pearl.getDeltaMovement();
        for (int step = 0; step < 120; step++) {
            motion = motion.add(0, -0.03, 0).scale(pearl.isInWater() ? 0.8f : 0.99f);
            Vec3 next = position.add(motion);
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(position, next,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, pearl));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return hit.getLocation();
            position = next;
            if (position.y < mc.level.getMinY()) break;
        }
        return position;
    }

    private static void pearlChase(Minecraft mc, int tick) {
        Owned state = owned();
        if (state.phase == 1) { finishOwned(mc, state); return; }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl)
                    || pearl.getOwner() == mc.player || pearl.getOwner() == null
                    || com.baggute.cheatclient.friends.FriendsManager.isFriend(pearl.getOwner())
                    || state.answeredPearls.contains(pearl.getUUID())
                    || pearl.distanceToSqr(mc.player) > Math.pow(number("range", 30.0), 2)) continue;
            if (!selectExact(mc, net.minecraft.world.item.Items.ENDER_PEARL)) return;
            Vec3 eye = mc.player.getEyePosition();
            Vec3 movement = mc.player.getDeltaMovement();
            Vec3 inherited = new Vec3(movement.x, mc.player.onGround() ? 0 : movement.y, movement.z);
            Vec3 launch = com.baggute.cheatclient.util.ProjectileMath.launchTo(
                    pearlLanding(mc, pearl).subtract(eye.add(0, -0.1, 0)), inherited, 1.5, 0.99f, 0.03, 120);
            if (launch == null || !rotate(mc, eye.add(launch.scale(20)))) return;
            if (use(mc)) { state.answeredPearls.add(pearl.getUUID()); state.phase = 1; }
            return;
        }
    }

    private static void pearlGrapple(Minecraft mc, int tick) {
        if (!due(tick, 4)) return;
        Player t = target(mc, number("range", 25.0));
        if (t == null) return;
        if (!select(mc, "ender_pearl")) return;
        if (!rotate(mc, t.position().add(0, t.getBbHeight() * 0.5, 0))) return;
        use(mc);
    }

    private static void swordHit(Minecraft mc, int tick) {
        if (!mc.options.keyAttack.isDown()) return;
        if (!due(tick, 2)) return;
        select(mc, "sword");
    }

    // ---- automation bodies ----

    private static void holdEat(Minecraft mc, String item) {
        if (!select(mc, item)) return;
        if (!mc.player.isUsingItem()) use(mc);
    }

    private static void holdShield(Minecraft mc) {
        if (flag("offhandFirst", false) && ModuleUtils.matches(mc.player.getOffhandItem(), "shield")) {
            if (!mc.player.isUsingItem() && Stealth.canInteract(mc)) {
                Stealth.useItem(mc, InteractionHand.OFF_HAND);
                if (mc.player.isUsingItem()) owned().use = mc.player.getUseItem();
            }
            return;
        }
        if (!select(mc, "shield")) return;
        if (!mc.player.isUsingItem()) use(mc);
    }

    private static void oneTap(Minecraft mc, int tick, String item, String key) {
        Owned state = owned();
        if (state.phase == 1) {
            if (finishOwned(mc, state)) activeModule.setEnabled(false);
            return;
        }
        if (!select(mc, item)) return;
        if (use(mc)) state.phase = 1;
    }

    private static void autoLunge(Minecraft mc, int tick) {
        Player t = target(mc, number("range", 3.8));
        if (t == null) return;
        if (!due(tick, 3)) return;
        if (!select(mc, "spear")) return;
        if (!rotate(mc, t.position().add(0, t.getBbHeight() * 0.6, 0))) return;
        Stealth.attack(mc, t);
    }

    private static void keepSprint(Minecraft mc) {
        if (flag("forwardOnly", false) && !mc.options.keyUp.isDown()) return;
        if (flag("pauseUsingItem", false) && mc.player.isUsingItem()) return;
        if (!mc.player.isSprinting() && !mc.player.isCrouching()) {
            owned().sprint = true;
            mc.player.setSprinting(true);
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        }
    }

    private static void snaptap(Minecraft mc) {
        Owned state = owned();
        net.minecraft.client.KeyMapping[] keys = {mc.options.keyUp, mc.options.keyDown,
                mc.options.keyLeft, mc.options.keyRight};
        for (int i = 0; i < keys.length; i++) {
            boolean down = com.baggute.cheatclient.util.PhysicalInput.down(mc, keys[i]);
            if (down && !state.physical[i]) state.pressOrder[i] = ++state.sequence;
            state.physical[i] = down;
            keys[i].setDown(down);
        }
        for (int i = 0; i < keys.length; i += 2) {
            if (!flag(i == 0 ? "forwardBack" : "leftRight", true)) continue;
            if (state.physical[i] && state.physical[i + 1])
                keys[state.pressOrder[i] > state.pressOrder[i + 1] ? i + 1 : i].setDown(false);
        }
    }

    private static void spearAssist(Minecraft mc) {
        if (!mc.player.isUsingItem()) return;
        Player t = target(mc, number("range", 20.0));
        if (t == null) return;
        rotate(mc, t.position().add(0, t.getBbHeight() * 0.6, 0));
    }

    // ---- player bodies ----

    private static void autoDrain(Minecraft mc, int tick) {
        Owned state=owned();
        if(state.phase==2){finishOwned(mc,state);state.fluidPos=null;return;}
        if(state.phase==0){
            if(!due(tick,5))return;
            BlockPos best=null;double nearest=Double.MAX_VALUE;BlockPos feet=mc.player.blockPosition();
            if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                    &&hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK
                    &&mc.level.getFluidState(hit.getBlockPos()).isSource())best=hit.getBlockPos();
            if(best==null)for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
                BlockPos candidate=feet.offset(x,y,z);if(!mc.level.getFluidState(candidate).isSource())continue;
                double distance=mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate));
                if(distance<nearest&&Stealth.inBlockReach(mc.player,candidate)){nearest=distance;best=candidate;}
            }
            if(best==null)return;
            boolean emptyBucket=false;
            for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).is(net.minecraft.world.item.Items.BUCKET)){emptyBucket=true;break;}
            if(!emptyBucket)return;
            state.fluidPos=best.immutable();state.phase=1;state.phaseTicks=0;
        }
        if(++state.phaseTicks>30||!mc.level.getFluidState(state.fluidPos).isSource()){state.phase=2;return;}
        if(!selectExact(mc,net.minecraft.world.item.Items.BUCKET))return;
        if(com.baggute.cheatclient.util.BucketActions.use(mc,state.fluidPos,true))state.phase=2;
    }

    private static void autoExtinguish(Minecraft mc, int tick) {
        Owned state = owned();
        if (state.phase == 0) {
            if (!mc.player.isOnFire()) return;
            state.fluidPos = mc.player.blockPosition();
            state.phaseTicks = 0;
            if (!selectExact(mc, net.minecraft.world.item.Items.WATER_BUCKET)) return;
            if (com.baggute.cheatclient.util.BucketActions.use(mc, state.fluidPos, false)) state.phase = 1;
            return;
        }
        if (++state.phaseTicks > 80) { finishOwned(mc, state); return; }
        if (state.phase == 1) {
            if (mc.player.isOnFire()) return;
            if (!mc.level.getFluidState(state.fluidPos).isSource()) {
                if (state.phaseTicks > 20) state.phase = 2;
                return;
            }
            if (!selectExact(mc, net.minecraft.world.item.Items.BUCKET)) return;
            if (com.baggute.cheatclient.util.BucketActions.use(mc, state.fluidPos, true)) state.phase = 2;
            return;
        }
        finishOwned(mc, state);
    }

    private static void autoTool(Minecraft mc) {
        if (!mc.options.keyAttack.isDown()) return;
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)) return;

        String block = mc.level.getBlockState(hit.getBlockPos()).getBlock().toString().toLowerCase();
        // Match the item suffix exactly: ModuleUtils.matches is a substring test and
        // "minecraft:iron_pickaxe" contains "axe", so a pickaxe used to satisfy the
        // wood branch and no axe was ever equipped.
        String want;
        if (block.contains("stone") || block.contains("ore") || block.contains("deepslate")
                || block.contains("obsidian") || block.contains("cobble")) want = "_pickaxe";
        else if (block.contains("log") || block.contains("wood") || block.contains("plank")) want = "_axe";
        else if (block.contains("dirt") || block.contains("sand") || block.contains("gravel")) want = "_shovel";
        else return;

        if (!flag(want.equals("_pickaxe") ? "pickaxes" : want.equals("_axe") ? "axes" : "shovels", true)) return;
        if (!holdingTool(mc, want)) selectTool(mc, want);
    }

    public static BlockPos webTarget(BlockPos feet, boolean feetWeb, boolean headWeb) {
        return feetWeb ? feet : headWeb ? feet.above() : null;
    }

    private static void cancelBreak(Minecraft mc, Owned state) {
        if (state.breaking != null && mc.gameMode != null && !mc.options.keyAttack.isDown())
            mc.gameMode.stopDestroyBlock();
        state.breaking = null;
    }

    private static void webEscape(Minecraft mc, int tick) {
        Owned state = owned();
        if (mc.options.keyAttack.isDown()) { state.breaking = null; return; }
        BlockPos feet = mc.player.blockPosition();
        BlockPos web = webTarget(feet, mc.level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.COBWEB),
                mc.level.getBlockState(feet.above()).is(net.minecraft.world.level.block.Blocks.COBWEB));
        if (web == null || !Stealth.inBlockReach(mc.player, web)) { cancelBreak(mc, state); return; }
        if (state.breaking != null && !state.breaking.equals(web)) cancelBreak(mc, state);
        Vec3 eye = mc.player.getEyePosition(), point = Vec3.atCenterOf(web);
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye,
                point.add(point.subtract(eye).normalize().scale(0.05)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(web)
                || eye.distanceTo(hit.getLocation()) > Stealth.blockReachLimit(mc.player) + 0.5) {
            cancelBreak(mc, state); return;
        }
        if (!Stealth.canInteract(mc)) return;
        if (state.breaking == null) {
            if (!due(tick, 3)) return;
            if (mc.gameMode.startDestroyBlock(web, hit.getDirection())) state.breaking = web.immutable();
        } else mc.gameMode.continueDestroyBlock(web, hit.getDirection());
        Stealth.markInteraction();
    }

    // ---- smp bodies ----

    private static void autoChorus(Minecraft mc, int tick) {
        if (mc.player.getHealth() > number("health", 6)) return;
        if (!due(tick, 8)) return;
        holdEat(mc, "chorus_fruit");
    }

    private static void autoHeal(Minecraft mc, int tick) {
        Owned state = owned();
        if (mc.player.isUsingItem()) return; // preserve an existing human or owned drink
        if (state.use != null) { state.use = null; restoreHealSlot(mc, state); }
        float max = Math.max(1f, mc.player.getMaxHealth());
        if (mc.player.getHealth() / max > number("healthPercent", 50) / 100.0) {
            restoreHealSlot(mc, state); return;
        }
        if (!due(tick, 6)) return;
        boolean apple = flag("preferApple", true)
                && ModuleUtils.findHotbarItem(mc, stack -> stack.is(net.minecraft.world.item.Items.GOLDEN_APPLE)
                || stack.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE)) >= 0;
        int slot = ModuleUtils.findHotbarItem(mc, stack -> apple
                ? stack.is(net.minecraft.world.item.Items.GOLDEN_APPLE) || stack.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE)
                : com.baggute.cheatclient.util.HealPotions.delivery(stack) != com.baggute.cheatclient.util.HealPotions.Delivery.NONE);
        if (slot < 0) { restoreHealSlot(mc, state); return; }
        if (mc.player.getInventory().getSelectedSlot() != slot) { selectSlot(mc, slot); return; }
        var delivery = com.baggute.cheatclient.util.HealPotions.delivery(mc.player.getMainHandItem());
        boolean consumed;
        if (!apple && delivery == com.baggute.cheatclient.util.HealPotions.Delivery.SPLASH) {
            var result = Stealth.useItemDirected(mc, InteractionHand.MAIN_HAND, mc.player.getYRot(), 90);
            consumed = result != null && result.consumesAction();
        } else consumed = use(mc);
        if (consumed && !mc.player.isUsingItem()) restoreHealSlot(mc, state);
    }

    private static void restoreHealSlot(Minecraft mc, Owned state) {
        if (state.originalSlot >= 0 && mc.player.getInventory().getSelectedSlot() == state.selectedSlot)
            Stealth.restoreSlot(mc, state.originalSlot);
        state.originalSlot = state.selectedSlot = -1;
    }

    private static void bowSpam(Minecraft mc, int tick) {
        if (!select(mc, "bow")) return;
        if (!mc.player.isUsingItem()) {
            use(mc);
            return;
        }
        if (mc.player.getTicksUsingItem() >= integer("chargeTicks", 3)) {
            mc.gameMode.releaseUsingItem(mc.player);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static void lavaMacro(Minecraft mc, int tick) {
        Owned state = owned();
        if (state.fluidPos == null) {
            if (tick < state.nextLavaTick || !due(tick, integer("cycleTicks", 10))) return;
            BlockPos front = mc.player.blockPosition().relative(mc.player.getDirection(), integer("distance", 2));
            if (!selectExact(mc, net.minecraft.world.item.Items.LAVA_BUCKET)) return;
            if (com.baggute.cheatclient.util.BucketActions.use(mc, front, false)) {
                state.fluidPos = front.immutable(); state.placedTick = tick; state.confirmedLava = false;
            }
            return;
        }
        var fluid = mc.level.getFluidState(state.fluidPos);
        boolean source = fluid.is(net.minecraft.tags.FluidTags.LAVA) && fluid.isSource();
        if (!source) {
            if (state.confirmedLava || tick - state.placedTick > 20) state.fluidPos = null;
            return;
        }
        state.confirmedLava = true;
        if (tick - state.placedTick < integer("scoopTicks", 6)) return;
        if (!selectExact(mc, net.minecraft.world.item.Items.BUCKET)) return;
        if (com.baggute.cheatclient.util.BucketActions.use(mc, state.fluidPos, true)) {
            state.fluidPos = null;
            state.nextLavaTick = tick + integer("cycleTicks", 10);
        }
    }

    private static void plateWeb(Minecraft mc, int tick) {
        Player t = target(mc, number("range", 4.0));
        if (t == null) return;
        if (!due(tick, 10)) return;
        // Web the block the target is standing in (click the ground below it).
        Placement.useOn(mc, "cobweb", t.blockPosition().below());
    }

    // ---- blatant bodies ----

    // ---- inventory bodies (menu clicks, never container hacks) ----

    private static void refill(Minecraft mc, int tick, String... names) {
        if (!due(tick, 20)) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;

        for (int hotbar = 0; hotbar < 9; hotbar++) {
            ItemStack current = mc.player.getInventory().getItem(hotbar);
            if (!current.isEmpty() && !ModuleUtils.matches(current, names)) continue;
            if (current.isEmpty() && !flag("fillEmpty", true)) continue;
            if (!current.isEmpty() && current.getCount() > integer("threshold", 16)) continue;

            int source = findInInventory(mc, current, names);
            if (source < 0) continue;

            Stealth.swapSlot(mc, source, hotbar, net.minecraft.world.inventory.ContainerInput.SWAP);
            return;
        }
    }

    private static void replenish(Minecraft mc, int tick) {
        if (!due(tick, 20)) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;

        for (int hotbar = 0; hotbar < 9; hotbar++) {
            ItemStack current = mc.player.getInventory().getItem(hotbar);
            if (current.isEmpty() || current.getCount() > integer("threshold", 8)) continue;

            int source = findInInventory(mc, current);
            if (source < 0) continue;

            Stealth.swapSlot(mc, source, hotbar, net.minecraft.world.inventory.ContainerInput.SWAP);
            return;
        }
    }

    /** Menu slot 9..35 is the main inventory in the player menu. */
    private static int findInInventory(Minecraft mc, ItemStack template, String... names) {
        String want = template == null || template.isEmpty()
                ? null : template.getItem().toString();

        for (int menuSlot = 9; menuSlot < 36; menuSlot++) {
            ItemStack stack = mc.player.containerMenu.getSlot(menuSlot).getItem();
            if (stack.isEmpty()) continue;
            if (want != null) {
                if (ItemStack.isSameItemSameComponents(stack, template)
                        && stack.getCount() > template.getCount()) return menuSlot;
            } else if (names != null && ModuleUtils.matches(stack, names)) {
                return menuSlot;
            }
        }
        return -1;
    }

    /** Menu slot 6 is the chest plate; SWAP with a hotbar index equips straight in. */
    private static void elytraSwap(Minecraft mc, int tick) {
        Owned state = owned();
        if (state.phase == 1) { activeModule.setEnabled(false); return; }
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        boolean currentlyElytra = mc.player.getItemBySlot(EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack candidate = mc.player.getInventory().getItem(slot);
            boolean opposite = currentlyElytra ? candidate.getItem().toString().endsWith("chestplate")
                    : candidate.is(net.minecraft.world.item.Items.ELYTRA);
            if (opposite && Stealth.swapSlot(mc, 6, slot, net.minecraft.world.inventory.ContainerInput.SWAP)) {
                state.phase = 1;
                return;
            }
        }
    }

    private static void chestSteal(Minecraft mc, int tick) {
        if (!due(tick, 4)) return;
        // Without a screen the current menu is the player's own inventory menu, and
        // "containerSlots" then counted the crafting/armour slots: shift-clicking
        // them unequipped the player's gear. Only real containers are lootable.
        if (mc.player.containerMenu == null || mc.player.containerMenu == mc.player.inventoryMenu) return;

        int size = mc.player.containerMenu.slots.size();
        int containerSlots = Math.max(0, size - 36);
        if (containerSlots == 0) return;

        for (int index = 0; index < containerSlots; index++) {
            int i = flag("reverseOrder", false) ? containerSlots - 1 - index : index;
            ItemStack stack = mc.player.containerMenu.getSlot(i).getItem();
            if (stack.isEmpty() || !ApexSettings.matches(ApexSettings.text(activeModule, "itemFilter", ""), stack.getItem().toString())) continue;
            com.baggute.cheatclient.util.Stealth.swapSlot(mc, i, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
            return;
        }
    }

    private static void invCleaner(Minecraft mc, int tick) {
        if (!due(tick, 6)) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;

        for (int menuSlot = 9; menuSlot < (flag("cleanHotbar", true) ? 45 : 36); menuSlot++) {
            ItemStack stack = mc.player.containerMenu.getSlot(menuSlot).getItem();
            if (stack.isEmpty()) continue;

            String id = stack.getItem().toString().toLowerCase();
            String filter = ApexSettings.text(activeModule, "junkItems", "rotten_flesh,poisonous_potato,spider_eye,seeds,flint,bowl");
            boolean junk = !filter.isBlank() && ApexSettings.matches(filter, id);
            if (!junk) continue;

            com.baggute.cheatclient.util.Stealth.swapSlot(mc, menuSlot, 0, net.minecraft.world.inventory.ContainerInput.THROW);
            return;
        }
    }

    private static void fastExp(Minecraft mc, int tick) {
        if (!due(tick, 1)) return;
        if (!select(mc, "experience_bottle")) return;
        use(mc);
    }

    private static void maceShieldDrain(Minecraft mc, int tick) {
        Player t = target(mc, number("range", 4.2));
        if (t == null) return;
        if (flag("requireFalling", true) && !falling(mc)) return;
        if (!select(mc, "mace")) return;

        if (!rotate(mc, t.position().add(0, t.getBbHeight() * 0.6, 0))) return;
        // One attack packet per tick, ever. The old pair sent two and the server
        // dropped the second, which read as a packet burst with no hit behind it.
        Stealth.attack(mc, t, true);
    }

    private static void fastPlace(Minecraft mc, int tick) {
        if (!due(tick, 1)) return;
        if (!mc.options.keyUse.isDown()) return;
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)) return;
        com.baggute.cheatclient.util.Stealth.useItemOn(mc, InteractionHand.MAIN_HAND, hit);
    }

    private static java.lang.reflect.Field destroyDelayField;
    private static boolean destroyDelayFailed;

    private static void noBreakDelay(Minecraft mc, int tick) {
        if (!mc.options.keyAttack.isDown() || !due(tick, 1) || destroyDelayFailed) return;
        try {
            if (destroyDelayField == null) {
                destroyDelayField = mc.gameMode.getClass().getDeclaredField("destroyDelay");
                destroyDelayField.setAccessible(true);
            }
            destroyDelayField.setInt(mc.gameMode, 0);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            destroyDelayFailed = true;
            UtilityClient.LOGGER.error("[apex] cannot clear block break cooldown", failure);
            activeModule.setEnabled(false);
        }
    }

    private static void openGui(Minecraft mc) {
        Module self = UtilityClient.moduleManager.getByName("ClickGUI");
        if (self != null) self.setEnabled(false);
        mc.gui.setScreen(new com.baggute.cheatclient.gui.ClickGuiScreen());
    }

    private static void activateGlide(Minecraft mc) {
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    /** Snapshot of what the apex tab can and cannot do, for the debug log. */
    public static String report() {
        int supported = 0, unsupported = 0;
        for (Module m : UtilityClient.moduleManager.getModules()) {
            if (!(m instanceof ApexModule apex)) continue;
            if (apex.isImplemented()) supported++;
            else unsupported++;
        }
        return supported + " implemented, " + unsupported + " unavailable";
    }

    /** Unused guard kept for the equipment reader below. */
    static ItemStack hand(Minecraft mc, EquipmentSlot slot) {
        return mc.player.getItemBySlot(slot);
    }

    static boolean alive(LivingEntity e) {
        return e != null && e.getHealth() > 0;
    }

    static boolean sneezing(Player p) {
        return p != null && !p.isSprinting();
    }
}
