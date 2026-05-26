package com.dkorduban.gridness;

import com.dkorduban.gridness.fixture.LayoutFixture;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Exploration tests that print parameter-sweep tables. Not strict pass/fail —
 * the assertions are minimal; the value is in the printed output.
 */
class StrideStudyTest {

    private static final Path FX = LayoutFixture.defaultDir();

    private static double maxNeighborJump(double[][] grid) {
        double max = 0;
        for (double[] row : grid) {
            for (int i = 1; i < row.length; i++) {
                max = Math.max(max, Math.abs(row[i] - row[i - 1]));
            }
        }
        for (int j = 1; j < grid.length; j++) {
            for (int i = 0; i < grid[0].length; i++) {
                max = Math.max(max, Math.abs(grid[j][i] - grid[j - 1][i]));
            }
        }
        return max;
    }

    private static double meanAbsJump(double[][] grid) {
        double sum = 0;
        int count = 0;
        for (double[] row : grid) {
            for (int i = 1; i < row.length; i++) {
                sum += Math.abs(row[i] - row[i - 1]);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    @Test
    void compareOverlapForSmoothness() throws Exception {
        // Probe pure grid (homogeneous) and a multi-character map (heterogeneous).
        LayoutFixture grid = LayoutFixture.load(FX, "grid_768");
        LayoutFixture mixed = LayoutFixture.load(FX, "four_districts_512");

        int tileSize = 32;
        int[] strides = {16, 24, 32};
        int[] pads = {1, 4, 8, 16};

        System.out.println("\n=== Smoothness study (stride x pad): tileSize=32, sampleStride=8, radius=30 ===");
        System.out.printf("%-14s %-8s %-8s %-10s %-12s %-12s%n",
                "layout", "stride", "pad", "overlap%", "max_jump", "mean_jump");
        LayoutFixture[] fixtures = { grid, mixed };
        String[] names = { "grid_768", "4dist_512" };
        for (int fi = 0; fi < fixtures.length; fi++) {
            LayoutFixture fx = fixtures[fi];
            for (int stride : strides) {
                for (int pad : pads) {
                    GridnessParams p = GridnessParams.builder()
                            .tileSize(tileSize).tileStride(stride)
                            .sampleStride(8).radius(30)
                            .extractionPad(pad)
                            .parallel(false).build();
                    Gridness g = new Gridness(fx.width, fx.height, p);
                    g.loadFromField(fx.raster);
                    double[][] out = g.readRect(40, 40, fx.width - 40, fx.height - 40);
                    double overlap = 100.0 * (tileSize - stride) / tileSize;
                    System.out.printf("%-14s %-8d %-8d %-10.0f %-12.4f %-12.4f%n",
                            names[fi], stride, pad, overlap, maxNeighborJump(out), meanAbsJump(out));
                }
            }
        }
        System.out.println();
    }

    /** Probe whether a single giant building gets extracted under different pads. */
    @Test
    void hugeBuildingExtractionStudy() {
        // Intrinsically parametric (size varies per row); no useful fixture form.
        int W = 256, H = 256;
        int[] sizes = {12, 24, 48, 96};
        int tileSize = 32;
        int stride = 24;

        System.out.println("\n=== Huge-building study: tileSize=32, stride=24, sampleStride=8, radius=30 ===");
        System.out.printf("%-14s %-8s %-14s%n", "building_size", "pad", "mean_score@center");
        for (int sz : sizes) {
            boolean[][] field = new boolean[H][W];
            int x0 = (W - sz) / 2, y0 = (H - sz) / 2;
            paintHollowRect(field, x0, y0, sz, sz);
            paintHollowRect(field, x0 - sz - 8, y0, sz, sz);
            paintHollowRect(field, x0 + sz + 8, y0, sz, sz);
            paintHollowRect(field, x0, y0 - sz - 8, sz, sz);
            paintHollowRect(field, x0, y0 + sz + 8, sz, sz);
            for (int pad : new int[]{1, 8, 16, 32, 64}) {
                GridnessParams p = GridnessParams.builder()
                        .tileSize(tileSize).tileStride(stride)
                        .sampleStride(8).radius(Math.max(30, sz))
                        .extractionPad(pad)
                        .minBuildingsInWindow(2)
                        .parallel(false).build();
                Gridness g = new Gridness(W, H, p);
                g.loadFromField(field);
                double[][] out = g.readRect(W / 2 - 8, H / 2 - 8, W / 2 + 8, H / 2 + 8);
                double sum = 0; int c = 0;
                for (double[] r : out) for (double v : r) { sum += v; c++; }
                double mean = c == 0 ? 0 : sum / c;
                System.out.printf("%-14d %-8d %-14.4f%n", sz, pad, mean);
            }
        }
        System.out.println();
    }

    @Test
    void compareOverlapForSpeed() throws Exception {
        LayoutFixture fx = LayoutFixture.load(FX, "grid_768");
        int[] strides = {16, 24, 32};

        System.out.println("\n=== Speed study (grid_768): tileSize=32, sampleStride=8, radius=30 ===");
        System.out.printf("%-10s %-14s %-14s %-14s%n",
                "stride", "fromScratch_ms", "singleEdit_ms", "tile_count");
        warmup(fx);
        for (int stride : strides) {
            GridnessParams p = GridnessParams.builder()
                    .tileSize(32).tileStride(stride)
                    .sampleStride(8).radius(30)
                    .parallel(true).build();

            long t0 = System.nanoTime();
            Gridness g = new Gridness(fx.width, fx.height, p);
            g.loadFromField(fx.raster);
            g.valueAt(fx.width / 2, fx.height / 2);
            double fromScratch = (System.nanoTime() - t0) / 1e6;

            java.util.Random r = new java.util.Random(42);
            long t1 = System.nanoTime();
            for (int k = 0; k < 50; k++) {
                g.setPixel(r.nextInt(fx.width), r.nextInt(fx.height));
                g.valueAt(fx.width / 2, fx.height / 2);
            }
            double singleEdit = (System.nanoTime() - t1) / 1e6 / 50.0;

            System.out.printf("%-10d %-14.2f %-14.2f %-14d%n",
                    stride, fromScratch, singleEdit, g.tileCount());
        }
        System.out.println();
    }

    private static void warmup(LayoutFixture fx) {
        for (int i = 0; i < 2; i++) {
            Gridness g = new Gridness(fx.width, fx.height, GridnessParams.defaults());
            g.loadFromField(fx.raster);
            g.valueAt(fx.width / 2, fx.height / 2);
        }
    }

    private static void paintHollowRect(boolean[][] field, int x0, int y0, int w, int h) {
        int H = field.length, W = field[0].length;
        for (int x = x0; x < x0 + w; x++) {
            if (y0 >= 0 && y0 < H && x >= 0 && x < W) field[y0][x] = true;
            if (y0 + h - 1 < H && y0 + h - 1 >= 0 && x >= 0 && x < W) field[y0 + h - 1][x] = true;
        }
        for (int y = y0; y < y0 + h; y++) {
            if (x0 >= 0 && x0 < W && y >= 0 && y < H) field[y][x0] = true;
            if (x0 + w - 1 < W && x0 + w - 1 >= 0 && y >= 0 && y < H) field[y][x0 + w - 1] = true;
        }
    }
}
