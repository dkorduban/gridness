package com.gridness;

import org.junit.jupiter.api.Test;

class StrideStudyTest {

    // Helper: produce a regular grid + (optionally) a rotated patch in the right half,
    // so we can probe both a homogeneous Hough field and one with a boundary.
    private static boolean[][] uniformGrid(int H, int W, int base, int gap, int margin) {
        boolean[][] field = new boolean[H][W];
        int period = base + gap;
        for (int y0 = margin; y0 + base <= H - margin; y0 += period) {
            for (int x0 = margin; x0 + base <= W - margin; x0 += period) {
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

    private static boolean[][] twoDistricts(int H, int W) {
        boolean[][] field = new boolean[H][W];
        // Left half: axis-aligned regular grid.
        int base = 12, gap = 4, margin = 8;
        int period = base + gap;
        for (int y0 = margin; y0 + base <= H - margin; y0 += period) {
            for (int x0 = margin; x0 + base <= W / 2 - margin; x0 += period) {
                paintHollowRect(field, x0, y0, base, base);
            }
        }
        // Right half: same grid but rotated 25 degrees around the right-half center.
        double cx = 3 * W / 4.0, cy = H / 2.0;
        double theta = Math.toRadians(25);
        double cos = Math.cos(theta), sin = Math.sin(theta);
        for (int y0 = margin; y0 + base <= H - margin; y0 += period) {
            for (int x0 = W / 2 + margin; x0 + base <= W - margin; x0 += period) {
                paintRotatedHollowRect(field, x0, y0, base, base, cx, cy, cos, sin);
            }
        }
        return field;
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

    private static void paintRotatedHollowRect(boolean[][] field, int x0, int y0, int w, int h,
                                                 double cx, double cy, double cos, double sin) {
        int H = field.length, W = field[0].length;
        // Walk the four edges of the unrotated rect and stamp rotated pixel positions.
        for (int x = x0; x < x0 + w; x++) {
            stampRot(field, x, y0, cx, cy, cos, sin);
            stampRot(field, x, y0 + h - 1, cx, cy, cos, sin);
        }
        for (int y = y0; y < y0 + h; y++) {
            stampRot(field, x0, y, cx, cy, cos, sin);
            stampRot(field, x0 + w - 1, y, cx, cy, cos, sin);
        }
    }

    private static void stampRot(boolean[][] field, int x, int y,
                                  double cx, double cy, double cos, double sin) {
        int H = field.length, W = field[0].length;
        double rx = (x - cx) * cos - (y - cy) * sin + cx;
        double ry = (x - cx) * sin + (y - cy) * cos + cy;
        int ix = (int) Math.round(rx);
        int iy = (int) Math.round(ry);
        if (ix >= 0 && ix < W && iy >= 0 && iy < H) field[iy][ix] = true;
    }

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
    void compareOverlapForSmoothness() {
        int W = 768, H = 768;
        boolean[][] uniform = uniformGrid(H, W, 12, 4, 8);
        boolean[][] mixed = twoDistricts(H, W);

        int tileSize = 32;
        int[] strides = {16, 24, 32};
        int[] pads = {1, 4, 8, 16};

        System.out.println("\n=== Smoothness study (stride x pad): tileSize=32, sampleStride=8, radius=30 ===");
        System.out.printf("%-12s %-8s %-8s %-10s %-12s %-12s%n",
                "layout", "stride", "pad", "overlap%", "max_jump", "mean_jump");
        boolean[][][] fields = new boolean[][][]{ uniform, mixed };
        String[] names = new String[]{ "uniform", "two_dist" };
        for (int fi = 0; fi < fields.length; fi++) {
            for (int stride : strides) {
                for (int pad : pads) {
                    GridnessParams p = GridnessParams.builder()
                            .tileSize(tileSize).tileStride(stride)
                            .sampleStride(8).radius(30)
                            .extractionPad(pad)
                            .parallel(false).build();
                    Gridness g = new Gridness(W, H, p);
                    g.loadFromField(fields[fi]);
                    double[][] out = g.readRect(40, 40, W - 40, H - 40);
                    double overlap = 100.0 * (tileSize - stride) / tileSize;
                    System.out.printf("%-12s %-8d %-8d %-10.0f %-12.4f %-12.4f%n",
                            names[fi], stride, pad, overlap, maxNeighborJump(out), meanAbsJump(out));
                }
            }
        }
        System.out.println();
    }

    /** Probe whether a single giant building gets extracted under different pads. */
    @Test
    void hugeBuildingExtractionStudy() {
        int W = 256, H = 256;
        int[] sizes = {12, 24, 48, 96};  // building widths to test
        int tileSize = 32;
        int stride = 24;

        System.out.println("\n=== Huge-building study: tileSize=32, stride=24, sampleStride=8, radius=30 ===");
        System.out.printf("%-14s %-8s %-14s%n", "building_size", "pad", "mean_score@center");
        for (int sz : sizes) {
            // Centered hollow rectangle of size sz x sz.
            boolean[][] field = new boolean[H][W];
            int x0 = (W - sz) / 2, y0 = (H - sz) / 2;
            paintHollowRect(field, x0, y0, sz, sz);
            // Also place 4 buildings of similar size around it so per-tile windows have neighbors
            // (otherwise minBuildingsInWindow kicks in and score is 0 regardless of extraction).
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
                // Sample a small box around the center building's centroid.
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
    void compareOverlapForSpeed() {
        int W = 768, H = 768;
        boolean[][] field = uniformGrid(H, W, 12, 4, 8);
        int[] strides = {16, 24, 32};

        System.out.println("\n=== Speed study: tileSize=32, sampleStride=8, radius=30 ===");
        System.out.printf("%-10s %-14s %-14s %-14s%n",
                "stride", "fromScratch_ms", "singleEdit_ms", "tile_count");
        // Warm up the JVM a bit so the first config isn't unfairly slow.
        warmup(W, H, field);
        for (int stride : strides) {
            GridnessParams p = GridnessParams.builder()
                    .tileSize(32).tileStride(stride)
                    .sampleStride(8).radius(30)
                    .parallel(true).build();

            long t0 = System.nanoTime();
            Gridness g = new Gridness(W, H, p);
            g.loadFromField(field);
            g.valueAt(W / 2, H / 2);
            double fromScratch = (System.nanoTime() - t0) / 1e6;

            // Time 50 individual edits, each followed by a forced read.
            java.util.Random r = new java.util.Random(42);
            long t1 = System.nanoTime();
            for (int k = 0; k < 50; k++) {
                g.setPixel(r.nextInt(W), r.nextInt(H));
                g.valueAt(W / 2, H / 2);
            }
            double singleEdit = (System.nanoTime() - t1) / 1e6 / 50.0;

            System.out.printf("%-10d %-14.2f %-14.2f %-14d%n",
                    stride, fromScratch, singleEdit, g.tileCount());
        }
        System.out.println();
    }

    private static void warmup(int W, int H, boolean[][] field) {
        for (int i = 0; i < 2; i++) {
            Gridness g = new Gridness(W, H, GridnessParams.defaults());
            g.loadFromField(field);
            g.valueAt(W / 2, H / 2);
        }
    }
}
