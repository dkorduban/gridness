package com.gridness;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 2)
@Fork(1)
public class GridnessBenchmark {

    /** Field size (square). */
    @Param({"768"})
    public int size;

    /** Tile size (tile stride = tileSize/2). */
    @Param({"32", "64", "128"})
    public int tile;

    /** Per-sample window radius in pixels. */
    @Param({"15", "30", "60"})
    public int radius;

    /** Heatmap sample spacing in pixels. */
    @Param({"4", "8"})
    public int sampleStride;

    private boolean[][] field;
    private GridnessParams params;
    private Gridness preloaded;
    private int[] xs;
    private int[] ys;
    private boolean[] vals;
    private Random rng;

    @Setup(Level.Trial)
    public void setup() {
        rng = new Random(42);
        field = regularGrid(size, size, 12, 4, 8);
        params = GridnessParams.builder()
                .tileSize(tile)
                .tileStride(tile / 2)
                .sampleStride(sampleStride)
                .radius(radius)
                .parallel(true)
                .build();
        int batchN = 64;
        xs = new int[batchN];
        ys = new int[batchN];
        vals = new boolean[batchN];
        for (int i = 0; i < batchN; i++) {
            xs[i] = rng.nextInt(size);
            ys[i] = rng.nextInt(size);
            vals[i] = rng.nextBoolean();
        }
    }

    @Setup(Level.Invocation)
    public void perInvocation() {
        preloaded = new Gridness(size, size, params);
        preloaded.loadFromField(field);
        preloaded.valueAt(size / 2, size / 2);
    }

    @Benchmark
    public double fromScratch() {
        Gridness g = new Gridness(size, size, params);
        g.loadFromField(field);
        return g.valueAt(size / 2, size / 2);
    }

    @Benchmark
    public double singlePixelUpdate() {
        preloaded.setPixel(rng.nextInt(size), rng.nextInt(size));
        return preloaded.valueAt(size / 2, size / 2);
    }

    @Benchmark
    public double batchUpdate() {
        preloaded.applyBatch(xs, ys, vals, false);
        return preloaded.valueAt(size / 2, size / 2);
    }

    @Benchmark
    public double readRectFull() {
        return sum(preloaded.readRect(0, 0, size, size));
    }

    private static double sum(double[][] m) {
        double s = 0;
        for (double[] r : m) for (double v : r) s += v;
        return s;
    }

    private static boolean[][] regularGrid(int H, int W, int base, int gap, int margin) {
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
}
