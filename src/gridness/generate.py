"""Synthetic city-layout generator.

Each layout is a 2D uint8 raster (1 = wall, 0 = empty).
Layouts are constructed by:
  1. building a list of filled-building masks,
  2. taking the 1-pixel outer boundary of each mask (XOR with erosion),
  3. OR-ing all boundaries into the raster.

Buildings can therefore have any shape, share walls, sit on rotated/sheared
grids, etc. The generator is fully deterministic given seed.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Literal

import numpy as np
from scipy.ndimage import binary_erosion
from skimage.draw import polygon as draw_polygon


# ---------- primitive shapes ----------

def filled_rect(H: int, W: int, x: int, y: int, w: int, h: int) -> np.ndarray:
    m = np.zeros((H, W), dtype=bool)
    x1, x2 = max(0, x), min(W, x + w)
    y1, y2 = max(0, y), min(H, y + h)
    m[y1:y2, x1:x2] = True
    return m


def filled_polygon(H: int, W: int, pts_xy: np.ndarray) -> np.ndarray:
    """pts_xy: (N, 2) array of (x, y)."""
    m = np.zeros((H, W), dtype=bool)
    rr, cc = draw_polygon(pts_xy[:, 1], pts_xy[:, 0], shape=(H, W))
    m[rr, cc] = True
    return m


def filled_rot_rect(H: int, W: int, cx: float, cy: float, w: float, h: float, angle_deg: float) -> np.ndarray:
    a = np.deg2rad(angle_deg)
    c, s = np.cos(a), np.sin(a)
    corners = np.array([[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]])
    R = np.array([[c, -s], [s, c]])
    pts = corners @ R.T + np.array([cx, cy])
    return filled_polygon(H, W, pts)


def filled_sheared_rect(H: int, W: int, x: float, y: float, w: float, h: float, shear: float) -> np.ndarray:
    """Parallelogram: bottom-left (x, y), spans (w, 0) and (shear*h, h)."""
    pts = np.array(
        [[x, y], [x + w, y], [x + w + shear * h, y + h], [x + shear * h, y + h]],
        dtype=float,
    )
    return filled_polygon(H, W, pts)


# ---------- raster assembly ----------

def boundary_of(mask: np.ndarray) -> np.ndarray:
    return mask & ~binary_erosion(mask)


def walls_from_buildings(building_masks: list[np.ndarray], H: int, W: int) -> np.ndarray:
    raster = np.zeros((H, W), dtype=np.uint8)
    for m in building_masks:
        raster |= boundary_of(m).astype(np.uint8)
    return raster


# ---------- non-rectangular footprints ----------

def l_shape(H: int, W: int, x: int, y: int, w: int, h: int, cut_w: int, cut_h: int) -> np.ndarray:
    """L-shape: rectangle (w, h) with top-right corner removed (cut_w, cut_h)."""
    m = filled_rect(H, W, x, y, w, h)
    m &= ~filled_rect(H, W, x + w - cut_w, y + h - cut_h, cut_w, cut_h)
    return m


def t_shape(H: int, W: int, x: int, y: int, w: int, h: int, stem_w: int, stem_h: int) -> np.ndarray:
    """T-shape: top horizontal bar (w, h - stem_h), stem below centered."""
    m = filled_rect(H, W, x, y + stem_h, w, h - stem_h)
    sx = x + (w - stem_w) // 2
    m |= filled_rect(H, W, sx, y, stem_w, stem_h)
    return m


def plus_shape(H: int, W: int, cx: int, cy: int, arm_long: int, arm_short: int) -> np.ndarray:
    m = filled_rect(H, W, cx - arm_long // 2, cy - arm_short // 2, arm_long, arm_short)
    m |= filled_rect(H, W, cx - arm_short // 2, cy - arm_long // 2, arm_short, arm_long)
    return m


# ---------- layout descriptor ----------

@dataclass
class Layout:
    name: str
    raster: np.ndarray
    expected: dict  # qualitative expectation, see below
    notes: str = ""

    @property
    def shape(self) -> tuple[int, int]:
        return self.raster.shape


# Expected score format:
#   "global": {"gridness": "high"|"medium"|"low"}
# or for regional layouts:
#   "regions": [{"bbox": [x, y, w, h], "gridness": "high"}, ...]


# ---------- layouts ----------

H_DEFAULT, W_DEFAULT = 200, 200


def L_grid_uniform(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    base, gap = 12, 4
    period = base + gap
    margin = 8
    bs = []
    y = margin
    while y + base <= H - margin:
        x = margin
        while x + base <= W - margin:
            bs.append(filled_rect(H, W, x, y, base, base))
            x += period
        y += period
    return Layout("grid_uniform", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="axis-aligned 12x12 buildings on 16-period grid")


def L_grid_mixed_sizes(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Mixed 1x1, 2x1, 1x2, 2x2 blocks on a shared 16-period grid."""
    rng = np.random.default_rng(seed)
    base, gap = 12, 4
    period = base + gap
    margin = 8
    nx = (W - 2 * margin) // period
    ny = (H - 2 * margin) // period
    used = np.zeros((ny, nx), dtype=bool)
    options = [((1, 1), 0.45), ((2, 1), 0.2), ((1, 2), 0.2), ((2, 2), 0.15)]
    bs = []
    for j in range(ny):
        for i in range(nx):
            if used[j, i]:
                continue
            order = rng.permutation(len(options))
            for k in order:
                (sx, sy), _ = options[k]
                if i + sx <= nx and j + sy <= ny and not used[j:j + sy, i:i + sx].any():
                    used[j:j + sy, i:i + sx] = True
                    x = margin + i * period
                    y = margin + j * period
                    w = sx * base + (sx - 1) * gap
                    h = sy * base + (sy - 1) * gap
                    bs.append(filled_rect(H, W, x, y, w, h))
                    break
    return Layout("grid_mixed_sizes", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="1x1/2x1/1x2/2x2 blocks on shared 16-period grid")


def L_grid_variable_rows(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Axis-aligned grid but row heights and column widths vary."""
    rng = np.random.default_rng(seed)
    margin = 8
    gap = 4
    col_widths = []
    x = margin
    while x < W - margin - 8:
        w = int(rng.choice([10, 12, 14, 18]))
        if x + w >= W - margin:
            break
        col_widths.append((x, w))
        x += w + gap
    row_heights = []
    y = margin
    while y < H - margin - 8:
        h = int(rng.choice([10, 14, 18]))
        if y + h >= H - margin:
            break
        row_heights.append((y, h))
        y += h + gap
    bs = [filled_rect(H, W, x, y, w, h) for (x, w) in col_widths for (y, h) in row_heights]
    return Layout("grid_variable_rows", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="rows and cols have varying widths/heights; still grid")


def _grid_centers_in_frame(a: np.ndarray, b: np.ndarray, origin: np.ndarray,
                           H: int, W: int, n_u: int, n_v: int):
    """Yield world-space centers for a u in [-n_u, n_u], v in [-n_v, n_v] lattice."""
    for iu in range(-n_u, n_u + 1):
        for iv in range(-n_v, n_v + 1):
            c = origin + iu * a + iv * b
            if 0 <= c[0] < W and 0 <= c[1] < H:
                yield c, iu, iv


def L_grid_rotated_30(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    angle = 30.0
    a_vec = np.array([16.0, 0.0])
    b_vec = np.array([0.0, 16.0])
    R = np.array([[np.cos(np.deg2rad(angle)), -np.sin(np.deg2rad(angle))],
                  [np.sin(np.deg2rad(angle)), np.cos(np.deg2rad(angle))]])
    a_vec = R @ a_vec
    b_vec = R @ b_vec
    origin = np.array([W / 2.0, H / 2.0])
    bs = []
    for c, _, _ in _grid_centers_in_frame(a_vec, b_vec, origin, H, W, 10, 10):
        bs.append(filled_rot_rect(H, W, c[0], c[1], 12, 12, angle))
    return Layout("grid_rotated_30", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="12x12 buildings on a perfect grid rotated 30 degrees")


def L_grid_sheared(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Parallelogram grid: staggered rows, axis-aligned building shapes."""
    a_vec = np.array([16.0, 0.0])  # x direction
    b_vec = np.array([4.0, 16.0])  # y + shear
    origin = np.array([20.0, 20.0])
    bs = []
    for c, _, _ in _grid_centers_in_frame(a_vec, b_vec, origin, H, W, 10, 10):
        bs.append(filled_sheared_rect(H, W, c[0] - 6, c[1] - 6, 12, 12, b_vec[0] / b_vec[1]))
    return Layout("grid_sheared", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="sheared parallelogram grid; 12x12 sheared buildings")


def L_grid_nonrect_buildings(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    rng = np.random.default_rng(seed)
    base, gap = 16, 4
    period = base + gap
    margin = 8
    bs = []
    y = margin
    while y + base <= H - margin:
        x = margin
        while x + base <= W - margin:
            shape = rng.choice(["rect", "L", "T", "plus"], p=[0.25, 0.3, 0.25, 0.2])
            if shape == "rect":
                bs.append(filled_rect(H, W, x, y, base, base))
            elif shape == "L":
                bs.append(l_shape(H, W, x, y, base, base, 6, 6))
            elif shape == "T":
                bs.append(t_shape(H, W, x, y, base, base, 6, 6))
            elif shape == "plus":
                bs.append(plus_shape(H, W, x + base // 2, y + base // 2, base, 6))
            x += period
        y += period
    return Layout("grid_nonrect_buildings", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="L/T/+ shapes placed on a grid; layout high, shape mid")


def L_rect_scattered(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    rng = np.random.default_rng(seed)
    bs = []
    attempts = 0
    placed = 0
    target = 60
    while placed < target and attempts < 5000:
        attempts += 1
        w = int(rng.integers(8, 20))
        h = int(rng.integers(8, 20))
        x = int(rng.integers(4, W - w - 4))
        y = int(rng.integers(4, H - h - 4))
        new = filled_rect(H, W, x - 2, y - 2, w + 4, h + 4)  # require 2-cell buffer
        if any((new & b).any() for b in bs):
            continue
        bs.append(filled_rect(H, W, x, y, w, h))
        placed += 1
    return Layout("rect_scattered", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "low"}},
                  notes=f"rectangular buildings randomly placed (n={placed}); layout low")


def L_organic_walks(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Irregular convex-ish polygons at random positions and angles."""
    rng = np.random.default_rng(seed)
    bs = []
    attempts = 0
    placed = 0
    target = 50
    while placed < target and attempts < 5000:
        attempts += 1
        n_verts = int(rng.integers(5, 9))
        cx = float(rng.integers(15, W - 15))
        cy = float(rng.integers(15, H - 15))
        angles = np.sort(rng.uniform(0, 2 * np.pi, size=n_verts))
        radii = rng.uniform(6, 14, size=n_verts)
        pts = np.stack([cx + radii * np.cos(angles), cy + radii * np.sin(angles)], axis=1)
        new = filled_polygon(H, W, pts)
        bs_so_far_combined = np.zeros((H, W), dtype=bool)
        for b in bs:
            bs_so_far_combined |= b
        # dilate other buildings by 3 px as buffer
        from scipy.ndimage import binary_dilation
        buffer = binary_dilation(bs_so_far_combined, iterations=3)
        if (new & buffer).any() or new.sum() < 50:
            continue
        bs.append(new)
        placed += 1
    return Layout("organic_walks", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "low"}},
                  notes=f"irregular polygons at random positions (n={placed}); layout low")


def L_hexagonal(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    a_vec = np.array([18.0, 0.0])
    b_vec = np.array([9.0, 18.0 * np.sqrt(3) / 2])
    origin = np.array([10.0, 10.0])
    bs = []
    for c, _, _ in _grid_centers_in_frame(a_vec, b_vec, origin, H, W, 14, 14):
        bs.append(filled_rect(H, W, int(c[0] - 6), int(c[1] - 6), 12, 12))
    return Layout("hexagonal", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "medium"}},
                  notes="hex-packed centers; should be partially grid-like")


def L_mixed_regions(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Left half: grid. Right half: organic."""
    rng = np.random.default_rng(seed)
    bs = []
    # left half: grid
    base, gap = 12, 4
    period = base + gap
    margin = 8
    y = margin
    while y + base <= H - margin:
        x = margin
        while x + base <= W // 2 - 4:
            bs.append(filled_rect(H, W, x, y, base, base))
            x += period
        y += period
    # right half: organic
    attempts = 0
    placed = 0
    target = 25
    from scipy.ndimage import binary_dilation
    while placed < target and attempts < 3000:
        attempts += 1
        n_verts = int(rng.integers(5, 9))
        cx = float(rng.integers(W // 2 + 10, W - 15))
        cy = float(rng.integers(15, H - 15))
        angles = np.sort(rng.uniform(0, 2 * np.pi, size=n_verts))
        radii = rng.uniform(6, 12, size=n_verts)
        pts = np.stack([cx + radii * np.cos(angles), cy + radii * np.sin(angles)], axis=1)
        new = filled_polygon(H, W, pts)
        combined = np.zeros((H, W), dtype=bool)
        for b in bs:
            combined |= b
        buffer = binary_dilation(combined, iterations=3)
        if (new & buffer).any() or new.sum() < 50:
            continue
        bs.append(new)
        placed += 1
    return Layout("mixed_regions", walls_from_buildings(bs, H, W),
                  {"regions": [
                      {"bbox": [0, 0, W // 2, H], "gridness": "high"},
                      {"bbox": [W // 2, 0, W // 2, H], "gridness": "low"},
                  ]},
                  notes="left half grid, right half organic")


def L_grid_with_holes(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    rng = np.random.default_rng(seed)
    base, gap = 12, 4
    period = base + gap
    margin = 8
    bs = []
    y = margin
    while y + base <= H - margin:
        x = margin
        while x + base <= W - margin:
            if rng.random() > 0.18:
                bs.append(filled_rect(H, W, x, y, base, base))
            x += period
        y += period
    return Layout("grid_with_holes", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="uniform grid with ~18% buildings randomly removed")


def L_row_only(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Strong horizontal rows but column positions jittered."""
    rng = np.random.default_rng(seed)
    margin = 8
    gap = 4
    bs = []
    y = margin
    base_h = 14
    while y + base_h <= H - margin:
        x = margin + int(rng.integers(0, 8))
        while x < W - margin - 8:
            w = int(rng.choice([10, 12, 14, 16, 20]))
            if x + w >= W - margin:
                break
            bs.append(filled_rect(H, W, x, y, w, base_h))
            x += w + gap + int(rng.integers(0, 4))
        y += base_h + gap
    return Layout("row_only", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "medium", "rowness": "high"}},
                  notes="strong horizontal rows, jittered column positions")


def L_tiny_grid(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    bs = []
    for j in range(2):
        for i in range(2):
            x = 60 + i * 40
            y = 60 + j * 40
            bs.append(filled_rect(H, W, x, y, 16, 16))
    return Layout("tiny_grid", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "medium", "low_evidence": True}},
                  notes="only 4 buildings; should produce low-confidence scores")


def L_dense_grid(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    base, gap = 6, 2
    period = base + gap
    margin = 4
    bs = []
    y = margin
    while y + base <= H - margin:
        x = margin
        while x + base <= W - margin:
            bs.append(filled_rect(H, W, x, y, base, base))
            x += period
        y += period
    return Layout("dense_grid", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "high"}},
                  notes="many tiny 6x6 buildings on tight grid")


def L_two_districts(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Left half axis-aligned grid, right half rotated 25-degree grid."""
    bs = []
    # left
    base, gap = 12, 4
    period = base + gap
    margin = 8
    y = margin
    while y + base <= H - margin:
        x = margin
        while x + base <= W // 2 - 4:
            bs.append(filled_rect(H, W, x, y, base, base))
            x += period
        y += period
    # right rotated
    angle = 25.0
    a_vec = np.array([16.0, 0.0])
    b_vec = np.array([0.0, 16.0])
    R = np.array([[np.cos(np.deg2rad(angle)), -np.sin(np.deg2rad(angle))],
                  [np.sin(np.deg2rad(angle)), np.cos(np.deg2rad(angle))]])
    a_vec = R @ a_vec
    b_vec = R @ b_vec
    origin = np.array([3 * W / 4, H / 2.0])
    for c, _, _ in _grid_centers_in_frame(a_vec, b_vec, origin, H, W, 8, 8):
        if c[0] > W // 2 + 4:
            bs.append(filled_rot_rect(H, W, c[0], c[1], 12, 12, angle))
    return Layout("two_districts", walls_from_buildings(bs, H, W),
                  {"regions": [
                      {"bbox": [0, 0, W // 2, H], "gridness": "high"},
                      {"bbox": [W // 2, 0, W // 2, H], "gridness": "high"},
                  ]},
                  notes="two grids at different angles meeting in middle")


def L_organic_arrangement_rect_buildings(H=H_DEFAULT, W=W_DEFAULT, seed=0) -> Layout:
    """Like rect_scattered but with rotated rectangles at random angles — perfect rectangles, no shared lines."""
    rng = np.random.default_rng(seed)
    bs = []
    attempts = 0
    placed = 0
    target = 40
    from scipy.ndimage import binary_dilation
    while placed < target and attempts < 5000:
        attempts += 1
        w = float(rng.integers(10, 22))
        h = float(rng.integers(10, 22))
        cx = float(rng.integers(15, W - 15))
        cy = float(rng.integers(15, H - 15))
        angle = float(rng.uniform(0, 180))
        new = filled_rot_rect(H, W, cx, cy, w, h, angle)
        combined = np.zeros((H, W), dtype=bool)
        for b in bs:
            combined |= b
        buffer = binary_dilation(combined, iterations=3)
        if (new & buffer).any() or new.sum() < 50:
            continue
        bs.append(new)
        placed += 1
    return Layout("rect_rotated_scattered", walls_from_buildings(bs, H, W),
                  {"global": {"gridness": "low"}},
                  notes=f"perfect rectangles at random angles & positions (n={placed}); layout low")


ALL_LAYOUTS: list[Callable[..., Layout]] = [
    L_grid_uniform,
    L_grid_mixed_sizes,
    L_grid_variable_rows,
    L_grid_rotated_30,
    L_grid_sheared,
    L_grid_nonrect_buildings,
    L_rect_scattered,
    L_rect_scattered,  # placeholder to be replaced
    L_organic_walks,
    L_hexagonal,
    L_mixed_regions,
    L_grid_with_holes,
    L_row_only,
    L_tiny_grid,
    L_dense_grid,
    L_two_districts,
    L_organic_arrangement_rect_buildings,
]

# Deduplicate
_seen = set()
_unique = []
for fn in ALL_LAYOUTS:
    if fn not in _seen:
        _unique.append(fn)
        _seen.add(fn)
ALL_LAYOUTS = _unique
