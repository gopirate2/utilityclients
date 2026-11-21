// language: Java, file: Triggerbot.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.StringSetting;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class Triggerbot extends Module {

    private final StringSetting entityFilter;
    private final DoubleSetting missChance;
    private final DoubleSetting rangeMin;
    private final DoubleSetting rangeMax;
    private final IntSetting cpsCap;
    private final IntSetting tickDelayMin;
    private final IntSetting tickDelayMax;
    private final IntSetting jitterMs;
    private final DoubleSetting attackCooldown;
    private final BoolSetting onlyWeapon;
    private final BoolSetting swing;
    private final BoolSetting ignoreInvisible;
    private final BoolSetting wtap;
    private final BoolSetting smartCrits;

    private final Random rng = new Random();
    private int ticksSinceLastAttack = 0;
    private int currentDelay = 2;
    private long lastAttackMs = 0L;

    /** Ticks left before sprint is put back after a W-tap; -1 when idle. */
    private int wTapRestoreTicks = -1;
    private net.minecraft.client.player.LocalPlayer sprintOwner;

    private double lastYVel = 0.0;
    private int knockbackTicks = 0;

    public Triggerbot() {
        super("Triggerbot", "Attacks the entity under your crosshair.", Category.COMBAT);

        this.entityFilter   = new StringSetting("entityFilter", "Enabled entity IDs",
                "minecraft:player,minecraft:zombie,minecraft:skeleton,minecraft:creeper,minecraft:enderman");
        this.missChance     = new DoubleSetting("missChance", "Chance to swing air instead of hit (%)", 0.0, 0.0, 50.0);
        this.rangeMin       = new DoubleSetting("rangeMin", "Minimum attack range", 0.0, 0.0, 3.0);
        // Ceiling tracks the server's own entity interaction range; the slider only
        // lowers it. The old 3.0 flat cap let a 3.0 hit through on a player whose
        // interaction range was already 3.0 plus a ping spike.
        this.rangeMax       = new DoubleSetting("rangeMax", "Maximum attack range", 3.0, 0.0, 3.5);
        this.cpsCap         = new IntSetting("cpsCap", "Max attacks per second (0 = uncapped)", 8, 0, 20);
        this.tickDelayMin   = new IntSetting("tickDelayMin", "Min ticks between attacks", 1, 0, 20);
        this.tickDelayMax   = new IntSetting("tickDelayMax", "Max ticks between attacks", 4, 0, 20);
        this.jitterMs       = new IntSetting("jitterMs", "Random timing jitter (+/- ms)", 30, 0, 200);
        this.attackCooldown = new DoubleSetting("attackCooldown", "Min attack cooldown (0-1)", 1.0, 0.0, 1.0);
        this.onlyWeapon     = new BoolSetting("onlyWeapon", "Only attack with sword or axe", true);
        this.swing          = new BoolSetting("swing", "Swing hand on attack", true);
        this.ignoreInvisible= new BoolSetting("ignoreInvisible", "Skip invisible entities", true);
        this.wtap           = new BoolSetting("wtap", "Reset sprint for one tick after a successful hit", false);
        this.smartCrits     = new BoolSetting("smartCrits", "After knockback, wait until falling to crit", false);

        addSetting(entityFilter);
        addSetting(missChance);
        addSetting(rangeMin);
        addSetting(rangeMax);
        addSetting(cpsCap);
        addSetting(tickDelayMin);
        addSetting(tickDelayMax);
        addSetting(jitterMs);
        addSetting(attackCooldown);
        addSetting(onlyWeapon);
        addSetting(swing);
        addSetting(ignoreInvisible);
        addSetting(wtap);
        addSetting(smartCrits);
    }

    public Set<String> getEntityFilter() {
        Set<String> out = new HashSet<>();
        for (String part : entityFilter.get().split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public StringSetting getEntityFilterSetting() { return entityFilter; }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (sprintOwner != null && sprintOwner != mc.player) { wTapRestoreTicks = -1; sprintOwner = null; }
        if (mc.gui.screen() != null || mc.isPaused()) { restoreSprint(mc); return; }

        // ---- knockback tracking ----
        double yVel = mc.player.getDeltaMovement().y;
        if (yVel > 0.35 && lastYVel <= 0.35) {
            knockbackTicks = 40;
        }
        if (knockbackTicks > 0) knockbackTicks--;
        lastYVel = yVel;

        // ---- W-tap restore ----
        // The stop is sent on the hit tick and the start one tick later: sprinting
        // has to be off when the attack packet is resolved, or the server sees a
        // sprint hit and the knockback is the strong one we were resetting away
        // from. Re-arming in the same tick would put the flag back before the
        // server read it.
        if (wTapRestoreTicks >= 0) {
            if (--wTapRestoreTicks <= 0) {
                wTapRestoreTicks = -1;
                restoreSprint(mc);
            }
            return;
        }

        // ---- timing gates ----
        if (ticksSinceLastAttack < currentDelay) {
            ticksSinceLastAttack++;
            return;
        }

        if (cpsCap.get() > 0) {
            long now = System.currentTimeMillis();
            long minInterval = 1000L / cpsCap.get();
            long jitter = rng.nextInt(jitterMs.get() * 2 + 1) - jitterMs.get();
            if (now - lastAttackMs < minInterval + jitter) return;
        }

        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult ehr)) return;

        Entity target = ehr.getEntity();
        if (target == null || target == mc.player || !target.isAlive()) return;

        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if (id == null || !getEntityFilter().contains(id.toString())) return;

        if (ignoreInvisible.get() && target.isInvisible()) return;

        // ---- range gate (closest distance from eye to hitbox) ----
        double distance = eyeToHitboxDistance(mc.player.getEyePosition(), target.getBoundingBox());
        double rMin = rangeMin.get();
        // Server-side ceiling, not the slider: the player's own interaction range plus
        // the arbiter buffer, and the slider can only pull it in.
        double rMax = Math.min(rangeMax.get(), Stealth.reachLimit(mc.player));
        if (rMax <= rMin) rMax = rMin + 0.01; // enforce max > min
        if (distance < rMin || distance > rMax) return;

        // 0.95 floor, not the slider. Anything below is resolved server-side with a
        // reduced multiplier and reads as an early swing. The slider can raise the
        // bar further, never lower it.
        float floor = (float) attackCooldown.get();
        if (Stealth.charge(mc.player) < floor) return;

        // A rotation packet and an interaction packet in one tick is the pattern the
        // combat check keys on. Defer the hit to the tick after the turn.
        if (Stealth.rotatedThisTick()) return;

        if (onlyWeapon.get()) {
            ItemStack held = mc.player.getMainHandItem();
            // Tag test, not a class-name substring: "pickaxe" contains "axe", so the
            // old string match let a pickaxe pass as a weapon.
            if (!held.is(ItemTags.SWORDS) && !held.is(ItemTags.AXES)) return;
        }

        // ---- smart crits gate ----
        if (smartCrits.get() && knockbackTicks > 0 && !isFallingForCrit(mc)) {
            return;
        }

        // ---- miss chance: swing animation, no attack packet ----
        if (missChance.get() > 0.0 && rng.nextDouble() * 100.0 < missChance.get()) {
            if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            // no gameMode.attack call — server sees the swing, no hit registers
            ticksSinceLastAttack = 0;
            currentDelay = randomDelay();
            lastAttackMs = System.currentTimeMillis();
            return;
        }

        doAttack(mc, target);
    }

    private void doAttack(Minecraft mc, Entity target) {
        if (mc.player == null) return;

        boolean wasSprinting = mc.player.isSprinting();

        // One attack packet per tick, arbitrated. A second attempt in the same tick
        // becomes a swing instead of a second interaction.
        if (!Stealth.attack(mc, target, swing.get(), (float) attackCooldown.get())) return;
        if (wtap.get() && wasSprinting && mc.player.connection != null) {
            sprintOwner = mc.player; sendSprint(mc, false); wTapRestoreTicks = 1;
        }

        ticksSinceLastAttack = 0;
        currentDelay = randomDelay();
        lastAttackMs = System.currentTimeMillis();
    }

    /** Sprint flag plus the matching command packet; both halves are required. */
    private void sendSprint(Minecraft mc, boolean sprinting) {
        Stealth.setSprinting(mc, sprinting);
    }
    private void restoreSprint(Minecraft mc) {
        if (sprintOwner == mc.player && mc.player != null && mc.options != null && mc.gui.screen() == null
                && mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown() && !mc.player.isUsingItem()) sendSprint(mc, true);
        sprintOwner = null; wTapRestoreTicks = -1;
    }

    /** Euclidean distance from a point to the closest point on an AABB. */
    private static double eyeToHitboxDistance(Vec3 eye, AABB box) {
        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean isFallingForCrit(Minecraft mc) {
        return AimPipeline.isFallingForCrit(mc);
    }

    private int randomDelay() {
        int lo = Math.min(tickDelayMin.get(), tickDelayMax.get());
        int hi = Math.max(tickDelayMin.get(), tickDelayMax.get());
        if (lo == hi) return lo;
        return lo + rng.nextInt(hi - lo + 1);
    }

    @Override
    protected void onEnable() {
        ticksSinceLastAttack = 0;
        currentDelay = randomDelay();
        lastAttackMs = 0L;
        wTapRestoreTicks = -1;
        knockbackTicks = 0;
        lastYVel = 0.0;
        UtilityClient.LOGGER.info("[Triggerbot] enabled");
    }

    @Override
    protected void onDisable() {
        // A W-tap left half-finished would leave the player unsprinted with no
        // restore coming, because the tick loop stops with the module.
        Minecraft mc = Minecraft.getInstance();
        restoreSprint(mc);
        wTapRestoreTicks = -1;
        UtilityClient.LOGGER.info("[Triggerbot] disabled");
    }
}
