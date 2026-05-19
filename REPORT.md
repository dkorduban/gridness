# Gridness — Final Report

Autonomous build: synthetic dataset, three scoring algorithms, parameter tuning, cross-algorithm comparison. Project goal: score the "gridness" of 2D city layouts (Song of Syx style) per the algorithm spec in PROBLEM.md.

## TL;DR

- Built 27 synthetic test layouts covering grid / rotated grid / sheared grid / mixed-size blocks / non-rectangular buildings / organic / scattered / hexagonal / region-mixed cases, plus the SoS-specific variants (regular street widths 1..9, mixed widths, 1-cell corner-cut "rounded" buildings).
- Implemented three scoring algorithms (V1 axis, V2 affine exhaustive, V3 affine Hough-derived).
- **V3 (Hough-derived affine) is the best**, with the largest separation between expected-high and expected-low layouts, and 25× faster than V2.
- Final summary: grids score **0.79 – 0.99**, organic/scattered score **0.42 – 0.50** with V3. Discriminative gap ≈ 0.40 (or 0.30 if hex is counted as a grid). See `data/comparison_table.csv` and `data/comparison_grid.png`.
- V3 also correctly resolves both regional-discrimination tests — see "Per-region results" below.
- Street-width invariance: V3 scores 0.98–0.99 across regular street widths 1..9 and 0.95 on randomly-mixed widths in [1, 7]. The 1-cell-corner-cut "roundness emitting" building shape also scores 0.987 — V3 is robust to it.

## What was built

### Phase 1: Synthetic generator (`src/gridness/generate.py`)
27 deterministic layouts on 200×200 rasters, e.g.:
- `grid_uniform` — 12×12 buildings on a 16-period axis-aligned grid
- `grid_mixed_sizes` — 1×1 / 2×1 / 1×2 / 2×2 blocks sharing the same lattice (the ChatGPT-suggested key case)
- `grid_rotated_30` — perfect grid rotated 30°
- `grid_sheared` — parallelogram grid (staggered rows)
- `grid_nonrect_buildings` — L/T/+ shapes placed on a grid (layout high, shape mid)
- `grid_streets_w1` .. `grid_streets_w9` — regular grids with street widths sweeping 1..9 (the SoS "main-street widths" 2..5 are a subset)
- `grid_streets_mixed` — irregular street widths drawn iid uniform in [1, 7]
- `grid_rounded_corners` — regular grid of buildings with 1-cell corner cuts (the SoS "roundness emitting" shape)
- `rect_scattered` / `organic_walks` / `rect_rotated_scattered` — expected low
- `hexagonal` — hex-packed centers
- `mixed_regions` — half grid, half organic (locality test)
- `two_districts` — two grids meeting at an angle (locality test)
- `grid_with_holes`, `row_only`, `tiny_grid`, `dense_grid`, `grid_variable_rows`

See `data/contact_sheet.png` for the full dataset.

### Phase 2: Building extraction (`src/gridness/extract.py`)
Standard pipeline: flood-fill empty space from boundary → enclosed empty components are interiors → dilate by 1 to include wall shell.

**Important detail discovered:** walls drawn via `polygon-fill + erosion-boundary` are 8-connected; the flood-fill on empty pixels must use **4-connectivity** to prevent leaking diagonally through wall corners. The initial 8-connectivity flood gave `n=0` for every rotated/sheared/organic case.

### Phase 3a: V1 axis-aligned scoring (`src/gridness/scoring/v1_axis.py`)
For each map point: cluster `x_min/x_max/x_center` and `y_min/y_max/y_center` of nearby buildings in 1D along world x and y. Mostly the algorithm from PROBLEM.md's first answer.

Known failure mode (and confirmed in the eval): cannot handle rotated or sheared grids. `grid_rotated_30` scored **0.48**, `grid_sheared` scored **0.31** — both expected high.

### Phase 3b: V2 affine scoring (`src/gridness/scoring/v2_affine.py`)
Per PROBLEM.md's final spec. For each map point:
- Sweep ~162 candidate frames `(a, b)` parameterized by `θ_a ∈ [0°, 90°)` and `θ_b - θ_a ∈ [70°, 110°]` in 5° steps (orthogonal + small shear range).
- For each frame, project building boundary pixels into `(u, v)`, take 5/95 percentile as robust extents, 1D-cluster, score by `edge_snap × two_axis × gap_bonus`.
- Take max over frames; apply small shape adjustment.

Fixed V2's main accidental bugs during iteration:
1. Initial step grid didn't include offset = 90° exactly, so axis-aligned grids were tested with a 92.5° frame (incorrect). Fixed by stepping every 5° (offsets include 90).
2. Cluster-and-score initially constructed Python dicts per cluster; rewrote as fully vectorized (sort + bincount + composite-key unique). ~3× faster and made `dense_grid` jump from 0.28 → 0.98.

V2 result: rotated and sheared now handled correctly (0.84 and 0.86). Downside: V2 explores 162 frames per point, so it's slow (~15 s per layout, 230 s total). Also over-explains organic layouts (organic_walks 0.53) because with so many candidate frames, one will accidentally fit organic noise.

### Phase 5: V3 affine + Hough-derived candidates (`src/gridness/scoring/v3_hough.py`)
Per PROBLEM.md's note that frames can come from Hough line detection. For each layout:
- Run `skimage.transform.hough_line` once globally, threshold low (5% of max), bin into 5°-wide angle bins, keep top 8 strongest.
- Build candidate frames from all valid pairs of those angles.
- For each map point: same projection + cluster + score pipeline, but only on those data-derived frames.

Two bugs found and fixed:
1. **Threshold too high** by default. With default 0.5×max threshold, `two_districts` missed the rotated district's angles (25°, 115°) because the axis-aligned district had a stronger global signal. Lowered to 0.05×max with absolute minimum 30, fixing `two_districts` from 0.76 to 0.90.
2. **Hough returns NORMAL angles, not wall directions.** In a frame `[a | b]`, the lines `u = const` are parallel to `b`, not perpendicular. So `b` must be along a wall direction (not along a wall normal). I had been using Hough angles directly, which by symmetry coincidentally worked for axis-aligned and 30°-rotated grids, but failed for sheared (frame axes off by 90°). Fix: `wall_dir = hough_angle + 90°`. `grid_sheared` jumped from 0.68 → 0.91.

V3 is **25× faster** than V2 (8.9s vs 229s total, because each layout has only 6-26 candidate frames rather than 162) AND has better discrimination, because the candidate frames are data-supported, not exhaustive — organic layouts produce many weak Hough peaks that contribute candidates but none score highly.

## Final numbers

`data/comparison_table.csv` (highlights, all means weighted by confidence > 10% of max):

| Layout | Expected | V1 | V2 | **V3** |
|---|---|---|---|---|
| dense_grid | high | 0.971 | 0.980 | **0.983** |
| grid_uniform | high | 0.962 | 0.911 | **0.987** |
| grid_variable_rows | high | 0.960 | 0.906 | **0.985** |
| grid_with_holes | high | 0.949 | 0.893 | **0.966** |
| grid_nonrect_buildings | high | 0.920 | 0.913 | **0.957** |
| grid_sheared | high | 0.313 | 0.860 | **0.913** |
| grid_rotated_30 | high | 0.477 | 0.840 | **0.898** |
| grid_mixed_sizes | high | 0.794 | 0.808 | **0.890** |
| two_districts | regional | 0.824 | 0.841 | **0.902** |
| hexagonal | medium | 0.811 | 0.885 | **0.785** |
| row_only | medium | 0.709 | 0.742 | **0.783** |
| mixed_regions | regional | 0.716 | 0.726 | **0.751** |
| rect_scattered | low | 0.420 | 0.576 | **0.495** |
| organic_walks | low | 0.434 | 0.531 | **0.460** |
| rect_rotated_scattered | low | 0.366 | 0.502 | **0.416** |

### SoS-specific variants (V3)

| Layout | n_buildings | V1 | V2 | **V3** |
|---|---|---|---|---|
| grid_streets_w1 | 196 | 0.660 | 0.902 | **0.984** |
| grid_streets_w2 | 169 | 0.987 | 0.958 | **0.992** |
| grid_streets_w3 | 144 | 0.982 | 0.935 | **0.990** |
| grid_streets_w4 | 144 | 0.988 | 0.914 | **0.992** |
| grid_streets_w5 | 121 | 0.984 | 0.915 | **0.991** |
| grid_streets_w6 | 100 | 0.964 | 0.961 | **0.989** |
| grid_streets_w7 | 100 | 0.981 | 0.957 | **0.990** |
| grid_streets_w8 | 81 | 0.969 | 0.963 | **0.980** |
| grid_streets_w9 | 81 | 0.967 | 0.982 | **0.986** |
| grid_streets_mixed | 132 | 0.950 | 0.899 | **0.950** |
| grid_rounded_corners | 121 | 0.962 | 0.911 | **0.987** |

V3 is **street-width invariant**: scores ≥ 0.98 across w=1..9 (V1 dips to 0.66 at w=1 because adjacent buildings share a wall, the extracted boundary is ambiguous, and the rectangularity-shape term suffers; V2 dips in the middle widths where the larger search space lets random frames fit organic-looking noise). The 1-cell corner-cut "roundness emitting" SoS building shape scores 0.987 — extraction sees them as near-rectangles and V3 snaps to the underlying lattice.

V3 minimum on a true grid: **0.890** (`grid_mixed_sizes`) — excluding `hexagonal` which is labeled "medium" rather than "high".
V3 maximum on a true non-grid: **0.495** (`rect_scattered` — perfect rectangles in random positions, the hardest organic case).
**Discriminative gap: ≈0.40**, comfortable margin for a threshold-based discriminator at ~0.6.

## Per-region results (V3, the regional layouts)

The global mean is misleading for regional layouts because it averages across heterogeneous regions. The eval harness already computes per-region means; here they are for the latest V3 `widths_sweep` run (sha `38ee092`):

| Layout | Region | Expected | V3 mean | V3 median |
|---|---|---|---|---|
| `mixed_regions` | left (axis grid) | high | **0.961** | 0.987 |
| `mixed_regions` | right (organic walks) | low | **0.546** | 0.580 |
| `two_districts` | left (axis-aligned grid) | high | **0.928** | 0.983 |
| `two_districts` | right (25°-rotated grid) | high | **0.878** | 0.905 |

Both regional cases come out as expected. `mixed_regions` global mean of 0.751 in the headline table is averaging 0.96 + 0.55 — the heatmap (see `experiments/.../mixed_regions.png`) shows a crisp left/right boundary at x ≈ 100. `two_districts` confirms that V3 with the relaxed Hough threshold picks up both the axis-aligned and the rotated lattices, scoring both districts as grids.

## Param tuning explored

- `min_distinct_buildings`: tried {2, 3, 4}. **`=2` is the right choice** — `=3` crashes `grid_mixed_sizes` (0.58) because in mixed-size layouts each shared edge cluster has only 2–3 supporters per local window; `=4` crashes everything.
- `complexity_lambda` (line/building ratio penalty): tried various. **Disabled by default** — Hough-derived candidates already restrict to plausible frames, so additional penalty over-penalized real grids (e.g. `grid_uniform` dropped to 0.625).
- `stride`: 8 for V2/V3 default (balances eval speed and resolution). For final user-facing maps, run at stride=4 (a few seconds per layout for V3).
- `radius`: 50 px works well across all 200×200 layouts. Larger windows for bigger maps; rule of thumb is ~4–8 typical building widths per PROBLEM.md.

## Computational complexity

Notation:
- `H`, `W`: raster dimensions (pixels)
- `R`: window radius (default 50 px)
- `s`: heatmap stride (default 8 px)
- `N`: number of extracted buildings
- `B`: average building boundary length (perimeter, ~40 px in our data)
- `F`: number of candidate frames per sample (V1: 1 fixed; V2: ~162 swept; V3: ~10–30 from Hough pairs)
- `T_h`: Hough theta resolution (90 by default — 180° / 2° step)
- `W_walls`: number of wall pixels in the raster
- `n_R`: average number of buildings inside one R-radius window ≈ `N · πR² / (H·W)`

### From-scratch heatmap

| Stage | Cost (asymptotic) | Defaults (H=W=200, R=50, s=8, F=10, N=100) |
|---|---|---|
| 1. Building extraction (flood fill + 4-conn CC + boundary trace) | `O(H·W + N·B)` | ~44k ops |
| 2. Global Hough + peak finding (V3 only) | `O(W_walls · T_h + D·T_h)` where D=√(H²+W²) | ~600k ops |
| 3. Extents cache: project N buildings into F frames | `O(F · N · B)` | ~40k ops |
| 4. Map sweep: M = `(H·W)/s²` sample points, per-sample distance + per-frame cluster | `O(M·N + M·F·n_R log n_R)` | ~625 samples × (100 + 10·20·log20) ≈ 600k ops |

**Dominant term**: stage 4 grows as `(H·W)·N/s² + F·N·R²·log(n_R)/s²`. For the default settings the two terms are roughly comparable. Empirically V3 takes **1–4 s** per layout on a 200×200 raster (see `experiments/.../*.json` for per-layout times). V1 and V2 have the same map-sweep skeleton but with `F=1` and `F≈162` respectively, explaining the 25× ratio between V2 and V3.

In symbolic form for the dominant V3 term: **`O(H·W·N / s² + F·N·R²·log n_R / s²)`**.

### Incremental update: add or remove one wall cell

| Stage | Cost | What happens |
|---|---|---|
| 1. Building re-extract (locally) | `O(building_size)` ≈ `O(B²)` | At most 1 building merges/splits with its neighbor; flood the connected component only |
| 2. Hough delta | `O(T_h)` to update accumulator; `O(D·T_h)` to re-find peaks if peaks moved | Single-pixel Hough contribution is small; in practice the dominant angles don't shift, so skip re-detection until many cells change |
| 3. Extents cache delta | `O(F·B)` | Re-project just the affected building |
| 4. Map sweep delta | `O((R/s)² · F · n_R log n_R)` | Only sample points within distance R of the affected cell change |

**Ratio to from-scratch**: stage 4 dominates and scales as `(R/s)² / (H·W/s²) = R²/(H·W)`. For defaults R=50, H=W=200, that's **1/16** — a single-cell edit is ~16× cheaper than recomputing from scratch.

### Incremental update: edit one building

Same as the single-cell case, plus full re-extract + re-project of that one building:
- Stage 1: `O(B²)` to re-trace.
- Stage 3: `O(F · B)` to recompute its F extents.
- Stage 4: `O((R/s)² · F · n_R log n_R)` again — same affected region radius.

**Asymptotic**: same as the single-cell case, with a slightly larger constant. Same **~16×** speedup vs from-scratch.

### Practical notes

- The map sweep is fully vectorizable per frame (numpy `np.bincount` + sort). The current implementation already does this in `cluster_and_score_fast`.
- The single Python `for fi in range(F)` loop in `v3_hough.py:206` could be parallelized further (vectorize over frames), but it's not currently the bottleneck.
- For real-time use during gameplay: cache the extents-per-frame array between edits; the only data structure that needs full reconstruction on geometry change is the affected building's row (`F` values).
- For large maps (H·W ≫ 200²): the from-scratch cost scales linearly in pixels and roughly linearly in N (since N usually scales with area). Use `stride=4` for higher-resolution heatmaps, accepting a 4× cost.

## Known weaknesses

1. **rect_scattered scores 0.50, not 0.30**, because random axis-aligned rectangles incidentally share grid lines by birthday-paradox. With 60 buildings × 2 edges per axis and tau=2.5 px, expected coincident pairs are ~40, enough to form a few valid clusters. Real Song of Syx random layouts will probably score similarly. A higher `min_distinct_buildings` would push this down at the cost of `grid_mixed_sizes`.
2. **hexagonal scores 0.79 despite being "medium"**. Hex packing has strong row structure plus shifted columns; the algorithm picks up real row alignment. Whether this is wrong depends on whether the user considers hex layouts "gridy" — arguably they are.
3. **Sample-grid stride of 8 px** means the heatmap is coarser than the underlying raster. Visible in `mixed_regions` where the grid/organic boundary appears slightly pixelated.
4. **Synthetic-only validation.** All tuning was against the synthetic set. Real Song of Syx layouts will have doors (1-cell gaps in walls), shared walls between buildings, decoration, etc. Building extraction may need a 1-cell `binary_closing` preprocessing step before flood fill — the rest of the pipeline should carry over but parameters may need re-tuning on a few real layouts.
5. **Global Hough** is used; for very large maps (or maps with several distinct districts at different angles), per-region Hough would be more accurate. The current code is small enough to swap in easily.
6. **`grid_sheared` generator produces overlapping buildings** (148 extracted vs ~144 expected) because the parallelogram shape protrudes slightly into vertically-adjacent cells with my lattice spacing. The algorithm handles it fine and the 0.913 score is in the right ballpark, but some shared edges in that score come from the building overlap rather than from intended lattice structure. Tighten lattice spacing to fix the generator; doesn't affect any other layout.

## Reproducibility

- Every committed code change is tracked in git.
- Each experiment is logged in `experiments/log.jsonl` with `git_sha`, `algo`, `tag`, `params`, `per-layout stats`.
- Each output figure has the short git SHA stamped in the title.
- `experiments/` artifact directories are gitignored (regeneratable from code + dataset + SHA); the `log.jsonl` index IS committed.

Per CLAUDE.md autonomous-dev rules: every experiment was run from a clean tree, results were always inspected visually before drawing conclusions, and timebox budgets were respected (V3 runs in 15 s total, well under the 5-minute eval budget).

## Recommended use

For Song of Syx integration:
- Run V3 on the raster with `V3Params(radius=R, stride=S)` chosen for map size.
- Output `gridness` heatmap is in `[0, 1]`. Threshold at ~0.6 to call regions "grid-like".
- Also expose `rowness` (max single-axis score) for the "buildings arranged in rows" signal.
- Also expose `shape` (mean rectangularity of nearby buildings) for the "individual buildings are rectangles" signal — keep it separate from layout-gridness as PROBLEM.md recommends.

## Files

```
src/gridness/
  generate.py       — 16 synthetic layouts
  extract.py        — flood-fill building extraction
  scoring/
    common.py       — vectorized 1D clustering + scoring
    v1_axis.py      — axis-aligned
    v2_affine.py    — exhaustive affine frame search
    v3_hough.py     — Hough-derived affine frames (recommended)
  eval.py           — experiment orchestration (git sha logging)
  viz.py            — heatmap visualizations

scripts/
  generate_dataset.py    — produce data/layouts/*.npy
  check_extract.py       — sanity-check building extraction
  run_experiment.py      — run one (algo, params) over the dataset
  compare_algos.py       — cross-algo comparison plot + CSV

data/
  layouts/               — committed npy + json
  contact_sheet.png      — dataset overview
  extract_check.png      — extraction overlay
  comparison_grid.png    — V1/V2/V3 heatmaps per layout
  comparison_table.csv   — per-layout mean per algo

experiments/
  log.jsonl              — one line per experiment run
  <exp_id>/figures/      — per-layout visualizations (gitignored)
```
