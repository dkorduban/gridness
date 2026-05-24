package com.gridness;

public final class GridnessParams {

    public final int tileSize;
    public final int tileStride;
    public final int sampleStride;
    public final Interpolation interpolation;

    /** Radius (in pixels) of the per-sample Gaussian window. Buildings outside R contribute 0. */
    public final double radius;
    /** Gaussian sigma = sigmaFrac * radius. */
    public final double sigmaFrac;

    public final int houghThetaSteps;
    public final int houghNumPeaks;
    public final double houghThresholdFrac;
    public final double houghMinPeakWeight;
    public final double houghMinAngleSepDeg;

    public final double minAngleSin;
    public final double clusterTolerance;
    public final int minDistinctBuildings;
    public final int requiredLinesPerAxis;
    public final int minBuildingsInTile;
    public final int minBuildingArea;

    public final double shapeFloor;
    public final double shapeWeight;

    public final boolean parallel;

    private GridnessParams(Builder b) {
        this.tileSize = b.tileSize;
        this.tileStride = b.tileStride;
        this.sampleStride = b.sampleStride;
        this.interpolation = b.interpolation;
        this.radius = b.radius;
        this.sigmaFrac = b.sigmaFrac;
        this.houghThetaSteps = b.houghThetaSteps;
        this.houghNumPeaks = b.houghNumPeaks;
        this.houghThresholdFrac = b.houghThresholdFrac;
        this.houghMinPeakWeight = b.houghMinPeakWeight;
        this.houghMinAngleSepDeg = b.houghMinAngleSepDeg;
        this.minAngleSin = b.minAngleSin;
        this.clusterTolerance = b.clusterTolerance;
        this.minDistinctBuildings = b.minDistinctBuildings;
        this.requiredLinesPerAxis = b.requiredLinesPerAxis;
        this.minBuildingsInTile = b.minBuildingsInTile;
        this.minBuildingArea = b.minBuildingArea;
        this.shapeFloor = b.shapeFloor;
        this.shapeWeight = b.shapeWeight;
        this.parallel = b.parallel;
        validate();
    }

    private void validate() {
        if (tileSize <= 0) throw new IllegalArgumentException("tileSize must be > 0");
        if (tileStride <= 0 || tileStride > tileSize)
            throw new IllegalArgumentException("tileStride must be in (0, tileSize]");
        if (sampleStride <= 0) throw new IllegalArgumentException("sampleStride must be > 0");
        if (radius <= 0) throw new IllegalArgumentException("radius must be > 0");
        if (sigmaFrac <= 0) throw new IllegalArgumentException("sigmaFrac must be > 0");
        if (houghThetaSteps <= 1) throw new IllegalArgumentException("houghThetaSteps must be > 1");
        if (houghNumPeaks <= 0) throw new IllegalArgumentException("houghNumPeaks must be > 0");
        if (clusterTolerance <= 0) throw new IllegalArgumentException("clusterTolerance must be > 0");
        if (minDistinctBuildings < 1) throw new IllegalArgumentException("minDistinctBuildings >= 1");
        if (requiredLinesPerAxis < 1) throw new IllegalArgumentException("requiredLinesPerAxis >= 1");
        if (minBuildingArea < 1) throw new IllegalArgumentException("minBuildingArea >= 1");
    }

    public static Builder builder() { return new Builder(); }

    public static GridnessParams defaults() { return new Builder().build(); }

    public static final class Builder {
        private int tileSize = 64;
        private int tileStride = 32;
        private int sampleStride = 8;
        private Interpolation interpolation = Interpolation.BILINEAR;
        private double radius = 30.0;
        private double sigmaFrac = 0.5;

        private int houghThetaSteps = 90;
        private int houghNumPeaks = 8;
        private double houghThresholdFrac = 0.05;
        private double houghMinPeakWeight = 30.0;
        private double houghMinAngleSepDeg = 5.0;

        private double minAngleSin = 0.34;
        private double clusterTolerance = 2.5;
        private int minDistinctBuildings = 2;
        private int requiredLinesPerAxis = 3;
        private int minBuildingsInTile = 4;
        private int minBuildingArea = 4;

        private double shapeFloor = 0.85;
        private double shapeWeight = 0.15;

        private boolean parallel = true;

        public Builder tileSize(int v) { this.tileSize = v; return this; }
        public Builder tileStride(int v) { this.tileStride = v; return this; }
        public Builder sampleStride(int v) { this.sampleStride = v; return this; }
        public Builder interpolation(Interpolation v) { this.interpolation = v; return this; }
        public Builder radius(double v) { this.radius = v; return this; }
        public Builder sigmaFrac(double v) { this.sigmaFrac = v; return this; }
        public Builder houghThetaSteps(int v) { this.houghThetaSteps = v; return this; }
        public Builder houghNumPeaks(int v) { this.houghNumPeaks = v; return this; }
        public Builder houghThresholdFrac(double v) { this.houghThresholdFrac = v; return this; }
        public Builder houghMinPeakWeight(double v) { this.houghMinPeakWeight = v; return this; }
        public Builder houghMinAngleSepDeg(double v) { this.houghMinAngleSepDeg = v; return this; }
        public Builder minAngleSin(double v) { this.minAngleSin = v; return this; }
        public Builder clusterTolerance(double v) { this.clusterTolerance = v; return this; }
        public Builder minDistinctBuildings(int v) { this.minDistinctBuildings = v; return this; }
        public Builder requiredLinesPerAxis(int v) { this.requiredLinesPerAxis = v; return this; }
        public Builder minBuildingsInTile(int v) { this.minBuildingsInTile = v; return this; }
        public Builder minBuildingArea(int v) { this.minBuildingArea = v; return this; }
        public Builder shapeFloor(double v) { this.shapeFloor = v; return this; }
        public Builder shapeWeight(double v) { this.shapeWeight = v; return this; }
        public Builder parallel(boolean v) { this.parallel = v; return this; }

        public GridnessParams build() { return new GridnessParams(this); }
    }
}
