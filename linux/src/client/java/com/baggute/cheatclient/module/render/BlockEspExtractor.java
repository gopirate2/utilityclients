// language: Java, file: BlockEspExtractor.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.render.BlockESP;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class BlockEspExtractor {

    private static final int MAX_WORK_PER_FRAME = 8192;
    private static final long SCAN_NANOS_PER_FRAME = 2_000_000;
    private static Scan scan;
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static net.minecraft.client.player.LocalPlayer player;
    private static long nextScan;
    private static boolean fastScan;

    private BlockEspExtractor() {}

    public static void emitGizmos() {
        BlockESP mod = UtilityClient.moduleManager == null ? null
                : (BlockESP) UtilityClient.moduleManager.getByName("BlockESP");
        Minecraft mc = Minecraft.getInstance();
        if (mod == null || !mod.isEnabled() || mc.level == null || mc.player == null) {
            scan = null; level = null; player = null; return;
        }
        int radius = (int) mod.getRadius(), capacity = mod.getMaxShown();
        BlockPos center = mc.player.blockPosition();
        Set<Block> targets = Set.copyOf(mod.getBlockSet());
        long now = System.nanoTime();
        boolean changed = scan == null || level != mc.level || player != mc.player
                || scan.radius != radius || scan.capacity != capacity || !scan.targets.equals(targets)
                || fastScan != mod.isFastScan();
        // Finish ordinary chunk transitions; restart only after a substantial move.
        boolean movedFar = scan != null && center.distSqr(scan.center) > Math.pow(Math.max(32, radius / 2), 2);
        if (changed || movedFar || (scan.done && now >= nextScan)) {
            List<BlockPos> retained = !changed && scan != null ? scan.visible.snapshot() : List.of();
            scan = new Scan(mc, center.immutable(), radius, capacity, targets);
            for (BlockPos pos : retained) if (valid(mc, pos, center, radius, targets)) scan.visible.add(pos);
            level = mc.level; player = mc.player; fastScan = mod.isFastScan();
        }
        if (!scan.done) {
            scan.advance(mc, new com.baggute.cheatclient.util.ScanBudget(
                    fastScan ? MAX_WORK_PER_FRAME : MAX_WORK_PER_FRAME / 4,
                    fastScan ? SCAN_NANOS_PER_FRAME : SCAN_NANOS_PER_FRAME / 2));
            if (scan.done) { scan.visible = scan.matches; nextScan = now + 1_000_000_000L; }
        }
        for (BlockPos pos : scan.visible.snapshot()) {
            if (!valid(mc, pos, center, radius, targets)) continue;
            Vec3 min = new Vec3(pos.getX(), pos.getY(), pos.getZ());
            Vec3 max = min.add(1, 1, 1);
            if (mod.isSimpleRender()) drawSimpleBox(min, max, mod.getColor());
            else drawBoxEdges(min, max, mod.getColor());
        }
    }

    private static boolean valid(Minecraft mc, BlockPos pos, BlockPos center, int radius, Set<Block> targets) {
        if (pos.distSqr(center) > (double) radius * radius) return false;
        LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        return chunk != null && targets.contains(chunk.getBlockState(pos).getBlock());
    }

    private record ChunkPos(int x, int z) {}

    private static final class Scan {
        final BlockPos center;
        final int radius, capacity, minY, maxY;
        final Set<Block> targets;
        final List<ChunkPos> chunks = new ArrayList<>();
        final com.baggute.cheatclient.util.NearestBlockSet matches;
        com.baggute.cheatclient.util.NearestBlockSet visible;
        int chunkIndex, sectionIndex, blockIndex;
        LevelChunk activeChunk;
        boolean done;

        Scan(Minecraft mc, BlockPos center, int radius, int capacity, Set<Block> targets) {
            this.center = center; this.radius = radius; this.capacity = capacity; this.targets = targets;
            minY = Math.max(mc.level.getMinY(), center.getY() - radius);
            maxY = Math.min(mc.level.getMaxY(), center.getY() + radius);
            matches = new com.baggute.cheatclient.util.NearestBlockSet(center, capacity);
            visible = new com.baggute.cheatclient.util.NearestBlockSet(center, capacity);
            for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++)
                for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++)
                    chunks.add(new ChunkPos(x, z));
            chunks.sort(Comparator.comparingDouble(p -> {
                double dx = p.x * 16.0 + 8 - center.getX(), dz = p.z * 16.0 + 8 - center.getZ();
                return dx * dx + dz * dz;
            }));
            done = targets.isEmpty() || minY > maxY;
        }

        void advance(Minecraft mc, com.baggute.cheatclient.util.ScanBudget budget) {
            // Discard a chunk reference if it unloaded/replaced between frames.
            if (activeChunk != null) {
                ChunkPos pos = chunks.get(chunkIndex);
                if (mc.level.getChunkSource().getChunk(pos.x, pos.z, false) != activeChunk) nextChunk();
            }
            while (!done && budget.next()) {
                if (chunkIndex >= chunks.size()) { done = true; break; }
                ChunkPos pos = chunks.get(chunkIndex);
                if (activeChunk == null) {
                    activeChunk = mc.level.getChunkSource().getChunk(pos.x, pos.z, false);
                    if (activeChunk == null) { nextChunk(); continue; }
                    sectionIndex = Math.max(0, (minY >> 4) - activeChunk.getMinSectionY());
                }
                LevelChunkSection[] sections = activeChunk.getSections();
                int baseY = (activeChunk.getMinSectionY() + sectionIndex) << 4;
                if (sectionIndex >= sections.length || baseY > maxY) { nextChunk(); continue; }
                LevelChunkSection section = sections[sectionIndex];
                // Palette rejection is mandatory, regardless of the old fastScan setting.
                if (blockIndex == 0 && (section == null || section.hasOnlyAir()
                        || !section.maybeHas(state -> targets.contains(state.getBlock())))) {
                    sectionIndex++; continue;
                }
                int index = blockIndex++;
                int x = index >> 8, y = (index >> 4) & 15, z = index & 15;
                int wx = (pos.x << 4) + x, wy = baseY + y, wz = (pos.z << 4) + z;
                if (wy >= minY && wy <= maxY && targets.contains(section.getBlockState(x, y, z).getBlock())) {
                    BlockPos block = new BlockPos(wx, wy, wz);
                    if (block.distSqr(center) <= (double) radius * radius) {
                        matches.add(block); visible.add(block);
                    }
                }
                if (blockIndex == 4096) { blockIndex = 0; sectionIndex++; }
            }
            if (chunkIndex >= chunks.size()) done = true;
        }

        private void nextChunk() {
            activeChunk = null; sectionIndex = blockIndex = 0; chunkIndex++;
        }
    }

    private static void drawBoxEdges(Vec3 min, Vec3 max, int color) {
        double pad = 0.005;
        double x0 = min.x + pad, y0 = min.y + pad, z0 = min.z + pad;
        double x1 = max.x - pad, y1 = max.y - pad, z1 = max.z - pad;

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

    private static void drawSimpleBox(Vec3 min, Vec3 max, int color) {
        double pad = 0.005;
        double x0 = min.x + pad, y0 = min.y + pad, z0 = min.z + pad;
        double x1 = max.x - pad, y1 = max.y - pad, z1 = max.z - pad;

        line(x0, y0, z0, x1, y0, z1, color);
        line(x1, y0, z0, x0, y0, z1, color);
        line(x0, y1, z0, x1, y1, z1, color);
        line(x1, y1, z0, x0, y1, z1, color);
        line(x0, y0, z0, x0, y1, z0, color);
        line(x1, y0, z0, x1, y1, z0, color);
    }

    private static void line(double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        Gizmos.line(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), color, 1.5F)
                .setAlwaysOnTop();
    }
}