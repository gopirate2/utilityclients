// language: Java, file: Freecam.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.BoolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public class Freecam extends Module {

    public static volatile Vec3 camPos = Vec3.ZERO;
    public static volatile float camYaw = 0f;
    public static volatile float camPitch = 0f;

    private final DoubleSetting speed;
    private final DoubleSetting verticalSpeed = new DoubleSetting("verticalSpeed", "Vertical movement speed multiplier", 1, 0.1, 4);
    private final BoolSetting followPitch = new BoolSetting("followPitch", "Forward/back movement follows camera pitch", false);

    private boolean forward, backward, left, right, up, down;
    private long lastFrameNs = 0L;
    private net.minecraft.client.player.LocalPlayer owner;

    private static net.minecraft.client.KeyMapping[] movementKeys(Minecraft mc) {
        return new net.minecraft.client.KeyMapping[]{mc.options.keyUp, mc.options.keyDown,
                mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump, mc.options.keyShift};
    }

    public boolean handlesKey(net.minecraft.client.input.KeyEvent event) {
        for (var mapping : movementKeys(Minecraft.getInstance())) if (mapping.matches(event)) return true;
        return false;
    }

    private void pollInput(Minecraft mc) {
        if (mc.player != owner) {
            owner = mc.player;
            lastFrameNs = 0;
            if (owner != null) {
                camPos = owner.getEyePosition();
                camYaw = owner.getYRot(); camPitch = owner.getXRot();
            }
        }
        boolean gameplay = owner != null && mc.level != null && mc.gui.screen() == null && !mc.isPaused();
        var keys = movementKeys(mc);
        boolean[] pressed = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) {
            pressed[i] = gameplay && com.baggute.cheatclient.util.PhysicalInput.down(mc, keys[i]);
            keys[i].setDown(false);
        }
        forward = pressed[0]; backward = pressed[1]; left = pressed[2]; right = pressed[3];
        up = pressed[4]; down = pressed[5];
        if (!gameplay || !com.baggute.cheatclient.util.PhysicalInput.focused(mc)) lastFrameNs = 0;
    }

    @Override
    public void onTick() { pollInput(Minecraft.getInstance()); }

    public Freecam() {
        super("Freecam", "Detach the camera and fly freely.", Category.RENDER);
        this.speed = new DoubleSetting("speed", "Camera speed", 14.0, 1.0, 60.0);
        addSetting(speed);
        addSetting(verticalSpeed);
        addSetting(followPitch);
    }

    public void setForward(boolean v)  { forward = v; }
    public void setBackward(boolean v) { backward = v; }
    public void setLeft(boolean v)     { left = v; }
    public void setRight(boolean v)    { right = v; }
    public void setUp(boolean v)       { up = v; }
    public void setDown(boolean v)     { down = v; }

    @Override
    public void onRenderFrame(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        pollInput(mc);
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || mc.isPaused()) return;

        long now = System.nanoTime();
        if (lastFrameNs == 0L) { lastFrameNs = now; return; }
        double dt = (now - lastFrameNs) / 1_000_000_000.0;
        lastFrameNs = now;
        if (dt > 0.1) dt = 0.1;
        if (dt <= 0.0) return;

        double fwd  = (this.forward ? 1 : 0) - (this.backward ? 1 : 0);
        double str  = (this.left ? 1 : 0) - (this.right ? 1 : 0);
        double vert = (this.up ? 1 : 0) - (this.down ? 1 : 0);

        if (Math.abs(fwd) < 0.001 && Math.abs(str) < 0.001 && Math.abs(vert) < 0.001) return;

        // Use the freecam's yaw for movement direction, not the player's.
        double yawRad = Math.toRadians(camYaw);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        double pitch = followPitch.get() ? Math.toRadians(camPitch) : 0;
        double dx = str * cos - fwd * sin * Math.cos(pitch);
        double dz = fwd * cos + str * sin * Math.cos(pitch);
        double dy = vert - fwd * Math.sin(pitch);

        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0.001) { dx /= len; dy /= len; dz /= len; }

        double dist = speed.get() * dt;
        camPos = camPos.add(dx * dist, dy * dist * verticalSpeed.get(), dz * dist);
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        owner = mc.player;
        camPos = mc.player.getEyePosition();
        camYaw = mc.player.getYRot();
        camPitch = mc.player.getXRot();
        forward = backward = left = right = up = down = false;
        lastFrameNs = 0L;
        // Releases are swallowed by KeyboardMixin while freecam is active, so any
        // key held at enable time would stay latched and keep the body walking.
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        UtilityClient.LOGGER.info("[Freecam] enabled at {}", camPos);
    }

    @Override
    protected void onDisable() {
        forward = backward = left = right = up = down = false;
        lastFrameNs = 0;
        owner = null;
        Minecraft mc = Minecraft.getInstance();
        boolean gameplay = mc.player != null && mc.gui.screen() == null && !mc.isPaused();
        for (var mapping : movementKeys(mc)) mapping.setDown(gameplay
                && com.baggute.cheatclient.util.PhysicalInput.down(mc, mapping));
        UtilityClient.LOGGER.info("[Freecam] disabled");
    }
}
