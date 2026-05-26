package com.dkorduban.gridness.internal;

/** Extracted building. Coordinates are in tile-local pixel space. */
public final class Building {
    public final int id;
    public final double centroidX;
    public final double centroidY;
    public final int minX, minY, maxX, maxY;
    public final int area;
    public final double rectangularity;
    /** Boundary cell coordinates packed as alternating x,y in tile-local space. */
    public final int[] boundary;

    public Building(int id, double centroidX, double centroidY,
                    int minX, int minY, int maxX, int maxY,
                    int area, double rectangularity, int[] boundary) {
        this.id = id;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.area = area;
        this.rectangularity = rectangularity;
        this.boundary = boundary;
    }

    public int boundaryLength() { return boundary.length >>> 1; }
}
