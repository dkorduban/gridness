package com.gridness.internal;

import com.gridness.GridnessParams;

import java.util.ArrayList;
import java.util.List;

/**
 * One scoring tile. Holds the per-tile cached state and recomputes on demand.
 *
 * The tile owns the rectangle [originX, originX+tileSize) x [originY, originY+tileSize).
 * Buildings are extracted from a one-cell padded region so the BFS can flood the exterior
 * correctly; only buildings whose centroid falls inside the unpadded tile are scored.
 */
public final class Tile {

    /**
     * Number of cells of padding read OUTSIDE the tile bbox during recompute,
     * to seed the boundary flood fill correctly. Any wall edit at a cell within
     * this padding distance of the tile bbox must mark the tile dirty.
     */
    public static final int PAD = 1;

    public final int index;
    public final int originX;
    public final int originY;
    public final int size;

    private volatile boolean dirty = true;
    private double score = 0.0;

    public Tile(int index, int originX, int originY, int size) {
        this.index = index;
        this.originX = originX;
        this.originY = originY;
        this.size = size;
    }

    public boolean dirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public double score() { return score; }

    /**
     * Recompute the tile score from the current wall grid.
     */
    public void recompute(WallGrid walls, HoughDetector hough, GridnessParams params) {
        final int pad = PAD;
        int rectW = size + 2 * pad;
        int rectH = size + 2 * pad;
        boolean[] localWalls = new boolean[rectW * rectH];
        walls.copyRect(originX - pad, originY - pad, rectW, rectH, localWalls);

        List<Building> allBuildings = BuildingExtractor.extract(localWalls, rectW, rectH, params.minBuildingArea);
        if (allBuildings.isEmpty()) { score = 0.0; dirty = false; return; }

        List<Building> inTile = new ArrayList<>(allBuildings.size());
        for (Building b : allBuildings) {
            double tileLocalX = b.centroidX - pad;
            double tileLocalY = b.centroidY - pad;
            if (tileLocalX < 0 || tileLocalX >= size) continue;
            if (tileLocalY < 0 || tileLocalY >= size) continue;
            inTile.add(b);
        }
        if (inTile.size() < params.minBuildingsInTile) { score = 0.0; dirty = false; return; }

        // Hough is run on the SAME walls used for extraction so wall directions
        // align with what the buildings see.
        double[] angles = hough.dominantAngles(localWalls, rectW, rectH,
                params.houghNumPeaks,
                params.houghThresholdFrac,
                params.houghMinPeakWeight,
                params.houghMinAngleSepDeg);

        score = FrameScorer.score(inTile, angles, params);
        dirty = false;
    }
}
