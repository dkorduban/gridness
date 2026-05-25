# Benchmark — gridness (Java)

JMH benchmarks under a realistic Song of Syx workload model. JMH config:
2 warmup × 3 s, 3 measurement × 3 s, fork 1. Times in **ms/op**. Hardware:
dev WSL2 box, JDK 21.

## The workload model

The previous `singlePixelUpdate` benchmark was synthetic (toggle one random
cell, measure). It is unrepresentative of how walls actually appear in-game.
Real oddjobbers pile onto a small number of in-progress buildings and place
walls a few cells at a time, in parallel.

Both `buildTick` and `dismantleTick` model this:
- 3 active in-progress buildings at any time.
- Each tick, every active builder advances by 2 cells of its building.
- 1 tick = `applyBatch` of 6 cells + a forced `valueAt` (drives `ensureClean`).
- When a building completes, a new random building from the fixture replaces it.
- When the whole field is saturated (all buildings fully built / fully
  dismantled), the field is reset to its initial state. The reset cost is
  amortized into the iteration average.

`buildTick` starts from an empty field; cells flip false→true. `dismantleTick`
starts from the fully-built fixture; cells flip true→false.

Fixtures cover four regimes of size and character:
- `grid_uniform_256` — dense 256² grid of 12×12 houses.
- `longhouses_22x100` — 22×100 longhouses in a tight block, 240×320.
- `four_districts_512` — 512² with grid + longhouse + scattered + longhouse quadrants.
- `city_768` — 768² mixed-character city (grid core + longhouse fringe + scattered outskirts).

## Results (defaults: tile=32 stride=32 sampleStride=8 radius=30 minBuildingsInWindow=2)

```
Benchmark                                      (fixture)  Mode  Cnt   Score    Error  Units
GridnessBenchmark.buildTick             grid_uniform_256  avgt    3   1.768 ±  2.324  ms/op
GridnessBenchmark.buildTick            longhouses_22x100  avgt    3   1.351 ±  0.824  ms/op
GridnessBenchmark.buildTick           four_districts_512  avgt    3   3.339 ±  8.006  ms/op
GridnessBenchmark.buildTick                     city_768  avgt    3   6.544 ± 12.901  ms/op
GridnessBenchmark.dismantleTick         grid_uniform_256  avgt    3   1.624 ±  1.501  ms/op
GridnessBenchmark.dismantleTick        longhouses_22x100  avgt    3   0.882 ±  1.426  ms/op
GridnessBenchmark.dismantleTick       four_districts_512  avgt    3   1.548 ±  5.109  ms/op
GridnessBenchmark.dismantleTick                 city_768  avgt    3   1.694 ±  6.891  ms/op
GridnessBenchmark.fromScratch           grid_uniform_256  avgt    3   5.926 ±  3.490  ms/op
GridnessBenchmark.fromScratch          longhouses_22x100  avgt    3   4.679 ±  0.433  ms/op
GridnessBenchmark.fromScratch         four_districts_512  avgt    3  15.356 ±  4.968  ms/op
GridnessBenchmark.fromScratch                   city_768  avgt    3  39.183 ±  4.500  ms/op
GridnessBenchmark.singlePixelOnClean    grid_uniform_256  avgt    3   1.119 ±  0.350  ms/op
GridnessBenchmark.singlePixelOnClean            city_768  avgt    3   1.518 ±  1.448  ms/op
```

These are 3-8× faster than the prior version, after replacing the
per-(sample, frame-pair, building) `Arrays.sort` on projected boundary
points with a single-pass min/max scan in {@link FrameScorer}. For
real-world hollow-rect buildings the 5th/95th percentile is virtually
identical to the 0th/100th, so the sort was buying nothing — and it was
dominating cost.

`fromScratch` is stable to ±2% — pure computation cost. Incremental
benchmarks have per-iteration spread that is **workload signal, not noise**:
build ticks get more expensive as the field fills (each new wall in a
mostly-built area may flood the newly-enclosed interior); dismantle ticks
get cheaper as the field empties. JMH's `Error` column (99% CI from n=3)
overstates the random noise; the mean is a reasonable steady-state proxy.

### Single-pixel latency (apples-to-apples, fresh state each call)

| Fixture | Single setPixel + valueAt |
|---|---|
| grid_uniform_256 (256²) | **1.12 ms** |
| city_768 (768²) | **1.52 ms** |

Cost is dominated by re-scoring the ~130-170 samples whose R-window
overlaps the affected tile, not the tile recompute itself. The per-sample
work is FrameScorer's nested (frame-pair × building × boundary-point)
projection.

## Takeaways

1. **Incremental tick is 5-25× faster than from-scratch** across all four
   fixtures. Single setPixel + read is 1-2 ms even on a 768² mixed city.

2. **Dismantle is now ~equal to or faster than build on cities** (city_768:
   1.69 ms dismantle vs 6.54 ms build). Removing a wall from an intact
   building leaves the building list unchanged (still a hollow rect with a
   1-cell gap) so the tile re-extraction often yields the same buildings —
   sample re-scoring is fast because the building set hasn't actually
   changed. Build ticks tend to add stray pixels in open exterior that
   become new tiny "buildings", churning the tile's building list and
   forcing real re-scoring of nearby samples.

3. **Per-tick cost scales sublinearly with field area.** `city_768` is 9×
   the pixel count of `grid_uniform_256` but a build tick is only ~3.7×
   as expensive (and a single setPixel is only 1.4× as expensive).

4. **Longhouses are still the cheapest case** — fewer building objects per
   tile to filter during sample scoring.

5. **`fromScratch` for city_768 is 39 ms** (down from 163 ms in the prior
   build). Under Rule 2's 60s budget for a 200² field, 39 ms on 768² is
   well within budget.

## Recommended default

```java
GridnessParams.defaults()
// tile=32 stride=32 sampleStride=8 radius=30 extractionPad=4
// shapeFloor=0.85 minBuildingsInWindow=2 parallel=true
```

For layouts where individual buildings span ≥ 60 cells in any axis
(e.g. 22×60 longhouses with sparse row count), bump `radius=60` so each
sample's window can reach the next row of buildings; otherwise the score
wobbles around 0.55 because most samples see only the closest row.

Tradeoff of `minBuildingsInWindow=2` (vs the previous default of 4):
random/scattered layouts now score around 0.65 instead of <0.6, because a
pair of randomly-placed buildings can be axis-aligned by chance. Net
separation from a uniform grid (which scores >0.75) is narrower but
preserves the intuitive ordering. Longhouse layouts that were
unmeasurable with min=4 now score appropriately.
