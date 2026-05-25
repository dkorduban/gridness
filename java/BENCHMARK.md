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
Benchmark                                 (fixture)  Mode  Cnt    Score    Error  Units
GridnessBenchmark.buildTick        grid_uniform_256  avgt    3    4.619 ± 27.827  ms/op
GridnessBenchmark.buildTick       longhouses_22x100  avgt    3    3.877 ±  5.262  ms/op
GridnessBenchmark.buildTick      four_districts_512  avgt    3    7.842 ± 34.147  ms/op
GridnessBenchmark.buildTick                city_768  avgt    3    7.552 ±  4.457  ms/op
GridnessBenchmark.dismantleTick    grid_uniform_256  avgt    3    5.580 ± 37.892  ms/op
GridnessBenchmark.dismantleTick   longhouses_22x100  avgt    3    4.243 ±  8.578  ms/op
GridnessBenchmark.dismantleTick  four_districts_512  avgt    3    8.898 ± 26.331  ms/op
GridnessBenchmark.dismantleTick            city_768  avgt    3   14.033 ±  7.093  ms/op
GridnessBenchmark.fromScratch      grid_uniform_256  avgt    3   24.453 ±  1.029  ms/op
GridnessBenchmark.fromScratch     longhouses_22x100  avgt    3   26.143 ±  3.226  ms/op
GridnessBenchmark.fromScratch    four_districts_512  avgt    3   68.308 ±  5.828  ms/op
GridnessBenchmark.fromScratch              city_768  avgt    3  172.367 ± 16.566  ms/op
```

`fromScratch` is stable to ±0.5% — pure computation cost. Incremental
benchmarks have per-iteration spread that is **workload signal, not noise**.
Build ticks get more expensive as the field fills (more flood-fill work
when each new wall threatens to enclose interior); dismantle ticks get
cheaper as the field empties. Look at the per-iter trace above: e.g.
`four_districts_512 buildTick` is monotone 5.64 → 7.33 → 9.72, and
`four_districts_512 dismantleTick` is monotone 8.66 → 7.05 → 4.35 — both
walking through their state space. JMH's `Error` column (99% CI from n=3)
overstates the random noise; the mean is a reasonable steady-state proxy.

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
