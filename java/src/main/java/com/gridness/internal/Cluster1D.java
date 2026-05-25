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
        /** Sum of weights over valid clusters (raw — caller can normalize). */
        public final double score;
        /** Number of valid clusters (≥ minDistinctBuildings), already capped at requiredLinesPerAxis. */
        public final int validClusters;
        /** Number of valid clusters BEFORE capping at requiredLinesPerAxis. */
        public final int validClustersUncapped;
        /** explained_mass = sum_valid_weights / total_weights, in [0,1]. */
        public final double explainedMass;
        /** tightness = max(0, 1 - avg_residual / tau), in [0,1]. */
        public final double tightness;

        public Result(double score, int validClusters, int validClustersUncapped,
                      double explainedMass, double tightness) {
            this.score = score;
            this.validClusters = validClusters;
            this.validClustersUncapped = validClustersUncapped;
            this.explainedMass = explainedMass;
            this.tightness = tightness;
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
        if (n == 0) return new Result(0.0, 0, 0, 0.0, 0.0);

        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(values[a], values[b]));

        // First pass: walk clusters greedily, tracking per-cluster (weight, center, residual_sum).
        // We process valid clusters in-flight (no allocation of cluster arrays).
        double totalWeight = 0.0;
        for (double w : weights) totalWeight += w;
        if (totalWeight <= 0.0) return new Result(0.0, 0, 0, 0.0, 0.0);

        double validWeightSum = 0.0;        // sum of weights in valid clusters
        double validResidualSum = 0.0;      // sum of residuals (w * |v - center|) in valid clusters
        int validCount = 0;                  // # of valid clusters (uncapped)

        // Walking buffers for the current open cluster:
        double clusterWeight = 0.0;
        double clusterWvSum = 0.0;          // sum w*v, for center
        // Cache values per cluster so we can compute residual in a second pass over the cluster's points.
        // Use arrays sized to n (worst case = single cluster of all n points).
        double[] clusterV = new double[n];
        double[] clusterW = new double[n];
        int clusterN = 0;
        double clusterLast = values[idx[0]];
        IntSet seen = new IntSet(Math.max(16, n));

        // open first cluster with idx[0]
        int first = idx[0];
        seen.add(buildingIds[first]);
        clusterV[0] = values[first];
        clusterW[0] = weights[first];
        clusterN = 1;
        clusterWeight = weights[first];
        clusterWvSum = weights[first] * values[first];

        for (int k = 1; k < n; k++) {
            int i = idx[k];
            double v = values[i];
            if (v - clusterLast <= tolerance) {
                clusterWeight += weights[i];
                clusterWvSum += weights[i] * v;
                clusterV[clusterN] = v;
                clusterW[clusterN] = weights[i];
                clusterN++;
                seen.add(buildingIds[i]);
                clusterLast = v;
            } else {
                if (seen.size() >= minDistinctBuildings) {
                    double center = clusterWvSum / Math.max(clusterWeight, 1e-9);
                    double rsum = 0.0;
                    for (int j = 0; j < clusterN; j++) rsum += clusterW[j] * Math.abs(clusterV[j] - center);
                    validWeightSum += clusterWeight;
                    validResidualSum += rsum;
                    validCount++;
                }
                seen.clear();
                clusterWeight = weights[i];
                clusterWvSum = weights[i] * v;
                clusterN = 1;
                clusterV[0] = v;
                clusterW[0] = weights[i];
                seen.add(buildingIds[i]);
                clusterLast = v;
            }
        }
        if (seen.size() >= minDistinctBuildings) {
            double center = clusterWvSum / Math.max(clusterWeight, 1e-9);
            double rsum = 0.0;
            for (int j = 0; j < clusterN; j++) rsum += clusterW[j] * Math.abs(clusterV[j] - center);
            validWeightSum += clusterWeight;
            validResidualSum += rsum;
            validCount++;
        }

        if (validCount == 0) return new Result(0.0, 0, 0, 0.0, 0.0);

        double explainedMass = validWeightSum / Math.max(totalWeight, 1e-9);
        double avgResidual = validResidualSum / Math.max(validWeightSum, 1e-9);
        double tightness = Math.max(0.0, 1.0 - avgResidual / tolerance);
        int reported = Math.min(validCount, requiredLinesPerAxis);
        return new Result(validWeightSum, reported, validCount, explainedMass, tightness);
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
