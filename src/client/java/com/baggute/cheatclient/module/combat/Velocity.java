// language: Java, file: Velocity.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Scales incoming knockback.
 *
 * <p>Entity motion packets, additive explosion impulses and local knockback
 * calculations are hooked independently. Settings are sampled at application,
 * including one chance roll for each incoming vector.
 *
 * <ul>
 *   <li>{@code LivingEntity#knockback} - the vanilla calculation, used for
 *       explosions and for anything the client resolves itself.</li>
 *   <li>{@code Entity#lerpMotion} - the path the server actually uses for the
 *       local player. A hurt knockback arrives as
 *       {@code ClientboundSetEntityMotionPacket} and lands in
 *       {@code ClientPacketListener#handleSetEntityMotion} as one
 *       {@code lerpMotion} call, so a knockback-only hook would never fire in
 *       normal play.</li>
 * </ul>
 *
 * <p>Setting names carry units and spaces because they are read in the GUI, not
 * in code.
 */
public class Velocity extends Module {

    private final DoubleSetting horizontal = new DoubleSetting(
            "Horizontal %", "Percent of X/Z knockback kept (0 = none)", 0.0, 0.0, 100.0);
    private final DoubleSetting vertical = new DoubleSetting(
            "Vertical %", "Percent of Y knockback kept (0 = none)", 0.0, 0.0, 100.0);
    private final DoubleSetting chance = new DoubleSetting(
            "Chance %", "Percent of hits the modifier applies to", 100.0, 0.0, 100.0);
    private final BoolSetting onlyWhenTargeting = new BoolSetting(
            "Only When Targeting", "Only while recently hit by or hitting an entity", false);

    /** Window in ticks for "recently in a fight". */
    private static final int TARGET_WINDOW = 100;

    public Velocity() {
        super("Velocity", "Scales knockback taken from hits and explosions.", Category.COMBAT);
        addSetting(horizontal);
        addSetting(vertical);
        addSetting(chance);
        addSetting(onlyWhenTargeting);
    }

    /**
     * One knockback vector with the two factors applied.
     *
     * <p>Both mixins route through this instead of multiplying inline, so there is
     * one expression of the rule and the functional test below exercises the same
     * code the game runs. Pure: it reads the arguments and writes nothing, which is
     * what lets {@code SelfTest} call it mid-session without touching live motion.
     *
     * <p>The horizontal factor applies to X and Z; vertical applies to Y only. A
     * single scale for all three axes would drop the player through the floor at
     * low horizontal values or throw them into the air at low vertical ones.
     */
    public static Vec3 scale(Vec3 motion, double horizontal, double vertical) {
        if (motion == null) return null;
        if (horizontal == 1.0 && vertical == 1.0) return motion;
        return new Vec3(motion.x * horizontal, motion.y * vertical, motion.z * horizontal);
    }

    /** Keep existing movement and scale only an additive impulse. */
    public static Vec3 scaleImpulse(Vec3 base, Vec3 result, double horizontal, double vertical) {
        return base.add(scale(result.subtract(base), horizontal, vertical));
    }

    /** Shared by Fabric and runtime hooks; settings take effect immediately. */
    public static Vec3 apply(Entity entity, Vec3 motion) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || entity != mc.player || com.baggute.cheatclient.UtilityClient.isUnloaded()
                || com.baggute.cheatclient.UtilityClient.moduleManager == null) return motion;
        var module = com.baggute.cheatclient.UtilityClient.moduleManager.getByName("Velocity");
        if (!(module instanceof Velocity velocity) || !velocity.isEnabled()) return motion;
        if (velocity.onlyWhenTargeting.get() && !recentlyFighting(mc.player)) return motion;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble(100.0) >= velocity.chance.get()) return motion;
        return scale(motion, velocity.horizontal.get() / 100.0, velocity.vertical.get() / 100.0);
    }

    /** True when the player took or dealt a hit inside {@link #TARGET_WINDOW} ticks. */
    private static boolean recentlyFighting(LivingEntity player) {
        int now = player.tickCount;
        int hurtBy = player.getLastHurtByMobTimestamp();
        if (hurtBy > 0 && now - hurtBy <= TARGET_WINDOW) return true;
        int hurt = player.getLastHurtMobTimestamp();
        return hurt > 0 && now - hurt <= TARGET_WINDOW;
    }
}
