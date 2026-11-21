package com.baggute.cheatclient.util;

import net.minecraft.core.BlockPos;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Deduplicated, bounded nearest results; the scan never retains every match. */
public final class NearestBlockSet {
    private final int capacity;
    private final TreeSet<BlockPos> positions;
    public NearestBlockSet(BlockPos center, int capacity) {
        this.capacity = Math.max(0, capacity);
        positions = new TreeSet<>(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(center))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
    }
    public void add(BlockPos pos) {
        if (capacity == 0) return;
        if (positions.size() == capacity && positions.comparator().compare(pos, positions.last()) >= 0) return;
        positions.add(pos.immutable());
        if (positions.size() > capacity) positions.pollLast();
    }
    public List<BlockPos> snapshot() { return List.copyOf(positions); }
    public int size() { return positions.size(); }
}
