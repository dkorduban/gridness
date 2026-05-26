package com.dkorduban.gridness;

import com.dkorduban.gridness.internal.ExteriorBitmap;
import com.dkorduban.gridness.internal.GlobalHough;
import com.dkorduban.gridness.internal.HoughDetector;
import com.dkorduban.gridness.internal.SampleGrid;
import com.dkorduban.gridness.internal.Tile;
import com.dkorduban.gridness.internal.TileGrid;
import com.dkorduban.gridness.internal.WallGrid;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Mutable wall grid + smooth gridness heatmap.
 *
 * <p>Per-sample Gaussian-weighted scoring (each sample at sampleStride spacing
 * uses its own radius-R window of buildings) layered on per-tile Hough +
 * building extraction (cheap unit of incremental recomputation).
 *
 * <p>Not thread-safe; uses ForkJoin internally when {@code parallel=true}.
 */
public final class Gridness {

    private final int width;
    private final int height;
    private final GridnessParams params;

    private final WallGrid walls;
    private final TileGrid tileGrid;
    private final Tile[] tiles;
    private final boolean[] tileDirty;
    private boolean anyTileDirty = true;

    private final HoughDetector hough;
    private final SampleGrid samples;
    private final ExteriorBitmap exterior;
    private boolean exteriorDirty = true;
    private final GlobalHough globalHough;
    private boolean globalHoughDirtyFromLoad = true;

    public Gridness(int width, int height, GridnessParams params) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("width and height must be positive");
        this.width = width;
        this.height = height;
        this.params = params;
        this.walls = new WallGrid(width, height);
        this.tileGrid = new TileGrid(width, height, params.tileSize, params.tileStride);
        int n = tileGrid.tileCount();
        this.tiles = new Tile[n];
        this.tileDirty = new boolean[n];
        for (int row = 0; row < tileGrid.rows(); row++) {
            for (int col = 0; col < tileGrid.cols(); col++) {
                int idx = tileGrid.tileIndex(col, row);
                tiles[idx] = new Tile(idx, tileGrid.originX(col), tileGrid.originY(row), params.tileSize);
                tileDirty[idx] = true;
            }
        }
        this.hough = new HoughDetector(params.houghThetaSteps);
        this.samples = new SampleGrid(width, height, params.sampleStride, tileGrid);
        this.exterior = new ExteriorBitmap(width, height);
        this.globalHough = params.useGlobalHough ? new GlobalHough(width, height, params.houghThetaSteps) : null;
    }

    public int width() { return width; }
    public int height() { return height; }
    public GridnessParams params() { return params; }

    // ---------------- setters ----------------

    public boolean isWall(int x, int y) { return walls.get(x, y); }

    public void setPixel(int x, int y) { changeOne(x, y, true); }
    public void unsetPixel(int x, int y) { changeOne(x, y, false); }

    private void changeOne(int x, int y, boolean value) {
        boolean prev = walls.set(x, y, value);
        if (prev != value) {
            markTilesAndSamplesAffected(x, y);
            anyTileDirty = true;
            if (!exteriorDirty) propagateExteriorEdit(x, y, prev, value);
            if (globalHough != null && !globalHoughDirtyFromLoad) {
                globalHough.applyEdit(x, y, prev, value);
                // Defer angle-change check + global tile invalidation to ensureClean.
            }
        }
    }

    /**
     * Apply the wall flip to the exterior bitmap incrementally and mark every
     * tile whose padded read-region overlaps any cell that changed exterior
     * status (so its building extraction is re-run).
     */
    private void propagateExteriorEdit(int x, int y, boolean prev, boolean value) {
        int n = exterior.updateAfterEdit(walls, x, y, prev, value);
        for (int k = 0; k < n; k++) {
            int idx = exterior.changedAt(k);
            int cx = idx % width;
            int cy = idx / width;
            if (cx == x && cy == y) continue;
            markTilesAndSamplesAffected(cx, cy);
        }
    }

    /**
     * Mark every tile whose padded read-region covers (x, y) as dirty, AND for
     * each newly-dirty tile mark every sample that could query it.
     */
    private void markTilesAndSamplesAffected(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        int cols = tileGrid.cols();
        int cMin = tileGrid.colMinForXPad(x, params.extractionPad);
        int cMax = tileGrid.colMaxForXPad(x, params.extractionPad);
        int rMin = tileGrid.rowMinForYPad(y, params.extractionPad);
        int rMax = tileGrid.rowMaxForYPad(y, params.extractionPad);
        for (int r = rMin; r <= rMax; r++) {
            int base = r * cols;
            for (int c = cMin; c <= cMax; c++) {
                int t = base + c;
                if (!tileDirty[t]) {
                    tileDirty[t] = true;
                    Tile tile = tiles[t];
                    samples.markDirtyAroundTile(tile.originX, tile.originY, tile.size, params.radius);
                }
            }
        }
    }

    public void applyBatch(int[] xs, int[] ys, boolean[] setOrUnset, boolean strict) {
        applyBatch(xs, ys, setOrUnset, xs.length, strict);
    }

    /** Like {@link #applyBatch(int[], int[], boolean[], boolean)} but uses only the first {@code n}
     *  entries of each array. Lets callers reuse pre-allocated max-size buffers. */
    public void applyBatch(int[] xs, int[] ys, boolean[] setOrUnset, int n, boolean strict) {
        if (xs.length < n || ys.length < n || setOrUnset.length < n)
            throw new IllegalArgumentException("xs/ys/setOrUnset too short for n=" + n);
        if (n == 0) return;

        long[] keys = new long[Math.max(16, Integer.highestOneBit(n) << 2)];
        byte[] vals = new byte[keys.length];
        Arrays.fill(keys, Long.MIN_VALUE);

        for (int i = 0; i < n; i++) {
            int x = xs[i], y = ys[i];
            if (x < 0 || x >= width || y < 0 || y >= height)
                throw new IndexOutOfBoundsException("(" + x + "," + y + ") out of " + width + "x" + height);
            long k = (((long) x) << 32) | (y & 0xFFFFFFFFL);
            byte newVal = setOrUnset[i] ? (byte) 1 : (byte) 2;
            int h = Long.hashCode(k) & (keys.length - 1);
            while (true) {
                if (keys[h] == Long.MIN_VALUE) {
                    keys[h] = k;
                    vals[h] = newVal;
                    break;
                }
                if (keys[h] == k) {
                    if (strict && vals[h] != newVal) {
                        throw new IllegalArgumentException(
                                "strict batch: conflicting ops at (" + x + "," + y + ")");
                    }
                    vals[h] = newVal;
                    break;
                }
                h = (h + 1) & (keys.length - 1);
            }
        }

        boolean changed = false;
        // Count distinct flipped cells first; if many, batch the exterior
        // bitmap update by deferring to a single full recompute in ensureClean.
        // This trades N small verify-BFSes for one global flood-fill,
        // which is a win whenever N is large enough (typically >~10).
        int distinctFlips = 0;
        for (int h = 0; h < keys.length; h++) {
            if (keys[h] == Long.MIN_VALUE) continue;
            int x = (int) (keys[h] >>> 32);
            int y = (int) keys[h];
            if (walls.get(x, y) != (vals[h] == 1)) distinctFlips++;
        }
        boolean deferExterior = exteriorDirty || distinctFlips > 8;
        for (int h = 0; h < keys.length; h++) {
            if (keys[h] == Long.MIN_VALUE) continue;
            int x = (int) (keys[h] >>> 32);
            int y = (int) keys[h];
            boolean value = vals[h] == 1;
            boolean prev = walls.set(x, y, value);
            if (prev != value) {
                markTilesAndSamplesAffected(x, y);
                if (!deferExterior) propagateExteriorEdit(x, y, prev, value);
                if (globalHough != null && !globalHoughDirtyFromLoad) {
                    globalHough.applyEdit(x, y, prev, value);
                }
                changed = true;
            }
        }
        if (changed) {
            anyTileDirty = true;
            if (deferExterior) exteriorDirty = true;
        }
    }

    public void loadFromField(boolean[][] field) {
        walls.clearTo(field);
        Arrays.fill(tileDirty, true);
        anyTileDirty = true;
        samples.markAllDirty();
        exteriorDirty = true;
        if (globalHough != null) globalHoughDirtyFromLoad = true;
    }

    // ---------------- read ----------------

    public double valueAt(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height)
            throw new IndexOutOfBoundsException("(" + x + "," + y + ") out of " + width + "x" + height);
        ensureClean();
        return interpolateAt(x, y);
    }

    /**
     * Read gridness over rect [x1,x2) x [y1,y2) at sampleStride spacing.
     * Result is double[ny][nx] (j=row, i=col), each entry the scored value at
     * the corresponding sample point. With interpolation=NEAREST those are
     * exact sample-grid values; with BILINEAR they are interpolated from the
     * underlying samples (only relevant if x1/y1 aren't sample-aligned or
     * sampleStride doesn't divide the rect cleanly).
     */
    public double[][] readRect(int x1, int y1, int x2, int y2) {
        if (x1 < 0 || y1 < 0 || x2 > width || y2 > height || x1 >= x2 || y1 >= y2)
            throw new IllegalArgumentException("invalid rect");
        ensureClean();

        int s = params.sampleStride;
        int nx = (x2 - x1 + s - 1) / s;
        int ny = (y2 - y1 + s - 1) / s;
        double[][] out = new double[ny][nx];
        for (int j = 0; j < ny; j++) {
            int y = y1 + j * s;
            if (y >= y2) y = y2 - 1;
            for (int i = 0; i < nx; i++) {
                int x = x1 + i * s;
                if (x >= x2) x = x2 - 1;
                out[j][i] = interpolateAt(x, y);
            }
        }
        return out;
    }

    private double interpolateAt(int x, int y) {
        int s = params.sampleStride;
        int nx = samples.nx();
        int ny = samples.ny();
        double cf = x / (double) s;
        double rf = y / (double) s;
        int c0 = (int) Math.floor(cf);
        int r0 = (int) Math.floor(rf);

        if (params.interpolation == Interpolation.NEAREST) {
            int c = Math.max(0, Math.min(nx - 1, (int) Math.round(cf)));
            int r = Math.max(0, Math.min(ny - 1, (int) Math.round(rf)));
            return samples.scoreAt(c, r);
        }

        int c1 = c0 + 1;
        int r1 = r0 + 1;
        double fx = cf - c0;
        double fy = rf - r0;
        int cc0 = Math.max(0, Math.min(nx - 1, c0));
        int cc1 = Math.max(0, Math.min(nx - 1, c1));
        int rr0 = Math.max(0, Math.min(ny - 1, r0));
        int rr1 = Math.max(0, Math.min(ny - 1, r1));
        double v00 = samples.scoreAt(cc0, rr0);
        double v10 = samples.scoreAt(cc1, rr0);
        double v01 = samples.scoreAt(cc0, rr1);
        double v11 = samples.scoreAt(cc1, rr1);
        double bx = Math.max(0.0, Math.min(1.0, fx));
        double by = Math.max(0.0, Math.min(1.0, fy));
        double top = v00 * (1 - bx) + v10 * bx;
        double bot = v01 * (1 - bx) + v11 * bx;
        return top * (1 - by) + bot * by;
    }

    // ---------------- recompute pipeline ----------------

    private double[] cachedGlobalAngles = null;

    private void ensureClean() {
        if (exteriorDirty) {
            exterior.recompute(walls);
            exteriorDirty = false;
        }
        if (globalHough != null) {
            if (globalHoughDirtyFromLoad) {
                globalHough.recompute(walls);
                globalHoughDirtyFromLoad = false;
                cachedGlobalAngles = null;  // force angle refresh below
            }
            double[] newAngles = globalHough.dominantAngles(
                    params.houghNumPeaks, params.houghThresholdFrac,
                    params.houghMinPeakWeight, params.houghMinAngleSepDeg);
            if (!angleArraysEqual(cachedGlobalAngles, newAngles)) {
                cachedGlobalAngles = newAngles;
                // Angles changed → every tile's stored angles + every sample's
                // score depend on them. Full invalidation.
                Arrays.fill(tileDirty, true);
                anyTileDirty = true;
                samples.markAllDirty();
            }
        }
        if (anyTileDirty) recomputeDirtyTiles();
        if (samples.anyDirty()) recomputeDirtySamples();
    }

    private static boolean angleArraysEqual(double[] a, double[] b) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private void recomputeDirtyTiles() {
        int n = tiles.length;
        int[] dirtyList = new int[n];
        int dn = 0;
        for (int i = 0; i < n; i++) if (tileDirty[i]) { dirtyList[dn++] = i; tileDirty[i] = false; }
        anyTileDirty = false;
        if (dn == 0) return;
        final double[] anglesForTiles = cachedGlobalAngles;  // null when global Hough off
        if (params.parallel && dn > 1) {
            final int dnF = dn;
            ForkJoinPool.commonPool().submit(() -> IntStream.range(0, dnF).parallel().forEach(k -> {
                tiles[dirtyList[k]].recompute(walls, exterior, hough, params, anglesForTiles);
            })).join();
        } else {
            for (int k = 0; k < dn; k++) tiles[dirtyList[k]].recompute(walls, exterior, hough, params, anglesForTiles);
        }
    }

    private void recomputeDirtySamples() {
        int[] dirty = samples.takeDirtyList();
        if (dirty.length == 0) return;
        if (params.parallel && dirty.length > 64) {
            ForkJoinPool.commonPool().submit(() -> IntStream.of(dirty).parallel().forEach(idx ->
                    samples.scoreOne(idx, tiles, params))).join();
        } else {
            for (int idx : dirty) samples.scoreOne(idx, tiles, params);
        }
    }

    /** Diagnostics. */
    public int dirtyTileCount() {
        int n = 0;
        for (boolean b : tileDirty) if (b) n++;
        return n;
    }
    public int dirtySampleCount() { return samples.dirtyCount(); }
    public int tileCount() { return tiles.length; }
    public int tileCols() { return tileGrid.cols(); }
    public int tileRows() { return tileGrid.rows(); }
    public int sampleCount() { return samples.nx() * samples.ny(); }
}
