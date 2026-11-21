// language: Java, file: VanishExtractor.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.render.AntiVanish;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the ghosts AntiVanish keeps after they leave the player list.
 *
 * <p>Follows the same shape as the other extractors: the module owns the data,
 * this walks a snapshot of it during the render pass and hands nothing back.
 */
public final class VanishExtractor {

    /** Player hitbox footprint; ghosts are drawn at their last known feet position. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    private VanishExtractor() {}

    public static void emitGizmos(LevelRenderState levelRenderState) {
        if (UtilityClient.moduleManager == null) return;

        AntiVanish antiVanish = UtilityClient.moduleManager.getAntiVanish();
        if (antiVanish == null || !antiVanish.drawEsp()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        int color = antiVanish.getColor();
        Vec3 camera = levelRenderState.cameraRenderState.pos;

        for (AntiVanish.Ghost ghost : antiVanish.ghosts()) {
            Vec3 feet = ghost.pos();
            AABB box = new AABB(
                    feet.x - HALF_WIDTH, feet.y, feet.z - HALF_WIDTH,
                    feet.x + HALF_WIDTH, feet.y + HEIGHT, feet.z + HALF_WIDTH);

            EspExtractor.drawBoxEdges(box, color);
            // A marker line down the middle makes the last known position obvious
            // even where the box is half-buried in terrain.
            Gizmos.line(feet, feet.add(0, HEIGHT, 0), color, 1.0F).setAlwaysOnTop();
            Gizmos.line(camera, feet.add(0, HEIGHT * 0.5, 0), color, 0.5F).setAlwaysOnTop();
        }
    }
}
