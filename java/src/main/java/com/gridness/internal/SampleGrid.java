package com.gridness.internal;

import com.gridness.GridnessParams;

import java.util.ArrayList;
import java.util.List;

/**
 * Heatmap of per-sample gridness values at sampleStride spacing. Each sample
 * scores using its own Gaussian-weighted radius window of nearby buildings,
 * gathered from surrounding tiles; the per-sample Hough is the Hough of the
 * tile that contains the sample.
 *
 * Sample (si, sj) lives at world position (si*S, sj*S) where S = sampleStride.
 */
public final class SampleGrid {

    private final int fieldWidth;
    private final int fieldHeight;
    private final int sampleStride;
    private final int nx;
    private final int ny;
    private final TileGrid tileGrid;

    private final double[] scores;
    private final boolean[] dirty;
    private boolean anyDirty = true;

    public SampleGrid(int fieldWidth, int fieldHeight, int sampleStride, TileGrid tileGrid) {
        this.fieldWidth = fieldWidth;
        this.fieldHeight = fieldHeight;
        this.sampleStride = sampleStride;
        this.tileGrid = tileGrid;
        this.nx = (fieldWidth + sampleStride - 1) / sampleStride;
        this.ny = (fieldHeight + sampleStride - 1) / sampleStride;
        this.scores = new double[nx * ny];
        this.dirty = new boolean[nx * ny];
        java.util.Arrays.fill(dirty, true);
    }

    public int nx() { return nx; }
    public int ny() { return ny; }
    public int sampleStride() { return sampleStride; }

    public double scoreAt(int si, int sj) { return scores[sj * nx + si]; }

    public int dirtyCount() {
        int n = 0;
        for (boolean b : dirty) if (b) n++;
        return n;
    }

    public boolean anyDirty() { return anyDirty; }

    /** Mark every sample dirty (used by from-scratch load). */
    public void markAllDirty() {
        java.util.Arrays.fill(dirty, true);
        anyDirty = true;
    }

    /**
     * Mark every sample that could query this tile's Hough or buildings as dirty.
     * A sample at (sx, sy) queries tile T iff sx is within R of T's bbox horizontally
     * AND sy is within R of T's bbox vertically (because we collect buildings from
     * tiles whose bbox is within R of the sample). Hough usage is contained inside
     * that bound too.
     */
    public void markDirtyAroundTile(int originX, int originY, int size, double radius) {
        int siMin = (int) Math.floor((originX - radius) / (double) sampleStride);
        int siMax = (int) Math.ceil((originX + size + radius) / (double) sampleStride);
        int sjMin = (int) Math.floor((originY - radius) / (double) sampleStride);
        int sjMax = (int) Math.ceil((originY + size + radius) / (double) sampleStride);
        if (siMin < 0) siMin = 0; if (siMax >= nx) siMax = nx - 1;
        if (sjMin < 0) sjMin = 0; if (sjMax >= ny) sjMax = ny - 1;
        if (siMin > siMax || sjMin > sjMax) return;
        for (int sj = sjMin; sj <= sjMax; sj++) {
            int rowBase = sj * nx;
            for (int si = siMin; si <= siMax; si++) {
                dirty[rowBase + si] = true;
            }
        }
        anyDirty = true;
    }

    /**
     * Snapshot dirty sample indices and clear the flags. Caller is responsible
     * for actually scoring them.
     */
    public int[] takeDirtyList() {
        int n = scores.length;
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (dirty[i]) { out[k++] = i; dirty[i] = false; }
        }
        anyDirty = false;
        if (k == n) return out;
        int[] trimmed = new int[k];
        System.arraycopy(out, 0, trimmed, 0, k);
        return trimmed;
    }

    /**
     * Score one sample by linear index. Reads from `tiles` and writes into the
     * sample scores array. Safe to call from multiple threads on disjoint indices.
     */
    public void scoreOne(int sampleIdx, Tile[] tiles, GridnessParams p) {
        int sj = sampleIdx / nx;
        int si = sampleIdx - sj * nx;
        int sx = si * sampleStride;
        int sy = sj * sampleStride;
        double R = p.radius;
        double sigma = R * p.sigmaFrac;
        double twoSigmaSq = 2.0 * sigma * sigma;
        double rSq = R * R;

        // Collect candidate buildings from tiles whose unpadded bbox is within R of (sx, sy).
        List<Building> selected = new ArrayList<>(16);
        DoubleList weights = new DoubleList(16);

        // Tile bbox at col c, row r is [originX[c], originX[c]+size) x [originY[r], originY[r]+size).
        // We want tiles where bbox is within R of (sx, sy).
        int cols = tileGrid.cols();
        int rows = tileGrid.rows();
        int ts = tileGrid.tileStride();
        int tsz = tileGrid.tileSize();
        // Coarse col/row range: tiles whose origin is within R + tsz of the sample.
        int colMin = Math.max(0, (int) Math.floor((sx - R - tsz) / (double) ts));
        int colMax = Math.min(cols - 1, (int) Math.ceil((sx + R) / (double) ts));
        int rowMin = Math.max(0, (int) Math.floor((sy - R - tsz) / (double) ts));
        int rowMax = Math.min(rows - 1, (int) Math.ceil((sy + R) / (double) ts));

        for (int r = rowMin; r <= rowMax; r++) {
            for (int c = colMin; c <= colMax; c++) {
                Tile t = tiles[r * cols + c];
                List<Building> bs = t.buildings();
                if (bs.isEmpty()) continue;
                for (Building b : bs) {
                    double dx = b.centroidX - sx;
                    double dy = b.centroidY - sy;
                    double d2 = dx * dx + dy * dy;
                    if (d2 > rSq) continue;
                    double w = Math.exp(-d2 / twoSigmaSq);
                    selected.add(b);
                    weights.add(w);
                }
            }
        }

        if (selected.size() < p.minBuildingsInWindow) {
            scores[sampleIdx] = 0.0;
            return;
        }

        // Hough source: the tile that contains this sample point (clamped to grid).
        int cc = Math.min(cols - 1, Math.max(0, sx / ts));
        int rr = Math.min(rows - 1, Math.max(0, sy / ts));
        double[] angles = tiles[rr * cols + cc].houghAngles();
        if (angles.length == 0) {
            scores[sampleIdx] = 0.0;
            return;
        }

        double[] wArr = weights.toArray();
        scores[sampleIdx] = FrameScorer.score(selected, wArr, angles, p);
    }

    /** Minimal growable double list. */
    private static final class DoubleList {
        double[] data;
        int size;
        DoubleList(int cap) { data = new double[Math.max(4, cap)]; }
        void add(double v) {
            if (size == data.length) {
                double[] nu = new double[data.length * 2];
                System.arraycopy(data, 0, nu, 0, size);
                data = nu;
            }
            data[size++] = v;
        }
        double[] toArray() {
            double[] out = new double[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
