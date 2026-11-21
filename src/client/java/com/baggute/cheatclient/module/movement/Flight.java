// language: Java, file: Flight.java, target: Fabric 26.2
package com.baggute.cheatclient.module.movement;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;

public class Flight extends Module {

    /** Vanilla abilities.flyingSpeed and the speed it actually produces. */
    private static final double VANILLA_FLYING_SPEED = 0.05;
    private static final double VANILLA_FLY_BLOCKS_PER_SECOND = 10.9;

    /**
     * Ceiling on the flight rate: vanilla flight plus five percent.
     *
     * <p>The server predicts a flying player's displacement from the same attribute
     * the client uses. Anything past five percent over the vanilla rate stops matching
     * that prediction, which is a movement flag before it is a kick - so the slider is
     * capped rather than the value being clamped at write time, and the config cannot
     * hold a number the wire refuses to send.
     */
    private static final double MAX_OVERSHOOT = 1.05;
    public static final double FLY_CEILING = VANILLA_FLY_BLOCKS_PER_SECOND * MAX_OVERSHOOT;

    private final DoubleSetting speed;
    private final BoolSetting verticalFly;
    private final BoolSetting antiKick;
    private com.baggute.cheatclient.util.AbilityLease abilityLease;

    private void restoreAbilities(Minecraft mc) {
        if (abilityLease != null) abilityLease.restore(mc.player);
        abilityLease = null;
    }

    private void applyAbilities(Minecraft mc) {
        var abilities = mc.player.getAbilities();
        if (abilityLease == null || !abilityLease.ownedBy(mc.player, abilities))
            abilityLease = new com.baggute.cheatclient.util.AbilityLease(mc.player, abilities);
        abilityLease.apply((float) (VANILLA_FLYING_SPEED * Math.min(speed.get(), FLY_CEILING)
                / VANILLA_FLY_BLOCKS_PER_SECOND));
    }

    /**
     * Anti-kick sink: 0.001 blocks once a second, not per tick.
     *
     * <p>Servers flag a player whose Y never changes; a slow, steady descent looks
     * like ordinary gravity drift. Per-tick motion was 40x this and still got
     * flagged, so the step is timed directly rather than folded into velocity.
     */
    private static final double SINK_PER_SECOND = 0.001;
    private static final long SINK_INTERVAL_MS = 1000L;
    private long lastSinkAt;

    public Flight() {
        super("Flight", "Fly freely through the world.", Category.MOVEMENT);
        this.speed       = new DoubleSetting("speed", "Speed (blocks/sec)", FLY_CEILING, 1.0, FLY_CEILING);
        this.verticalFly = new BoolSetting("verticalFly", "Space/Shift for up/down", true);
        this.antiKick    = new BoolSetting("antiKick", "Tiny downward motion to avoid kick", true);
        addSetting(speed);
        addSetting(verticalFly);
        addSetting(antiKick);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { restoreAbilities(mc); return; }

        boolean creative = mc.player.isCreative() || mc.player.isSpectator();
        if (creative) { applyAbilities(mc); return; }
        restoreAbilities(mc);

        // strafe: LEFT = +1, RIGHT = -1 (matches vanilla Entity.getInputVector input.x)
        double strafe = 0, forward = 0, vertical = 0;
        if (mc.options.keyLeft.isDown())   strafe  += 1;
        if (mc.options.keyRight.isDown())  strafe  -= 1;
        if (mc.options.keyUp.isDown())     forward += 1;
        if (mc.options.keyDown.isDown())   forward -= 1;
        if (verticalFly.get()) {
            if (mc.options.keyJump.isDown())  vertical += 1;
            if (mc.options.keyShift.isDown()) vertical -= 1;
        }

        // Normalize horizontal input so diagonal isn't faster
        double horizMag = Math.sqrt(forward * forward + strafe * strafe);
        if (horizMag > 0.001) {
            forward /= horizMag;
            strafe  /= horizMag;
        }

        double perTick = Math.min(speed.get(), FLY_CEILING) / 20.0;

        // Vanilla formula from Entity.getInputVector:
        //   x' = strafe * cos - forward * sin
        //   z' = forward * cos + strafe * sin
        float yawRad = (float) Math.toRadians(mc.player.getYRot());
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        double dx = (strafe * cos - forward * sin) * perTick;
        double dz = (forward * cos + strafe * sin) * perTick;

        boolean hovering = verticalFly.get() && Math.abs(vertical) < 0.001;
        double dy;
        if (hovering) {
            // Hover: the next movement tick subtracts gravity before moving, so
            // writing 0 here made the player sink. Cancel that gravity instead.
            dy = mc.player.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY);
        } else {
            dy = vertical * perTick;
        }

        // The sink is applied as a position step, not as velocity: velocity is
        // overwritten every tick, so a per-tick decrement turns into a constant
        // drift. Moving the player 0.001 blocks once a second is what "slowly
        // descending like gravity" actually looks like to a server.
        if (antiKick.get() && (hovering || Math.abs(vertical) < 0.001)) {
            long now = System.currentTimeMillis();
            if (now - lastSinkAt >= SINK_INTERVAL_MS) {
                lastSinkAt = now;
                mc.player.setPos(mc.player.getX(),
                        mc.player.getY() - SINK_PER_SECOND, mc.player.getZ());
            }
        } else {
            lastSinkAt = System.currentTimeMillis();
        }

        mc.player.setDeltaMovement(dx, dy, dz);
        mc.player.fallDistance = 0.0f;
        mc.player.setOnGround(false);
        mc.player.hurtMarked = true;
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        abilityLease = null;
        lastSinkAt = System.currentTimeMillis();
        if (mc.player != null && (mc.player.isCreative() || mc.player.isSpectator())) applyAbilities(mc);
        UtilityClient.LOGGER.info("[Flight] enabled");
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        restoreAbilities(mc);
        UtilityClient.LOGGER.info("[Flight] disabled");
    }
}