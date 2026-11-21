package com.baggute.cheatclient.util;

/** Caps both work and elapsed time in a progressive render scan. */
public final class ScanBudget {
    private final int limit;
    private final long started = System.nanoTime(), duration;
    private int units;
    public ScanBudget(int limit, long durationNanos) {
        this.limit = Math.max(0, limit);
        this.duration = Math.max(0, durationNanos);
    }
    public boolean next() {
        if (units >= limit || System.nanoTime() - started >= duration) return false;
        units++;
        return true;
    }
    public int units() { return units; }
}
