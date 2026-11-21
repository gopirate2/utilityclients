// language: Java, file: AutoClicker.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.util.Stealth;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

/** Clicks at most once per tick while the configured button is held. Entity clicks use gameMode.attack; mining requires a real non-air, non-liquid block hit within reach. */
public final class AutoClicker extends Module {

    private final IntSetting cps = new IntSetting("cps", "Clicks per second", 12, 1, 30);
    private final IntSetting jitter =
            new IntSetting("jitter", "Random extra delay (ms)", 0, 0, 40);
    private final BoolSetting playersOnly =
            new BoolSetting("playersOnly", "Only click players", false);
    private final IntSetting button =
            new IntSetting("button", "Mouse button: 0 = left (attack), 1 = right (use)", 0, 0, 1);
    private final BoolSetting resetCooldown =
            new BoolSetting("resetCooldown", "Reset the attack meter after each click", true);
    private final BoolSetting swing = new BoolSetting("swing", "Swing hand", true);

    private long nextClickMs;
    private int lastClicks;
    private long windowStart;

    public AutoClicker() {
        super("AutoClicker", "Performs real clicks at a fixed CPS.", Category.COMBAT);
        addSetting(cps);
        addSetting(jitter);
        addSetting(playersOnly);
        addSetting(button);
        addSetting(resetCooldown);
        addSetting(swing);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.level == null) return;

        if (mc.gui.screen() != null || mc.isPaused()) {
            nextClickMs = 0;
            return;
        }
        // No button, no clicks. The timer resets so the first click after a hold
        // lands immediately instead of waiting out the previous interval.
        if (!holding(mc)) {
            nextClickMs = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (nextClickMs == 0) nextClickMs = now;

        int rate = Math.max(1, cps.get());
        // Ticks arrive at 20 Hz, so a fast rate has to fire more than once per tick
        // to be honoured at all. Four is the ceiling a single tick can carry.
        if (now >= nextClickMs) {
            click(mc);
            nextClickMs = now + intervalMs(rate);
        }
        // A stall (alt-tab, chunk load) must not bank a burst of clicks.
        if (now - nextClickMs > 500L) nextClickMs = now + intervalMs(rate);
    }

    /** True while the configured mouse button is held. */
    private boolean holding(Minecraft mc) {
        return button.get() == 0
                ? mc.options.keyAttack.isDown()
                : mc.options.keyUse.isDown();
    }

    private long intervalMs(int rate) {
        long base = 1000L / rate;
        int spread = jitter.get();
        if (spread <= 0) return base;
        long offset = java.util.concurrent.ThreadLocalRandom.current().nextInt(spread + 1);
        return Math.max(1L, base + offset);
    }

    /** One click, through the same paths a physical mouse press uses. */
    private void click(Minecraft mc) {
        // A click that lands on the same tick as a rotation packet is the pattern the
        // combat check keys on, and the interaction resolves against the view the
        // server held before the turn arrived. Skip this tick; the next one is clean.
        if (Stealth.rotatedThisTick() || Stealth.swappedThisTick()) return;

        if (button.get() == 1) {
            clickRight(mc);
            return;
        }

        if (AntiMiss.shouldBlockClick(mc)) return;
        Entity target = aimedTarget(mc);

        if (target != null) {
            if (!Stealth.attackUncharged(mc, target, swing.get())) return;
            if (resetCooldown.get()) mc.player.resetAttackStrengthTicker();
            countClick();
            return;
        }

        // No entity in front: this is a mining click, so drive the destroy path
        // the same way a held button does. Minecraft.startAttack() is private and
        // only reaches these two calls anyway.
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) {
            if (!validMiningHit(mc, hit) || !Stealth.canInteract(mc)) return;
            // startDestroyBlock returns false for a block already being broken, and
            // that is exactly when the progress has to be continued instead.
            if (!mc.gameMode.startDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
                mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection());
            }
            Stealth.markInteraction();
        }
        if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        countClick();
    }

    /** Right click: use the held item, or use it on whatever is aimed at. */
    private void clickRight(Minecraft mc) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && !mc.player.getMainHandItem().isEmpty()) {
            com.baggute.cheatclient.util.Stealth.useItemOn(mc, InteractionHand.MAIN_HAND, hit);
        } else if (mc.hitResult instanceof EntityHitResult entityHit) {
            com.baggute.cheatclient.util.Stealth.interact(mc, entityHit.getEntity(), entityHit,
                    InteractionHand.MAIN_HAND);
        } else {
            com.baggute.cheatclient.util.Stealth.useItem(mc, InteractionHand.MAIN_HAND);
        }
        if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        countClick();
    }
    public static boolean validMiningHit(Minecraft mc, net.minecraft.world.phys.BlockHitResult hit) {
        if (mc == null || mc.player == null || mc.level == null || hit == null
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !Stealth.inBlockReach(mc.player, hit.getBlockPos())) return false;
        var state = mc.level.getBlockState(hit.getBlockPos());
        return !state.isAir() && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
    }

    private void countClick() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= 1000L) {
            windowStart = now;
            lastClicks = 0;
        }
        lastClicks++;
    }

    /** Clicks performed in the current one-second window; the HUD reads this. */
    public int clicksThisSecond() {
        return lastClicks;
    }

    private Entity aimedTarget(Minecraft mc) {
        if (!(mc.hitResult instanceof EntityHitResult hit)) return null;
        Entity aimed = hit.getEntity();
        if (!(aimed instanceof LivingEntity living) || !living.isAlive()) return null;
        if (playersOnly.get() && !(aimed instanceof Player)) return null;
        // The arbiter's limit, not a flat 3.0: it tracks interaction range plus the
        // latency buffer that belongs with it.
        if (!Stealth.inEntityReach(mc.player, aimed)) return null;
        return aimed;
    }

    @Override
    protected void onEnable() {
        nextClickMs = 0;
        lastClicks = 0;
        windowStart = System.currentTimeMillis();
    }
}
