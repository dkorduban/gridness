package com.gridness;

import com.gridness.fixture.LayoutFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GridnessTest {

    private static Path FX;

    @BeforeAll
    static void resolveFixtures() {
        FX = LayoutFixture.defaultDir();
    }

    private static LayoutFixture fx(String name) throws Exception {
        return LayoutFixture.load(FX, name);
    }

    private static Gridness load(LayoutFixture fx, GridnessParams p) {
        Gridness g = new Gridness(fx.width, fx.height, p);
        g.loadFromField(fx.raster);
        return g;
    }

    private static double meanOver(Gridness g, int margin) {
        double[][] out = g.readRect(margin, margin, g.width() - margin, g.height() - margin);
        double sum = 0;
        int n = 0;
        for (double[] row : out) for (double v : row) { sum += v; n++; }
        return sum / n;
    }

    @Test
    void defaultParamsScoreGridHigh() throws Exception {
        LayoutFixture grid = fx("grid_uniform_256");
        Gridness g = load(grid, GridnessParams.defaults());
        double mean = meanOver(g, 32);
        assertTrue(mean > 0.75, "defaults() grid mean=" + mean + ", expected > 0.75");
    }

    @Test
    void uniformGridScoresHigh() throws Exception {
        LayoutFixture grid = fx("grid_uniform_256");
        Gridness g = load(grid, GridnessParams.builder()
                .tileSize(128).tileStride(64).sampleStride(8)
                .parallel(false).build());
        double mean = meanOver(g, 32);
        assertTrue(mean > 0.75, "grid mean=" + mean);
    }

    @Test
    void scatteredScoresLow() throws Exception {
        // With minBuildingsInWindow=2 (default), scattered scores mid-range
        // (~0.67) rather than low — pairs of randomly-placed buildings can
        // happen to be axis-aligned. Separation from uniform grid (>0.75) is
        // narrower but still meaningful.
        LayoutFixture scat = fx("scattered_256");
        Gridness g = load(scat, GridnessParams.builder()
                .tileSize(128).tileStride(64).sampleStride(8)
                .parallel(false).build());
        double mean = meanOver(g, 32);
        assertTrue(mean < 0.72, "scattered mean=" + mean + ", expected < 0.72");
    }

    /**
     * Sparse fixtures (few buildings per R-window) need a lower Hough peak
     * threshold than the python-matched default of 18, because each tile
     * sees only a handful of walls and the Hough accumulator never reaches
     * 18 votes even for a clean grid.
     */
    private static GridnessParams sparseLayoutParams() {
        return GridnessParams.builder().houghMinPeakWeight(5).build();
    }

    @Test
    void longhouseBlockScoresHigh() throws Exception {
        // 22-wide x 60-tall in a 160x256 canvas yields only 16 buildings total.
        // Needs radius=60 to reach the next row + permissive Hough threshold.
        LayoutFixture lh = fx("longhouses_22x60");
        Gridness g = load(lh, GridnessParams.builder()
                .radius(60).houghMinPeakWeight(5).build());
        double mean = meanOver(g, 16);
        assertTrue(mean > 0.65, "longhouses_22x60 mean=" + mean + ", expected > 0.65");
    }

    @Test
    void longLonghousesScoreHigh() throws Exception {
        LayoutFixture lh = fx("longhouses_12x100");
        Gridness g = load(lh, sparseLayoutParams());
        double mean = meanOver(g, 16);
        assertTrue(mean > 0.65, "longhouses_12x100 mean=" + mean + ", expected > 0.65");
    }

    @Test
    void twoDistrictsContrast() throws Exception {
        // Left half: dense uniform grid (high). Right half: scattered (low).
        LayoutFixture td = fx("two_districts_256x512");
        Gridness g = load(td, GridnessParams.defaults());
        double left = sliceMean(g, 32, 32, td.width / 2 - 16, td.height - 32);
        double right = sliceMean(g, td.width / 2 + 16, 32, td.width - 32, td.height - 32);
        assertTrue(left > right + 0.2,
                "expected left district to score noticeably higher; left=" + left + " right=" + right);
        assertTrue(left > 0.6, "left=" + left);
        assertTrue(right < 0.6, "right=" + right);
    }

    @Test
    void fourDistrictsHaveDistinctScores() throws Exception {
        // Coarse sanity: each quadrant has a different layout regime — at least
        // one should land above 0.5 and at least one below 0.5.
        LayoutFixture fd = fx("four_districts_512");
        Gridness g = load(fd, GridnessParams.defaults());
        int half = fd.width / 2;
        double nw = sliceMean(g, 16, 16, half - 16, half - 16);
        double ne = sliceMean(g, half + 16, 16, fd.width - 16, half - 16);
        double sw = sliceMean(g, 16, half + 16, half - 16, fd.height - 16);
        double se = sliceMean(g, half + 16, half + 16, fd.width - 16, fd.height - 16);
        double max = Math.max(Math.max(nw, ne), Math.max(sw, se));
        double min = Math.min(Math.min(nw, ne), Math.min(sw, se));
        assertTrue(max - min > 0.15,
                "districts should differ; nw=" + nw + " ne=" + ne + " sw=" + sw + " se=" + se);
    }

    @Test
    void readRectShapeIsCorrect() {
        Gridness g = new Gridness(256, 256, GridnessParams.builder().sampleStride(8).build());
        double[][] out = g.readRect(0, 0, 64, 64);
        assertEquals(8, out.length);
        assertEquals(8, out[0].length);
    }

    @Test
    void incrementalEditChangesOnlyAffectedTiles() throws Exception {
        Gridness g = new Gridness(256, 256,
                GridnessParams.builder().tileSize(128).tileStride(64).build());
        g.loadFromField(fx("grid_uniform_256").raster);
        g.valueAt(128, 128);
        assertEquals(0, g.dirtyTileCount());
        g.setPixel(100, 100);
        int dirty = g.dirtyTileCount();
        assertTrue(dirty >= 1 && dirty <= 4, "expected 1..4 dirty tiles, got " + dirty);
    }

    @Test
    void batchStrictThrowsOnConflict() {
        Gridness g = new Gridness(64, 64, GridnessParams.defaults());
        assertThrows(IllegalArgumentException.class,
                () -> g.applyBatch(new int[]{5, 5}, new int[]{7, 7}, new boolean[]{true, false}, true));
    }

    @Test
    void batchNonStrictLastWins() {
        Gridness g = new Gridness(64, 64, GridnessParams.defaults());
        g.applyBatch(new int[]{5, 5, 5}, new int[]{7, 7, 7},
                new boolean[]{true, false, true}, false);
        assertTrue(g.isWall(5, 7));
        g.applyBatch(new int[]{5, 5}, new int[]{7, 7}, new boolean[]{true, false}, false);
        assertFalse(g.isWall(5, 7));
    }

    @Test
    void megaBuildingProducesNonZeroScore() throws Exception {
        // Sparse layout (one big building + 8 small) — needs permissive Hough.
        LayoutFixture mega = fx("mega_in_grid_256");
        Gridness g = load(mega, sparseLayoutParams());
        double[][] out = g.readRect(110, 110, 130, 130);
        double max = 0;
        for (double[] row : out) for (double v : row) max = Math.max(max, v);
        assertTrue(max > 0.0, "megabuilding center max=" + max + ", expected > 0");
    }

    @Test
    void heatmapIsSmooth() throws Exception {
        LayoutFixture grid = fx("grid_uniform_256");
        GridnessParams p = GridnessParams.builder()
                .tileSize(64).tileStride(32).sampleStride(4).radius(30)
                .parallel(false).build();
        Gridness g = load(grid, p);
        double[][] out = g.readRect(40, 40, grid.width - 40, grid.height - 40);
        double maxJump = 0;
        for (double[] row : out) {
            for (int i = 1; i < row.length; i++) {
                maxJump = Math.max(maxJump, Math.abs(row[i] - row[i - 1]));
            }
        }
        assertTrue(maxJump < 0.25, "expected smooth heatmap (max jump < 0.25), got " + maxJump);
    }

    @Test
    void loadFromFieldMarksAllDirty() {
        Gridness g = new Gridness(256, 256, GridnessParams.builder().tileSize(128).tileStride(64).build());
        g.valueAt(0, 0);
        assertEquals(0, g.dirtyTileCount());
        g.loadFromField(new boolean[256][256]);
        assertEquals(g.tileCount(), g.dirtyTileCount());
    }

    @Test
    void incrementalMatchesFromScratch() throws Exception {
        LayoutFixture grid = fx("grid_uniform_256");
        int W = grid.width, H = grid.height;
        boolean[][] initial = grid.raster;
        GridnessParams p = GridnessParams.builder()
                .tileSize(128).tileStride(64).sampleStride(8).parallel(false).build();

        Gridness incr = new Gridness(W, H, p);
        incr.loadFromField(initial);
        incr.valueAt(W / 2, H / 2);
        assertEquals(0, incr.dirtyTileCount());

        int[] xs = {64, 128, 192, 100, 150, 50, 64, 128, 200};
        int[] ys = {100, 100, 100, 64, 128, 200, 64, 128, 150};
        boolean[] vals = {true, false, true, false, true, false, true, false, true};
        boolean[][] finalField = new boolean[H][W];
        for (int y = 0; y < H; y++) System.arraycopy(initial[y], 0, finalField[y], 0, W);
        for (int i = 0; i < xs.length; i++) finalField[ys[i]][xs[i]] = vals[i];

        incr.applyBatch(xs, ys, vals, false);
        assertTrue(incr.dirtyTileCount() > 0);
        double[][] incrOut = incr.readRect(0, 0, W, H);

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

    @Test
    void grid768ScoresHigh() throws Exception {
        // Stress test on a large pure grid: should land high regardless of position.
        LayoutFixture grid = fx("grid_768");
        long t0 = System.nanoTime();
        Gridness g = load(grid, GridnessParams.defaults());
        double mean = meanOver(g, 64);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(mean > 0.75, "grid_768 mean=" + mean);
        assertTrue(ms < 30_000, "grid_768 from-scratch took " + ms + "ms");
    }

    @Test
    void city768FromScratchTimebox() throws Exception {
        // 768x768 mixed-character city; must complete a full from-scratch evaluation
        // well under the Rule 2 budget (<60s on a 200x200 raster — 768x768 is
        // ~15x more cells, so we allow up to 45s).
        LayoutFixture city = fx("city_768");
        long t0 = System.nanoTime();
        Gridness g = load(city, GridnessParams.defaults());
        g.valueAt(city.width / 2, city.height / 2);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 45_000, "city_768 from-scratch took " + ms + "ms");
    }

    private static double sliceMean(Gridness g, int x1, int y1, int x2, int y2) {
        double[][] out = g.readRect(x1, y1, x2, y2);
        double sum = 0;
        int n = 0;
        for (double[] row : out) for (double v : row) { sum += v; n++; }
        return sum / n;
    }
}
