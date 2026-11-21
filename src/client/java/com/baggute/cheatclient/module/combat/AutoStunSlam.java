// language: Java, file: AutoStunSlam.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.util.Stealth;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/** Equips an axe for a shield target, attacks, waits, then equips and attacks with the mace. Silent hits report target rotation to the server without moving the local camera. */
public class AutoStunSlam extends Module {

    private final DoubleSetting range;
    private final BoolSetting autoSwap;
    private final IntSetting missChance;
    private final BoolSetting silentAim;

    /** Ticks between selecting the axe and swinging it. */
    private static final int AXE_SWAP_TICKS = 1;

    /** Degrees of view error that still counts as aimed. */
    private static final float AIM_TOLERANCE = 2.5f;

    /** Per-tick rotation ceiling. */
    private static final float MAX_DEGREES_PER_TICK = 20.0f;
    /** Ticks between breaking the shield and the mace hit. */
    private static final int MACE_SWAP_TICKS = 2;
    /** Ticks to hold the mace in hand after the hit before returning. */
    private static final int RESTORE_TICKS = 2;

    private int previousSlot = -1;
    private int ownedSlot = -1;
    private int axeTicks = -1;
    private int maceTicks = -1;
    private int restoreTicks = -1;
    private int cooldownTicks = 0;
    private int targetId = -1;
    private int comboTicks;

    public AutoStunSlam() {
        super("AutoStunSlam", "Breaks a raised shield with an axe, then slams with a mace.", Category.COMBAT);
        this.range = new DoubleSetting("range", "Reach for both hits", 4.5, 1.0, 6.0);
        this.autoSwap = new BoolSetting("autoSwap", "Switch weapons only when attacking", true);
        this.missChance = new IntSetting("missChance", "Chance to swing and miss (%)", 0, 0, 100);
        this.silentAim = new BoolSetting("silentAim", "Aim server-side only", true);
        addSetting(range);
        addSetting(autoSwap);
        addSetting(missChance);
        addSetting(silentAim);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            previousSlot = -1;
            axeTicks = maceTicks = restoreTicks = -1;
            targetId = -1;
            comboTicks = 0;
            return;
        }
        if (ownedSlot >= 0 && mc.player.getInventory().getSelectedSlot() != ownedSlot) {
            previousSlot = ownedSlot = -1; axeTicks = maceTicks = restoreTicks = -1;
            targetId = -1; cooldownTicks = 10; return;
        }
        if (previousSlot >= 0 && (++comboTicks > 100 || mc.gui.screen() != null || mc.isPaused())) {
            restoreSlot(mc);
            axeTicks = maceTicks = restoreTicks = -1;
            targetId = -1;
            cooldownTicks = 10;
            return;
        }

        if (axeTicks > 0) {
            if (--axeTicks == 0) {
                axeTicks = -1;
                swingAt(mc, true);
            }
            return;
        }

        if (maceTicks > 0) {
            if (--maceTicks == 0) {
                maceTicks = -1;
                swingAt(mc, false);
            }
            return;
        }

        if (restoreTicks > 0) {
            if (--restoreTicks == 0) {
                restoreTicks = -1;
                restoreSlot(mc);
            }
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // Do not start a combo on a half-charged weapon: the hit would be a wet noodle.
        if (mc.gui.screen() != null || mc.isPaused()) return;

        Entity target = findShieldingTarget(mc);
        if (target == null) return;

        // autoSwap off means "use what I am already holding", not "give up". The old
        // guard resolved the axe slot to -1 whenever autoSwap was disabled, so the
        // module returned on every tick and never fired at all.
        int axeSlot;
        if (autoSwap.get()) {
            axeSlot = findSlot(mc, true);
            if (axeSlot < 0) {
                // No axe in the hotbar: nothing to break with, so do not flail.
                return;
            }
        } else {
            if (!(mc.player.getMainHandItem().getItem() instanceof AxeItem)) return;
            axeSlot = mc.player.getInventory().getSelectedSlot();
        }

        int original = mc.player.getInventory().getSelectedSlot();
        if (!Stealth.swapSlot(mc, axeSlot)) return;
        previousSlot = original;
        ownedSlot = axeSlot;
        comboTicks = 0;
        targetId = target.getId();
        axeTicks = AXE_SWAP_TICKS;
    }

    private void swingAt(Minecraft mc, boolean withAxe) {
        Entity target = mc.level == null ? null : mc.level.getEntity(targetId);
        if (target == null || !target.isAlive() || !inRange(mc, target)) {
            restoreSlot(mc);
            cooldownTicks = 4;
            return;
        }

        int held = mc.player.getInventory().getSelectedSlot();
        int wanted = autoSwap.get() ? findSlot(mc, withAxe)
                : ((withAxe && mc.player.getMainHandItem().getItem() instanceof AxeItem)
                    || (!withAxe && mc.player.getMainHandItem().is(net.minecraft.world.item.Items.MACE)) ? held : -1);
        if (wanted < 0) {
            restoreSlot(mc);
            axeTicks = maceTicks = -1;
            targetId = -1;
            cooldownTicks = 10;
            return;
        }
        if (wanted >= 0 && mc.player.getInventory().getSelectedSlot() != wanted) {
            if (Stealth.swapSlot(mc, wanted)) ownedSlot = wanted;
            // Switching now would mean swinging the old item: wait a tick for the
            // server to resolve the swap, then come back here.
            if (withAxe) axeTicks = 1; else maceTicks = 1;
            return;
        }

        if (rollMiss()) {
            Stealth.swing(mc);
        } else if (!attackSilently(mc, target)) {
            // Rotation tick: re-arm the same stage so the hit goes out next tick with
            // the view already on the target.
            if (withAxe) axeTicks = 1; else maceTicks = 1;
            return;
        }

        if (withAxe) {
            // Mace comes out now so the server has it two ticks before the slam.
            int maceSlot = findSlot(mc, false);
            maceTicks = MACE_SWAP_TICKS;
        } else {
            restoreTicks = RESTORE_TICKS;
            cooldownTicks = 10;
            targetId = -1;
        }
    }

    /**
     * Aims, then hits.
     *
     * <p>Returns false when the rotation went out this tick, which means the caller
     * must wait one tick before the attack: a rotation packet and an interaction
     * packet in one 50 ms slice is the shape a snap-aim check resolves as a hit
     * against a view the server had not received yet.
     */
    private boolean attackSilently(Minecraft mc, Entity target) {
        Vec3 point = AimPipeline.closestPoint(target, mc.player.getEyePosition(1.0f), 0);
        if (silentAim.get()) {
            Vec3 delta = point.subtract(mc.player.getEyePosition(1.0f));
            float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
            return Stealth.attackDirected(mc, target, yaw, pitch, true, .95f);
        }

        if (!aimedAt(mc, point)) {
            AimPipeline.tickRotate(mc, point, MAX_DEGREES_PER_TICK, true,
                    AIM_TOLERANCE, AimPipeline.DEFAULT_DRIFT_PIXELS);
            return false;
        }

        return Stealth.attack(mc, target, true);
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

    private boolean rollMiss() {
        int chance = missChance.get();
        if (chance <= 0) return false;
        if (chance >= 100) return true;
        return ThreadLocalRandom.current().nextInt(100) < chance;
    }

    private Entity findShieldingTarget(Minecraft mc) {
        Entity best = null;
        double bestDistance = range.get() * range.get();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (com.baggute.cheatclient.friends.FriendsManager.isFriend(entity)) continue;
            if (!(entity instanceof Player player) || player == mc.player || !player.isAlive()) continue;
            if (!player.isUsingItem() || !(player.getUseItem().getItem() instanceof ShieldItem)) continue;
            if (!Stealth.validTarget(mc.player, player)) continue;
            double distance = Math.pow(Stealth.eyeToHitbox(mc.player.getEyePosition(), player.getBoundingBox()), 2);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private boolean inRange(Minecraft mc, Entity target) {
        // Centre distance for the module's own gate, plus the arbiter's eye-to-box
        // check, which is the measure the server resolves the interaction with.
        return Stealth.eyeToHitbox(mc.player.getEyePosition(), target.getBoundingBox()) <= range.get()
                && Stealth.inEntityReach(mc.player, target);
    }

    private void restoreSlot(Minecraft mc) {
        if (previousSlot >= 0 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == ownedSlot
                && mc.player.getInventory().getSelectedSlot() != previousSlot) {
            Stealth.restoreSlot(mc, previousSlot);
        }
        previousSlot = -1;
        ownedSlot = -1;
        comboTicks = 0;
    }

    private int findSlot(Minecraft mc, boolean axe) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (axe ? stack.getItem() instanceof AxeItem : isMace(stack)) return slot;
        }
        return -1;
    }

    private boolean isMace(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.MACE);
    }
    public boolean ownsClick(Minecraft mc) {
        return isEnabled() && mc != null && mc.player != null && mc.level != null
                && (previousSlot >= 0 || (findShieldingTarget(mc) != null && findSlot(mc, true) >= 0
                && (autoSwap.get() || mc.player.getMainHandItem().getItem() instanceof AxeItem)));
    }

    @Override
    protected void onEnable() {
        previousSlot = -1;
        ownedSlot = -1;
        axeTicks = -1;
        maceTicks = -1;
        restoreTicks = -1;
        cooldownTicks = 0;
        targetId = -1;
        UtilityClient.LOGGER.info("[AutoStunSlam] enabled");
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) restoreSlot(mc);
        axeTicks = -1;
        maceTicks = -1;
        restoreTicks = -1;
        cooldownTicks = 0;
        targetId = -1;
    }
}
