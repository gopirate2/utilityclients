package com.baggute.cheatclient.util;

import net.minecraft.world.phys.Vec3;

/** Bounded low-arc solver for the game's gravity, drag, then movement steps. */
public final class ProjectileMath {
    private ProjectileMath() {}

    public static Vec3 launchTo(Vec3 displacement, Vec3 inherited, double speed,
                                double drag, double gravity, int maxTicks) {
        if (!finite(displacement) || !finite(inherited) || !Double.isFinite(speed) || speed <= 0
                || !Double.isFinite(drag) || drag <= 0 || drag > 1 || !Double.isFinite(gravity)
                || gravity < 0 || maxTicks <= 0 || maxTicks > 1200) return null;
        double previousTime = 0.001;
        double previousError = required(displacement, inherited, drag, gravity, previousTime).lengthSqr() - speed * speed;
        for (double time = 0.25; time <= maxTicks; time += 0.25) {
            double error = required(displacement, inherited, drag, gravity, time).lengthSqr() - speed * speed;
            if (previousError >= 0 && error <= 0) {
                double lo = previousTime, hi = time;
                for (int i = 0; i < 45; i++) {
                    double middle = (lo + hi) / 2;
                    if (required(displacement, inherited, drag, gravity, middle).lengthSqr() > speed * speed)
                        lo = middle;
                    else hi = middle;
                }
                return required(displacement, inherited, drag, gravity, (lo + hi) / 2);
            }
            previousTime = time;
            previousError = error;
        }
        return null;
    }

    private static Vec3 required(Vec3 displacement, Vec3 inherited, double drag, double gravity, double time) {
        int ticks = (int) Math.floor(time);
        double fraction = time - ticks;
        double sum, drop;
        if (drag == 1) {
            sum = time;
            drop = -gravity * (ticks * (ticks + 1.0) / 2 + fraction * (ticks + 1));
        } else {
            double power = Math.pow(drag, ticks);
            sum = drag * ((1 - power) / (1 - drag) + fraction * power);
            drop = -gravity * drag * (time - sum) / (1 - drag);
        }
        return new Vec3(displacement.x / sum - inherited.x,
                (displacement.y - drop) / sum - inherited.y, displacement.z / sum - inherited.z);
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
