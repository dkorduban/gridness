package com.dkorduban.gridness.internal;

/**
 * Small straight-line Hough transform sized for tile-area rasters.
 * Returns the top-N dominant wall NORMAL angles in radians (in [0, pi)).
 * Caller is responsible for converting normals to wall directions (add pi/2).
 */
public final class HoughDetector {

    private final int thetaSteps;
    private final double[] cosTheta;
    private final double[] sinTheta;

    public HoughDetector(int thetaSteps) {
        if (thetaSteps < 2) throw new IllegalArgumentException("thetaSteps >= 2");
        this.thetaSteps = thetaSteps;
        this.cosTheta = new double[thetaSteps];
        this.sinTheta = new double[thetaSteps];
        for (int t = 0; t < thetaSteps; t++) {
            double theta = Math.PI * t / thetaSteps;  // [0, pi)
            cosTheta[t] = Math.cos(theta);
            sinTheta[t] = Math.sin(theta);
        }
    }

    /**
     * Run Hough on a tile-sized boolean wall raster and return the dominant
     * wall normal angles (radians, [0, pi)). Result is sorted by descending strength.
     *
     * @param walls   row-major wall raster (true = wall)
     * @param W       width
     * @param H       height
     * @param maxAngles maximum number of angles to return
     * @param thresholdFrac peaks must be >= thresholdFrac * max accumulator
     * @param minPeakWeight peaks must be >= this absolute count
     * @param minAngleSepDeg minimum angular separation between returned peaks
     */
    public double[] dominantAngles(boolean[] walls, int W, int H,
                                    int maxAngles,
                                    double thresholdFrac,
                                    double minPeakWeight,
                                    double minAngleSepDeg) {
        int rhoMax = (int) Math.ceil(Math.sqrt(W * (double) W + H * (double) H));
        int rhoRange = 2 * rhoMax + 1;
        int[] accum = new int[thetaSteps * rhoRange];

        for (int y = 0; y < H; y++) {
            int rowBase = y * W;
            for (int x = 0; x < W; x++) {
                if (!walls[rowBase + x]) continue;
                for (int t = 0; t < thetaSteps; t++) {
                    int rho = (int) Math.round(x * cosTheta[t] + y * sinTheta[t]);
                    int idx = t * rhoRange + (rho + rhoMax);
                    accum[idx]++;
                }
            }
        }

        // Collapse to per-theta peak strength: max rho-bin per theta.
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
        if (globalMax == 0) return new double[0];

        double threshold = Math.max(thresholdFrac * globalMax, minPeakWeight);
        int minSepBins = Math.max(1, (int) Math.round(minAngleSepDeg * thetaSteps / 180.0));

        // Local maxima (NMS within minSepBins) above threshold.
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
        if (nCand == 0) return new double[0];

        // Sort candidates by score descending and keep top maxAngles.
        Integer[] order = new Integer[nCand];
        for (int i = 0; i < nCand; i++) order[i] = i;
        Integer[] sortedOrder = order;
        java.util.Arrays.sort(sortedOrder, (a, b) -> Integer.compare(candidateScore[b], candidateScore[a]));
        int keep = Math.min(maxAngles, nCand);
        double[] out = new double[keep];
        for (int i = 0; i < keep; i++) {
            int t = candidateIdx[sortedOrder[i]];
            out[i] = Math.PI * t / thetaSteps;
        }
        return out;
    }
}
