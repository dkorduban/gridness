package com.gridness;

import com.gridness.fixture.LayoutFixture;
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

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Realistic per-tick benchmarks. Simulates Song of Syx oddjobbers piling onto
 * a small number of in-progress buildings: each tick, {@link #ACTIVE} buildings
 * advance by {@link #CELLS_PER_TICK} cells (placed or removed). When a building
 * fully completes, a new random building from the fixture replaces it.
 *
 * <p>Build and dismantle scenarios are separate {@code @Benchmark} methods.
 * Hyperparameter grid is intentionally small per the user spec.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class GridnessBenchmark {

    private static final int ACTIVE = 3;
    private static final int CELLS_PER_TICK = 2;
    private static final int BATCH = ACTIVE * CELLS_PER_TICK;

    /** Shared scenario state. Subclasses pick build-vs-dismantle initial fill. */
    public abstract static class ScenarioState {

        Gridness g;
        int W, H;
        List<int[][]> buildings;
        Random rng;
        int sampleX, sampleY;

        // Pre-allocated batch arrays so the timed region doesn't allocate.
        final int[] xs = new int[BATCH];
        final int[] ys = new int[BATCH];
        final boolean[] vals = new boolean[BATCH];

        // Active building slots.
        final int[] activeBuildingIdx = new int[ACTIVE];
        final int[] activeCursor = new int[ACTIVE];
        final int[][] activeOrder = new int[ACTIVE][];

        protected abstract String fixtureName();
        protected abstract boolean[][] initialField(LayoutFixture fx);
        protected abstract boolean tickValue();

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            LayoutFixture fx = LayoutFixture.load(LayoutFixture.defaultDir(), fixtureName());
            W = fx.width;
            H = fx.height;
            buildings = fx.buildings;
            sampleX = W / 2;
            sampleY = H / 2;
            rng = new Random(42);
            g = new Gridness(W, H, GridnessParams.defaults());
            boolean[][] initial = initialField(fx);
            g.loadFromField(initial);
            g.valueAt(sampleX, sampleY);
            for (int i = 0; i < ACTIVE; i++) startNew(i);
        }

        void startNew(int slot) {
            int idx = rng.nextInt(buildings.size());
            activeBuildingIdx[slot] = idx;
            activeCursor[slot] = 0;
            int[][] cells = buildings.get(idx);
            int n = cells.length;
            int[] order = activeOrder[slot];
            if (order == null || order.length != n) {
                order = new int[n];
                activeOrder[slot] = order;
            }
            for (int i = 0; i < n; i++) order[i] = i;
            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int t = order[i]; order[i] = order[j]; order[j] = t;
            }
        }

        /** Fill the pre-allocated batch arrays with this tick's cells. */
        void prepareTick() {
            boolean v = tickValue();
            int out = 0;
            for (int slot = 0; slot < ACTIVE; slot++) {
                int[][] cells = buildings.get(activeBuildingIdx[slot]);
                int[] order = activeOrder[slot];
                for (int k = 0; k < CELLS_PER_TICK; k++) {
                    if (activeCursor[slot] >= cells.length) {
                        startNew(slot);
                        cells = buildings.get(activeBuildingIdx[slot]);
                        order = activeOrder[slot];
                    }
                    int[] cell = cells[order[activeCursor[slot]]];
                    xs[out] = cell[0];
                    ys[out] = cell[1];
                    vals[out] = v;
                    out++;
                    activeCursor[slot]++;
                }
            }
        }
    }

    /** Starts with an empty field; each tick adds wall cells. */
    @State(Scope.Benchmark)
    public static class BuildState extends ScenarioState {
        @Param({"grid_uniform_256", "city_768"})
        public String fixture;
        @Override protected String fixtureName() { return fixture; }
        @Override protected boolean[][] initialField(LayoutFixture fx) { return new boolean[fx.height][fx.width]; }
        @Override protected boolean tickValue() { return true; }
    }

    /** Starts with the fixture fully built; each tick removes wall cells. */
    @State(Scope.Benchmark)
    public static class DismantleState extends ScenarioState {
        @Param({"grid_uniform_256", "city_768"})
        public String fixture;
        @Override protected String fixtureName() { return fixture; }
        @Override protected boolean[][] initialField(LayoutFixture fx) { return fx.raster; }
        @Override protected boolean tickValue() { return false; }
    }

    @Benchmark
    public double buildTick(BuildState s) {
        s.prepareTick();
        s.g.applyBatch(s.xs, s.ys, s.vals, false);
        return s.g.valueAt(s.sampleX, s.sampleY);
    }

    @Benchmark
    public double dismantleTick(DismantleState s) {
        s.prepareTick();
        s.g.applyBatch(s.xs, s.ys, s.vals, false);
        return s.g.valueAt(s.sampleX, s.sampleY);
    }

    /** Baseline: cost of a full from-scratch evaluation. */
    @State(Scope.Benchmark)
    public static class FromScratchState {
        @Param({"grid_uniform_256", "city_768"})
        public String fixture;
        LayoutFixture fx;
        GridnessParams params;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            fx = LayoutFixture.load(LayoutFixture.defaultDir(), fixture);
            params = GridnessParams.defaults();
        }
    }

    @Benchmark
    public double fromScratch(FromScratchState s) {
        Gridness g = new Gridness(s.fx.width, s.fx.height, s.params);
        g.loadFromField(s.fx.raster);
        return g.valueAt(s.fx.width / 2, s.fx.height / 2);
    }
}
