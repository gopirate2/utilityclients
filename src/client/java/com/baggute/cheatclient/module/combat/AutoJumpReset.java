// language: Java, file: AutoJumpReset.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/** Detects damage with knockback before movement and calls the vanilla grounded jump. A cooldown prevents repeated resets; physical key bindings remain untouched. */
public class AutoJumpReset extends Module {

    private final IntSetting jumpDelay;
    private final IntSetting holdTicks;
    private final BoolSetting onlyOnGround;
    private final BoolSetting requireKnockback;
    private final DoubleSetting minDamage;
    private final DoubleSetting chance;

    private final Random rng = new Random();

    private double lastHealth = -1.0;
    private int pendingJumpTicks = 0;
    private int holdRemainingTicks = 0;
    private boolean syntheticJump;
    private boolean processedBeforeTick;

    public AutoJumpReset() {
        super("AutoJumpReset", "Jumps on the tick you get hit to cancel knockback.", Category.COMBAT);

        this.jumpDelay       = new IntSetting("jumpDelay", "Ticks to wait after the hit", 0, 0, 4);
        this.holdTicks       = new IntSetting("holdTicks", "Ticks before another jump reset", 1, 1, 4);
        this.onlyOnGround    = new BoolSetting("onlyOnGround", "Only jump when standing on ground", true);
        this.requireKnockback = new BoolSetting("requireKnockback", "Only jump on real knockback", true);
        this.minDamage       = new DoubleSetting("minDamage", "Minimum damage taken to react", 0.0, 0.0, 20.0);
        this.chance          = new DoubleSetting("chance", "Chance to react (%)", 100.0, 0.0, 100.0);

        addSetting(jumpDelay);
        addSetting(holdTicks);
        addSetting(onlyOnGround);
        addSetting(requireKnockback);
        addSetting(minDamage);
        addSetting(chance);
    }

    @Override
    public void onTick() {
        if (processedBeforeTick) { processedBeforeTick = false; return; }
        advance();
    }
    public void beforeTick() {
        advance(); processedBeforeTick = true;
    }
    private void advance() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            releaseJump(mc);
            lastHealth = -1.0;
            pendingJumpTicks = 0;
            return;
        }

        // Count the press down across whole ticks: the key has to still be down
        // when the input pass reads it, so the release happens one tick after the
        // last held tick, not on the first.
        if (mc.gui.screen() != null || mc.isPaused() || !com.baggute.cheatclient.util.PhysicalInput.focused(mc) || !mc.player.isAlive() || mc.player.isSleeping()) {
            releaseJump(mc); pendingJumpTicks = 0;
            lastHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            return;
        }
        if (syntheticJump && --holdRemainingTicks <= 0) releaseJump(mc);

        if (pendingJumpTicks > 0 && --pendingJumpTicks == 0) {
            if (!onlyOnGround.get() || mc.player.onGround()) pressJump(mc);
        }

        double health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (lastHealth < 0.0) {
            lastHealth = health;
            return;
        }

        double lost = lastHealth - health;
        lastHealth = health;

        if (mc.gui.screen() != null || mc.isPaused()) return;
        if (lost <= 0.0 || lost < minDamage.get()) return;
        if (onlyOnGround.get() && !mc.player.onGround()) return;
        if (!mc.player.isAlive() || mc.player.isSleeping()) return;
        if (requireKnockback.get() && !hasKnockback(mc)) return;
        if (chance.get() < 100.0 && rng.nextDouble() * 100.0 >= chance.get()) return;
        if (pendingJumpTicks > 0 || syntheticJump) return;

        pendingJumpTicks = jumpDelay.get();
        if (pendingJumpTicks == 0) pressJump(mc);
    }

    private boolean hasKnockback(Minecraft mc) {
        Vec3 velocity = mc.player.getDeltaMovement();
        return Math.hypot(velocity.x, velocity.z) > 0.05;
    }

    private void pressJump(Minecraft mc) {
        // Never fight the human: if the key is already down, vanilla jumps anyway.
        if (mc.options == null || mc.options.keyJump.isDown() || !mc.player.onGround()
                || mc.player.isInWater() || mc.player.onClimbable() || mc.player.isPassenger()) return;
        mc.player.jumpFromGround();
        syntheticJump = true;
        holdRemainingTicks = Math.max(1, holdTicks.get());
        UtilityClient.LOGGER.debug("[AutoJumpReset] jump reset fired");
    }

    private void releaseJump(Minecraft mc) {
        syntheticJump = false;
        holdRemainingTicks = 0;
    }

    @Override
    protected void onEnable() {
        processedBeforeTick = false;
        lastHealth = -1.0;
        pendingJumpTicks = 0;
        holdRemainingTicks = 0;
        syntheticJump = false;
        UtilityClient.LOGGER.info("[AutoJumpReset] enabled");
    }

    @Override
    protected void onDisable() {
        processedBeforeTick = false;
        releaseJump(Minecraft.getInstance());
        pendingJumpTicks = 0;
        lastHealth = -1.0;
        UtilityClient.LOGGER.info("[AutoJumpReset] disabled");
    }
}
