// language: Java, file: AimAssist.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class AimAssist extends Module {

    private final StringSetting entityFilter;
    private final DoubleSetting range;
    private final DoubleSetting fov;
    private final DoubleSetting speed;
    private final DoubleSetting smoothness;
    private final DoubleSetting stopAngle;
    private final DoubleSetting humanize;
    private final DoubleSetting missChance;
    private final IntSetting predict;
    private final IntSetting pickupDelay;
    private final IntSetting missDurationMs;
    private final BoolSetting aimVertical;
    private final BoolSetting onlyWeapon;
    private final BoolSetting requireLeftClick;

    private final Random rng = new Random();
    private Entity lockedTarget = null;
    private long lastTargetChangeMs = 0L;

    private Vec3 currentOffset = Vec3.ZERO;
    private long lastOffsetChangeMs = 0L;

    // miss state
    private boolean inMiss = false;
    private long missStartMs = 0L;
    private Vec3 missTargetPoint = Vec3.ZERO;

    // throttle miss rolls so we don't re-roll every frame
    private long lastMissRollMs = 0L;

    public AimAssist() {
        super("AimAssist", "Smoothly aims your view toward the nearest target.", Category.COMBAT);

        this.entityFilter     = new StringSetting("entityFilter", "Enabled entity IDs",
                "minecraft:player,minecraft:zombie,minecraft:skeleton,minecraft:creeper,minecraft:enderman");
        this.range            = new DoubleSetting("range", "Target range (blocks)", 4.5, 1.0, 6.0);
        this.fov              = new DoubleSetting("fov", "FOV angle to lock within (deg)", 90.0, 5.0, 180.0);
        this.speed            = new DoubleSetting("speed", "Aim speed (0-1)", 0.35, 0.05, 1.0);
        this.smoothness       = new DoubleSetting("smoothness", "Rotation smoothness", 1.0, 0.1, 5.0);
        this.stopAngle        = new DoubleSetting("stopAngle", "Stop within N deg (deg)", 1.5, 0.0, 15.0);
        this.humanize         = new DoubleSetting("humanize", "Random aim offset (blocks)", 0.05, 0.0, 0.5);
        this.missChance       = new DoubleSetting("missChance", "Look away chance (%)", 0.0, 0.0, 50.0);
        this.predict          = new IntSetting("predict", "Target position prediction (ticks)", 0, 0, 20);
        this.pickupDelay      = new IntSetting("pickupDelay", "Delay after target switch (ms)", 80, 0, 500);
        this.missDurationMs   = new IntSetting("missDurationMs", "Look-away duration (ms)", 300, 100, 1500);
        this.aimVertical      = new BoolSetting("aimVertical", "Adjust pitch (vertical)", true);
        this.onlyWeapon       = new BoolSetting("onlyWeapon", "Only aim with sword or axe", true);
        this.requireLeftClick = new BoolSetting("requireLeftClick", "Only aim while left mouse held", false);

        addSetting(entityFilter);
        addSetting(range);
        addSetting(fov);
        addSetting(speed);
        addSetting(smoothness);
        addSetting(stopAngle);
        addSetting(humanize);
        addSetting(missChance);
        addSetting(predict);
        addSetting(pickupDelay);
        addSetting(missDurationMs);
        addSetting(aimVertical);
        addSetting(onlyWeapon);
        addSetting(requireLeftClick);
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

    // ---- 20 Hz: target selection and rotation ----

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || mc.isPaused()) {
            lockedTarget = null;
            return;
        }

        if (lockedTarget != null) {
            if (!lockedTarget.isAlive()) lockedTarget = null;
            else if (lockedTarget.distanceToSqr(mc.player.getEyePosition())
                    > range.get() * range.get()) lockedTarget = null;
        }

        Entity newTarget = pickTarget(mc);
        if (newTarget != lockedTarget) {
            lockedTarget = newTarget;
            if (newTarget != null) lastTargetChangeMs = System.currentTimeMillis();
        }

        aimTick(mc);
    }

    // ---- rotation, once per client tick ----

    /**
     * One rotation step per tick.
     *
     * <p>Rotating from the render frame meant a fresh rotation packet every frame -
     * sixty a second where the tick clock runs twenty. That is a signature on its own,
     * and it also defeated the travel budget: the same arc covered in three frames is
     * covered in three ticks only if the step is per tick. The camera still renders
     * smoothly because {@code yRotO} interpolation does the in-between.
     */
    private void aimTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (lockedTarget == null || !lockedTarget.isAlive()) return;
        if (requireLeftClick.get() && !mc.options.keyAttack.isDown()) return;

        if (onlyWeapon.get()) {
            String item = mc.player.getMainHandItem().getItem().toString();
            // "pickaxe" also contains "axe", so match the registry suffixes.
            if (!item.contains("_sword") && !item.contains("_axe")) return;
        }

        if (System.currentTimeMillis() - lastTargetChangeMs < pickupDelay.get()) return;

        Vec3 eye = mc.player.getEyePosition(1.0f);
        long now = System.currentTimeMillis();

        // ---- miss state machine ----
        if (inMiss) {
            if (now - missStartMs >= missDurationMs.get()) {
                inMiss = false;
                lastMissRollMs = now;
            } else {
                // aim at the miss point instead of the target
                AimPipeline.rotateTo(mc, missTargetPoint, speed.get(), smoothness.get(),
                        aimVertical.get(), stopAngle.get());
                return;
            }
        }

        // ---- roll for a miss ----
        double missPct = missChance.get();
        if (missPct > 0.0 && !inMiss && now - lastMissRollMs > 500) {
            // only roll when we're actually aiming at the target (not mid-recovery)
            if (rng.nextDouble() * 100.0 < missPct) {
                inMiss = true;
                missStartMs = now;
                missTargetPoint = pickMissPoint(mc, eye);
                return;
            }
            lastMissRollMs = now;
        }

        // ---- normal aim: closest point on target's hitbox ----
        Vec3 targetPoint = getPredictedClosestPoint(lockedTarget, eye).add(humanizeOffset());
        AimPipeline.rotateTo(mc, targetPoint, speed.get(), smoothness.get(),
                aimVertical.get(), stopAngle.get());
    }

    // ---- helpers ----

    /** Closest point on the target's AABB (predicted forward if `predict` > 0). */
    private Vec3 getPredictedClosestPoint(Entity e, Vec3 from) {
        AABB box = e.getBoundingBox();
        int ticks = predict.get();
        if (ticks > 0) {
            Vec3 vel = e.getDeltaMovement();
            box = box.move(vel.x * ticks, vel.y * ticks, vel.z * ticks);
        }
        // closest point on the AABB to the eye position
        double cx = Mth.clamp(from.x, box.minX, box.maxX);
        double cy = Mth.clamp(from.y, box.minY, box.maxY);
        double cz = Mth.clamp(from.z, box.minZ, box.maxZ);
        return new Vec3(cx, cy, cz);
    }

    /** Picks a plausible look-away point: a spot on the ground or nearby terrain. */
    private Vec3 pickMissPoint(Minecraft mc, Vec3 eye) {
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double distance = 3.0 + rng.nextDouble() * 6.0;
        double dx = Math.cos(angle) * distance;
        double dz = Math.sin(angle) * distance;
        Vec3 candidate = new Vec3(eye.x + dx, eye.y - 0.5 - rng.nextDouble() * 1.5, eye.z + dz);
        // keep the point inside the world
        int minY = mc.level.getMinY();
        if (candidate.y < minY + 1) candidate = new Vec3(candidate.x, minY + 1, candidate.z);
        return candidate;
    }

    private Vec3 humanizeOffset() {
        double h = humanize.get();
        if (h <= 0) return Vec3.ZERO;
        long now = System.currentTimeMillis();
        if (now - lastOffsetChangeMs > 400) {
            currentOffset = new Vec3(
                    (rng.nextDouble() * 2.0 - 1.0) * h,
                    (rng.nextDouble() * 2.0 - 1.0) * h,
                    (rng.nextDouble() * 2.0 - 1.0) * h
            );
            lastOffsetChangeMs = now;
        }
        return currentOffset;
    }

    private Entity pickTarget(Minecraft mc) {
        Set<String> filter = getEntityFilter();
        double rangeSq = range.get() * range.get();
        double fovCos = Math.cos(Math.toRadians(fov.get() / 2.0));
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);

        Entity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (com.baggute.cheatclient.friends.FriendsManager.isFriend(entity)) continue;
            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;
            if (!(entity instanceof LivingEntity)) continue;
            if (entity.distanceToSqr(eye) > rangeSq) continue;
            if (!mc.player.hasLineOfSight(entity)) continue;

            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (id == null || !filter.contains(id.toString())) continue;

            Vec3 targetPos = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
            Vec3 toTarget = targetPos.subtract(eye).normalize();
            double dot = toTarget.dot(look);
            if (dot < fovCos) continue;

            double score = entity.distanceToSqr(eye) - dot * 100.0;
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    @Override
    protected void onEnable() {
        lockedTarget = null;
        currentOffset = Vec3.ZERO;
        lastOffsetChangeMs = 0L;
        inMiss = false;
        missStartMs = 0L;
        lastMissRollMs = 0L;
        UtilityClient.LOGGER.info("[AimAssist] enabled");
    }

    @Override
    protected void onDisable() {
        UtilityClient.LOGGER.info("[AimAssist] disabled");
    }
}
