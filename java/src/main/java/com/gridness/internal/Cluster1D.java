package com.gridness.internal;

/**
 * Greedy 1D clustering for V3 affine scoring. Mirrors `cluster_and_score_fast`
 * in the Python prototype: sort values, walk left-to-right merging into the
 * current cluster while within `tolerance`, then score = sum(weight) over
 * clusters with at least `minDistinctBuildings` distinct sources; the count of
 * valid clusters caps at `requiredLinesPerAxis`.
 */
public final class Cluster1D {

    public static final class Result {
        public final double score;       // sum of weights over valid clusters
        public final int validClusters;  // count of valid clusters
        public Result(double score, int validClusters) {
            this.score = score;
            this.validClusters = validClusters;
        }
    }

    private Cluster1D() { }

    /**
     * Cluster values + score.
     *
     * @param values    1D positions (will be sorted in-place via index permutation)
     * @param weights   per-value weight (gaussian weight from sample point)
     * @param buildingIds  per-value building id (for distinct-source counting)
     * @param tolerance  max gap within a cluster
     * @param minDistinctBuildings  min distinct buildings to count cluster as valid
     * @param requiredLinesPerAxis  cap on counted valid clusters
     */
    public static Result clusterAndScore(double[] values, double[] weights, int[] buildingIds,
                                          double tolerance,
                                          int minDistinctBuildings,
                                          int requiredLinesPerAxis) {
        int n = values.length;
        if (n == 0) return new Result(0.0, 0);

        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(values[a], values[b]));

        double totalScore = 0.0;
        int validCount = 0;
        double clusterWeight = 0.0;
        double clusterStart = values[idx[0]];
        double clusterLast = clusterStart;
        // Track distinct buildings within current cluster using a small hash set.
        // We use a simple open-addressing int set sized to handle up to N.
        IntSet seen = new IntSet(Math.max(16, n));

        seen.add(buildingIds[idx[0]]);
        clusterWeight = weights[idx[0]];

        for (int k = 1; k < n; k++) {
            int i = idx[k];
            double v = values[i];
            if (v - clusterLast <= tolerance) {
                clusterWeight += weights[i];
                seen.add(buildingIds[i]);
                clusterLast = v;
            } else {
                if (seen.size() >= minDistinctBuildings) {
                    totalScore += clusterWeight;
                    validCount++;
                }
                seen.clear();
                clusterStart = v;
                clusterLast = v;
                clusterWeight = weights[i];
                seen.add(buildingIds[i]);
            }
        }
        if (seen.size() >= minDistinctBuildings) {
            totalScore += clusterWeight;
            validCount++;
        }

        int reported = Math.min(validCount, requiredLinesPerAxis);
        // We want score to also be capped meaningfully. The Python version returns sum-of-weights
        // for valid clusters but then min(valid_count, required) is used for support. We mirror:
        return new Result(totalScore, reported);
    }

    /** Tiny open-addressing int set. */
    static final class IntSet {
        private int[] table;
        private int mask;
        private int size;
        private static final int EMPTY = Integer.MIN_VALUE;

        IntSet(int capacity) {
            int cap = 8;
            while (cap < capacity * 2) cap <<= 1;
            this.table = new int[cap];
            this.mask = cap - 1;
            java.util.Arrays.fill(table, EMPTY);
            this.size = 0;
        }

        void add(int v) {
            int h = (v * 0x9E3779B1) & mask;
            while (true) {
                int cur = table[h];
                if (cur == EMPTY) { table[h] = v; size++; return; }
                if (cur == v) return;
                h = (h + 1) & mask;
            }
        }

        int size() { return size; }

        void clear() {
            java.util.Arrays.fill(table, EMPTY);
            size = 0;
        }
    }
}
