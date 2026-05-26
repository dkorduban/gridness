package com.dkorduban.gridness.sim;

import com.dkorduban.gridness.Gridness;
import com.dkorduban.gridness.fixture.LayoutFixture;

import java.util.List;
import java.util.Random;

/**
 * Oddjobber-style build/dismantle simulator. Models N active in-progress
 * buildings, each with M workers (with per-tick jitter) placing or removing
 * wall cells in parallel. When a building completes, a new random one
 * replaces it. When the whole field is saturated (build mode: every
 * building's cells are walls; dismantle mode: every cell is empty), the
 * field is reset to its initial state and the cycle continues.
 *
 * <p>Shared by {@code GridnessBenchmark} and the {@code GridnessViewer}.
 * Not thread-safe; expect one tick at a time on a single thread.
 */
public final class BuildSim {

    public enum Mode { BUILD, DISMANTLE }

    private final Gridness g;
    private final LayoutFixture fx;
    private final boolean[][] initialField;
    private final List<int[][]> buildings;
    private final Random rng;
    private final Mode mode;
    private final boolean targetWall;
    private final int activeBuildings;
    private final int workersPerBuilding;
    private final int workersJitter;

    private final int[] activeBuildingIdx;
    private final int[] activeCursor;
    private final int[][] activeOrder;

    private final int maxBatch;
    private final int[] xs;
    private final int[] ys;
    private final boolean[] vals;
    private int lastBatchSize;

    private long totalCellsApplied;
    private long totalTicks;

    public BuildSim(Gridness g, LayoutFixture fx, Mode mode,
                    int activeBuildings, int workersPerBuilding, int workersJitter,
                    long seed) {
        this.g = g;
        this.fx = fx;
        this.mode = mode;
        this.targetWall = (mode == Mode.BUILD);
        this.activeBuildings = activeBuildings;
        this.workersPerBuilding = workersPerBuilding;
        this.workersJitter = workersJitter;
        this.buildings = fx.buildings;
        this.rng = new Random(seed);

        this.initialField = mode == Mode.BUILD ? new boolean[fx.height][fx.width] : copyField(fx.raster);

        this.activeBuildingIdx = new int[activeBuildings];
        this.activeCursor = new int[activeBuildings];
        this.activeOrder = new int[activeBuildings][];

        this.maxBatch = activeBuildings * (workersPerBuilding + workersJitter);
        this.xs = new int[maxBatch];
        this.ys = new int[maxBatch];
        this.vals = new boolean[maxBatch];

        g.loadFromField(initialField);
        g.valueAt(fx.width / 2, fx.height / 2);

        for (int i = 0; i < activeBuildings; i++) startNew(i);
    }

    public Mode mode() { return mode; }
    public long ticks() { return totalTicks; }
    public long cellsApplied() { return totalCellsApplied; }
    public int lastBatchSize() { return lastBatchSize; }
    public int activeBuildingsCount() { return activeBuildings; }
    public int workersPerBuilding() { return workersPerBuilding; }
    public int workersJitter() { return workersJitter; }

    /** Apply one tick: prepare a jittered batch and push it through Gridness. Returns cells applied. */
    public int tick() {
        int n = prepareBatch();
        g.applyBatch(xs, ys, vals, n, false);
        lastBatchSize = n;
        totalCellsApplied += n;
        totalTicks++;
        return n;
    }

    private int prepareBatch() {
        int out = 0;
        for (int slot = 0; slot < activeBuildings; slot++) {
            int workers = workersPerBuilding;
            if (workersJitter > 0) workers += rng.nextInt(2 * workersJitter + 1) - workersJitter;
            if (workers < 1) workers = 1;

            int[][] cells = buildings.get(activeBuildingIdx[slot]);
            int[] order = activeOrder[slot];
            for (int k = 0; k < workers; k++) {
                if (activeCursor[slot] >= cells.length) {
                    startNew(slot);
                    cells = buildings.get(activeBuildingIdx[slot]);
                    order = activeOrder[slot];
                }
                int[] cell = cells[order[activeCursor[slot]]];
                xs[out] = cell[0];
                ys[out] = cell[1];
                vals[out] = targetWall;
                out++;
                activeCursor[slot]++;
            }
        }
        return out;
    }

    private void startNew(int slot) {
        for (int attempt = 0; attempt < 16; attempt++) {
            int idx = rng.nextInt(buildings.size());
            if (buildingHasWork(idx)) {
                useBuilding(slot, idx);
                return;
            }
        }
        // Field is saturated — reset and start fresh.
        g.loadFromField(initialField);
        g.valueAt(fx.width / 2, fx.height / 2);
        useBuilding(slot, rng.nextInt(buildings.size()));
    }

    private boolean buildingHasWork(int idx) {
        int[][] cells = buildings.get(idx);
        for (int[] xy : cells) {
            if (g.isWall(xy[0], xy[1]) != targetWall) return true;
        }
        return false;
    }

    private void useBuilding(int slot, int idx) {
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

    private static boolean[][] copyField(boolean[][] src) {
        boolean[][] out = new boolean[src.length][];
        for (int y = 0; y < src.length; y++) out[y] = src[y].clone();
        return out;
    }
}
