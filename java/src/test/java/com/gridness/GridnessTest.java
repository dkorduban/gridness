package com.gridness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GridnessTest {

    // Build a regular-grid wall layout: rectangular buildings on a base x base lattice with `gap` streets.
    private static boolean[][] regularGrid(int H, int W, int base, int gap, int margin) {
        boolean[][] field = new boolean[H][W];
        int period = base + gap;
        for (int y0 = margin; y0 + base <= H - margin; y0 += period) {
            for (int x0 = margin; x0 + base <= W - margin; x0 += period) {
                // hollow rectangle: only border becomes wall
                for (int x = x0; x < x0 + base; x++) {
                    field[y0][x] = true;
                    field[y0 + base - 1][x] = true;
                }
                for (int y = y0; y < y0 + base; y++) {
                    field[y][x0] = true;
                    field[y][x0 + base - 1] = true;
                }
            }
        }
        return field;
    }

    // Random scattered rectangles, expected low gridness.
    private static boolean[][] scattered(int H, int W, int n, long seed) {
        boolean[][] field = new boolean[H][W];
        java.util.Random r = new java.util.Random(seed);
        int placed = 0, attempts = 0;
        while (placed < n && attempts < n * 50) {
            attempts++;
            int w = 6 + r.nextInt(8);
            int h = 6 + r.nextInt(8);
            int x0 = 4 + r.nextInt(W - w - 8);
            int y0 = 4 + r.nextInt(H - h - 8);
            // Reject if overlapping any existing wall in extended bbox.
            boolean overlap = false;
            for (int y = y0 - 2; y < y0 + h + 2 && !overlap; y++) {
                if (y < 0 || y >= H) continue;
                for (int x = x0 - 2; x < x0 + w + 2; x++) {
                    if (x < 0 || x >= W) continue;
                    if (field[y][x]) { overlap = true; break; }
                }
            }
            if (overlap) continue;
            for (int x = x0; x < x0 + w; x++) {
                field[y0][x] = true;
                field[y0 + h - 1][x] = true;
            }
            for (int y = y0; y < y0 + h; y++) {
                field[y][x0] = true;
                field[y][x0 + w - 1] = true;
            }
            placed++;
        }
        return field;
    }

    @Test
    void defaultParamsScoreGridHigh() {
        // Locks in the "recommended default" path used by GridnessParams.defaults().
        int W = 256, H = 256;
        Gridness g = new Gridness(W, H, GridnessParams.defaults());
        g.loadFromField(regularGrid(H, W, 12, 4, 8));
        double mean = meanOverGrid(g);
        assertTrue(mean > 0.75, "defaults() grid mean=" + mean + ", expected > 0.75");
    }

    @Test
    void uniformGridScoresHigh() {
        int W = 256, H = 256;
        boolean[][] field = regularGrid(H, W, 12, 4, 8);
        Gridness g = new Gridness(W, H,
                GridnessParams.builder().tileSize(128).tileStride(64).sampleStride(8)
                        .interpolation(Interpolation.BILINEAR).parallel(false).build());
        g.loadFromField(field);
        double mean = meanOverGrid(g);
        assertTrue(mean > 0.75, "uniform grid mean=" + mean + ", expected > 0.75");
    }

    @Test
    void scatteredScoresLow() {
        int W = 256, H = 256;
        boolean[][] field = scattered(H, W, 30, 42L);
        Gridness g = new Gridness(W, H,
                GridnessParams.builder().tileSize(128).tileStride(64).sampleStride(8)
                        .interpolation(Interpolation.BILINEAR).parallel(false).build());
        g.loadFromField(field);
        double mean = meanOverGrid(g);
        assertTrue(mean < 0.6, "scattered mean=" + mean + ", expected < 0.6");
    }

    @Test
    void readRectShapeIsCorrect() {
        Gridness g = new Gridness(256, 256, GridnessParams.builder().sampleStride(8).build());
        double[][] out = g.readRect(0, 0, 64, 64);
        assertEquals(8, out.length);
        assertEquals(8, out[0].length);
    }

    @Test
    void incrementalEditChangesOnlyAffectedTiles() {
        Gridness g = new Gridness(256, 256,
                GridnessParams.builder().tileSize(128).tileStride(64).build());
        g.loadFromField(regularGrid(256, 256, 12, 4, 8));
        // First read forces all tiles clean.
        g.valueAt(128, 128);
        assertEquals(0, g.dirtyTileCount());
        // Flip a wall at a point in the interior; expect at most 4 dirty tiles
        // (the overlapping tile bracket at deep interior points).
        g.setPixel(100, 100);
        int dirty = g.dirtyTileCount();
        assertTrue(dirty >= 1 && dirty <= 4, "expected 1..4 dirty tiles, got " + dirty);
    }

    @Test
    void batchStrictThrowsOnConflict() {
        Gridness g = new Gridness(64, 64, GridnessParams.defaults());
        int[] xs = {5, 5};
        int[] ys = {7, 7};
        boolean[] vals = {true, false};
        assertThrows(IllegalArgumentException.class,
                () -> g.applyBatch(xs, ys, vals, true));
    }

    @Test
    void batchNonStrictLastWins() {
        Gridness g = new Gridness(64, 64, GridnessParams.defaults());
        int[] xs = {5, 5, 5};
        int[] ys = {7, 7, 7};
        boolean[] vals = {true, false, true};
        g.applyBatch(xs, ys, vals, false);
        assertTrue(g.isWall(5, 7));

        boolean[] vals2 = {true, false};
        g.applyBatch(new int[]{5, 5}, new int[]{7, 7}, vals2, false);
        assertFalse(g.isWall(5, 7));
    }

    @Test
    void megaBuildingProducesNonZeroScore() {
        // A single 100x100 hollow rectangle is bigger than 2*extractionPad in every
        // axis. Previously this disappeared entirely (no tile fully encloses it,
        // exterior flood-fill leaked from the tile boundary). With the global
        // exterior bitmap, each tile that overlaps the building's perimeter
        // extracts its truncated piece, so samples near it see *something*.
        int W = 256, H = 256;
        boolean[][] field = new boolean[H][W];
        int x0 = 70, y0 = 70, size = 100;
        // Outer hollow rect.
        for (int x = x0; x < x0 + size; x++) { field[y0][x] = true; field[y0 + size - 1][x] = true; }
        for (int y = y0; y < y0 + size; y++) { field[y][x0] = true; field[y][x0 + size - 1] = true; }
        // Add 8 normal-sized buildings inside so the megabuilding is in a city.
        addHollow(field, x0 + 10, y0 + 10, 12, 12);
        addHollow(field, x0 + 30, y0 + 10, 12, 12);
        addHollow(field, x0 + 50, y0 + 10, 12, 12);
        addHollow(field, x0 + 70, y0 + 10, 12, 12);
        addHollow(field, x0 + 10, y0 + 78, 12, 12);
        addHollow(field, x0 + 30, y0 + 78, 12, 12);
        addHollow(field, x0 + 50, y0 + 78, 12, 12);
        addHollow(field, x0 + 70, y0 + 78, 12, 12);

        Gridness g = new Gridness(W, H, GridnessParams.defaults());
        g.loadFromField(field);
        // Sample a small box around the megabuilding center: previously this would
        // have been 0; now it should be > 0 because each tile sees a truncated
        // piece.
        double[][] out = g.readRect(x0 + 40, y0 + 40, x0 + 60, y0 + 60);
        double max = 0;
        for (double[] row : out) for (double v : row) max = Math.max(max, v);
        assertTrue(max > 0.0, "megabuilding center max=" + max + ", expected > 0");
    }

    private static void addHollow(boolean[][] field, int x0, int y0, int w, int h) {
        int H = field.length, W = field[0].length;
        for (int x = x0; x < x0 + w; x++) {
            if (y0 >= 0 && y0 < H && x >= 0 && x < W) field[y0][x] = true;
            if (y0 + h - 1 >= 0 && y0 + h - 1 < H && x >= 0 && x < W) field[y0 + h - 1][x] = true;
        }
        for (int y = y0; y < y0 + h; y++) {
            if (x0 >= 0 && x0 < W && y >= 0 && y < H) field[y][x0] = true;
            if (x0 + w - 1 >= 0 && x0 + w - 1 < W && y >= 0 && y < H) field[y][x0 + w - 1] = true;
        }
    }

    @Test
    void heatmapIsSmooth() {
        // On a uniform grid the smooth heatmap should change gradually between
        // adjacent samples. Discrete tile-discontinuities would show up as big
        // step changes; bound the max |delta| between adjacent sample columns.
        int W = 256, H = 256;
        boolean[][] field = regularGrid(H, W, 12, 4, 8);
        GridnessParams p = GridnessParams.builder()
                .tileSize(64).tileStride(32).sampleStride(4).radius(30)
                .parallel(false).build();
        Gridness g = new Gridness(W, H, p);
        g.loadFromField(field);
        double[][] out = g.readRect(40, 40, W - 40, H - 40);
        double maxJump = 0;
        for (double[] row : out) {
            for (int i = 1; i < row.length; i++) {
                maxJump = Math.max(maxJump, Math.abs(row[i] - row[i - 1]));
            }
        }
        assertTrue(maxJump < 0.25,
                "expected smooth heatmap (max neighbor jump < 0.25), got " + maxJump);
    }

    @Test
    void loadFromFieldMarksAllDirty() {
        Gridness g = new Gridness(256, 256, GridnessParams.builder().tileSize(128).tileStride(64).build());
        // After construction every tile is dirty; force clean by a read.
        g.valueAt(0, 0);
        assertEquals(0, g.dirtyTileCount());
        g.loadFromField(new boolean[256][256]);
        assertEquals(g.tileCount(), g.dirtyTileCount());
    }

    @Test
    void incrementalMatchesFromScratch() {
        // Real dirty-propagation test: start from state A, force CLEAN, mutate to
        // state B incrementally; compare to a fresh load of state B.
        int W = 256, H = 256;
        boolean[][] initial = regularGrid(H, W, 12, 4, 8);
        GridnessParams p = GridnessParams.builder()
                .tileSize(128).tileStride(64).sampleStride(8).parallel(false).build();

        Gridness incr = new Gridness(W, H, p);
        incr.loadFromField(initial);
        // Force all tiles clean.
        incr.valueAt(W / 2, H / 2);
        assertEquals(0, incr.dirtyTileCount(), "all tiles should be clean after first read");

        // Mutate: toggle several cells across the field, including some near tile seams.
        int[] xs = {64, 128, 192, 100, 150, 50, 64, 128, 200};
        int[] ys = {100, 100, 100, 64, 128, 200, 64, 128, 150};
        boolean[] vals = {true, false, true, false, true, false, true, false, true};
        // Build the final field state by replaying these flips deterministically.
        boolean[][] finalField = new boolean[H][W];
        for (int y = 0; y < H; y++) System.arraycopy(initial[y], 0, finalField[y], 0, W);
        for (int i = 0; i < xs.length; i++) finalField[ys[i]][xs[i]] = vals[i];

        // Apply incrementally to incr.
        incr.applyBatch(xs, ys, vals, false);
        assertTrue(incr.dirtyTileCount() > 0, "expected some tiles dirty after edits");
        double[][] incrOut = incr.readRect(0, 0, W, H);

        // Fresh load of the same final state.
        Gridness fresh = new Gridness(W, H, p);
        fresh.loadFromField(finalField);
        double[][] freshOut = fresh.readRect(0, 0, W, H);

        for (int j = 0; j < incrOut.length; j++) {
            for (int i = 0; i < incrOut[0].length; i++) {
                assertEquals(freshOut[j][i], incrOut[j][i], 1e-9,
                        "mismatch at (" + i + "," + j + ")");
            }
        }
    }

    private static double meanOverGrid(Gridness g) {
        double[][] out = g.readRect(32, 32, g.width() - 32, g.height() - 32);
        double sum = 0;
        int n = 0;
        for (double[] row : out) for (double v : row) { sum += v; n++; }
        return sum / n;
    }
}
