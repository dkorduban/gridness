package com.gridness;

import com.gridness.internal.HoughDetector;
import com.gridness.internal.Tile;
import com.gridness.internal.TileGrid;
import com.gridness.internal.WallGrid;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Mutable wall grid + gridness heatmap.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Construct with field dimensions and {@link GridnessParams}.</li>
 *   <li>Initialize walls via {@link #loadFromField(boolean[][])} (from scratch)
 *       or by individual setters / {@link #applyBatch(int[], int[], boolean[], boolean)}.</li>
 *   <li>Query gridness via {@link #valueAt(int, int)} or {@link #readRect(int, int, int, int)}.</li>
 * </ul>
 *
 * <p>Not thread-safe. Internally may use ForkJoin to recompute dirty tiles in parallel.
 */
public final class Gridness {

    private final int width;
    private final int height;
    private final GridnessParams params;

    private final WallGrid walls;
    private final TileGrid tileGrid;
    private final Tile[] tiles;
    private final boolean[] dirty;
    private boolean anyDirty = true;

    private final HoughDetector hough;

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
        this.dirty = new boolean[n];
        for (int row = 0; row < tileGrid.rows(); row++) {
            for (int col = 0; col < tileGrid.cols(); col++) {
                int idx = tileGrid.tileIndex(col, row);
                tiles[idx] = new Tile(idx, tileGrid.originX(col), tileGrid.originY(row), params.tileSize);
                dirty[idx] = true;
            }
        }
        this.hough = new HoughDetector(params.houghThetaSteps);
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
            tileGrid.markTilesContaining(x, y, Tile.PAD, dirty);
            anyDirty = true;
        }
    }

    /**
     * Apply a batch of pixel changes.
     *
     * @param xs   per-op x coordinates
     * @param ys   per-op y coordinates
     * @param setOrUnset  true=set wall, false=unset wall
     * @param strict  if true, throws IllegalArgumentException when the same
     *                (x,y) appears with both true and false values; if false,
     *                the last op for each (x,y) wins.
     */
    public void applyBatch(int[] xs, int[] ys, boolean[] setOrUnset, boolean strict) {
        if (xs.length != ys.length || xs.length != setOrUnset.length)
            throw new IllegalArgumentException("xs/ys/setOrUnset length mismatch");
        int n = xs.length;
        if (n == 0) return;

        // Deduplicate by (x,y); last wins for non-strict, throw on conflict for strict.
        // For typical batches we use a long-keyed open-addressing map: key = ((long)x << 32) | (y & 0xFFFFFFFFL).
        long[] keys = new long[Math.max(16, Integer.highestOneBit(n) << 2)];
        byte[] vals = new byte[keys.length];  // 0 = empty, 1 = set, 2 = unset
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
        for (int h = 0; h < keys.length; h++) {
            if (keys[h] == Long.MIN_VALUE) continue;
            int x = (int) (keys[h] >>> 32);
            int y = (int) keys[h];
            boolean value = vals[h] == 1;
            boolean prev = walls.set(x, y, value);
            if (prev != value) {
                tileGrid.markTilesContaining(x, y, Tile.PAD, dirty);
                changed = true;
            }
        }
        if (changed) anyDirty = true;
    }

    /**
     * Replace the entire wall field. All tiles are marked dirty.
     * Expected shape: field[y][x].
     */
    public void loadFromField(boolean[][] field) {
        walls.clearTo(field);
        Arrays.fill(dirty, true);
        anyDirty = true;
    }

    // ---------------- read ----------------

    /**
     * Gridness value interpolated at integer pixel (x, y).
     */
    public double valueAt(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height)
            throw new IndexOutOfBoundsException("(" + x + "," + y + ") out of " + width + "x" + height);
        ensureClean();
        return interpolateAt(x, y);
    }

    /**
     * Read the gridness heatmap over the rectangle [x1, x2) x [y1, y2) at
     * sampleStride spacing. Result is a 2D array indexed as [j][i] where
     * j corresponds to ys at y1, y1 + sampleStride, ... and i to xs similarly.
     *
     * Both x1 < x2 and y1 < y2 are required, and the rectangle must lie inside
     * the field.
     */
    public double[][] readRect(int x1, int y1, int x2, int y2) {
        if (x1 < 0 || y1 < 0 || x2 > width || y2 > height || x1 >= x2 || y1 >= y2)
            throw new IllegalArgumentException("invalid rect (" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")");
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
        // Tile centers form an irregular grid (last column/row may be clamped).
        // For interpolation we use the regular tileStride grid for col/row indices.
        int cols = tileGrid.cols();
        int rows = tileGrid.rows();
        int ts = params.tileStride;

        double cf = (x - params.tileSize * 0.5) / ts;
        double rf = (y - params.tileSize * 0.5) / ts;
        int c0 = (int) Math.floor(cf);
        int r0 = (int) Math.floor(rf);
        double fx = cf - c0;
        double fy = rf - r0;

        if (params.interpolation == Interpolation.NEAREST) {
            int c = Math.max(0, Math.min(cols - 1, (int) Math.round(cf)));
            int r = Math.max(0, Math.min(rows - 1, (int) Math.round(rf)));
            return tiles[tileGrid.tileIndex(c, r)].score();
        }

        int c1 = c0 + 1;
        int r1 = r0 + 1;
        int cc0 = Math.max(0, Math.min(cols - 1, c0));
        int cc1 = Math.max(0, Math.min(cols - 1, c1));
        int rr0 = Math.max(0, Math.min(rows - 1, r0));
        int rr1 = Math.max(0, Math.min(rows - 1, r1));
        double v00 = tiles[tileGrid.tileIndex(cc0, rr0)].score();
        double v10 = tiles[tileGrid.tileIndex(cc1, rr0)].score();
        double v01 = tiles[tileGrid.tileIndex(cc0, rr1)].score();
        double v11 = tiles[tileGrid.tileIndex(cc1, rr1)].score();
        // Clamp blend weights when out of regular range (because we clamped indices).
        double bx = Math.max(0.0, Math.min(1.0, fx));
        double by = Math.max(0.0, Math.min(1.0, fy));
        double top = v00 * (1 - bx) + v10 * bx;
        double bot = v01 * (1 - bx) + v11 * bx;
        return top * (1 - by) + bot * by;
    }

    // ---------------- recompute ----------------

    private void ensureClean() {
        if (!anyDirty) return;
        // Snapshot dirty indices to a compact list.
        int n = tiles.length;
        int[] dirtyList = new int[n];
        int dn = 0;
        for (int i = 0; i < n; i++) if (dirty[i]) { dirtyList[dn++] = i; dirty[i] = false; }
        anyDirty = false;
        if (dn == 0) return;
        if (params.parallel && dn > 1) {
            ForkJoinPool pool = ForkJoinPool.commonPool();
            int dn_f = dn;
            pool.submit(() -> IntStream.range(0, dn_f).parallel().forEach(k -> {
                tiles[dirtyList[k]].recompute(walls, hough, params);
            })).join();
        } else {
            for (int k = 0; k < dn; k++) tiles[dirtyList[k]].recompute(walls, hough, params);
        }
    }

    /** Number of tiles currently waiting to be recomputed (visible for tests/diagnostics). */
    public int dirtyTileCount() {
        int n = 0;
        for (boolean b : dirty) if (b) n++;
        return n;
    }

    /** Number of tiles in the grid (cols * rows). */
    public int tileCount() { return tiles.length; }

    /** Tile cols (visible for tests). */
    public int tileCols() { return tileGrid.cols(); }
    /** Tile rows (visible for tests). */
    public int tileRows() { return tileGrid.rows(); }
}
