// language: Java, file: EspExtractor.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.friends.FriendsManager;
import com.baggute.cheatclient.module.render.ESP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class EspExtractor {

    private EspExtractor() {}

    public static void emitGizmos(LevelRenderState levelRenderState) {
        if (UtilityClient.moduleManager == null) return;

        ESP esp = UtilityClient.moduleManager.getESP();
        if (esp == null || !esp.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        Vec3 camPos = levelRenderState.cameraRenderState.pos;
        double rangeSq = esp.getRange() * esp.getRange();
        Set<String> filter = esp.getEntityFilter();

        Vec3 crosshair = getCrosshairWorldPoint(mc, partialTick);
        int tracerColor = esp.getTracerColor();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;
            if (entity.distanceToSqr(camPos) > rangeSq) continue;

            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (id == null) continue;
            if (!filter.contains(id.toString())) continue;

            // Friends read green, everyone else keeps the configured colour, so a
            // friend is never mistaken for a target in a crowded fight.
            int boxColor;
            if (entity instanceof Player player && FriendsManager.isFriend(player)) {
                boxColor = FriendsManager.FRIEND_COLOR;
            } else {
                boxColor = entity instanceof Player ? esp.getPlayerColor() : esp.getMobColor();
            }

            drawBoxEdges(entity.getBoundingBox().inflate(0.05), boxColor);

            if (esp.isTracers()) {
                Vec3 from = entity.getPosition(partialTick)
                        .add(0, entity.getBbHeight() * 0.5, 0);
                Vec3 to   = crosshair;
                Gizmos.line(from, to, tracerColor, 1.5F).setAlwaysOnTop();
            }
        }
    }

    /**
     * Interpolated crosshair world point. Uses the same partial tick that
     * the renderer uses, so the endpoint matches the camera-visible frame
     * exactly — no lag when moving or turning.
     */
    private static Vec3 getCrosshairWorldPoint(Minecraft mc, float partialTick) {
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 look = mc.player.getViewVector(partialTick);
        double reach = 128.0;

        HitResult hit = mc.player.pick(reach, partialTick, false);
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            return hit.getLocation();
        }
        return eye.add(look.scale(reach));
    }

    static void drawBoxEdges(AABB b, int color) {
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ;
        double x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        line(x0, y0, z0, x1, y0, z0, color);
        line(x1, y0, z0, x1, y0, z1, color);
        line(x1, y0, z1, x0, y0, z1, color);
        line(x0, y0, z1, x0, y0, z0, color);
        line(x0, y1, z0, x1, y1, z0, color);
        line(x1, y1, z0, x1, y1, z1, color);
        line(x1, y1, z1, x0, y1, z1, color);
        line(x0, y1, z1, x0, y1, z0, color);
        line(x0, y0, z0, x0, y1, z0, color);
        line(x1, y0, z0, x1, y1, z0, color);
        line(x1, y0, z1, x1, y1, z1, color);
        line(x0, y0, z1, x0, y1, z1, color);
    }

    private static void line(double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        Gizmos.line(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), color, 1.5F)
                .setAlwaysOnTop();
    }
}