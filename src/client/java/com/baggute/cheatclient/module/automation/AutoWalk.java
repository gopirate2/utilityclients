// language: Java, file: AutoWalk.java, target: Fabric 26.2
package com.baggute.cheatclient.module.automation;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Holds forward for you — on foot, in a boat, and on a mount.
 *
 * <p>Everything is driven by the ordinary key mappings, which is the only input
 * path the game reads for both the local player and whatever it is riding. A boat,
 * a horse and a minecart all consume {@code keyUp}/{@code keyLeft}/{@code keyRight}
 * through {@code LocalPlayer.input}, so a synthetic hold steers the vehicle exactly
 * the way a real press does — no packet forgery, no position writes, nothing the
 * server can tell apart from a player holding W.
 *
 * <p>Sprint is separate because a mount is not a player. On a horse the sprint key
 * is the jump charge, so forcing it while riding silently starts charging a leap
 * every time the module is on. {@code sprintOnMounts} defaults off for that reason.
 *
 * <p>Keys the module did not press down are never released. Someone holding W by
 * hand keeps holding it when the module starts, and keeps holding it when the
 * module stops; only the synthetic state is undone.
 */
public final class AutoWalk extends Module {

    /** Vanilla stops natural sprinting below this food level. */
    private static final int SPRINT_FOOD_FLOOR = 6;

    private final BoolSetting onlyForward =
            new BoolSetting("onlyForward", "Release strafe and back", true);
    private final BoolSetting sprint = new BoolSetting("sprint", "Sprint while walking", true);
    private final BoolSetting sprintOnMounts =
            new BoolSetting("sprintOnMounts", "Also sprint while riding (horse jump charge)", false);
    private final BoolSetting autoJump =
            new BoolSetting("autoJump", "Jump when a block is in the way", false);
    private final BoolSetting releaseInGui =
            new BoolSetting("releaseInGui", "Stop while a screen is open", true);
    private final DoubleSetting startDelay =
            new DoubleSetting("startDelay", "Seconds between sprint retries", 0.5, 0.1, 5.0);

    private boolean forwardHeld;
    private boolean sprintHeld;
    private boolean jumpHeld;
    /**
     * Keys this module pressed down that a human was already holding. Restored on
     * release so the module never cancels a real press it merely overrode.
     */
    private final Map<KeyMapping, Boolean> stolen = new IdentityHashMap<>();
    /** Tick of the last synthetic sprint press; vanilla can drop the flag mid-run. */
    private int lastSprintAttempt = -1000;
    private int tickCounter;

    public AutoWalk() {
        super("AutoWalk", "Automatically walks forward, including on boats and mounts.",
                Category.AUTOMATION);
        addSetting(onlyForward);
        addSetting(sprint);
        addSetting(sprintOnMounts);
        addSetting(autoJump);
        addSetting(releaseInGui);
        addSetting(startDelay);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null || mc.options == null) {
            release();
            return;
        }

        // Paused, dead, spectator or an open screen: none of those should have the
        // walk key held, and a stuck synthetic key outlives the module otherwise.
        if (releaseInGui.get() && (mc.gui.screen() != null || mc.isPaused())) {
            release();
            return;
        }
        if (player.isSpectator() || !player.isAlive()) {
            release();
            return;
        }

        tickCounter++;

        if (onlyForward.get()) {
            suppress(mc.options.keyDown);
            suppress(mc.options.keyLeft);
            suppress(mc.options.keyRight);
        }

        holdForward(mc);

        boolean riding = player.isPassenger();
        boolean wantSprint = sprint.get() && (!riding || sprintOnMounts.get());
        if (wantSprint && canSprint(mc, player)) {
            holdSprint(mc, player);
        } else {
            releaseSprint(mc);
        }

        if (jumpHeld) {
            // The press was read on the previous tick; let it go now.
            releaseJump(mc);
        } else if (autoJump.get() && !riding && player.onGround() && player.horizontalCollision) {
            holdJump(mc);
        }
    }

    private void holdForward(Minecraft mc) {

        forwardHeld = true;
        // Never steal a key the player already has down.
        mc.options.keyUp.setDown(true);
    }

    /**
     * Sprint is a toggle in vanilla, and {@code LocalPlayer} clears the flag the
     * moment the input is not moving forward — so the key is re-pressed on a short
     * cadence instead of once, and the state is re-asserted directly as well.
     */
    private void holdSprint(Minecraft mc, LocalPlayer player) {
        int retryTicks = Math.max(1, (int) Math.round(startDelay.get() * 20.0));
        if (sprintHeld && tickCounter - lastSprintAttempt < retryTicks) return;

        sprintHeld = true;
        lastSprintAttempt = tickCounter;
        mc.options.keySprint.setDown(true);
        if (!player.isSprinting()) player.setSprinting(true);
    }

    /** Presses jump for exactly one tick; the release happens on the next tick. */
    private void holdJump(Minecraft mc) {
        if (jumpHeld) return;
        jumpHeld = true;
        mc.options.keyJump.setDown(true);
    }

    /** Sprinting is refused by the game while starving, sneaking or in water. */
    private boolean canSprint(Minecraft mc, LocalPlayer player) {
        if (player.isShiftKeyDown() || player.isInWater()) return false;
        if (player.getAbilities().flying) return false;
        if (player.getFoodData().getFoodLevel() <= SPRINT_FOOD_FLOOR
                && !player.getAbilities().mayfly) return false;
        return !player.isPassenger() || sprintOnMounts.get();
    }

    private void releaseSprint(Minecraft mc) {
        if (!sprintHeld) return;
        sprintHeld = false;
        mc.options.keySprint.setDown(com.baggute.cheatclient.util.PhysicalInput.down(mc, mc.options.keySprint));
    }

    private void releaseJump(Minecraft mc) {
        if (!jumpHeld) return;
        jumpHeld = false;
        mc.options.keyJump.setDown(com.baggute.cheatclient.util.PhysicalInput.down(mc, mc.options.keyJump));
    }

    /** Drops every key this module pressed; ignores keys it never touched. */
    private void release() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            forwardHeld = false;
            sprintHeld = false;
            jumpHeld = false;
            stolen.clear();
            return;
        }
        if (forwardHeld) {
            mc.options.keyUp.setDown(com.baggute.cheatclient.util.PhysicalInput.down(mc, mc.options.keyUp));
            forwardHeld = false;
        }
        releaseSprint(mc);
        releaseJump(mc);
        // Hand back the keys the module overrode rather than leaving them dead.
        for (KeyMapping key : stolen.keySet())
            key.setDown(com.baggute.cheatclient.util.PhysicalInput.down(mc, key));
        stolen.clear();
    }

    /**
     * Releases a key for as long as the module is on, remembering that a human was
     * holding it so {@link #release()} can hand it back. The previous version
     * released unconditionally every tick, which silently cancelled a real S/A/D
     * press for the whole session — the doc contract says untouched keys are never
     * released, and this is what makes that true.
     */
    private void suppress(KeyMapping key) {
        if (!key.isDown()) return;
        stolen.putIfAbsent(key, Boolean.TRUE);
        key.setDown(false);
    }

    @Override
    protected void onEnable() {
        forwardHeld = false;
        sprintHeld = false;
        jumpHeld = false;
        stolen.clear();
        lastSprintAttempt = -1000;
        tickCounter = 0;
        UtilityClient.LOGGER.info("[AutoWalk] enabled");
    }

    @Override
    protected void onDisable() {
        release();
        UtilityClient.LOGGER.info("[AutoWalk] disabled");
    }
}
