package com.dkorduban.gridness.internal;

import com.dkorduban.gridness.GridnessParams;

import java.util.ArrayList;
import java.util.List;

/**
 * One tile = the unit at which buildings are extracted and a local Hough is
 * computed. Per-sample scoring lives in {@link SampleGrid}; tiles no longer
 * compute a per-tile score themselves.
 *
 * <p>Buildings are stored in GLOBAL coordinates and assigned canonically to
 * the tile whose unpadded bbox contains the building centroid.
 */
public final class Tile {

    public final int index;
    public final int originX;
    public final int originY;
    public final int size;

    private volatile boolean dirty = true;
    private List<Building> buildings = List.of();
    private double[] houghAngles = new double[0];

    public Tile(int index, int originX, int originY, int size) {
        this.index = index;
        this.originX = originX;
        this.originY = originY;
        this.size = size;
    }

    public boolean dirty() { return dirty; }
    public void markDirty() { dirty = true; }

    public List<Building> buildings() { return buildings; }
    public double[] houghAngles() { return houghAngles; }

    /**
     * Recompute buildings + Hough angles from the current wall grid.
     * If {@code globalAngles != null}, use those instead of running per-tile Hough.
     * Buildings get GLOBAL coordinates.
     */
    public void recompute(WallGrid walls, ExteriorBitmap exterior,
                          HoughDetector hough, GridnessParams params,
                          double[] globalAngles) {
        final int pad = params.extractionPad;
        int rectW = size + 2 * pad;
        int rectH = size + 2 * pad;
        boolean[] localWalls = new boolean[rectW * rectH];
        walls.copyRect(originX - pad, originY - pad, rectW, rectH, localWalls);
        boolean[] localExterior = new boolean[rectW * rectH];
        exterior.copyRect(originX - pad, originY - pad, rectW, rectH, localExterior);

        List<Building> allBuildings = BuildingExtractor.extract(localWalls, localExterior, rectW, rectH, params.minBuildingArea);
        List<Building> kept = new ArrayList<>(allBuildings.size());
        int outId = 0;
        for (Building b : allBuildings) {
            // Convert padded-local centroid to GLOBAL by subtracting pad and adding origin.
            double gx = (b.centroidX - pad) + originX;
            double gy = (b.centroidY - pad) + originY;
            if (gx < originX || gx >= originX + size) continue;
            if (gy < originY || gy >= originY + size) continue;
            // Translate the boundary array too.
            int[] gBoundary = new int[b.boundary.length];
            for (int k = 0; k < b.boundary.length; k += 2) {
                gBoundary[k]     = b.boundary[k]     + (originX - pad);
                gBoundary[k + 1] = b.boundary[k + 1] + (originY - pad);
            }
            kept.add(new Building(outId++, gx, gy,
                    b.minX + (originX - pad), b.minY + (originY - pad),
                    b.maxX + (originX - pad), b.maxY + (originY - pad),
                    b.area, b.rectangularity, gBoundary));
        }
        this.buildings = kept;

        if (globalAngles != null) {
            this.houghAngles = globalAngles;
        } else {
            this.houghAngles = hough.dominantAngles(localWalls, rectW, rectH,
                    params.houghNumPeaks,
                    params.houghThresholdFrac,
                    params.houghMinPeakWeight,
                    params.houghMinAngleSepDeg);
        }
        dirty = false;
    }
}
