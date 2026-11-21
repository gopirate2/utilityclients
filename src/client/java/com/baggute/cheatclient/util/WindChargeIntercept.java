package com.baggute.cheatclient.util;

import net.minecraft.world.phys.Vec3;

/** Moving-pearl interception using 26.2's gravity, drag, then movement order. */
public final class WindChargeIntercept {
    public static final double PEARL_DRAG = (double) 0.99f;
    public static final double PEARL_GRAVITY = 0.03;
    public static final double WIND_SPEED = 1.5;
    public static final int MAX_FLIGHT_TICKS = 80;
    // Inside the pearl's inflated projectile hitbox; leave room for shot spread.
    public static final double HIT_MARGIN = 0.2;

    public record Motion(Vec3 position, Vec3 velocity) {}
    public record Launch(Vec3 direction, Vec3 intercept, double ticks) {
        public float yaw() { return (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0); }
        public float pitch() { return (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))); }
    }

    private WindChargeIntercept() {}

    public static Vec3 look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** Includes the movement the game's shootFromRotation adds to both projectiles. */
    public static Vec3 inherited(Vec3 movement, boolean onGround) {
        return new Vec3(movement.x, onGround ? 0 : movement.y, movement.z);
    }

    public static Motion advance(Vec3 position, Vec3 velocity, double drag, double gravity, int ticks) {
        if (!finite(position) || !finite(velocity) || !validPhysics(drag, gravity)
                || ticks < 0 || ticks > 1200) return null;
        for (int i = 0; i < ticks; i++) {
            velocity = velocity.add(0, -gravity, 0).scale(drag);
            position = position.add(velocity);
        }
        return new Motion(position, velocity);
    }

    public static Launch solve(Vec3 origin, Vec3 inherited, Vec3 pearlPosition,
                               Vec3 pearlVelocity, double drag, double gravity) {
        return solve(origin, inherited, pearlPosition, pearlVelocity, drag, gravity, MAX_FLIGHT_TICKS);
    }

    public static Launch solve(Vec3 origin, Vec3 inherited, Vec3 pearlPosition,
                               Vec3 pearlVelocity, double drag, double gravity, int maxTicks) {
        if (!finite(origin) || !finite(inherited) || !finite(pearlPosition) || !finite(pearlVelocity)
                || !validPhysics(drag, gravity) || maxTicks < 1 || maxTicks > MAX_FLIGHT_TICKS) return null;
        Vec3[] path = new Vec3[maxTicks + 1];
        path[0] = pearlPosition;
        Vec3 velocity = pearlVelocity;
        for (int i = 1; i <= maxTicks; i++) {
            velocity = velocity.add(0, -gravity, 0).scale(drag);
            path[i] = path[i - 1].add(velocity);
            if (!finite(path[i])) return null;
        }
        double previous = 1.0 / 16.0;
        double previousError = error(path, origin, inherited, previous);
        for (double time = 0.125; time <= maxTicks; time += 0.125) {
            double error = error(path, origin, inherited, time);
            if (error <= 0) {
                double low = previous, high = time;
                if (previousError <= 0) high = previous;
                else for (int i = 0; i < 36; i++) {
                    double middle = (low + high) * 0.5;
                    if (error(path, origin, inherited, middle) > 0) low = middle;
                    else high = middle;
                }
                Vec3 target = sample(path, high);
                Vec3 shot = target.subtract(origin).subtract(inherited.scale(high));
                if (!finite(shot) || shot.lengthSqr() < 1.0e-12) return null;
                return new Launch(shot.normalize(), target, high);
            }
            previous = time;
            previousError = error;
        }
        return null;
    }

    private static double error(Vec3[] path, Vec3 origin, Vec3 inherited, double ticks) {
        return sample(path, ticks).subtract(origin).subtract(inherited.scale(ticks)).length()
                - WIND_SPEED * ticks - HIT_MARGIN;
    }

    private static Vec3 sample(Vec3[] path, double ticks) {
        int whole = (int) ticks;
        if (whole >= path.length - 1) return path[path.length - 1];
        return path[whole].lerp(path[whole + 1], ticks - whole);
    }

    private static boolean validPhysics(double drag, double gravity) {
        return Double.isFinite(drag) && drag > 0 && drag <= 1
                && Double.isFinite(gravity) && gravity >= 0 && gravity <= 1;
    }

    public static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
