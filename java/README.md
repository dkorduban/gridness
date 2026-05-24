# gridness-java

Java port of the V3 (Hough + affine grid-line snap) gridness scorer from the
Python prototype, restructured as a mutable data structure suitable for use
inside a game loop.

## Layout

```
java/
  build.gradle.kts        — Gradle build (Kotlin DSL) with JUnit 5 + JMH
  settings.gradle.kts
  gradlew, gradlew.bat    — Gradle wrapper (no host gradle required)
  env.sh                  — sources the local JDK + Gradle for shells that have neither
  src/main/java/com/gridness/
    Gridness.java         — public API
    GridnessParams.java   — builder-style config
    Interpolation.java    — NEAREST | BILINEAR
    internal/
      WallGrid.java       — packed bitset wall storage
      TileGrid.java       — overlapping tile geometry
      Tile.java           — per-tile cached score; recomputes on demand
      BuildingExtractor.java — flood-fill exterior + CCL interiors
      HoughDetector.java  — straight-line Hough → dominant angle peaks
      Cluster1D.java      — greedy 1D clustering used by FrameScorer
      FrameScorer.java    — projects buildings into affine frames, scores
  src/test/java/com/gridness/...    — JUnit 5 tests
  src/jmh/java/com/gridness/...     — JMH benchmark
```

## Build & test

The repo ships with a Gradle wrapper; bootstrap it with:

```bash
source java/env.sh        # only needed if you don't have JDK 21 + gradle on PATH
cd java
./gradlew test
./gradlew jmh             # ~5 min, full matrix
```

## API at a glance

```java
GridnessParams p = GridnessParams.builder()
    .tileSize(128).tileStride(64).sampleStride(8)
    .interpolation(Interpolation.BILINEAR)
    .parallel(true)
    .build();

Gridness g = new Gridness(768, 768, p);

// Bulk load (use after loading a save).
boolean[][] field = ...;     // field[y][x] true means wall
g.loadFromField(field);

// Incremental edits.
g.setPixel(x, y);
g.unsetPixel(x, y);
g.applyBatch(xs, ys, setOrUnset, /*strict=*/false);

// Read.
double v = g.valueAt(x, y);
double[][] block = g.readRect(x1, y1, x2, y2);   // at sampleStride spacing
```

## Design

- **Tiles**: the field is covered by overlapping `tileSize`-sized tiles spaced
  `tileStride` apart. Each tile computes a single gridness value at its center
  via the V3 algorithm: local Hough → dominant angles → candidate affine
  frames → cluster boundary projections → take the best.
- **Interpolation**: heatmap values at arbitrary `(x, y)` are interpolated
  between the four nearest tile centers (`BILINEAR`) or taken from the closest
  (`NEAREST`).
- **Dirty propagation**: a wall edit at `(x, y)` flips the `dirty` flag on
  every tile whose bbox contains that cell (at most 4 with 50% overlap).
  Read calls lazily recompute only dirty tiles (in parallel by default).
- **Concurrency**: not externally thread-safe. Internal recomputation uses the
  common ForkJoin pool when `parallel=true` and there are multiple dirty
  tiles.

## Benchmark snapshot (768×768 regular grid, tile=64/stride=32, defaults)

| benchmark             | time   |
|-----------------------|--------|
| fromScratch           | ~16 ms |
| singlePixelUpdate     | ~0.5 ms |
| batchUpdate (64 ops)  | ~3 ms  |
| readRectFull          | ~0.3 ms (after warm) |

Numbers from JMH on the dev machine; treat as order-of-magnitude.

## Matches the Python prototype

The scoring math (frame projection, 5/95 percentile extents, greedy 1D cluster,
`edge_snap · two_axis · (shape_floor + shape_weight · rect)`) mirrors
`src/gridness/scoring/v3_hough.py` and `src/gridness/scoring/common.py`. The
difference is that this implementation runs the math **per tile** (one score
per tile, interpolated across the heatmap) rather than per sample point with a
Gaussian-weighted radius window. The trade is coarser spatial resolution for
much cheaper incremental updates.
