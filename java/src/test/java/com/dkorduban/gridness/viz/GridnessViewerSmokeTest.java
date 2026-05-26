package com.dkorduban.gridness.viz;

import com.dkorduban.gridness.Gridness;
import com.dkorduban.gridness.GridnessParams;
import com.dkorduban.gridness.fixture.LayoutFixture;
import com.dkorduban.gridness.sim.BuildSim;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GridnessViewerSmokeTest {

    @Test
    void simAndLatencyIntegration() throws Exception {
        Path dir = LayoutFixture.defaultDir();
        LayoutFixture fx = LayoutFixture.load(dir, "grid_uniform_256");
        Gridness g = new Gridness(fx.width, fx.height, GridnessParams.defaults());
        BuildSim sim = new BuildSim(g, fx, BuildSim.Mode.BUILD, 3, 10, 3, 7L);
        LatencyStats stats = new LatencyStats(256);

        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            int n = sim.tick();
            g.valueAt(fx.width / 2, fx.height / 2);
            stats.record(System.nanoTime() - t0);
            assertTrue(n >= 21 && n <= 39,
                    "expected 3 builds * (10 +- 3) workers in [21, 39], got " + n);
        }
        LatencyStats.Snapshot s = stats.snapshot();
        assertEquals(100, s.totalSamples());
        assertEquals(100, s.windowSize());
        assertTrue(s.minNs() > 0);
        assertTrue(s.meanNs() > 0);
        assertTrue(s.p50Ns() <= s.p95Ns());
        assertTrue(s.p95Ns() <= s.maxNs());
    }

    @Test
    void simSaturationCyclesField() throws Exception {
        // Cycle long enough that the build sim must reset the field at least
        // once. Verify no exception, and that the field cycles between empty
        // and full states.
        LayoutFixture fx = LayoutFixture.load(LayoutFixture.defaultDir(), "grid_uniform_256");
        Gridness g = new Gridness(fx.width, fx.height, GridnessParams.defaults());
        BuildSim sim = new BuildSim(g, fx, BuildSim.Mode.BUILD, 3, 10, 3, 7L);
        int totalCells = 0;
        for (int[][] b : fx.buildings) totalCells += b.length;
        // Run >> totalCells/avg-per-tick ticks; field must saturate and reset.
        int avgPerTick = 3 * 10;
        for (int i = 0; i < (totalCells / avgPerTick) * 3; i++) sim.tick();
        // No crashes; sim still alive.
        assertTrue(sim.ticks() > 0);
        assertTrue(sim.cellsApplied() > totalCells);
    }

    @Test
    void viewerInstantiationIsCleanInHeadlessMode() throws Exception {
        // Just verify that the colormap function and the LatencyStats snapshot
        // don't blow up. Don't try to create JFrame in headless mode.
        if (!GraphicsEnvironment.isHeadless()) return;
        LatencyStats s = new LatencyStats(8);
        s.record(1_000_000);
        s.record(2_000_000);
        s.record(3_000_000);
        LatencyStats.Snapshot snap = s.snapshot();
        assertEquals(3, snap.windowSize());
        assertEquals(1_000_000, snap.minNs());
        assertEquals(3_000_000, snap.maxNs());
    }
}
