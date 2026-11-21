// language: Java, file: ShieldBreaker.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Random;

/**
 * Shield pressure with a mace, one packet class per tick.
 *
 * <p>The cycle is a state machine, not a burst of calls:
 *
 * <pre>
 *   EQUIP   carried-item packet for the mace slot        (Stealth.swapSlot)
 *   ATTACK  one attack packet, no rotation, no swap      (Stealth.attack)
 *   SPAM    attack, swing, attack, swing ...             (Stealth.attack / Stealth.swing)
 *   RETURN  carried-item packet for the slot held before (Stealth.swapSlot)
 * </pre>
 *
 * <p>A swap never shares a tick with an attack: {@code Stealth.swapSlot} refuses a
 * tick that already attacked or rotated, and {@code Stealth.attack} refuses
 * a tick that already swapped or rotated. The ordering is enforced by the arbiter,
 * not by this file remembering to check.
 *
 * <p><b>Charge gate.</b> The vanilla attack bar is skipped deliberately. Shield
 * damage is a durability race, not a damage roll, so the module spends the tick on a
 * packet instead of waiting for 0.95. Every other arbiter guarantee still applies:
 * one attack packet per tick, target inside {@code entityInteractionRange() + 0.5},
 * no rotation on the tick. Between real attacks the module sends
 * {@code LocalPlayer.swing} - the same packet a click on air produces - so the
 * animation and the click rate match the packet stream.
 *
 * <p><b>Mace, not axe.</b> A mace does not carry the axe's shield-disable debuff, so
 * the shield falls to raw hit pressure and knockback rather than a cooldown. The
 * module is built around that: a burst of fast hits, then the return swap.
 */
public class ShieldBreaker extends Module {

    /** TASK 1: a target this close to the crosshair counts as aimed at. */
    private static final double CROSSHAIR_RANGE = 4.0;

    /** Ticks a single phase may spend waiting on the arbiter before the cycle drops. */
    private static final int PHASE_TIMEOUT = 12;

    /** Floor between two "missing mace" log lines, so a misconfigured run cannot spam. */
    private static final long MISSING_LOG_INTERVAL_MS = 4000L;

    private enum State { IDLE, EQUIP, ATTACK, SPAM, RETURN }

    private final BoolSetting onclick;
    private final DoubleSetting range;
    private final DoubleSetting missChance;
    private final BoolSetting spamMace;
    private final IntSetting attacksPerBurst;
    private final BoolSetting autoSwap;
    private final IntSetting swapToSlot;
    private final IntSetting swapBackDelay;
    private final IntSetting delayBeforeBreaking;
    private final BoolSetting shieldStun;

    private final Random rng = new Random();

    private State state = State.IDLE;
    private int stateTicks;
    private int attacksRemaining;
    /** True when the next spam tick is an animation tick rather than a hit. */
    private boolean swingNext;
    private int returnWait;

    private int maceSlot = -1;
    private int previousSlot = -1;

    private Player lastTarget;
    private boolean targetWasBlocking;
    private long firstBlockDetectedMs;
    private long lastAttackMs;
    private long lastMissingLogMs;
    private long lastStunPacketMs;
    private int stunTicksRemaining;

    public ShieldBreaker() {
        super("ShieldBreaker", "Mace pressure on a blocking target, one packet class per tick.", Category.COMBAT);

        this.onclick             = new BoolSetting("onclick", "Only act while left-click held", false);
        this.range               = new DoubleSetting("range", "Search range when not aiming", 3.5, 1.0, 4.0);
        this.missChance          = new DoubleSetting("missChance", "Chance to skip a cycle (%)", 0.0, 0.0, 100.0);
        this.spamMace            = new BoolSetting("spamMace", "Keep swinging until the shield drops", true);
        this.attacksPerBurst     = new IntSetting("attacksPerBurst", "Real attacks per burst", 4, 1, 20);
        this.autoSwap            = new BoolSetting("autoSwap", "Auto-swap to the mace from the hotbar", true);
        this.swapToSlot          = new IntSetting("swapToSlot", "Fallback slot to return to (1-9)", 1, 1, 9);
        this.swapBackDelay       = new IntSetting("swapBackDelay", "Ticks before the return swap", 2, 0, 20);
        this.delayBeforeBreaking = new IntSetting("delayBeforeBreaking", "Delay before breaking (ms)", 150, 0, 2000);
        this.shieldStun          = new BoolSetting("shieldStun", "Follow up after the shield drops", true);

        addSetting(onclick);
        addSetting(range);
        addSetting(missChance);
        addSetting(spamMace);
        addSetting(attacksPerBurst);
        addSetting(autoSwap);
        addSetting(swapToSlot);
        addSetting(swapBackDelay);
        addSetting(delayBeforeBreaking);
        addSetting(shieldStun);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            state = State.IDLE;
            return;
        }
        if (mc.gui.screen() != null || mc.isPaused()) return;

        if (onclick.get() && !mc.options.keyAttack.isDown() && state != State.RETURN) {
            if (state != State.RETURN) abort();
            return;
        }

        stateTicks++;
        switch (state) {
            case IDLE -> tickIdle(mc);
            case EQUIP -> tickEquip(mc);
            case ATTACK -> tickAttack(mc);
            case SPAM -> tickSpam(mc);
            case RETURN -> tickReturn(mc);
        }
    }

    // ---- phases ----

    private void tickIdle(Minecraft mc) {
        Player target = findTarget(mc);
        if (target == null) {
            lastTarget = null;
            targetWasBlocking = false;
            firstBlockDetectedMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        boolean blocking = target.isBlocking();

        if (target != lastTarget) {
            lastTarget = target;
            targetWasBlocking = blocking;
            firstBlockDetectedMs = blocking ? now : 0L;
            lastAttackMs = 0L;
            stunTicksRemaining = 0;
        }

        // Shield came down inside the window after a hit: that is the follow-up.
        if (targetWasBlocking && !blocking && lastAttackMs > 0 && now - lastAttackMs < 400) {
            stunTicksRemaining = 5;
        }
        targetWasBlocking = blocking;

        if (!blocking) {
            if (shieldStun.get() && stunTicksRemaining > 0) {
                stunTicksRemaining--;
                doStun(mc, target);
            }
            return;
        }

        if (firstBlockDetectedMs == 0L) firstBlockDetectedMs = now;
        if (now - firstBlockDetectedMs < delayBeforeBreaking.get()) return;
        if (now - lastAttackMs < 250) return;
        if (missChance.get() > 0.0 && rng.nextDouble() * 100.0 < missChance.get()) return;

        int slot = findMaceSlot(mc);
        if (slot < 0) {
            logMissingMace();
            return;
        }
        maceSlot = slot;

        if (isMaceHeld(mc)) {
            // Nothing to carry: the mace is already the held item.
            previousSlot = -1;
            enter(State.ATTACK);
            return;
        }
        if (!autoSwap.get()) return;
        enter(State.EQUIP);
    }

    private void tickEquip(Minecraft mc) {
        if (isMaceHeld(mc)) { enter(State.ATTACK); return; }
        if (stateTicks > PHASE_TIMEOUT) { abort(); return; }
        // A rotation or an interaction on this tick means the swap waits for the next.
        if (Stealth.swapBlocked() || Stealth.attackedThisTick()) return;

        if (maceSlot < 0) maceSlot = findMaceSlot(mc);
        if (maceSlot < 0) { logMissingMace(); abort(); return; }

        if (previousSlot < 0) previousSlot = mc.player.getInventory().getSelectedSlot();
        if (Stealth.swapSlot(mc, maceSlot)) enter(State.ATTACK);
    }

    private void tickAttack(Minecraft mc) {
        if (!isMaceHeld(mc)) { abort(); return; }
        if (stateTicks > PHASE_TIMEOUT) { abort(); return; }

        Player target = resolveTarget(mc);
        // Out of range or no longer a legal target: drop the cycle, do not keep
        // swinging at where the player used to be.
        if (target == null || !Stealth.inEntityReach(mc.player, target)) { abort(); return; }

        // False here is a one-tick deferral (a rotation or swap already spent this
        // tick), not a rejection: the reach and validity checks above are the reject.
        if (!Stealth.attack(mc, target, true)) return;

        lastAttackMs = System.currentTimeMillis();
        attacksRemaining = Math.max(0, attacksPerBurst.get() - 1);
        swingNext = true;
        enter(spamMace.get() && attacksRemaining > 0 ? State.SPAM : State.RETURN);
        if (state == State.RETURN) returnWait = swapBackDelay.get();
    }

    private void tickSpam(Minecraft mc) {
        Player target = resolveTarget(mc);
        if (target == null || !Stealth.inEntityReach(mc.player, target)) { endBurst(); return; }
        if (stateTicks > PHASE_TIMEOUT * 2) { endBurst(); return; }

        // Alternate: the animation tick keeps the click rate and the packet stream
        // in the same shape a fast player produces.
        if (swingNext) {
            Stealth.swing(mc);
            swingNext = false;
            return;
        }

        if (!Stealth.attackUncharged(mc, target, true)) return;

        lastAttackMs = System.currentTimeMillis();
        swingNext = true;
        if (--attacksRemaining <= 0) endBurst();
    }

    private void tickReturn(Minecraft mc) {
        if (returnWait > 0) { returnWait--; return; }
        if (stateTicks > PHASE_TIMEOUT * 2) {
            previousSlot = -1;
            maceSlot = -1;
            enter(State.IDLE);
            return;
        }
        if (Stealth.swapBlocked() || Stealth.attackedThisTick()) return;

        int back = previousSlot >= 0
                ? previousSlot
                : Math.max(0, Math.min(8, swapToSlot.get() - 1));
        previousSlot = -1;
        maceSlot = -1;

        if (back == mc.player.getInventory().getSelectedSlot()) { enter(State.IDLE); return; }

        if (Stealth.swapSlot(mc, back)) {
            enter(State.IDLE);
        } else {
            // Arbiter refused: keep the target slot and retry on the next tick.
            previousSlot = back;
        }
    }

    // ---- helpers ----

    private void enter(State next) {
        state = next;
        stateTicks = 0;
    }

    /** Drops the cycle but still puts the hotbar back. */
    private void abort() {
        attacksRemaining = 0;
        swingNext = false;
        if (previousSlot >= 0) {
            returnWait = 0;
            enter(State.RETURN);
        } else {
            enter(State.IDLE);
        }
    }

    private void endBurst() {
        attacksRemaining = 0;
        swingNext = false;
        returnWait = swapBackDelay.get();
        enter(State.RETURN);
    }

    /** Crosshair player first, then the nearest player holding a shield or a mace. */
    private Player findTarget(Minecraft mc) {
        Player aimed = crosshairPlayer(mc);
        if (aimed != null && isHolder(aimed) && Stealth.inEntityReach(mc.player, aimed)) return aimed;

        double limit = Math.min(range.get(), Stealth.reachLimit(mc.player));
        Player best = null;
        double bestDistance = limit * limit;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (com.baggute.cheatclient.friends.FriendsManager.isFriend(entity)) continue;
            if (!(entity instanceof Player p) || p == mc.player || !p.isAlive()) continue;
            if (!isHolder(p)) continue;
            if (!Stealth.inEntityReach(mc.player, p)) continue;
            double distance = p.distanceToSqr(mc.player);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = p;
            }
        }
        return best;
    }

    /**
     * Target used mid-cycle: the crosshair player when it is still a legal target,
     * otherwise the same scan. Never a player who is not carrying a shield or a mace,
     * which is what stops the module spinning at someone with an empty hotbar.
     */
    private Player resolveTarget(Minecraft mc) {
        Player aimed = crosshairPlayer(mc);
        if (aimed != null && isHolder(aimed) && Stealth.inEntityReach(mc.player, aimed)) return aimed;
        return findTarget(mc);
    }

    private Player crosshairPlayer(Minecraft mc) {
        if (!(mc.hitResult instanceof EntityHitResult hit)) return null;
        if (!(hit.getEntity() instanceof Player p) || p == mc.player || !p.isAlive()
                || com.baggute.cheatclient.friends.FriendsManager.isFriend(p)) return null;
        return p.distanceToSqr(mc.player) <= CROSSHAIR_RANGE * CROSSHAIR_RANGE ? p : null;
    }

    /** True when the player is blocking or carrying a shield or a mace. */
    private boolean isHolder(Player p) {
        if (p.isBlocking()) return true;
        return isMace(p.getMainHandItem()) || isShield(p.getMainHandItem())
                || isMace(p.getOffhandItem()) || isShield(p.getOffhandItem());
    }

    private boolean isMace(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.MACE);
    }

    private boolean isShield(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.SHIELD);
    }

    private boolean isMaceHeld(Minecraft mc) {
        return isMace(mc.player.getMainHandItem());
    }

    private int findMaceSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (isMace(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private void logMissingMace() {
        long now = System.currentTimeMillis();
        if (now - lastMissingLogMs < MISSING_LOG_INTERVAL_MS) return;
        lastMissingLogMs = now;
        UtilityClient.LOGGER.info("[ShieldBreaker] Missing Mace");
    }

    /**
     * Follow-up once the shield is down. A sprint-start packet before the hit is the
     * sprint reset a player does by hand; both packets are ordinary and the attack
     * still goes through the charged arbiter path, not the spam path.
     */
    private void doStun(Minecraft mc, Player target) {
        if (mc.player.connection == null) return;

        long now = System.currentTimeMillis();
        if (now - lastStunPacketMs < 100) return;
        if (!Stealth.strengthReady(mc.player)) return;
        if (Stealth.swapBlocked() || Stealth.attackedThisTick()) return;

        lastStunPacketMs = now;
        mc.player.setSprinting(true);
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        Stealth.attack(mc, target);
    }

    @Override
    protected void onEnable() {
        state = State.IDLE;
        stateTicks = 0;
        attacksRemaining = 0;
        swingNext = false;
        returnWait = 0;
        maceSlot = -1;
        previousSlot = -1;
        lastTarget = null;
        targetWasBlocking = false;
        firstBlockDetectedMs = 0L;
        lastAttackMs = 0L;
        stunTicksRemaining = 0;
        UtilityClient.LOGGER.info("[ShieldBreaker] enabled");
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && previousSlot >= 0
                && mc.player.getInventory().getSelectedSlot() != previousSlot) {
            Stealth.restoreSlot(mc, previousSlot);
        }
        state = State.IDLE;
        attacksRemaining = 0;
        swingNext = false;
        returnWait = 0;
        maceSlot = -1;
        previousSlot = -1;
        UtilityClient.LOGGER.info("[ShieldBreaker] disabled");
    }
}
