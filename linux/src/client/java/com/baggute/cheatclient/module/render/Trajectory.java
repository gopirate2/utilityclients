// language: Java, file: Trajectory.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;

/**
 * Predicts where the held projectile will land using the real arrow physics, and
 * reports it in the trajectory HUD widget.
 */
public class Trajectory extends Module {

    private final IntSetting ticks;
    private final BoolSetting showPitch;

    public Trajectory() {
        super("Trajectory", "Shows where the held projectile will land.", Category.RENDER);

        this.ticks = new IntSetting("ticks", "Simulation length in ticks", 100, 20, 200);
        this.showPitch = new BoolSetting("showPitch", "Show the required pitch", true);

        addSetting(ticks);
        addSetting(showPitch);
    }

    public int ticks() { return ticks.get(); }
    public boolean showPitch() { return showPitch.get(); }

    /**
     * Landing distance and drop for a projectile launched at the given pitch.
     *
     * <p>Minecraft's rotation is not an elevation angle: negative pitch means
     * looking up, positive means looking down. The launch velocity therefore has
     * to negate the sine term, otherwise an upward shot is simulated downwards
     * (and vice versa) and the reported range is mirrored.
     */
    public static double[] predict(float pitchDeg, double speed, int maxTicks) {
        double pitch = Math.toRadians(pitchDeg);
        double vx = Math.cos(pitch) * speed;
        double vy = -Math.sin(pitch) * speed;
        double x = 0, y = 0, peak = 0;

        for (int tick = 0; tick < maxTicks; tick++) {
            x += vx;
            y += vy;
            vy -= 0.05;
            vx *= 0.99;
            vy *= 0.99;
            peak = Math.max(peak, y);
            if (y <= 0 && tick > 0) break;
        }
        return new double[]{x, y, peak};
    }
}
