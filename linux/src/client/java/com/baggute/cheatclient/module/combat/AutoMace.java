// language: Java, file: AutoMace.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/** Equips a mace, directs the server aim for the actual attack, then restores the owned slot. Silent aim leaves the local camera unchanged; charge and falling requirements are configurable. */
public class AutoMace extends Module {

    /** Degrees of view error that still counts as aimed. */
    private static final float AIM_TOLERANCE = 2.5f;

    /** Per-tick rotation ceiling. */
    private static final float MAX_DEGREES_PER_TICK = 20.0f;

    private final DoubleSetting range;
    private final DoubleSetting minCharge;
    private final DoubleSetting missChance;
    private final IntSetting cooldownTicks;
    private final BoolSetting autoSwap;
    private final BoolSetting silentAim;
    private final BoolSetting requireFall;
    private final DoubleSetting fallBlocks;
    private final BoolSetting requireLineOfSight;
    private final BoolSetting onlyWhileClicking;

    private final Random rng = new Random();

    private int previousSlot = -1;
    private int ownedSlot = -1;
    private int swapBackTicks = 0;
    private int cycleTicks;
    private int attackCooldownTicks = 0;
    /** Set once the aim phase has the view on the target; the swing follows next tick. */
    private boolean aimReady;
    /** True when this swing was rolled as a miss, so no attack packet is sent. */
    private boolean rolledMiss;

    public AutoMace() {
        super("AutoMace", "Aims and swings a mace at nearby players.", Category.COMBAT);

        // The ceiling is the server's own interaction range plus the arbiter buffer.
        this.range = new DoubleSetting("range", "Target range", 3.0, 1.0, 3.5);
        this.minCharge = new DoubleSetting("minCharge", "Minimum attack charge (0 = any)", 0.0, 0.0, 1.0);
        this.missChance = new DoubleSetting("missChance", "Chance to swing air (%)", 0.0, 0.0, 100.0);
        this.cooldownTicks = new IntSetting("cooldownTicks", "Ticks between swings", 8, 1, 40);
        this.autoSwap = new BoolSetting("autoSwap", "Swap to the mace only when attacking", true);
        this.silentAim = new BoolSetting("silentAim", "Aim the attack server-side without moving your camera", true);
        this.requireFall = new BoolSetting("requireFall", "Only swing while falling far enough for mace bonus", false);
        this.fallBlocks = new DoubleSetting("fallBlocks", "Minimum fall distance for the mace bonus", 1.5, 1.5, 8.0);
        this.requireLineOfSight = new BoolSetting("requireLineOfSight", "Skip targets behind blocks", true);
        this.onlyWhileClicking = new BoolSetting("onlyWhileClicking", "Only swing while left-click held", false);

        addSetting(range);
        addSetting(minCharge);
        addSetting(missChance);
        addSetting(cooldownTicks);
        addSetting(autoSwap);
        addSetting(silentAim);
        addSetting(requireFall);
        addSetting(fallBlocks);
        addSetting(requireLineOfSight);
        addSetting(onlyWhileClicking);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            previousSlot = -1; swapBackTicks = 0; cycleTicks = 0; aimReady = false; return;
        }
        if (ownedSlot >= 0 && mc.player.getInventory().getSelectedSlot() != ownedSlot) {
            previousSlot = ownedSlot = -1; swapBackTicks = 0; aimReady = false;
            attackCooldownTicks = cooldownTicks.get(); return;
        }

        if (attackCooldownTicks > 0) attackCooldownTicks--;
        if (previousSlot >= 0 && swapBackTicks < 0 && ++cycleTicks > 60) swapBackTicks = 0;
        restoreSlotIfReady(mc);
        if (attackCooldownTicks > 0) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;
        if (onlyWhileClicking.get() && !mc.options.keyAttack.isDown()) return;

        // Mace bonus damage only applies past 1.5 blocks of fall, so a player who is
        // technically airborne but only fell 0.4 is a wasted swing.
        if (requireFall.get()
                && (!AimPipeline.isFallingForCrit(mc)
                        || mc.player.fallDistance < fallBlocks.get())) {
            aimReady = false;
            return;
        }

        // Match the minimum the player selected in the shared attack gate.
        float floor = (float) minCharge.get();
        if (Stealth.charge(mc.player) < floor) {
            aimReady = false;
            return;
        }

        // Players only: an entity filter is applied before scoring, so a closer mob
        // cannot shadow the player standing behind it.
        double limit = Math.min(range.get(), Stealth.reachLimit(mc.player));
        Entity picked = pickTarget(mc, limit);
        if (!(picked instanceof net.minecraft.world.entity.player.Player target)) {
            aimReady = false;
            return;
        }
        if (target == mc.player || !Stealth.inEntityReach(mc.player, target)) {
            aimReady = false;
            return;
        }

        if (!isMace(mc.player.getMainHandItem())) {
            if (!autoSwap.get()) return;
            int maceSlot = findMaceSlot(mc);
            if (maceSlot < 0 || maceSlot == mc.player.getInventory().getSelectedSlot()) return;
            // A swap shares no tick with an attack or a rotation.
            if (Stealth.swapBlocked() || Stealth.attackedThisTick()) return;
            int original = mc.player.getInventory().getSelectedSlot();
            if (!Stealth.swapSlot(mc, maceSlot)) return;
            previousSlot = original;
            ownedSlot = maceSlot;
            // Hold the swap open across the aim and attack ticks; otherwise the restore
            // undid the equip first and the module flickered the hotbar without swinging.
            swapBackTicks = -1;
            cycleTicks = 0;
            return;
        }

        swing(mc, target);
    }

    /** Aim phase, then attack phase. Never both on the same tick. */
    private void swing(Minecraft mc, Entity target) {
        Vec3 point = AimPipeline.closestPoint(target, mc.player.getEyePosition(1.0f), 0);

        if (silentAim.get()) {
            if (!Stealth.canInteract(mc)) return;
            boolean miss = missChance.get() > 0 && rng.nextDouble() * 100 < missChance.get();
            if (miss) Stealth.swing(mc);
            else {
                Vec3 delta = point.subtract(mc.player.getEyePosition(1.0f));
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                if (!Stealth.attackDirected(mc, target, yaw, pitch, true, (float) minCharge.get())) return;
            }
            aimReady = false; rolledMiss = false;
            attackCooldownTicks = cooldownTicks.get();
            if (previousSlot >= 0) swapBackTicks = 3;
            return;
        }

        if (!aimReady) {
            if (!aimedAt(mc, point)) {
                AimPipeline.tickRotate(mc, point, MAX_DEGREES_PER_TICK, true,
                        AIM_TOLERANCE, AimPipeline.DEFAULT_DRIFT_PIXELS);
                return;
            }
            aimReady = true;
            rolledMiss = missChance.get() > 0.0 && rng.nextDouble() * 100.0 < missChance.get();
            return;
        }

        if (!aimedAt(mc, point)) { aimReady = false; return; }
        // The view already covers the target, so this tick is the click alone.
        if (rolledMiss) {
            Stealth.swing(mc);
        } else {
            if (!Stealth.attack(mc, target, !silentAim.get(), (float) minCharge.get())) return;
        }

        aimReady = false;
        rolledMiss = false;
        attackCooldownTicks = cooldownTicks.get();
        if (previousSlot >= 0) swapBackTicks = 3;
    }

    private Entity pickTarget(Minecraft mc, double limit) {
        Entity best = null; double nearest = limit;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.player.Player)
                    || !Stealth.validTarget(mc.player, entity)
                    || (requireLineOfSight.get() && !mc.player.hasLineOfSight(entity))) continue;
            double distance = Stealth.eyeToHitbox(mc.player.getEyePosition(), entity.getBoundingBox());
            if (distance <= nearest) { nearest = distance; best = entity; }
        }
        return best;
    }

    /** True when the view is already within {@link #AIM_TOLERANCE} of the point. */
    private boolean aimedAt(Minecraft mc, Vec3 point) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 to = point.subtract(eye);
        double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(to.y, horizontal));

        float yawError = Math.abs(Mth.wrapDegrees(wantYaw - mc.player.getYRot()));
        float pitchError = Math.abs(wantPitch - mc.player.getXRot());
        return yawError <= AIM_TOLERANCE && pitchError <= AIM_TOLERANCE;
    }

    private void restoreSlotIfReady(Minecraft mc) {
        if (swapBackTicks > 0) swapBackTicks--;
        if (swapBackTicks == 0 && previousSlot >= 0) {
            if (isMace(mc.player.getMainHandItem())
                    && mc.player.getInventory().getSelectedSlot() != previousSlot) {
                Stealth.restoreSlot(mc, previousSlot);
            }
            previousSlot = -1;
            ownedSlot = -1;
        }
    }

    private boolean isMace(ItemStack stack) {
        return ModuleUtils.matches(stack, "mace");
    }

    private int findMaceSlot(Minecraft mc) {
        return ModuleUtils.findHotbarItem(mc, "mace");
    }
    public boolean ownsClick(Minecraft mc) {
        return isEnabled() && mc != null && mc.player != null && mc.level != null
                && (!requireFall.get() || (AimPipeline.isFallingForCrit(mc) && mc.player.fallDistance >= fallBlocks.get()))
                && (isMace(mc.player.getMainHandItem()) || (autoSwap.get() && findMaceSlot(mc) >= 0))
                && pickTarget(mc, Math.min(range.get(), Stealth.reachLimit(mc.player))) != null;
    }

    @Override
    protected void onEnable() {
        previousSlot = -1;
        ownedSlot = -1;
        swapBackTicks = 0;
        attackCooldownTicks = 0;
        aimReady = false;
        rolledMiss = false;
        UtilityClient.LOGGER.info("[AutoMace] enabled");
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && previousSlot >= 0 && isMace(mc.player.getMainHandItem())) {
            Stealth.restoreSlot(mc, previousSlot);
        }
        previousSlot = -1;
        ownedSlot = -1;
        swapBackTicks = 0;
        attackCooldownTicks = 0;
        aimReady = false;
        rolledMiss = false;
    }
}
