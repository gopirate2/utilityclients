// language: Java, file: AimPipeline.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.baggute.cheatclient.util.Stealth;

import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * One aiming path for every combat module: pick a target, then rotate toward a
 * point with the same speed/smoothing/quantisation rules everywhere.
 *
 * Rotation is quantised to the mouse GCD so the resulting deltas look like real
 * mouse movement rather than a straight clamp.
 */
public final class AimPipeline {

    private static final RandomGenerator RNG = RandomGenerator.getDefault();

    /** Correlated drift, one walk per axis. Index 0 is yaw, 1 is pitch. */
    private static final float[] DRIFT = new float[2];

    /**
     * Fraction of last tick's drift carried forward. At 1.0 the walk never
     * settles and the aim wanders; at 0.0 it degenerates into the white noise
     * this exists to avoid.
     */
    private static final float DRIFT_DECAY = 0.72f;

    /** Default drift budget for anything that tracks a target rather than snapping. */
    public static final float DEFAULT_DRIFT_PIXELS = 1.0f;

    public static final int MIN_TRAVEL_TICKS = 3;
    public static final int MAX_TRAVEL_TICKS = 6;
    private static int remainingTicks;
    private static int lastSteerTick = -1;
    private static float goalYaw, goalPitch;

    public static void reset() {
        remainingTicks = 0;
        lastSteerTick = -1;
        DRIFT[0] = DRIFT[1] = 0;
    }

    private AimPipeline() {}

    /** Nearest visible living entity inside range and FOV, filtered by registry id. */
    public static Entity pickTarget(Minecraft mc, double range, double fovDegrees,
                                    Set<String> filter, boolean requireLineOfSight) {
        if (mc.player == null || mc.level == null) return null;
        double rangeSq = range * range;
        double fovCos = Math.cos(Math.toRadians(fovDegrees / 2.0));
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);

        Entity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (com.baggute.cheatclient.friends.FriendsManager.isFriend(entity)) continue;
            if (entity == mc.player || !entity.isAlive()) continue;
            if (!(entity instanceof LivingEntity)) continue;
            if (Stealth.eyeToHitbox(eye, entity.getBoundingBox()) > range) continue;
            if (requireLineOfSight && !mc.player.hasLineOfSight(entity)) continue;

            if (filter != null && !filter.isEmpty()) {
                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                if (id == null || !filter.contains(id.toString())) continue;
            }

            Vec3 targetPos = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
            Vec3 toTarget = targetPos.subtract(eye).normalize();
            double dot = toTarget.dot(look);
            if (dot < fovCos) continue;

            double score = entity.distanceToSqr(eye) - dot * 100.0;
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    /** Closest point on the target box, optionally extrapolated forward. */
    public static Vec3 closestPoint(Entity entity, Vec3 from, int predictTicks) {
        AABB box = entity.getBoundingBox();
        if (predictTicks > 0) {
            Vec3 velocity = entity.getDeltaMovement();
            box = box.move(velocity.x * predictTicks, velocity.y * predictTicks, velocity.z * predictTicks);
        }
        double cx = Mth.clamp(from.x, box.minX, box.maxX);
        double cy = Mth.clamp(from.y, box.minY, box.maxY);
        double cz = Mth.clamp(from.z, box.minZ, box.maxZ);
        return new Vec3(cx, cy, cz);
    }

    /** Steers the player rotation toward a world point. */
    public static void rotateTo(Minecraft mc, Vec3 worldPoint, double speed, double smoothness,
                                boolean vertical, double stopAngle) {
        rotateTo(mc, worldPoint, speed, smoothness, vertical, stopAngle, DEFAULT_DRIFT_PIXELS);
    }

    /**
     * Steers the player rotation toward a world point with an explicit drift
     * budget. Pass {@code 0} for a clean track with no tremor.
     *
     * <p>The per-tick step is capped so the remaining angle takes at least
     * {@link #MIN_TRAVEL_TICKS} ticks to close, and the resulting delta is quantised
     * to whole mouse pixels before it is applied. Both halves matter: the cap removes
     * the instant snap, the quantiser removes the sub-pixel remainder that no physical
     * mouse could have produced.
     *
     * @param driftPixels maximum drift in mouse pixels, per axis
     */
    public static void rotateTo(Minecraft mc, Vec3 worldPoint, double speed, double smoothness,
                                boolean vertical, double stopAngle, float driftPixels) {
        if (mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 delta = worldPoint.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));

        float cap = (float) (180.0 * Mth.clamp(speed / Math.max(0.01, smoothness), 0.01, 1.0));
        steer(mc, targetYaw, vertical ? targetPitch : mc.player.getXRot(), cap, stopAngle, driftPixels);
    }

    /**
     * Rotation with an explicit per-tick degree ceiling.
     *
     * <p>Used where the caller knows the arc it can afford - a bow draw, a crossbow
     * charge - and wants the cap expressed in degrees rather than as a fraction of the
     * remaining angle.
     */
    public static void tickRotate(Minecraft mc, Vec3 worldPoint, float maxDegreesPerTick,
                                  boolean vertical, double stopAngle, float driftPixels) {
        if (mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 delta = worldPoint.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));

        steer(mc, targetYaw, vertical ? targetPitch : mc.player.getXRot(),
                maxDegreesPerTick, stopAngle, driftPixels);
    }

    /** Absolute-angle callers use the same bounded interpolation path. */
    public static void apply(Minecraft mc, float yaw, float pitch) {
        steer(mc, yaw, pitch, 30.0f, 0.0, 0.0f);
    }

    /** One quantized mouse update per tick, with a finite 3-6 tick travel budget. */
    public static void steer(Minecraft mc, float targetYaw, float targetPitch,
                             float maxDegreesPerTick, double stopAngle, float driftPixels) {
        if (mc == null || mc.player == null || !Float.isFinite(targetYaw)
                || !Float.isFinite(targetPitch) || !Float.isFinite(maxDegreesPerTick)) return;
        if (!Stealth.canRotate(mc) || lastSteerTick == Stealth.tick()) return;
        float currentYaw = mc.player.getYRot(), currentPitch = mc.player.getXRot();
        targetPitch = Mth.clamp(targetPitch, -90.0f, 90.0f);
        float dy = Mth.wrapDegrees(targetYaw - currentYaw), dp = targetPitch - currentPitch;
        float gcd = mouseGcd(mc);
        if (maxDegreesPerTick <= 0.0f) return;
        float pixelCap = (float) Math.floor(maxDegreesPerTick / gcd) * gcd;
        if (pixelCap <= 0.0f) return;
        float stop = (float) Math.max(Math.max(0.0, stopAngle), gcd * 0.51);
        if (Math.abs(dy) <= stop && Math.abs(dp) <= stop) {
            remainingTicks = 0;
            return;
        }
        boolean newArc = remainingTicks == 0 || Stealth.tick() - lastSteerTick > 2
                || Math.abs(Mth.wrapDegrees(targetYaw - goalYaw)) > 25.0f
                || Math.abs(targetPitch - goalPitch) > 25.0f;
        if (newArc) {
            remainingTicks = Math.max(MIN_TRAVEL_TICKS, (int) Math.ceil(
                    Math.max(Math.abs(dy), Math.abs(dp)) / pixelCap));
            DRIFT[0] = DRIFT[1] = 0;
        }
        goalYaw = targetYaw;
        goalPitch = targetPitch;
        // Divide by ticks left, not a constant fraction of the remaining arc.
        // The final tick consumes the remainder, rather than approaching forever.
        float jitter = remainingTicks == 1 ? 0.0f : Math.max(0.0f, driftPixels);
        float yawStep = quantizeJittered(dy / remainingTicks, gcd, jitter, 0);
        float pitchStep = quantizeJittered(dp / remainingTicks, gcd, jitter, 1);
        yawStep = Mth.clamp(yawStep, -pixelCap, pixelCap);
        pitchStep = Mth.clamp(pitchStep, -pixelCap, pixelCap);
        // Clamp in pixel space so hitting a pitch pole preserves quantization.
        long pitchPixels = Math.round(pitchStep / gcd);
        long minPixels = (long) Math.ceil((-90.0f - currentPitch) / gcd);
        long maxPixels = (long) Math.floor((90.0f - currentPitch) / gcd);
        pitchStep = Math.max(minPixels, Math.min(maxPixels, pitchPixels)) * gcd;
        remainingTicks--;
        lastSteerTick = Stealth.tick();
        if (yawStep == 0.0f && pitchStep == 0.0f) return;
        mc.player.setYRot(currentYaw + yawStep);
        mc.player.setXRot(currentPitch + pitchStep);
        Stealth.markRotation();
    }

    /**
     * Rotation produced by one mouse pixel at the current sensitivity.
     *
     * <p>{@code MouseHandler.turnPlayer} scales the raw delta by
     * {@code f * f * f * 8.0} where {@code f = sensitivity * 0.6 + 0.2}, and
     * {@code Entity.turn} then multiplies by {@code 0.15} before it reaches the
     * rotation. The step is therefore {@code f^3 * 1.2} degrees - the old
     * {@code * 0.15} on top of that quantised to a sixteenth of a real pixel, so
     * the deltas were finer than any mouse could produce.
     */
    public static float mouseGcd(Minecraft mc) {
        double sensitivity = mc.options.sensitivity().get();
        double f = sensitivity * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    public static float quantize(float delta, float step) {
        if (step <= 0) return delta;
        return Math.round(delta / step) * step;
    }

    /**
     * Rounds a rotation delta to whole mouse pixels and adds correlated drift.
     *
     * <p>Drift is a decaying random walk, not a fresh sample per tick. Sampling
     * independently produces white noise, and white noise is a signature of its
     * own: a hand drifts smoothly between ticks instead of resampling from
     * scratch. The walk is added in pixels before the multiply, so the returned
     * delta never leaves the GCD grid - which is the entire reason the quantiser
     * exists. Adding degrees of noise after the multiply hands a checker exactly
     * the remainder the quantiser just removed.
     *
     * @param axis 0 for yaw, 1 for pitch; each keeps its own walk
     * @param maxPixels drift budget in pixels, 0 to disable
     */
    public static float quantizeJittered(float delta, float step, float maxPixels, int axis) {
        if (step <= 0) return delta;

        if (maxPixels <= 0f) {
            DRIFT[axis] = 0f;
            return Math.round(delta / step) * step;
        }

        float walk = DRIFT[axis] * DRIFT_DECAY + (RNG.nextFloat() * 2f - 1f);
        DRIFT[axis] = Mth.clamp(walk, -maxPixels, maxPixels);

        return Math.round(delta / step + DRIFT[axis]) * step;
    }

    /**
     * A critical hit needs a fall: airborne, descending, and already carrying fall
     * distance. Every module that wants a crit asks here instead of rolling its own.
     */
    public static boolean isFallingForCrit(Minecraft mc) {
        if (mc.player == null) return false;
        if (mc.player.onGround()) return false;
        if (mc.player.isInWater()) return false;
        if (mc.player.onClimbable()) return false;
        if (mc.player.isPassenger()) return false;
        if (mc.player.getDeltaMovement().y >= 0) return false;
        return mc.player.fallDistance > 0.0f;
    }

    /** True when the entity is in front of the player's eyes within the angle. */
    public static boolean inFov(Minecraft mc, Entity entity, double fovDegrees) {
        if (mc.player == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 to = entity.position().add(0, entity.getBbHeight() * 0.5, 0).subtract(eye).normalize();
        return to.dot(mc.player.getViewVector(1.0f)) >= Math.cos(Math.toRadians(fovDegrees / 2.0));
    }
}
