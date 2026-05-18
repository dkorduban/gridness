Q:
what's a good algorithm to detect measure of "square" city layout vs "organic" in each region of a city builder game like Songs of Syx? e.g. NY vs London. Input: 2D raster of walls/empty space. not as much about the "squareness" or "roundness" of the building, but general layout (is everithing in rows vs not, even if individual buildings are recrangle. again, it should give a measure for each poing of map, not just average.

more context: a rectangular building is typically a rectangle w/ wall width of 1. e.g. 12 x 15 cells -> 10x13 interior space. of cause non-rectangular buildings should impact non-squaredness somewhat, but if all they are arranged in a perfact grid it still should be considered decently "gridy"

A:
With that representation, I would **not** make raw wall-orientation the main signal. If every wall lives on a square tile grid, then even an organic maze of orthogonal walls has lots of `0°/90°` edges. The better primary signal is:

> Can the buildings be explained by a small set of shared row/column lines?

So treat the raster as **building objects**, then compute a local **grid-line fit score**.

## Better model

For each point `p`, compute:

```text
gridness(p) =
    layout_gridness(p) * shape_adjustment(p)
```

Where:

```text
layout_gridness = mostly about placement/alignment of buildings
shape_adjustment = small penalty/bonus for rectangular vs weird buildings
```

This matches your intent:

```text
perfect rectangular buildings randomly scattered -> low / medium-low
weird buildings arranged on a perfect grid -> still fairly high
rectangular buildings arranged on a perfect grid -> very high
```

## Step 1: Extract buildings from the wall raster

Since a building is a 1-tile wall loop around interior space:

```text
wall = 1
empty = 0
```

A good extraction method:

1. Optionally close tiny 1-tile door gaps.
2. Flood-fill empty space from the map boundary.
3. Empty cells not reached by the flood fill are enclosed interiors.
4. Each enclosed interior component is one building interior.
5. Dilate the interior by 1 tile to include its wall shell.

For each building `b`, store:

```text
footprint mask
centroid c_b
area A_b
bounding box
boundary cells
```

If buildings can share walls or have doors, this may need some game-specific cleanup, but the goal is the same: get building-level objects instead of counting every wall cell equally.

## Step 2: Score layout by fitting local grid lines

For each map point `p`, take buildings within a radius `R`.

Use a radius large enough to include multiple buildings:

```text
R = 4 to 8 typical building widths
```

or use multiple radii:

```text
R = 32, 64, 128 cells
```

For a local window, ask:

> Do many buildings share the same x/y row coordinates?

For an axis-aligned tile game, you can usually use map `x/y` directly. If rotated grids are possible, search over candidate angles.

For each building in the window, collect layout coordinates:

```text
x_min, x_max, x_center
y_min, y_max, y_center
```

For a 12×15 building, for example:

```text
x_min, x_max = left/right wall lines
y_min, y_max = top/bottom wall lines
x_center, y_center = building center
```

Then cluster these coordinates.

A grid-like area will have many buildings sharing the same row/column lines:

```text
x = 10, 25, 40, 55, ...
y = 8, 23, 38, 53, ...
```

An organic area will have more scattered coordinates:

```text
x = 7, 12, 19, 31, 34, 48, ...
y = 5, 17, 22, 29, 43, 46, ...
```

## Local coordinate-cluster score

For one axis, say `x`, collect:

```text
x_min
x_max
x_center
```

from each nearby building.

Cluster those values with a tolerance like:

```text
tau = 1.5 to 3 cells
```

Then compute:

```text
axis_score =
    explained_mass
    * cluster_count_score
    * tightness_score
```

Where:

```text
explained_mass =
    fraction of buildings whose x features fall into valid shared clusters

cluster_count_score =
    clamp(number_of_valid_clusters / required_clusters, 0, 1)

tightness_score =
    1 - average_distance_to_cluster_center / tau
```

Important: a valid cluster should be supported by multiple buildings, not just one building’s own sides.

For example:

```text
valid cluster = coordinate line used by at least 2 or 3 buildings
```

Then compute:

```text
x_score = coordinate_cluster_score(x_min, x_max, x_center)
y_score = coordinate_cluster_score(y_min, y_max, y_center)
```

A strong two-direction grid score:

```text
grid_layout = sqrt(x_score * y_score)
```

A one-direction row score:

```text
row_layout = max(x_score, y_score)
```

I would keep both. Then combine them like:

```text
layout_gridness = 0.75 * grid_layout + 0.25 * row_layout
```

This way, a district with clear rows but weak columns still gets some credit, but a true square-grid district scores much higher.

## Step 3: Add a small building-shape adjustment

For each building, compute how rectangle-like it is.

Simple version:

```text
shape_rectangularity_b =
    building_area / bounding_box_area
```

For a perfect 12×15 rectangle:

```text
shape_rectangularity ≈ 1
```

For an L-shaped building:

```text
shape_rectangularity < 1
```

Then average locally:

```text
local_shape = weighted_average(shape_rectangularity_b)
```

But do not let this dominate. Use it as a mild modifier:

```text
final_gridness =
    layout_gridness * (0.75 + 0.25 * local_shape)
```

That gives the behavior you described:

```text
perfect grid, rectangular buildings:
layout = 1.00
shape = 1.00
final = 1.00

perfect grid, weird buildings:
layout = 1.00
shape = 0.40
final = 0.85

random layout, rectangular buildings:
layout = 0.25
shape = 1.00
final = 0.25

random layout, weird buildings:
layout = 0.25
shape = 0.40
final = 0.2125
```

So building shape matters, but arrangement matters more.

## Pseudocode

```python
def gridness_at_point(buildings, p, R):
    nearby = [
        b for b in buildings
        if distance(b.centroid, p) <= R
    ]

    if len(nearby) < MIN_BUILDINGS:
        return None  # not enough evidence

    weights = {
        b: gaussian(distance(b.centroid, p), sigma=R / 2)
        for b in nearby
    }

    # If buildings are always axis-aligned, only test angle = 0.
    # If rotated grids are possible, test angles in [0, 90).
    best_layout = 0
    best_angle = 0

    for angle in candidate_angles():
        x_features = []
        y_features = []

        for b in nearby:
            # Project footprint into local coordinate frame.
            umin, umax, vmin, vmax = projected_bbox(b.footprint, angle)

            uc = (umin + umax) / 2
            vc = (vmin + vmax) / 2

            w = weights[b]

            # Side lines matter more than centers.
            x_features.append((umin, w, b.id))
            x_features.append((umax, w, b.id))
            x_features.append((uc, 0.35 * w, b.id))

            y_features.append((vmin, w, b.id))
            y_features.append((vmax, w, b.id))
            y_features.append((vc, 0.35 * w, b.id))

        x_score = coordinate_cluster_score(
            x_features,
            tolerance=TAU,
            min_distinct_buildings=2,
            required_clusters=3
        )

        y_score = coordinate_cluster_score(
            y_features,
            tolerance=TAU,
            min_distinct_buildings=2,
            required_clusters=3
        )

        grid_layout = sqrt(x_score * y_score)
        row_layout = max(x_score, y_score)

        layout = 0.75 * grid_layout + 0.25 * row_layout

        if layout > best_layout:
            best_layout = layout
            best_angle = angle

    shape_sum = 0
    weight_sum = 0

    for b in nearby:
        bbox_area = projected_bbox_area(b.footprint, best_angle)
        shape = b.area / max(bbox_area, 1)

        w = weights[b]
        shape_sum += w * shape
        weight_sum += w

    local_shape = shape_sum / max(weight_sum, EPS)

    final_gridness = best_layout * (0.75 + 0.25 * local_shape)

    return clamp(final_gridness, 0, 1)
```

And the coordinate cluster score:

```python
def coordinate_cluster_score(features, tolerance, min_distinct_buildings, required_clusters):
    # features are tuples:
    # (coordinate_value, weight, building_id)

    clusters = cluster_1d(features, eps=tolerance)

    valid_clusters = []
    explained_weight = 0
    residual_sum = 0
    total_weight = sum(w for value, w, bid in features)

    for cluster in clusters:
        building_ids = set(bid for value, w, bid in cluster)

        if len(building_ids) < min_distinct_buildings:
            continue

        center = weighted_mean(value for value, w, bid in cluster)
        cluster_weight = sum(w for value, w, bid in cluster)

        valid_clusters.append(cluster)
        explained_weight += cluster_weight

        for value, w, bid in cluster:
            residual_sum += w * abs(value - center)

    if total_weight <= 0:
        return 0

    explained_mass = explained_weight / total_weight

    cluster_count_score = clamp(
        len(valid_clusters) / required_clusters,
        0,
        1
    )

    if explained_weight > 0:
        average_residual = residual_sum / explained_weight
        tightness_score = clamp(1 - average_residual / tolerance, 0, 1)
    else:
        tightness_score = 0

    return explained_mass * cluster_count_score * tightness_score
```

## Why this handles your rectangle case better

A 12×15 rectangle contributes these layout facts:

```text
left wall line
right wall line
top wall line
bottom wall line
center point
```

It does **not** contribute 54 separate wall pixels.

That matters because otherwise large rectangular buildings overpower the score.

With object-level scoring:

```text
one big rectangle alone -> not enough shared grid lines
many rectangles sharing rows/columns -> high score
many rectangles scattered randomly -> low score
many weird shapes placed on shared rows/columns -> still high-ish
```

## Suggested final outputs

I would output several heatmaps, not just one:

```text
gridness:
    strong two-axis square-grid layout

rowness:
    buildings arranged in rows, even if columns are weak

shape_rectangularity:
    local building-shape regularity

organicness:
    1 - gridness
```

For your goal, the most useful combined score is probably:

```text
layout_gridness = 0.75 * two_axis_grid + 0.25 * one_axis_rowness

final_gridness = layout_gridness * (0.75 + 0.25 * shape_rectangularity)
```

That makes the layout dominate, while still allowing non-rectangular buildings to reduce the score slightly.

Q:
well this kinda may be gamed by offsetting each building by several pixels in new row, effectively creating diagonal streets and parallelogram blocks, which will kill one direction gridness. but in fact still be a grid. I'd like to have some method that works for any angle (w/ some level)

A:
The failure mode is real: axis-aligned row/column clustering is too restrictive. I’d reframe the score as a local affine-lattice fit, allowing rotated, sheared, or staggered grids.

Q:
yeah, also having one row of blocks larger other dimension and another smaller, while still rectangular, should be considered decently gridy (i.e. 20x20 blocks with 10x20 blocks and maybe some 10x10 blocks all together in a grid)

A:
Yes. That means the model should **not** require one building per lattice cell, and it should **not** require centers to lie on a single uniform lattice.

The better abstraction is:

> A gridy district is made of buildings whose **edges/corners snap to a shared local grid of lines**, and each building may span one or more grid cells.

So instead of:

```text
building center ≈ origin + i*a + j*b
```

use:

```text
building footprint ≈ rectangle spanning grid lines
```

where the local grid is:

```text
world_position = origin + u*a + v*b
```

with arbitrary non-parallel vectors `a` and `b`.

A building can occupy:

```text
1 x 1 cells  -> 10x10
1 x 2 cells  -> 10x20
2 x 2 cells  -> 20x20
2 x 1 cells  -> 20x10
```

and all of those should still score highly if their boundaries line up.

## Use an affine grid-line model

For a local region around point `p`, fit two families of grid lines:

```text
u = constant
v = constant
```

In world space, these can be:

```text
axis-aligned
rotated
diagonal
sheared
parallelogram-like
```

The important part is that nearby buildings share the same local coordinate system.

In this model, a building is grid-compatible if its footprint is close to:

```text
u_min = some grid line
u_max = some other grid line
v_min = some grid line
v_max = some other grid line
```

The distance between `u_min` and `u_max` does **not** have to be one unit. So mixed block sizes are fine.

## Why center-lattice alone is too strict

Suppose the underlying grid has 10-cell spacing.

A `10x10` building has center:

```text
(5, 5)
```

A `20x20` building has center:

```text
(10, 10)
```

A `10x20` building has center:

```text
(5, 10)
```

Those centers live on different phases of the grid. If you only score centers, the result can look less regular than it should.

But their **edges** are all on:

```text
x = 0, 10, 20, 30, ...
y = 0, 10, 20, 30, ...
```

So edge/corner snapping is the correct main signal.

## Revised local score

For each point `p`, compute:

```text
gridness(p) =
    best_affine_grid_line_score(p)
    * small_shape_adjustment(p)
```

Where:

```text
best_affine_grid_line_score
```

means:

> Find the best local pair of grid directions and check whether building boundaries snap to shared line families.

## Step 1: Extract building objects

From the wall raster, detect building footprints as before:

```text
wall cells -> building shell
enclosed empty cells -> building interior
building footprint = interior + wall shell
```

For each building, store:

```text
footprint mask
centroid
boundary/corner cells
area
local bounding box
rectangularity
```

Still avoid treating every wall pixel equally. A large rectangle should not dominate the score just because it has more wall cells.

## Step 2: Generate candidate local grid frames

A local grid frame is two non-parallel vectors:

```text
a, b
```

They define a coordinate transform:

```text
q = inverse([a b]) * (x - origin)
q = (u, v)
```

You can get candidate `a, b` from several sources:

```text
pairwise displacements between building corners
pairwise displacements between building centers
dominant directions in outdoor/street skeleton
local Hough/Radon line detection
```

Do not force `a` and `b` to be perpendicular.

Reject nearly parallel frames:

```text
angle_score = abs(cross(a, b)) / (length(a) * length(b))

if angle_score < 0.25:
    reject
```

That avoids absurdly skinny “grids” that overfit noise.

## Step 3: For each candidate frame, snap building edges to grid lines

For each nearby building, transform its boundary/corner points into candidate grid coordinates:

```text
(u, v) = inverse([a b]) * x
```

Then get robust projected extents:

```text
u_min, u_max
v_min, v_max
```

Use robust percentiles instead of exact min/max if footprints are noisy:

```text
u_min = 5th percentile of u coordinates
u_max = 95th percentile of u coordinates
v_min = 5th percentile of v coordinates
v_max = 95th percentile of v coordinates
```

Now collect all local edge-coordinate observations:

```text
U observations:
    u_min of each building
    u_max of each building

V observations:
    v_min of each building
    v_max of each building
```

Cluster `U` and `V` separately with tolerance `tau`.

A good grid-like area has many buildings sharing the same `u` and `v` line clusters.

A random organic area has lots of one-off edge positions.

## Key change: line positions do not need uniform spacing

Do **not** require:

```text
U lines = 0, 10, 20, 30, 40, ...
```

strictly.

Allow:

```text
U lines = 0, 10, 20, 40, 50, 70, ...
```

because buildings may span multiple cells or rows may have different block sizes.

What matters more is:

```text
many building edges reuse the same U/V lines
buildings span between pairs of existing lines
there are two clear non-parallel line families
```

Spacing regularity can be a small bonus, not a hard requirement.

## Good scoring components

For a candidate frame `A = [a b]`, compute:

```text
edge_snap_score
shared_line_score
two_axis_score
complexity_penalty
optional_gap_score
```

### 1. Edge snap score

For each building edge coordinate, measure distance to the nearest valid line cluster.

```text
edge_snap_score =
    average exp(-distance_to_nearest_line^2 / tau^2)
```

High if building boundaries lie near local grid lines.

### 2. Shared line score

A line cluster should be supported by multiple buildings.

For example:

```text
valid U line = used by at least 2 buildings
valid V line = used by at least 2 buildings
```

Then:

```text
shared_line_score =
    fraction of edge observations assigned to valid shared lines
```

This prevents the algorithm from declaring every random rectangle gridy just because each rectangle has its own private lines.

### 3. Two-axis score

Require both coordinate directions to be meaningful:

```text
U_support = clamp(valid_U_line_count / needed_U_lines, 0, 1)
V_support = clamp(valid_V_line_count / needed_V_lines, 0, 1)

two_axis_score = sqrt(U_support * V_support)
```

Use something like:

```text
needed_U_lines = 3
needed_V_lines = 3
```

If you only have one row of buildings, the confidence should be lower or classified as “row-like” rather than fully grid-like.

### 4. Complexity penalty

Organic layouts can sometimes be explained by creating many unique lines. Penalize that.

A simple version:

```text
complexity_penalty =
    shared_edge_count / total_edge_count
```

A stronger version:

```text
complexity_penalty =
    exp(-lambda * unique_line_count / explained_building_count)
```

But do not make this too harsh, because real grids with many buildings naturally have many lines.

### 5. Optional gap regularity bonus

For the sorted valid `U` lines, compute gaps:

```text
gap_i = U[i+1] - U[i]
```

Do the same for `V`.

Then check whether most gaps come from a small set of sizes or multiples:

```text
10, 20, 30
```

This should be a **bonus**, not a requirement:

```text
final_candidate_score =
    edge_snap_score
    * shared_line_score
    * two_axis_score
    * complexity_penalty
    * angle_score
    * (0.85 + 0.15 * gap_regularity_bonus)
```

This way, a grid with mixed `10x10`, `10x20`, and `20x20` buildings remains highly gridy.

## Pseudocode

```python
def gridness_at_point(buildings, p, R):
    nearby = [
        b for b in buildings
        if distance(b.centroid, p) <= R
    ]

    if len(nearby) < MIN_BUILDINGS:
        return None  # low confidence

    weights = {
        b: gaussian(distance(b.centroid, p), sigma=R / 2)
        for b in nearby
    }

    candidate_frames = estimate_candidate_affine_frames(nearby)

    best = 0.0

    for A in candidate_frames:
        # A is a 2x2 matrix: columns are local grid vectors a and b.
        if nearly_singular(A):
            continue

        angle_score = affine_angle_score(A)
        if angle_score < 0.25:
            continue

        U_obs = []
        V_obs = []

        for b in nearby:
            q_points = [
                inverse(A) @ x
                for x in representative_boundary_points(b)
            ]

            us = [q.x for q in q_points]
            vs = [q.y for q in q_points]

            # Robust min/max avoids one weird wall cell ruining the score.
            u0 = percentile(us, 5)
            u1 = percentile(us, 95)
            v0 = percentile(vs, 5)
            v1 = percentile(vs, 95)

            w = weights[b]

            U_obs.append((u0, w, b.id))
            U_obs.append((u1, w, b.id))
            V_obs.append((v0, w, b.id))
            V_obs.append((v1, w, b.id))

        U_clusters = cluster_1d(U_obs, tolerance=TAU)
        V_clusters = cluster_1d(V_obs, tolerance=TAU)

        valid_U = [
            c for c in U_clusters
            if distinct_building_count(c) >= 2
        ]

        valid_V = [
            c for c in V_clusters
            if distinct_building_count(c) >= 2
        ]

        edge_snap_score = score_edge_snapping(
            U_obs, V_obs,
            valid_U, valid_V,
            tolerance=TAU
        )

        shared_line_score = score_shared_line_usage(
            U_obs, V_obs,
            valid_U, valid_V
        )

        U_support = clamp(len(valid_U) / REQUIRED_LINES_PER_AXIS, 0, 1)
        V_support = clamp(len(valid_V) / REQUIRED_LINES_PER_AXIS, 0, 1)

        two_axis_score = sqrt(U_support * V_support)

        complexity_penalty = compute_complexity_penalty(
            valid_U, valid_V, nearby
        )

        gap_bonus = compute_gap_regularity_bonus(valid_U, valid_V)

        candidate_score = (
            edge_snap_score
            * shared_line_score
            * two_axis_score
            * complexity_penalty
            * angle_score
            * (0.85 + 0.15 * gap_bonus)
        )

        best = max(best, candidate_score)

    local_shape = weighted_average(
        [building_rectangularity(b) for b in nearby],
        [weights[b] for b in nearby]
    )

    # Shape should matter, but not dominate.
    final = best * (0.85 + 0.15 * local_shape)

    return clamp(final, 0, 1)
```

## Mixed-size example

Suppose your local grid lines are:

```text
U lines: 0, 10, 20, 30, 40
V lines: 0, 10, 20, 30, 40
```

Then these buildings are all grid-compatible:

```text
10x10:
    U: 0 -> 10
    V: 0 -> 10

10x20:
    U: 10 -> 20
    V: 0 -> 20

20x20:
    U: 20 -> 40
    V: 0 -> 20

20x10:
    U: 0 -> 20
    V: 20 -> 30
```

They span different numbers of intervals, but their boundaries reuse the same local grid lines. That should score highly.

## Combine with the earlier lattice score

The best practical system is probably:

```text
layout_gridness =
    max(
        affine_grid_line_score,
        affine_center_lattice_score * 0.85
    )
```

Why keep both?

```text
affine_grid_line_score
```

handles:

```text
mixed 10x10 / 10x20 / 20x20 blocks
merged cells
shared rows and columns
variable block sizes
```

while:

```text
affine_center_lattice_score
```

helps with:

```text
offset rows
diagonal placement patterns
repeated building placement
cases where wall geometry is noisy
```

For your use case, I would make **grid-line snapping** the main signal and use **center/translation lattice** as an auxiliary signal.

## Useful output maps

I would output several maps instead of one:

```text
affine_gridness:
    gridy under any rotation/shear, variable block sizes allowed

orthogonal_gridness:
    affine_gridness penalized if the two directions are not near 90 degrees

spacing_regularity:
    whether line gaps are uniform or mostly multiples of a base size

rowness:
    one strong axis but weak second axis

shape_rectangularity:
    how rectangular the buildings themselves are

organicness:
    1 - affine_gridness
```

The key distinction is:

```text
affine grid-line score = planned/grid layout
orthogonal score       = square/Manhattan-like layout
spacing regularity     = equal-sized blocks
shape rectangularity   = individual building shape
```

For your examples, mixed `20x20`, `10x20`, and `10x10` blocks should have:

```text
high affine_gridness
medium/high spacing_regularity
high shape_rectangularity
high orthogonal_gridness if not sheared
lower orthogonal_gridness if heavily sheared
```

That matches the intuition: variable block sizes do not make a layout organic if the block boundaries still participate in the same local grid.
