// language: Java, file: StorageEspExtractor.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.render.StorageESP;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class StorageEspExtractor {

    private static final List<Entry> found = new ArrayList<>();
    private static long lastScanMs = 0L;
    private static int lastChunkX = Integer.MIN_VALUE;
    private static int lastChunkZ = Integer.MIN_VALUE;
    private static int lastScanRadius = -1;

    private record Entry(BlockPos pos, int color) {}

    private StorageEspExtractor() {}

    public static void emitGizmos() {
        if (UtilityClient.moduleManager == null) return;

        StorageESP mod = (StorageESP) UtilityClient.moduleManager.getByName("StorageESP");
        if (mod == null || !mod.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        int radius = (int) mod.getRadius();
        int interval = mod.getScanIntervalMs();
        BlockPos center = mc.player.blockPosition();
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;

        long now = System.currentTimeMillis();
        boolean needScan = radius != lastScanRadius
                || now - lastScanMs > interval
                || cx != lastChunkX
                || cz != lastChunkZ;
        if (needScan) {
            rescan(mc, center, radius, mod);
            lastScanMs = now;
            lastChunkX = cx;
            lastChunkZ = cz;
            lastScanRadius = radius;
        }

        double radiusSq = (double) radius * radius;
        for (Entry e : found) {
            if (e.pos().distSqr(center) > radiusSq) continue;
            drawBox(e.pos(), e.color());
        }
    }

    private static void rescan(Minecraft mc, BlockPos center, int radius, StorageESP mod) {
        found.clear();

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(chunkX, chunkZ, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (Math.abs(pos.getX() - center.getX()) > radius) continue;
                    if (Math.abs(pos.getY() - center.getY()) > radius) continue;
                    if (Math.abs(pos.getZ() - center.getZ()) > radius) continue;

                    Block block = be.getBlockState().getBlock();
                    int color = mod.getColorFor(block);
                    if (color == 0) continue;
                    found.add(new Entry(pos.immutable(), color));
                }
            }
        }
    }

    private static void drawBox(BlockPos pos, int color) {
        double x0 = pos.getX() + 0.005;
        double y0 = pos.getY() + 0.005;
        double z0 = pos.getZ() + 0.005;
        double x1 = pos.getX() + 0.995;
        double y1 = pos.getY() + 0.995;
        double z1 = pos.getZ() + 0.995;

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