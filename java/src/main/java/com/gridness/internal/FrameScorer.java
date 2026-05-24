package com.gridness.internal;

import com.gridness.GridnessParams;

import java.util.List;

/**
 * Affine grid-line snapping scorer (V3-style) — operates on an arbitrary set
 * of buildings with per-building weights and a set of Hough-derived wall NORMAL
 * angles. Used by SampleGrid: each sample point passes the buildings within its
 * radius and Gaussian weights.
 */
public final class FrameScorer {

    private FrameScorer() { }

    public static double score(List<Building> buildings, double[] perBuildingWeight,
                                double[] wallNormals, GridnessParams p) {
        int n = buildings.size();
        if (n != perBuildingWeight.length)
            throw new IllegalArgumentException("buildings/weights length mismatch");
        if (n < p.minBuildingsInWindow) return 0.0;
        if (wallNormals.length == 0) return 0.0;

        double weightSum = 0.0;
        for (double w : perBuildingWeight) weightSum += w;
        if (weightSum <= 0.0) return 0.0;

        int m = wallNormals.length;
        double[] dirCos = new double[m];
        double[] dirSin = new double[m];
        for (int i = 0; i < m; i++) {
            double a = wallNormals[i] + Math.PI / 2;
            dirCos[i] = Math.cos(a);
            dirSin[i] = Math.sin(a);
        }

        double meanRect = 0.0;
        for (int i = 0; i < n; i++) meanRect += buildings.get(i).rectangularity * perBuildingWeight[i];
        meanRect /= weightSum;

        double[] uLo = new double[n];
        double[] uHi = new double[n];
        double[] vLo = new double[n];
        double[] vHi = new double[n];

        double[] values = new double[n * 2];
        double[] weights = new double[n * 2];
        int[] ids = new int[n * 2];
        for (int bi = 0; bi < n; bi++) {
            weights[2 * bi]     = perBuildingWeight[bi];
            weights[2 * bi + 1] = perBuildingWeight[bi];
            ids[2 * bi]         = bi;
            ids[2 * bi + 1]     = bi;
        }

        double bestScore = 0.0;
        for (int i = 0; i < m; i++) {
            double ax = dirCos[i], ay = dirSin[i];
            for (int j = i + 1; j < m; j++) {
                double bx = dirCos[j], by = dirSin[j];
                double det = ax * by - ay * bx;
                if (Math.abs(det) < p.minAngleSin) continue;
                double invDet = 1.0 / det;
                double m00 = by * invDet;
                double m01 = -bx * invDet;
                double m10 = -ay * invDet;
                double m11 = ax * invDet;

                for (int bi = 0; bi < n; bi++) {
                    Building bld = buildings.get(bi);
                    int[] bd = bld.boundary;
                    int len = bd.length >>> 1;
                    if (len == 0) { uLo[bi] = uHi[bi] = vLo[bi] = vHi[bi] = 0; continue; }
                    double[] u = new double[len];
                    double[] v = new double[len];
                    for (int k = 0; k < len; k++) {
                        double x = bd[2 * k];
                        double y = bd[2 * k + 1];
                        u[k] = m00 * x + m01 * y;
                        v[k] = m10 * x + m11 * y;
                    }
                    int loIdx = (int) Math.max(0, Math.floor(0.05 * (len - 1)));
                    int hiIdx = (int) Math.min(len - 1, Math.ceil(0.95 * (len - 1)));
                    java.util.Arrays.sort(u);
                    java.util.Arrays.sort(v);
                    uLo[bi] = u[loIdx];
                    uHi[bi] = u[hiIdx];
                    vLo[bi] = v[loIdx];
                    vHi[bi] = v[hiIdx];
                }

                for (int bi = 0; bi < n; bi++) {
                    values[2 * bi]     = uLo[bi];
                    values[2 * bi + 1] = uHi[bi];
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

                double maxWeight = 2.0 * weightSum;
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
}
