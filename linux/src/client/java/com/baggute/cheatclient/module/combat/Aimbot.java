package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.util.Stealth;
import com.baggute.cheatclient.util.PhysicalInput;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Rotates toward a visible living target while attacking. Target selection and
 * rotation both come from AimPipeline, so it shares behaviour with AimAssist.
 */
public final class Aimbot extends Module {

    private final DoubleSetting range  = new DoubleSetting("range", "Target range", 4.5, 1.0, 6.0);
    private final DoubleSetting speed  = new DoubleSetting("speed", "Aim speed", 0.5, 0.05, 1.0);
    private final DoubleSetting fov    = new DoubleSetting("fov", "Target FOV", 120.0, 5.0, 180.0);
    private final BoolSetting snapEyes = new BoolSetting("aimEyes", "Aim at the head instead of the box", false);
    private LivingEntity tracked;
    private long lastFrame;

    public Aimbot() {
        super("Aimbot", "Rotates toward a visible living target while attacking.", Category.COMBAT);
        addSetting(range);
        addSetting(speed);
        addSetting(fov);
        addSetting(snapEyes);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;
        if (!mc.options.keyAttack.isDown() || !PhysicalInput.focused(mc)) { tracked = null; lastFrame = 0; return; }
        if (eligible(mc, tracked)) return;

        Entity target = AimPipeline.pickTarget(
                mc, range.get(), fov.get(), null, true);
        tracked = target instanceof LivingEntity living ? living : null;
    }

    private boolean eligible(Minecraft mc, LivingEntity target) {
        return target != null && target != mc.player && target.isAlive() && target.isAttackable()
                && !com.baggute.cheatclient.friends.FriendsManager.isFriend(target)
                && Stealth.eyeToHitbox(mc.player.getEyePosition(), target.getBoundingBox()) <= range.get()
                && AimPipeline.inFov(mc, target, fov.get()) && mc.player.hasLineOfSight(target);
    }
    @Override public void onRenderFrame(float partialTick) {
        Minecraft mc = Minecraft.getInstance(); long now = System.nanoTime();
        if (mc == null || mc.player == null || mc.level == null || mc.options == null
                || mc.gui.screen() != null || mc.isPaused() || !PhysicalInput.focused(mc)
                || !mc.options.keyAttack.isDown() || !eligible(mc, tracked)) { lastFrame = 0; return; }
        double seconds = lastFrame == 0 ? 1.0 / 60 : Math.min(.05, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (seconds <= 0 || Stealth.attackedThisTick() || Stealth.swappedThisTick()) return;
        Vec3 point = snapEyes.get() ? tracked.getEyePosition(partialTick)
                : tracked.getBoundingBox().getCenter().add(tracked.getPosition(partialTick).subtract(tracked.position()));
        Vec3 delta = point.subtract(mc.player.getEyePosition(partialTick));
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        float dy = smoothStep(Mth.wrapDegrees(yaw - mc.player.getYRot()), speed.get(), seconds);
        float dp = smoothStep(pitch - mc.player.getXRot(), speed.get(), seconds);
        mc.player.setYRot(mc.player.getYRot() + dy);
        mc.player.setXRot(Mth.clamp(mc.player.getXRot() + dp, -90, 90));
        mc.player.yRotO += dy; mc.player.xRotO += dp;
        if (dy != 0 || dp != 0) Stealth.markRotation();
    }
    public static float smoothStep(float error, double speed, double seconds) {
        if (!Float.isFinite(error) || !Double.isFinite(seconds) || seconds <= 0) return 0;
        double amount = 1 - Math.exp(-seconds / (.02 + (1 - speed) * .10));
        double cap = (90 + 630 * speed) * seconds;
        return (float) Math.max(-cap, Math.min(cap, error * amount));
    }
    @Override protected void onEnable() { tracked = null; lastFrame = 0; }
    @Override protected void onDisable() { tracked = null; lastFrame = 0; }
    @Override public void onInputSuspended() { tracked = null; lastFrame = 0; }
}
