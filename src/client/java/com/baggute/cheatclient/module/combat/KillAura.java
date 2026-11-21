// language: Java, file: KillAura.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.StringSetting;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Attack-while-aimed, arbitrated through {@link Stealth}.
 *
 * <p>The rotation and the hit are two separate ticks. A tick that turns toward the
 * target returns without attacking; the next tick, if the view already covers the
 * target, sends the single attack packet the arbiter allows. That is the shape a
 * player produces - look, then click - and it is the shape the server checks for.
 */
public class KillAura extends Module {

    /** Degrees of view error that still counts as "aimed". */
    private static final float AIM_TOLERANCE = 3.0f;

    private final StringSetting entityFilter;
    private final DoubleSetting range;
    private final BoolSetting autoBlock;
    private final BoolSetting noSwing;
    private final DoubleSetting aimSpeed;
    private boolean blocking;

    public KillAura() {
        super("KillAura", "Silently attacks entities around you.", Category.COMBAT);

        this.entityFilter = new StringSetting("entityFilter", "Entity types to attack",
                "minecraft:player,minecraft:zombie,minecraft:skeleton,minecraft:creeper,minecraft:enderman");
        // The ceiling is the server's own entity interaction range plus the arbiter
        // buffer; the slider can only lower it. The old 4.5-6.0 default asked the
        // server to accept hits it drops on arrival.
        this.range = new DoubleSetting("range", "Attack range", 3.0, 1.0, 3.5);
        this.autoBlock = new BoolSetting("autoBlock", "Auto-shield when not attacking", false);
        this.noSwing = new BoolSetting("noSwing", "Do not swing hand", false);
        this.aimSpeed = new DoubleSetting("aimSpeed", "Silent aim speed", 0.45, 0.1, 1.0);

        addSetting(entityFilter);
        addSetting(range);
        addSetting(autoBlock);
        addSetting(noSwing);
        addSetting(aimSpeed);
    }

    public Set<String> getEntityFilter() {
        Set<String> out = new HashSet<>();
        for (String p : entityFilter.get().split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            // World unload / disconnect while blocking: the KeyMapping would stay
            // down into the next world and hold right-click from the moment of join.
            releaseBlock(mc);
            return;
        }
        // A screen or the pause menu must stop both the attack and the key hold:
        // the module was swinging and shielding while the game was paused.
        if (mc.gui.screen() != null || mc.isPaused()) {
            releaseBlock(mc);
            return;
        }

        Entity target = findTarget(mc);

        // Only drive right-click when an offhand shield can actually block: otherwise
        // this eats/throws whatever is in the main hand, and the key can stay down.
        boolean canBlock = mc.player.getOffhandItem().is(Items.SHIELD);
        if (autoBlock.get() && canBlock) {
            boolean want = target == null || !Stealth.strengthReady(mc.player);
            if (want != blocking) {
                blocking = want;
                mc.options.keyUse.setDown(want);
            }
        } else if (blocking) {
            blocking = false;
            mc.options.keyUse.setDown(false);
        }

        if (target == null) return;

        Vec3 point = AimPipeline.closestPoint(target, mc.player.getEyePosition(1.0f), 0);
        Vec3 delta = point.subtract(mc.player.getEyePosition(1.0f));
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        Stealth.attackDirected(mc, target, yaw, pitch, !noSwing.get(), .95f);
    }

    /** True when the view is already within {@link #AIM_TOLERANCE} of the point. */
    private boolean aimedAt(Minecraft mc, Vec3 point) {
        if (mc.player == null) return false;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 to = point.subtract(eye);
        double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(to.y, horizontal));

        float yawError = Math.abs(Mth.wrapDegrees(wantYaw - mc.player.getYRot()));
        float pitchError = Math.abs(wantPitch - mc.player.getXRot());
        return yawError <= AIM_TOLERANCE && pitchError <= AIM_TOLERANCE;
    }

    private Entity findTarget(Minecraft mc) {
        Entity best = null;
        // Runtime limit, not the slider: the server's own interaction range decides.
        double limit = Math.min(range.get(), Stealth.reachLimit(mc.player));
        double bestDist = limit * limit;
        Set<String> filter = getEntityFilter();
        if (filter.isEmpty()) return null;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (com.baggute.cheatclient.friends.FriendsManager.isFriend(e)) continue;
            if (e == mc.player || !(e instanceof LivingEntity) || !e.isAlive()) continue;

            // One key lookup per entity, compared as "namespace:path" - the same
            // string the filter setting is written in.
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (key == null || !filter.contains(key.toString())) continue;

            // Eye-to-box, so a tall entity whose centre is out of range but whose
            // feet are not still reads as reachable the way the server measures it.
            if (!Stealth.inEntityReach(mc.player, e)) continue;

            double d = Math.pow(Stealth.eyeToHitbox(mc.player.getEyePosition(), e.getBoundingBox()), 2);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    protected void onEnable() {
        UtilityClient.LOGGER.info("[KillAura] enabled");
    }

    /** Drops the synthetic right-click hold, leaving a human press alone. */
    private void releaseBlock(Minecraft mc) {
        if (blocking && mc.options != null) mc.options.keyUse.setDown(false);
        blocking = false;
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        // Only release the key if this module was the one holding it down; a human
        // holding right-click should not lose their shield when the module stops.
        releaseBlock(mc);
        UtilityClient.LOGGER.info("[KillAura] disabled");
    }
}
