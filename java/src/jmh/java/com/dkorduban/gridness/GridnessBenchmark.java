package com.dkorduban.gridness;

import com.dkorduban.gridness.fixture.LayoutFixture;
import com.dkorduban.gridness.sim.BuildSim;
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

/**
 * Realistic per-tick benchmarks using the oddjobber model. Each tick advances
 * 3 in-progress buildings; each building has ~10 workers (±3 jitter) placing
 * (build) or removing (dismantle) wall cells. See {@link BuildSim}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class GridnessBenchmark {

    private static final int ACTIVE = 3;
    private static final int WORKERS_PER_BUILDING = 10;
    private static final int WORKERS_JITTER = 3;

    public abstract static class ScenarioState {
        BuildSim sim;
        Gridness g;
        int sampleX, sampleY;

        protected abstract String fixtureName();
        protected abstract BuildSim.Mode mode();

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            LayoutFixture fx = LayoutFixture.load(LayoutFixture.defaultDir(), fixtureName());
            g = new Gridness(fx.width, fx.height, paramsFromSysprop());
            sampleX = fx.width / 2;
            sampleY = fx.height / 2;
            sim = new BuildSim(g, fx, mode(), ACTIVE, WORKERS_PER_BUILDING, WORKERS_JITTER, 42L);
        }
    }

    @State(Scope.Benchmark)
    public static class BuildState extends ScenarioState {
        @Param({"grid_uniform_256", "longhouses_22x100", "four_districts_512", "city_768"})
        public String fixture;
        @Override protected String fixtureName() { return fixture; }
        @Override protected BuildSim.Mode mode() { return BuildSim.Mode.BUILD; }
    }

    @State(Scope.Benchmark)
    public static class DismantleState extends ScenarioState {
        @Param({"grid_uniform_256", "longhouses_22x100", "four_districts_512", "city_768"})
        public String fixture;
        @Override protected String fixtureName() { return fixture; }
        @Override protected BuildSim.Mode mode() { return BuildSim.Mode.DISMANTLE; }
    }

    @Benchmark
    public double buildTick(BuildState s) {
        s.sim.tick();
        return s.g.valueAt(s.sampleX, s.sampleY);
    }

    @Benchmark
    public double dismantleTick(DismantleState s) {
        s.sim.tick();
        return s.g.valueAt(s.sampleX, s.sampleY);
    }

    /**
     * Single setPixel + read on a freshly-clean field. Apples-to-apples
     * baseline against the historical 0.6 ms number — keeps any per-tick
     * scenario overhead (building selection, batch arrays) out of the
     * measurement.
     */
    private static GridnessParams paramsFromSysprop() {
        // Defaults are now the recommended config (tile=128 hpw=45). The
        // matchingConfig knob selects the slower tile=256 reference so a
        // perf A/B against the gold standard is still possible.
        return "true".equalsIgnoreCase(System.getProperty("gridness.bench.matchingConfig"))
                ? GridnessParams.builder().tileSize(256).tileStride(256).houghMinPeakWeight(30).build()
                : GridnessParams.defaults();
    }

    @State(Scope.Benchmark)
    public static class SinglePixelState {
        @Param({"grid_uniform_256", "city_768"})
        public String fixture;
        Gridness g;
        LayoutFixture fx;
        Random rng;

        @Setup(Level.Trial)
        public void trial() throws Exception {
            fx = LayoutFixture.load(LayoutFixture.defaultDir(), fixture);
            rng = new Random(42);
        }

        @Setup(Level.Invocation)
        public void invocation() {
            g = new Gridness(fx.width, fx.height, paramsFromSysprop());
            g.loadFromField(fx.raster);
            g.valueAt(fx.width / 2, fx.height / 2);
        }
    }

    @Benchmark
    public double singlePixelOnClean(SinglePixelState s) {
        s.g.setPixel(s.rng.nextInt(s.fx.width), s.rng.nextInt(s.fx.height));
        return s.g.valueAt(s.fx.width / 2, s.fx.height / 2);
    }

    /** Baseline: cost of a full from-scratch evaluation. */
    @State(Scope.Benchmark)
    public static class FromScratchState {
        @Param({"grid_uniform_256", "longhouses_22x100", "four_districts_512", "city_768"})
        public String fixture;
        LayoutFixture fx;
        GridnessParams params;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            fx = LayoutFixture.load(LayoutFixture.defaultDir(), fixture);
            params = paramsFromSysprop();
        }
    }

    @Benchmark
    public double fromScratch(FromScratchState s) {
        Gridness g = new Gridness(s.fx.width, s.fx.height, s.params);
        g.loadFromField(s.fx.raster);
        return g.valueAt(s.fx.width / 2, s.fx.height / 2);
    }
}
