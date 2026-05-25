package com.gridness.viz;

import java.util.Arrays;

/**
 * Fixed-capacity ring buffer of latency samples (nanoseconds) + percentile
 * snapshots. Writes are non-blocking; {@link #snapshot()} copies & sorts the
 * current window so percentiles are point-in-time consistent.
 *
 * <p>Not strictly thread-safe — assumed pattern is one writer (sim thread)
 * + one reader (Swing thread) where the reader tolerates slightly torn
 * reads for the live counters. The snapshot copies once into a stable
 * buffer before sorting, so percentile values are self-consistent.
 */
public final class LatencyStats {

    private final long[] data;
    private final int capacity;
    private volatile int writeIdx;
    private volatile long writeCount;

    public LatencyStats(int capacity) {
        this.capacity = capacity;
        this.data = new long[capacity];
    }

    public void record(long nanos) {
        int idx = writeIdx;
        data[idx] = nanos;
        writeIdx = (idx + 1) % capacity;
        writeCount++;
    }

    public long totalSamples() { return writeCount; }

    public Snapshot snapshot() {
        long count = writeCount;
        int n = (int) Math.min(count, capacity);
        if (n == 0) return Snapshot.EMPTY;
        long[] copy = new long[n];
        // Copy in any order — we'll sort.
        System.arraycopy(data, 0, copy, 0, n);
        Arrays.sort(copy);
        long sum = 0;
        for (long v : copy) sum += v;
        return new Snapshot(
                count,
                n,
                copy[0],
                copy[n - 1],
                sum / (double) n,
                pct(copy, 0.05),
                pct(copy, 0.50),
                pct(copy, 0.95),
                pct(copy, 0.99));
    }

    private static long pct(long[] sorted, double p) {
        int n = sorted.length;
        int idx = (int) Math.round(p * (n - 1));
        if (idx < 0) idx = 0; else if (idx >= n) idx = n - 1;
        return sorted[idx];
    }

    public record Snapshot(
            long totalSamples,
            int windowSize,
            long minNs,
            long maxNs,
            double meanNs,
            long p05Ns,
            long p50Ns,
            long p95Ns,
            long p99Ns) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
