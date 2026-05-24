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

## Results (defaults: tile=32 stride=32 sampleStride=8 radius=30)

```
Benchmark                                 (fixture)  Mode  Cnt    Score    Error  Units
GridnessBenchmark.buildTick        grid_uniform_256  avgt    3    4.239 ± 36.495  ms/op
GridnessBenchmark.buildTick       longhouses_22x100  avgt    3    3.150 ±  6.271  ms/op
GridnessBenchmark.buildTick      four_districts_512  avgt    3    7.562 ± 37.429  ms/op
GridnessBenchmark.buildTick                city_768  avgt    3    6.990 ± 21.525  ms/op
GridnessBenchmark.dismantleTick    grid_uniform_256  avgt    3    4.712 ± 35.944  ms/op
GridnessBenchmark.dismantleTick   longhouses_22x100  avgt    3    3.427 ±  4.832  ms/op
GridnessBenchmark.dismantleTick  four_districts_512  avgt    3    6.684 ± 39.692  ms/op
GridnessBenchmark.dismantleTick            city_768  avgt    3   12.687 ±  3.319  ms/op
GridnessBenchmark.fromScratch      grid_uniform_256  avgt    3   24.349 ±  2.780  ms/op
GridnessBenchmark.fromScratch     longhouses_22x100  avgt    3   23.059 ±  2.053  ms/op
GridnessBenchmark.fromScratch    four_districts_512  avgt    3   61.711 ±  5.033  ms/op
GridnessBenchmark.fromScratch              city_768  avgt    3  163.076 ±  6.824  ms/op
```

### Per-iteration spread (3 measurement iters each)

```
buildTick      grid_uniform_256:  3.06   6.55   3.11
buildTick      longhouses_22x100: 3.25   2.77   3.43
buildTick      four_districts_512:5.64   7.33   9.72
buildTick      city_768:          6.03   6.63   8.31
dismantleTick  grid_uniform_256:  3.84   6.97   3.33
dismantleTick  longhouses_22x100: 3.45   3.68   3.15
dismantleTick  four_districts_512:8.66   7.05   4.35
dismantleTick  city_768:         12.49  12.74  12.84
fromScratch    grid_uniform_256: 24.41  24.18  24.46
fromScratch    longhouses_22x100:23.14  23.11  22.93
fromScratch    four_districts_512:61.86 61.88  61.39
fromScratch    city_768:        162.81 162.91 163.50
```

`fromScratch` is stable to ±0.5% — pure computation cost. Incremental
benchmarks have per-iteration spread because the per-tick cost is
state-dependent: a tick on a half-built building can flood-fill a different
region than one on a freshly-empty area. The mean is meaningful; the
"Error" column (JMH's 99% CI with n=3) overstates the true noise.

## Takeaways

1. **Incremental tick is 4-20× faster than from-scratch** across all four
   fixtures. Per-tick cost is mostly Hough recomputation for the 1-4 tiles
   that contain the edited cells, plus a small constant for sample re-scoring.

2. **Dismantle is slightly more expensive than build on cities, similar on
   grids.** When removing a wall cell adjacent to a previously-enclosed
   interior, the exterior bitmap flood-fills the newly-exposed region. Build
   ticks rarely trigger flood — adding a wall to an exterior cell almost
   always leaves all 4 neighbors anchored to the field boundary.

3. **Per-tick cost scales sublinearly with field area.** `city_768` is 9× the
   pixel count of `grid_uniform_256` but a build tick is only ~1.6× as
   expensive. Incremental work is dominated by the few affected tiles + their
   sample neighborhood, not the whole field.

4. **Longhouses (22×100) are the cheapest case.** Each tick affects ~2 large
   buildings; Hough recomputation per tile is similar to a small building
   (it's a function of walls in tile, not buildings); and there are fewer
   building objects per tile to filter through during sample scoring.

5. **`fromScratch` for city_768 is 163 ms** — at the edge of Rule 2's 60s
   timebox (the rule was written for 200² grids; 768² is 14× the pixels).
   For interactive use, never call `fromScratch` after the initial load.

## Recommended default

```java
GridnessParams.defaults()
// tile=32 stride=32 sampleStride=8 radius=30 extractionPad=4
// shapeFloor=0.85 minBuildingsInWindow=4 parallel=true
```

For layouts dominated by buildings ≥ 30 cells across (e.g. dense longhouse
blocks), use `radius=60` and `minBuildingsInWindow=2` so each sample's
window sees enough buildings to score.
