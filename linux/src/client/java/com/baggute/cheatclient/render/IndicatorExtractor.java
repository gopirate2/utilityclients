package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.render.ArmorIndicators;
import com.baggute.cheatclient.module.render.HealthIndicators;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class IndicatorExtractor {

    private IndicatorExtractor() {}

    public static void emit(LevelRenderState state) {
        if (UtilityClient.moduleManager == null) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        HealthIndicators h = (HealthIndicators) UtilityClient.moduleManager.getByName("HealthIndicators");
        ArmorIndicators a = (ArmorIndicators) UtilityClient.moduleManager.getByName("ArmorIndicators");
        Vec3 cam = state.cameraRenderState.pos;

        // distanceToSqr() is squared, so the limit has to be squared as well.
        double maxRangeSq = maxRangeSq(h, a);

        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player) continue;
            double distSq = e.distanceToSqr(cam);
            if (distSq > maxRangeSq) continue;

            double y = le.getY() + le.getBbHeight() + 0.15;

            if (h != null && h.isEnabled() && distSq <= h.getRange() * h.getRange()) {
                hearts(new Vec3(le.getX(), y + .6, le.getZ()), state.cameraRenderState.orientation,
                        le.getHealth(), le.getMaxHealth(), le.getAbsorptionAmount(), h.getScale(), h.getColor());
            }

            if (a != null && a.isEnabled() && le instanceof Player p && distSq <= a.getRange() * a.getRange()) {
                icons(new Vec3(le.getX(), y + .2, le.getZ()), state.cameraRenderState.orientation,
                        p.getArmorValue(), 20, 0, a.getScale(), a.getColor(), ARMOR);
            }
        }
    }

    private static double maxRangeSq(HealthIndicators h, ArmorIndicators a) {
        double range = 0;
        if (h != null && h.isEnabled()) range = h.getRange();
        if (a != null && a.isEnabled()) range = Math.max(range, a.getRange());
        return range * range;
    }

    // Minecraft-style pixel heart silhouette, empty border plus full/half colored interiors.
    private static final String[] HEART = {".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."};
    private static final String[] ARMOR = {"##...##", "#######", ".#####.", ".#####.", ".#####.", ".#####."};
    public static void hearts(Vec3 origin, org.joml.Quaternionf orientation, float health, float maximum,
                              float absorption, double scale, int color) {
        icons(origin, orientation, health, maximum, absorption, scale, color, HEART);
    }
    private static void icons(Vec3 origin, org.joml.Quaternionf orientation, float health, float maximum,
                              float absorption, double scale, int color, String[] shape) {
        if (!Float.isFinite(health) || !Float.isFinite(maximum) || !Float.isFinite(absorption)
                || !Double.isFinite(scale) || scale <= 0) return;
        int normal = Math.max(1, Math.min(100, (int) Math.ceil(maximum / 2)));
        int extra = Math.max(0, Math.min(20, (int) Math.ceil(absorption / 2)));
        double pixel = .028 * scale;
        var q = orientation == null ? new org.joml.Quaternionf() : orientation;
        org.joml.Vector3f right = new org.joml.Vector3f(1,0,0).rotate(q);
        org.joml.Vector3f up = new org.joml.Vector3f(0,1,0).rotate(q);
        Vec3 r = new Vec3(right.x, right.y, right.z), u = new Vec3(up.x, up.y, up.z);
        for (int index = 0; index < normal + extra; index++) {
            int row = index / 10, column = index % 10;
            int rowCount = Math.min(10, normal + extra - row * 10);
            float remaining = index < normal ? health - index * 2 : absorption - (index - normal) * 2;
            int fill = remaining >= 2 ? 2 : remaining > 0 ? 1 : 0;
            int tint = index < normal ? color : 0xFFFFCC32;
            double left = (column * 8 - (rowCount * 8 - 1) / 2.0) * pixel;
            for (int py = 0; py < shape.length; py++) for (int px = 0; px < shape[py].length(); px++) {
                if (shape[py].charAt(px) == '.') continue;
                boolean edge = py == 0 || py == shape.length - 1 || px == 0 || px == 6
                        || (px > 0 && shape[py].charAt(px - 1) == '.')
                        || (px < 6 && shape[py].charAt(px + 1) == '.')
                        || (py > 0 && shape[py - 1].charAt(px) == '.')
                        || (py + 1 < shape.length && shape[py + 1].charAt(px) == '.');
                int pixelColor = edge ? 0xEE171717 : (fill == 2 || (fill == 1 && px <= 3)) ? tint : 0xBB542929;
                Vec3 corner = origin.add(r.scale(left + px * pixel)).add(u.scale((-row * 8 - py) * pixel));
                Gizmos.rect(corner, corner.add(r.scale(pixel)), corner.add(r.scale(pixel)).add(u.scale(-pixel)),
                        corner.add(u.scale(-pixel)), GizmoStyle.fill(pixelColor)).setAlwaysOnTop();
            }
        }
    }
}
