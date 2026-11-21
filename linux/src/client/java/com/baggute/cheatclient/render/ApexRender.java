// language: Java, file: ApexRender.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.apex.ApexSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 3D layer for the apex visual entries. Everything here rides the same Gizmos
 * collector the ESP modules already use, so nothing new has to be submitted to the
 * render pipeline.
 */
public final class ApexRender {

    private static final Deque<Vec3> TRAIL = new ArrayDeque<>();

    private ApexRender() {}

    public static void emit(LevelRenderState state) {
        if (UtilityClient.moduleManager == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (on("DashTrails")) dashTrails(mc);
        else TRAIL.clear();

        if (on("ObsidianGlow")) obsidianGlow(mc);
        if (on("MaceImpact")) maceImpact(mc);
    }

    private static boolean on(String name) {
        Module module = UtilityClient.moduleManager.getByName(name);
        return module != null && module.isEnabled();
    }

    /** A fading ribbon behind the player while they move. */
    private static void dashTrails(Minecraft mc) {
        Module settings = ApexSettings.module("DashTrails");
        int length = ApexSettings.integer(settings, "length", 28);
        double spacing = ApexSettings.number(settings, "spacing", 0.2);
        float width = (float) ApexSettings.number(settings, "width", 2);
        int configuredColor = ApexSettings.color(settings, 0xE07C9CFF);
        while (TRAIL.size() > length) TRAIL.removeFirst();
        Vec3 now = mc.player.position();
        // A respawn, portal or teleport jumps the player across the world; joining
        // the new position to the stale points drew one ribbon across the map.
        if (!TRAIL.isEmpty() && TRAIL.peekLast().distanceToSqr(now) > 64.0) {
            TRAIL.clear();
        }
        if (TRAIL.isEmpty() || TRAIL.peekLast().distanceToSqr(now) > spacing * spacing) {
            TRAIL.addLast(now);
            while (TRAIL.size() > length) TRAIL.removeFirst();
        }
        if (TRAIL.size() < 2) return;

        Vec3 previous = null;
        int index = 0;
        for (Vec3 point : TRAIL) {
            if (previous != null) {
                float fade = (float) index / TRAIL.size();
                int alpha = (int) ((configuredColor >>> 24) * (0.2142857 + fade * 0.7857143));
                int color = (alpha << 24) | (configuredColor & 0xFFFFFF);
                Gizmos.line(previous.add(0, 0.1, 0), point.add(0, 0.1, 0), color, width);
            }
            previous = point;
            index++;
        }
    }

    /** Outlines obsidian around you so anchor spots read at a glance. */
    private static void obsidianGlow(Minecraft mc) {
        Module settings = ApexSettings.module("ObsidianGlow");
        int radius = ApexSettings.integer(settings, "radius", 8);
        int vertical = ApexSettings.integer(settings, "verticalRadius", 4);
        int maximum = ApexSettings.integer(settings, "maxBlocks", 24);
        BlockPos origin = mc.player.blockPosition();
        int drawn = 0;

        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -vertical, -radius), origin.offset(radius, vertical, radius))) {
            if (drawn >= maximum) break;
            if (!mc.level.getBlockState(pos).is(Blocks.OBSIDIAN)) continue;

            box(new Vec3(pos.getX(), pos.getY(), pos.getZ()),
                    new Vec3(pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1), ApexSettings.color(settings, 0xC0A78BFA));
            drawn++;
        }
    }

    /** Expanding rings on the ground when a mace hit lands nearby. */
    private static void maceImpact(Minecraft mc) {
        Module settings = ApexSettings.module("MaceImpact");
        double range = ApexSettings.number(settings, "range", 6);
        String hand = mc.player.getMainHandItem().getItem().toString().toLowerCase();
        if (!hand.contains("mace")) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player target) || target == mc.player) continue;
            if (target.hurtTime <= 0) continue;
            if (target.distanceToSqr(mc.player) > range * range) continue;

            double x = target.getX();
            double y = target.getY() + 0.05;
            double z = target.getZ();
            double radius = 0.6 + (1.0 - target.hurtTime / 10.0) * (ApexSettings.number(settings, "radius", 2) - 0.6);
            int segments = ApexSettings.integer(settings, "segments", 20);

            for (int i = 0; i < segments; i++) {
                double a1 = (double) i / segments * Math.PI * 2;
                double a2 = (double) (i + 1) / segments * Math.PI * 2;
                Gizmos.line(
                        new Vec3(x + Math.cos(a1) * radius, y, z + Math.sin(a1) * radius),
                        new Vec3(x + Math.cos(a2) * radius, y, z + Math.sin(a2) * radius),
                        ApexSettings.color(settings, 0xD0F87171), (float) ApexSettings.number(settings, "width", 2.5)).setAlwaysOnTop();
            }
            return;
        }
    }

    private static void box(Vec3 min, Vec3 max, int color) {
        Vec3[] corners = {
                new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z),
                new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z),
                new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z),
                new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z)
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] edge : edges) {
            Gizmos.line(corners[edge[0]], corners[edge[1]], color, 1.5F).setAlwaysOnTop();
        }
    }
}
