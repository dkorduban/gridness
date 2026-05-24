# gridness-java

Java port of the V3 (Hough + affine grid-line snap) gridness scorer as a
mutable data structure. Produces a **smooth** gridness heatmap (per-sample
Gaussian-weighted scoring), with incremental updates on wall edits.

## Layout

```
java/
  build.gradle.kts        — Gradle build (Kotlin DSL) with JUnit 5 + JMH
  settings.gradle.kts
  gradlew, gradlew.bat    — Gradle wrapper
  env.sh                  — sources the local JDK + Gradle for shells that have neither
  src/main/java/com/gridness/
    Gridness.java         — public API
    GridnessParams.java   — builder-style config
    Interpolation.java    — NEAREST | BILINEAR (between samples)
    internal/
      WallGrid.java       — packed bitset wall storage
      TileGrid.java       — overlapping tile geometry
      Tile.java           — per-tile cached buildings + Hough angles
      SampleGrid.java     — per-sample cached gridness scores
      BuildingExtractor.java
      HoughDetector.java
      Cluster1D.java
      FrameScorer.java    — projects buildings into affine frames, scores
  src/test/java/com/gridness/...    — JUnit 5 tests
  src/jmh/java/com/gridness/...     — JMH benchmark
```

## Build & test

```bash
source java/env.sh      # only if JDK 21 + gradle aren't on PATH
cd java
./gradlew test
./gradlew jmh           # full sweep, ~7-10 min
```

## API at a glance

```java
GridnessParams p = GridnessParams.builder()
    .tileSize(64).tileStride(32)
    .sampleStride(8)
    .radius(30).sigmaFrac(0.5)
    .interpolation(Interpolation.BILINEAR)
    .parallel(true)
    .build();

Gridness g = new Gridness(768, 768, p);

g.loadFromField(field);                         // boolean[H][W], true = wall
g.setPixel(x, y);
g.unsetPixel(x, y);
g.applyBatch(xs, ys, setOrUnset, /*strict=*/false);

double v = g.valueAt(x, y);
double[][] block = g.readRect(x1, y1, x2, y2); // at sampleStride spacing
```

## Design

Two cached layers:

1. **Tile layer**: covers the field with overlapping `tileSize` tiles spaced
   `tileStride` apart. Each tile holds (a) the buildings whose centroid lies
   in it (extracted from a 1-cell-padded read window), and (b) the dominant
   Hough wall-normal angles from those same walls. Dirty when any wall edit
   touches its padded read region.
2. **Sample layer**: a dense grid at `sampleStride` spacing. Each sample
   gathers buildings within `radius` from the surrounding tiles, computes
   Gaussian weights (`σ = sigmaFrac · radius`), takes Hough angles from the
   tile containing it, and scores via the V3 affine pipeline.

**Smooth heatmap**: adjacent samples share most of their R-window so values
change continuously (modulo the discrete Hough source switching at tile
boundaries, which is small in practice).

**Incremental updates**:
- A wall edit at `(x, y)` marks every tile whose padded bbox contains
  `(x, y)` dirty (≤4 with 50% overlap).
- For each newly-dirty tile, every sample that could query it (whose R-window
  overlaps the tile bbox) is also marked dirty.
- On the next read, dirty tiles are recomputed first (parallel via ForkJoin),
  then dirty samples (also parallel above a small threshold).

**Concurrency**: not externally thread-safe. Internal recomputation uses the
common ForkJoin pool when `parallel=true`.

## Matches the Python prototype

Same scoring math as `src/gridness/scoring/v3_hough.py` and `common.py`:
project boundary into affine frame, take 5/95 percentile extents per axis,
greedy 1D cluster, score = `edge_snap · two_axis · (shape_floor + shape_weight · rect)`.
The only difference is `radius` defaults to 30 here (vs 50 in the Python),
since user feedback says ≥30 is plenty for the SoS use case.
