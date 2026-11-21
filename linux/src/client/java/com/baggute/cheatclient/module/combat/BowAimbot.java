// language: Java, file: BowAimbot.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Solves the arrow drop instead of aiming flat. Pitch is bisected against a
 * per-tick drag simulation of the actual arrow physics (0.99 drag, 0.05 gravity),
 * so a distant target gets the same lead a good player computes by feel.
 */
public class BowAimbot extends Module {

    /** Per-tick rotation ceiling. 14 degrees covers a wide correction in six ticks. */
    private static final float MAX_DEGREES_PER_TICK = 14.0f;

    private final DoubleSetting range;
    private final BoolSetting onlyWhileDrawing;
    private final DoubleSetting minCharge;
    private final BoolSetting silent;
    private LivingEntity tracked;
    private Vec3 previousPosition, trackedVelocity = Vec3.ZERO;
    private float shotYaw, shotPitch;
    private boolean shotReady;
    private int sampledTick = Integer.MIN_VALUE;

    public BowAimbot() {
        super("BowAimbot", "Aims bow shots with real arrow drop compensation.", Category.COMBAT);

        this.range = new DoubleSetting("range", "Max target distance", 40.0, 5.0, 96.0);
        this.onlyWhileDrawing = new BoolSetting("onlyWhileDrawing", "Only aim while the bow is drawn", true);
        this.minCharge = new DoubleSetting("minCharge", "Minimum draw before aiming", 0.5, 0.1, 1.0);
        this.silent = new BoolSetting("silent", "Aim the released shot server-side without moving your camera", false);

        addSetting(range);
        addSetting(onlyWhileDrawing);
        addSetting(minCharge);
        addSetting(silent);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        shotReady = false;
        if (mc.player == null || mc.level == null) { tracked = null; sampledTick = Integer.MIN_VALUE; return; }

        boolean drawing = mc.player.isUsingItem() && mc.player.getUseItem().is(net.minecraft.world.item.Items.BOW);
        if (mc.gui.screen() != null || mc.isPaused()) return;
        if (onlyWhileDrawing.get() && !drawing) return;
        if (drawing && drawCharge(mc) < minCharge.get()) return;

        boolean holdingBow = mc.player.getMainHandItem().is(net.minecraft.world.item.Items.BOW)
                || mc.player.getOffhandItem().is(net.minecraft.world.item.Items.BOW);
        if (!holdingBow) return;

        LivingEntity target = pick(mc);
        if (target == null) return;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 position = target.position();
        Vec3 motion = target.getDeltaMovement();
        if (tracked != target || sampledTick != mc.player.tickCount) {
            if (tracked == target && previousPosition != null) {
                int elapsed = Math.max(1, mc.player.tickCount - sampledTick);
                Vec3 observed = position.subtract(previousPosition).scale(1.0 / elapsed);
                if (observed.lengthSqr() > 16) trackedVelocity = Vec3.ZERO;
                else if (motion.lengthSqr() > 1e-6) trackedVelocity = motion;
                else trackedVelocity = trackedVelocity.scale(.5).add(observed.scale(.5));
            } else trackedVelocity = motion;
            tracked = target; previousPosition = position; sampledTick = mc.player.tickCount;
        }
        Vec3 solution = predictAim(eye, target.getBoundingBox().getCenter(), trackedVelocity, drawing ? velocity(mc) : 3);
        float yaw = (float) solution.x, pitch = (float) solution.y;
        if (!Float.isFinite(pitch)) return;
        shotYaw = yaw; shotPitch = pitch; shotReady = true;
        if (silent.get()) return;

        // One capped, quantised step per tick. The old path wrote the solved angles
        // straight onto the player and shipped a rotation packet in the same call, so a
        // 60-degree correction arrived in a single 50 ms slice - no hand produces that,
        // and the launch direction it produced was the loud part of the module.
        AimPipeline.steer(mc, yaw, pitch, MAX_DEGREES_PER_TICK, 0.15, AimPipeline.DEFAULT_DRIFT_PIXELS);
    }

    /** Iterates flight time and target lead against the same drag/drop simulation as the pitch solver. */
    public Vec3 predictAim(Vec3 eye, Vec3 target, Vec3 motion, double speed) {
        double time = eye.distanceTo(target) / Math.max(.1, speed);
        float pitch = Float.NaN; Vec3 delta = target.subtract(eye);
        for (int i = 0; i < 8; i++) {
            delta = target.add(motion.scale(time)).subtract(eye);
            double horizontal = Math.hypot(delta.x, delta.z);
            pitch = solvePitch(horizontal, delta.y, speed);
            if (!Float.isFinite(pitch)) return new Vec3(Double.NaN, Double.NaN, 0);
            double next = simulate(-pitch, speed, horizontal).z;
            if (Math.abs(next - time) < .001) { time = next; break; }
            time = next;
        }
        return new Vec3(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90, pitch, time);
    }
    public Object beginRelease(net.minecraft.world.entity.player.Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !silent.get() || player != mc.player || !player.isUsingItem()
                || !player.getUseItem().is(net.minecraft.world.item.Items.BOW)) return null;
        onTick();
        if (!shotReady || !Float.isFinite(shotYaw) || !Float.isFinite(shotPitch)) return null;
        float[] view = {mc.player.getYRot(), mc.player.getXRot()};
        mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                shotYaw, shotPitch, mc.player.onGround(), mc.player.horizontalCollision));
        return view;
    }
    public static void endRelease(Object player, Object token) {
        if (!(player instanceof net.minecraft.client.player.LocalPlayer local) || !(token instanceof float[] view)) return;
        local.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                view[0], view[1], local.onGround(), local.horizontalCollision));
    }

    private double velocity(Minecraft mc) {
        float charge = mc.player.getTicksUsingItem() / 20.0f;
        charge = (charge * charge + charge * 2) / 3;
        charge = Math.min(1.0f, charge);
        return charge * 3.0;
    }

    /** Draw progress 0..1, matching the vanilla charge curve the velocity uses. */
    private double drawCharge(Minecraft mc) {
        return Math.min(1.0, mc.player.getTicksUsingItem() / 20.0);
    }

    /** Finds the lower reachable arc, then bisects that monotonic branch. */
    private float solvePitch(double horizontal, double dy, double speed) {
        if (!Double.isFinite(horizontal) || !Double.isFinite(dy) || !Double.isFinite(speed)
                || speed <= 0.0 || horizontal < 0.001)
            return Float.NaN;
        float previous = -85.0f;
        Vec3 previousPath = simulate(previous, speed, horizontal);
        double previousError = previousPath.x >= horizontal ? previousPath.y - dy : Double.NaN;
        for (float angle = -84.0f; angle <= 85.0f; angle += 1.0f) {
            Vec3 path = simulate(angle, speed, horizontal);
            double error = path.x >= horizontal ? path.y - dy : Double.NaN;
            if (Double.isFinite(previousError) && Double.isFinite(error) && previousError <= 0.0 && error >= 0.0) {
                float lo = previous, hi = angle;
                for (int i = 0; i < 24; i++) {
                    float mid = (lo + hi) / 2.0f;
                    Vec3 trial = simulate(mid, speed, horizontal);
                    if (trial.x < horizontal || trial.y < dy) lo = mid; else hi = mid;
                }
                return -(lo + hi) / 2.0f;
            }
            previous = angle;
            previousError = error;
        }
        return Float.NaN;
    }

    @Override
    protected void onDisable() {
        tracked = null; previousPosition = null; trackedVelocity = Vec3.ZERO;
        sampledTick = Integer.MIN_VALUE; shotReady = false;
    }

    /**
     * Where the arrow is vertically once it has covered `horizontal` blocks.
     * The loop has to run until it reaches the target distance: returning on the
     * first step (x >= 0) left the whole drop compensation unused.
     */
    private Vec3 simulate(float pitchDeg, double speed, double horizontal) {
        double pitch = Math.toRadians(pitchDeg);
        double vx = Math.cos(pitch) * speed;
        double vy = Math.sin(pitch) * speed;
        double x = 0, y = 0;

        for (int tick = 0; tick < 120; tick++) {
            if (x + vx >= horizontal) return new Vec3(horizontal, y + vy * (horizontal - x) / vx, tick + (horizontal - x) / vx);
            x += vx;
            y += vy;
            vx *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return new Vec3(x, y, 120);
    }

    private LivingEntity pick(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (var entity : mc.level.entitiesForRendering()) {
            if (com.baggute.cheatclient.friends.FriendsManager.isFriend(entity)) continue;
            if (!(entity instanceof LivingEntity living) || living == mc.player) continue;
            if (!(living instanceof net.minecraft.world.entity.player.Player)) continue;
            if (living.getHealth() <= 0) continue;
            if (!mc.player.hasLineOfSight(living)) continue;

            double dist = living.distanceTo(mc.player);
            if (dist > range.get()) continue;

            // Prefer what the crosshair is already near so aiming stays predictable.
            Vec3 to = living.position().subtract(eye).normalize();
            Vec3 look = mc.player.getLookAngle();
            double dot = to.dot(look);
            double score = (1.0 - dot) * 10 + dist * 0.1;
            if (score < bestScore) { bestScore = score; best = living; }
        }
        return best;
    }
}
