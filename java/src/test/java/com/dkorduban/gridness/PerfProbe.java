package com.dkorduban.gridness;

import com.dkorduban.gridness.fixture.LayoutFixture;
import com.dkorduban.gridness.sim.BuildSim;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class PerfProbe {

    @Test
    void buildTickBreakdown() throws Exception {
        Path dir = LayoutFixture.defaultDir();
        LayoutFixture fx = LayoutFixture.load(dir, "city_768");
        GridnessParams p = GridnessParams.builder().parallel(true).build();
        Gridness g = new Gridness(fx.width, fx.height, p);
        BuildSim sim = new BuildSim(g, fx, BuildSim.Mode.BUILD, 3, 10, 3, 42L);
        // Warm up
        for (int i = 0; i < 100; i++) {
            sim.tick();
            g.valueAt(fx.width / 2, fx.height / 2);
        }
        // Measure
        long applyNs = 0, valueNs = 0, dirtyTilesAcc = 0, dirtySamplesAcc = 0, cellsAcc = 0;
        int N = 200;
        for (int i = 0; i < N; i++) {
            long ta = System.nanoTime();
            int n = sim.tick();
            applyNs += System.nanoTime() - ta;
            cellsAcc += n;
            dirtyTilesAcc += g.dirtyTileCount();
            dirtySamplesAcc += g.dirtySampleCount();
            long tv = System.nanoTime();
            g.valueAt(fx.width / 2, fx.height / 2);
            valueNs += System.nanoTime() - tv;
        }
        System.out.printf("buildTick city_768: avg apply=%.2fms valueAt=%.2fms per tick (%.2f cells, %.1f tiles, %.1f samples)%n",
                applyNs / (double) N / 1e6, valueNs / (double) N / 1e6, cellsAcc / (double) N,
                dirtyTilesAcc / (double) N, dirtySamplesAcc / (double) N);
    }

    @Test
    void singleEditDuringBuild() throws Exception {
        // What's the cost of one single setPixel during a partially-built city?
        LayoutFixture fx = LayoutFixture.load(LayoutFixture.defaultDir(), "city_768");
        GridnessParams p = GridnessParams.builder().parallel(true).build();
        Gridness g = new Gridness(fx.width, fx.height, p);
        BuildSim sim = new BuildSim(g, fx, BuildSim.Mode.BUILD, 3, 10, 3, 42L);
        for (int i = 0; i < 100; i++) {
            sim.tick();
            g.valueAt(fx.width / 2, fx.height / 2);
        }
        java.util.Random r = new java.util.Random(42);
        long totalNs = 0;
        int N = 1000;
        for (int i = 0; i < N; i++) {
            int x = r.nextInt(fx.width), y = r.nextInt(fx.height);
            boolean isWall = g.isWall(x, y);
            long t0 = System.nanoTime();
            if (isWall) g.unsetPixel(x, y);
            else g.setPixel(x, y);
            g.valueAt(fx.width / 2, fx.height / 2);
            totalNs += System.nanoTime() - t0;
        }
        System.out.printf("single edit during partial build: %.3fms per edit%n",
                totalNs / (double) N / 1e6);
    }

    @Test
    void dismantleTickBreakdown() throws Exception {
        LayoutFixture fx = LayoutFixture.load(LayoutFixture.defaultDir(), "city_768");
        GridnessParams p = GridnessParams.builder().parallel(true).build();
        Gridness g = new Gridness(fx.width, fx.height, p);
        BuildSim sim = new BuildSim(g, fx, BuildSim.Mode.DISMANTLE, 3, 10, 3, 42L);
        for (int i = 0; i < 100; i++) {
            sim.tick();
            g.valueAt(fx.width / 2, fx.height / 2);
        }
        long totalNs = 0, dirtyTilesAcc = 0, dirtySamplesAcc = 0, cellsAcc = 0;
        int N = 200;
        for (int i = 0; i < N; i++) {
            int n = sim.tick();
            cellsAcc += n;
            long t0 = System.nanoTime();
            dirtyTilesAcc += g.dirtyTileCount();
            dirtySamplesAcc += g.dirtySampleCount();
            g.valueAt(fx.width / 2, fx.height / 2);
            totalNs += System.nanoTime() - t0;
        }
        System.out.printf("dismantleTick city_768: avg %dms per tick (%.2f cells), avg %.1f dirty tiles, %.1f dirty samples%n",
                totalNs / N / 1_000_000, cellsAcc / (double) N,
                dirtyTilesAcc / (double) N, dirtySamplesAcc / (double) N);
    }
}
