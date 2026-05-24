package com.gridness.internal;

import com.gridness.GridnessParams;

import java.util.List;

/**
 * Affine grid-line snapping scorer (V3-style) — operates on the buildings of a
 * single tile. Builds candidate (a, b) frames from Hough-derived wall
 * directions, projects each building's boundary into (u, v), takes 5/95
 * percentile as robust extents, clusters extents along both axes, and combines
 * into a [0,1] gridness score.
 */
public final class FrameScorer {

    /**
     * Score one tile.
     *
     * @param buildings    buildings whose centroids fall in the tile
     * @param wallNormals  Hough-derived wall NORMAL angles (radians)
     * @param p            params
     * @return score in [0,1]; 0 if not enough buildings or no valid frames
     */
    public static double score(List<Building> buildings, double[] wallNormals, GridnessParams p) {
        int n = buildings.size();
        if (n < p.minBuildingsInTile) return 0.0;
        if (wallNormals.length == 0) return 0.0;

        // Convert normals to wall directions, build frames from all valid pairs.
        int m = wallNormals.length;
        double[] dirCos = new double[m];
        double[] dirSin = new double[m];
        for (int i = 0; i < m; i++) {
            double a = wallNormals[i] + Math.PI / 2;
            dirCos[i] = Math.cos(a);
            dirSin[i] = Math.sin(a);
        }

        // Precompute building boundary arrays as doubles for projection.
        // (We could also use the int boundary arrays but doubles avoid casts in the hot loop.)
        // We compute extents lazily per frame, since most tiles have few frames.

        double bestScore = 0.0;
        double meanRect = 0.0;
        for (Building b : buildings) meanRect += b.rectangularity;
        meanRect /= n;

        // For each unordered pair (i, j), build a frame [a | b] where columns are wall directions.
        int maxPairs = m * (m - 1) / 2;
        double[] uLo = new double[n];
        double[] uHi = new double[n];
        double[] vLo = new double[n];
        double[] vHi = new double[n];

        double[] values = new double[n * 2];
        double[] weights = new double[n * 2];
        int[] ids = new int[n * 2];

        for (int i = 0; i < m; i++) {
            double ax = dirCos[i], ay = dirSin[i];
            for (int j = i + 1; j < m; j++) {
                double bx = dirCos[j], by = dirSin[j];
                // Frame M = [a b]. We need M^{-1} to project x,y -> u,v.
                double det = ax * by - ay * bx;
                if (Math.abs(det) < p.minAngleSin) continue;
                double invDet = 1.0 / det;
                // [u; v] = M^{-1} [x; y]; M^{-1} = (1/det) * [[by, -bx], [-ay, ax]]
                double m00 = by * invDet;
                double m01 = -bx * invDet;
                double m10 = -ay * invDet;
                double m11 = ax * invDet;

                // Project boundary of each building and compute 5/95 percentile.
                for (int bi = 0; bi < n; bi++) {
                    Building bld = buildings.get(bi);
                    int[] bd = bld.boundary;
                    int len = bd.length >>> 1;
                    if (len == 0) { uLo[bi] = uHi[bi] = vLo[bi] = vHi[bi] = 0; continue; }
                    double[] u = SCRATCH_U.get();
                    double[] v = SCRATCH_V.get();
                    if (u.length < len) { u = new double[len]; SCRATCH_U.set(u); }
                    if (v.length < len) { v = new double[len]; SCRATCH_V.set(v); }
                    for (int k = 0; k < len; k++) {
                        double x = bd[2 * k];
                        double y = bd[2 * k + 1];
                        u[k] = m00 * x + m01 * y;
                        v[k] = m10 * x + m11 * y;
                    }
                    int loIdx = (int) Math.max(0, Math.floor(0.05 * (len - 1)));
                    int hiIdx = (int) Math.min(len - 1, Math.ceil(0.95 * (len - 1)));
                    // Use quickselect via Arrays.sort for simplicity on small len (~30-50).
                    double[] uSorted = java.util.Arrays.copyOf(u, len);
                    double[] vSorted = java.util.Arrays.copyOf(v, len);
                    java.util.Arrays.sort(uSorted, 0, len);
                    java.util.Arrays.sort(vSorted, 0, len);
                    uLo[bi] = uSorted[loIdx];
                    uHi[bi] = uSorted[hiIdx];
                    vLo[bi] = vSorted[loIdx];
                    vHi[bi] = vSorted[hiIdx];
                }

                // Cluster u-extents and v-extents.
                for (int bi = 0; bi < n; bi++) {
                    values[2 * bi]     = uLo[bi];
                    values[2 * bi + 1] = uHi[bi];
                    weights[2 * bi]    = 1.0;
                    weights[2 * bi + 1] = 1.0;
                    ids[2 * bi]        = bi;
                    ids[2 * bi + 1]    = bi;
                }
                Cluster1D.Result uRes = Cluster1D.clusterAndScore(
                        values, weights, ids,
                        p.clusterTolerance, p.minDistinctBuildings, p.requiredLinesPerAxis);

                for (int bi = 0; bi < n; bi++) {
                    values[2 * bi]     = vLo[bi];
                    values[2 * bi + 1] = vHi[bi];
                }
                Cluster1D.Result vRes = Cluster1D.clusterAndScore(
                        values, weights, ids,
                        p.clusterTolerance, p.minDistinctBuildings, p.requiredLinesPerAxis);

                // Per Python v3_hough.py: edge_snap = sqrt(u_score * v_score) normalized,
                // two_axis = sqrt(U_support * V_support) where support = min(valid/required, 1).
                // Normalize u_score by total possible weight (2n) for [0,1].
                double maxWeight = 2.0 * n;
                double uScore = uRes.score / maxWeight;
                double vScore = vRes.score / maxWeight;
                double edgeSnap = Math.sqrt(Math.max(0.0, uScore) * Math.max(0.0, vScore));

                double uSupport = (double) uRes.validClusters / p.requiredLinesPerAxis;
                double vSupport = (double) vRes.validClusters / p.requiredLinesPerAxis;
                if (uSupport > 1.0) uSupport = 1.0;
                if (vSupport > 1.0) vSupport = 1.0;
                double twoAxis = Math.sqrt(uSupport * vSupport);

                double score = edgeSnap * twoAxis;
                if (score > bestScore) bestScore = score;
            }
        }

        double withShape = bestScore * (p.shapeFloor + p.shapeWeight * meanRect);
        if (withShape < 0.0) withShape = 0.0;
        if (withShape > 1.0) withShape = 1.0;
        return withShape;
    }

    // Thread-local scratch buffers to avoid per-frame allocation in the inner loop.
    private static final ThreadLocal<double[]> SCRATCH_U = ThreadLocal.withInitial(() -> new double[256]);
    private static final ThreadLocal<double[]> SCRATCH_V = ThreadLocal.withInitial(() -> new double[256]);

    private FrameScorer() { }
}
