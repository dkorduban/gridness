# Benchmark — gridness (Java)

JMH benchmarks under a realistic Song of Syx workload model. JMH config:
2 warmup × 3 s, 3 measurement × 3 s, fork 1. Times in **ms/op**.
Hardware: 13th Gen Intel Core i9-13900H @ 2.60 GHz, WSL2, JDK 21.

For headline single-update / build-tick numbers and the Python L1
match-quality table, see the [top-level README](../README.md#benchmarks).
This document covers the **workload model**, **per-cell normalization**,
and the **qualitative takeaways** that don't change with the preset.

## The workload model

A naïve `singlePixelUpdate` benchmark (toggle one random cell, measure)
is unrepresentative of how walls actually appear in-game. Real
oddjobbers pile onto a small number of in-progress buildings and place
walls in parallel — ~10 workers per building, with some scheduling
jitter.

Both `buildTick` and `dismantleTick` model this:
- 3 active in-progress buildings at any time.
- Each tick, every active building advances by ~10 cells (uniform 7–13, jitter ±3).
- 1 tick = `applyBatch` of ~30 cells + a forced `valueAt` (drives `ensureClean`).
- When a building completes, a new random building from the fixture replaces it.
- When the whole field is saturated (all buildings fully built / fully
  dismantled), the field is reset to its initial state. The reset cost is
  amortized into the iteration average.

The reusable simulator lives at `com.dkorduban.gridness.sim.BuildSim`;
the viewer uses the same defaults.

`buildTick` starts from an empty field; cells flip false→true.
`dismantleTick` starts from the fully-built fixture; cells flip
true→false.

Fixtures cover four regimes of size and character:
- `grid_uniform_256` — dense 256² grid of 12×12 houses.
- `longhouses_22x100` — 22×100 longhouses in a tight block, 240×320.
- `four_districts_512` — 512² with grid + longhouse + scattered + longhouse quadrants.
- `city_768` — 768² mixed-character city (grid core + longhouse fringe + scattered outskirts).

## Results — `balanced` preset (default: tile=128 stride=128 hpw=45 sampleStride=8 radius=30)

```
Benchmark                                      (fixture)  Mode  Cnt   Score    Error  Units
GridnessBenchmark.buildTick             grid_uniform_256  avgt    3   1.421 ±  0.695  ms/op
GridnessBenchmark.buildTick            longhouses_22x100  avgt    3   1.149 ±  0.188  ms/op
GridnessBenchmark.buildTick           four_districts_512  avgt    3   2.523 ±  0.890  ms/op
GridnessBenchmark.buildTick                     city_768  avgt    3   4.435 ±  0.759  ms/op
GridnessBenchmark.dismantleTick         grid_uniform_256  avgt    3   1.508 ±  0.540  ms/op
GridnessBenchmark.dismantleTick        longhouses_22x100  avgt    3   1.104 ±  0.611  ms/op
GridnessBenchmark.dismantleTick       four_districts_512  avgt    3   2.650 ±  0.211  ms/op
GridnessBenchmark.dismantleTick                 city_768  avgt    3   4.806 ±  0.837  ms/op
GridnessBenchmark.fromScratch           grid_uniform_256  avgt    3   2.086 ±  0.483  ms/op
GridnessBenchmark.fromScratch          longhouses_22x100  avgt    3   1.574 ±  0.594  ms/op
GridnessBenchmark.fromScratch         four_districts_512  avgt    3   4.554 ±  0.796  ms/op
GridnessBenchmark.fromScratch                   city_768  avgt    3  13.479 ±  1.558  ms/op
GridnessBenchmark.singlePixelOnClean    grid_uniform_256  avgt    3   1.190 ±  0.165  ms/op
GridnessBenchmark.singlePixelOnClean            city_768  avgt    3   1.538 ±  0.934  ms/op
```

To run with the slow but Python-matching `high fidelity` preset
(`tile=256 hpw=30`):

```bash
gradle :jmh -PmatchingConfig=true
```

### Per-cell normalization at `balanced` (per-tick ÷ ~30 cells per tick)

| Fixture | build (ms/cell) | dismantle (ms/cell) |
|---|---|---|
| grid_uniform_256 | 0.047 | 0.050 |
| longhouses_22x100 | 0.038 | 0.037 |
| four_districts_512 | 0.084 | 0.088 |
| city_768 | 0.148 | 0.160 |

### Single-pixel latency on a clean field (apples-to-apples)

| Fixture | Single setPixel + valueAt |
|---|---|
| grid_uniform_256 (256²) | **1.19 ms** |
| city_768 (768²) | **1.54 ms** |

Cost is dominated by re-scoring the samples whose R-window overlaps the
affected tile, not the tile recompute itself. The per-sample work is
`FrameScorer`'s nested (frame-pair × building × boundary-point)
projection.

## Takeaways

1. **Incremental tick is 3–6× faster than from-scratch** across all four
   fixtures. Single setPixel + read is 1–2 ms even on a 768² mixed city.

2. **Build and dismantle are now close to symmetric** at `balanced` —
   a larger tile (128 vs 32) absorbs the per-edit churn that used to
   make `build` 10× more expensive than `dismantle` on the old tile=32
   default. Both modes pay roughly the same per-tile recompute cost
   regardless of which direction the wall flipped, because the
   tile-level building list is no longer dominated by stray-cell churn
   at the tile boundary.

3. **Per-tick cost scales sublinearly with field area.** `city_768` is
   9× the pixel count of `grid_uniform_256` but a build tick is only
   ~3× as expensive (and a single setPixel is only ~30 % more
   expensive). The reason: an edit only invalidates one tile (plus
   neighbors via `extractionPad`), and the dirty-tile set is what
   `ensureClean` walks — not the whole field.

4. **Longhouses are the cheapest case** — fewer buildings per tile to
   filter during sample scoring, so the dirty-sample loop has less
   per-iteration work.

5. **Tile size dominates per-edit cost.** Going from `tile=32` to
   `tile=128` (the `fast` → `balanced` preset jump) makes a single
   edit ~3× more expensive on city_768 (0.51 → 1.54 ms), because each
   dirty tile has 16× more cells to re-scan for buildings. The win is
   a much better Python L1 match and removal of false-positive
   hotspots on scattered/organic layouts. See
   [`scripts/sweep_java_params.py`](../scripts/sweep_java_params.py) for the sweep.

## Recommended defaults

`GridnessParams.defaults()` — `tileSize=128 tileStride=128
houghMinPeakWeight=45 sampleStride=8 radius=30 extractionPad=4
parallel=true`.

For layouts where individual buildings span ≥ 60 cells in any axis
(e.g. 22×60 longhouses with sparse row count), bump `radius=60` so each
sample's window can reach the next row of buildings; otherwise the
score wobbles around 0.55 because most samples see only the closest
row.

For larger buildings (≥ ~8 cells across in any axis), check that
`2 * extractionPad ≥ max building extent` — otherwise a building can
"leak" the local exterior flood-fill at a tile boundary and be silently
dropped. The default `extractionPad=4` handles buildings up to ~8 cells
across; bump it for bigger structures, paying roughly quadratic cost in
read-region area.

If sub-millisecond per-edit latency matters more than Python parity,
use the `fast` preset:

```java
GridnessParams.builder()
    .tileSize(32).tileStride(32)
    .houghMinPeakWeight(18)
    .build();
```

If offline visual fidelity matters more than latency, use the
`high fidelity` preset:

```java
GridnessParams.builder()
    .tileSize(256).tileStride(256)
    .houghMinPeakWeight(30)
    .boundaryClipPercentile(0.05)
    .useGlobalHough(true)
    .build();
```
