// language: Java, file: Speed.java, target: Fabric 26.2
package com.baggute.cheatclient.module.movement;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public class Speed extends Module {

    /**
     * Vanilla sprint speed in blocks per second.
     *
     * <p>{@code 5.612} is the measured value for an unmodified player sprinting on
     * flat ground at 20 ticks per second. The module's ceiling is derived from it
     * rather than set by hand, so the cap tracks the server's own expectation.
     */
    private static final double VANILLA_SPRINT_BPS = 5.612;

    /** Allowed overshoot on the server's expected movement rate. */
    private static final double MAX_OVERSHOOT = 1.05;

    /** Hard ceiling in blocks per second: vanilla sprint plus five percent. */
    public static final double SPEED_CEILING = VANILLA_SPRINT_BPS * MAX_OVERSHOOT;

    private final DoubleSetting targetSpeed;
    private final DoubleSetting acceleration;    // 0.1 slow → 1.0 instant
    private final BoolSetting onlySprinting;
    private final BoolSetting onlyOnGround;

    private double currentSpeed = 0.0;           // blocks per tick
    private double lastDirX = 0.0, lastDirZ = 0.0;

    public Speed() {
        super("Speed", "Sets your movement to a fixed speed in the direction you move.", Category.MOVEMENT);
        // The slider tops out at the derived ceiling. Above vanilla sprint by more than
        // five percent the server's own movement prediction stops matching the reported
        // displacement, which is a flag before it is a kick.
        this.targetSpeed   = new DoubleSetting("targetSpeed", "Target speed (blocks/sec)",
                Math.min(9.0, SPEED_CEILING), 5.6, SPEED_CEILING);
        this.acceleration  = new DoubleSetting("acceleration", "Acceleration (0.1 slow - 1.0 instant)", 0.7, 0.1, 1.0);
        this.onlySprinting = new BoolSetting("onlySprinting", "Only apply while sprinting", true);
        this.onlyOnGround  = new BoolSetting("onlyOnGround", "Only apply on ground", false);
        addSetting(targetSpeed);
        addSetting(acceleration);
        addSetting(onlySprinting);
        addSetting(onlyOnGround);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (onlySprinting.get() && !mc.player.isSprinting()) {
            currentSpeed = 0;
            return;
        }
        if (onlyOnGround.get() && !mc.player.onGround()) {
            currentSpeed = 0;
            return;
        }

        // Movement direction comes from the movement keys (vanilla behaviour); the
        // module only replaces the resulting speed. Strafing strafes, backing up
        // backs up, and looking around does not steer you.
        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe  = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double inputMag = Math.sqrt(forward * forward + strafe * strafe);

        double targetPerTick = inputMag > 0.001
                ? Math.min(targetSpeed.get(), SPEED_CEILING) / 20.0
                : 0.0;

        // Snappy lerp — acceleration slider decides how fast we get there.
        // 0.7 default: ~70% of the gap closes per tick, so full stop in ~3 ticks.
        double a = acceleration.get();
        currentSpeed += (targetPerTick - currentSpeed) * a;

        if (Math.abs(currentSpeed) < 0.005) {
            if (targetPerTick == 0) { currentSpeed = 0; return; }
        }

        if (inputMag > 0.001) {
            forward /= inputMag;
            strafe /= inputMag;
            float yawRad = (float) Math.toRadians(mc.player.getYRot());
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);
            lastDirX = strafe * cos - forward * sin;
            lastDirZ = forward * cos + strafe * sin;
        }

        Vec3 vel = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(lastDirX * currentSpeed, vel.y, lastDirZ * currentSpeed);
    }

    @Override
    protected void onEnable() {
        currentSpeed = 0;
        lastDirX = 0; lastDirZ = 0;
        UtilityClient.LOGGER.info("[Speed] enabled");
    }
    @Override
    protected void onDisable() {
        currentSpeed = 0;
        lastDirX = 0; lastDirZ = 0;
        UtilityClient.LOGGER.info("[Speed] disabled");
    }
}