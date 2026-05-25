package com.gridness.internal;

import java.util.Arrays;

/**
 * Field-wide Hough accumulator with incremental wall edits. When enabled,
 * all tiles use these globally-derived angles instead of running per-tile
 * Hough — matches the Python prototype's behavior (one Hough per layout)
 * and dramatically improves Java's heatmap agreement with Python on layouts
 * where per-tile peaks are too weak (organic walks, scattered, etc.).
 *
 * <p>Per-edit cost: O(thetaSteps) for accumulator update + O(thetaSteps *
 * rhoRange) for peak detection (only on next read). Both are independent of
 * field size, so this scales well even at 768x768.
 */
public final class GlobalHough {

    private final int W, H, thetaSteps;
    private final int rhoMax, rhoRange;
    private final int[] accum;
    private final double[] cosTheta, sinTheta;
    private double[] cachedAngles = new double[0];
    private boolean angleDirty = true;
    private int wallCount;  // tracked for thresholding

    public GlobalHough(int W, int H, int thetaSteps) {
        this.W = W;
        this.H = H;
        this.thetaSteps = thetaSteps;
        this.rhoMax = (int) Math.ceil(Math.sqrt(W * (double) W + H * (double) H));
        this.rhoRange = 2 * rhoMax + 1;
        this.accum = new int[thetaSteps * rhoRange];
        this.cosTheta = new double[thetaSteps];
        this.sinTheta = new double[thetaSteps];
        for (int t = 0; t < thetaSteps; t++) {
            double theta = Math.PI * t / thetaSteps;
            cosTheta[t] = Math.cos(theta);
            sinTheta[t] = Math.sin(theta);
        }
    }

    /** Rebuild accumulator from scratch over the given wall grid. */
    public void recompute(WallGrid walls) {
        Arrays.fill(accum, 0);
        wallCount = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (!walls.get(x, y)) continue;
                wallCount++;
                for (int t = 0; t < thetaSteps; t++) {
                    int rho = (int) Math.round(x * cosTheta[t] + y * sinTheta[t]);
                    accum[t * rhoRange + (rho + rhoMax)]++;
                }
            }
        }
        angleDirty = true;
    }

    /** Apply a single-cell edit (wasWall → nowWall) to the accumulator. */
    public void applyEdit(int x, int y, boolean wasWall, boolean nowWall) {
        if (wasWall == nowWall) return;
        int delta = nowWall ? 1 : -1;
        if (delta > 0) wallCount++; else wallCount--;
        for (int t = 0; t < thetaSteps; t++) {
            int rho = (int) Math.round(x * cosTheta[t] + y * sinTheta[t]);
            accum[t * rhoRange + (rho + rhoMax)] += delta;
        }
        angleDirty = true;
    }

    /** Return dominant wall NORMAL angles (radians, [0, π)), sorted by descending strength. */
    public double[] dominantAngles(int maxAngles,
                                    double thresholdFrac,
                                    double minPeakWeight,
                                    double minAngleSepDeg) {
        if (!angleDirty) return cachedAngles;
        // Per-theta peak strength = max over rho.
        int[] thetaScore = new int[thetaSteps];
        int globalMax = 0;
        for (int t = 0; t < thetaSteps; t++) {
            int base = t * rhoRange;
            int best = 0;
            for (int r = 0; r < rhoRange; r++) {
                int v = accum[base + r];
                if (v > best) best = v;
            }
            thetaScore[t] = best;
            if (best > globalMax) globalMax = best;
        }
        if (globalMax == 0) {
            cachedAngles = new double[0];
            angleDirty = false;
            return cachedAngles;
        }
        double threshold = Math.max(thresholdFrac * globalMax, minPeakWeight);
        int minSepBins = Math.max(1, (int) Math.round(minAngleSepDeg * thetaSteps / 180.0));

        int[] candidateIdx = new int[thetaSteps];
        int[] candidateScore = new int[thetaSteps];
        int nCand = 0;
        for (int t = 0; t < thetaSteps; t++) {
            int s = thetaScore[t];
            if (s < threshold) continue;
            boolean isLocalMax = true;
            for (int d = 1; d <= minSepBins; d++) {
                int left = (t - d + thetaSteps) % thetaSteps;
                int right = (t + d) % thetaSteps;
                if (thetaScore[left] > s || thetaScore[right] > s) { isLocalMax = false; break; }
            }
            if (isLocalMax) {
                candidateIdx[nCand] = t;
                candidateScore[nCand] = s;
                nCand++;
            }
        }
        if (nCand == 0) {
            cachedAngles = new double[0];
            angleDirty = false;
            return cachedAngles;
        }
        Integer[] order = new Integer[nCand];
        for (int i = 0; i < nCand; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(candidateScore[b], candidateScore[a]));
        int keep = Math.min(maxAngles, nCand);
        double[] out = new double[keep];
        for (int i = 0; i < keep; i++) {
            int t = candidateIdx[order[i]];
            out[i] = Math.PI * t / thetaSteps;
        }
        cachedAngles = out;
        angleDirty = false;
        return cachedAngles;
    }

    public int wallCount() { return wallCount; }
}
