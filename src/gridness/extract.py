"""Building extraction from a wall raster.

Pipeline:
  1. Flood-fill empty space starting from the boundary.
  2. Empty cells not reached by flood are building interiors.
  3. Each connected interior component = one building.
  4. Footprint = interior dilated by 1 cell (interior + adjacent walls).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.ndimage import binary_dilation, binary_erosion, label


@dataclass
class Building:
    id: int
    centroid: np.ndarray  # (x, y) — col, row — float
    bbox: tuple[int, int, int, int]  # (x_min, y_min, x_max, y_max) inclusive
    area: int
    bbox_area: int
    rectangularity: float
    # boundary pixel coords (x_world, y_world) — N points along the outer ring of the footprint
    boundary_xy: np.ndarray  # shape (N, 2)
    # corner-ish points (the bbox corners projected onto boundary; cheap proxy for "corners")
    corner_xy: np.ndarray  # shape (4, 2)

    @property
    def x_min(self) -> int: return self.bbox[0]
    @property
    def y_min(self) -> int: return self.bbox[1]
    @property
    def x_max(self) -> int: return self.bbox[2]
    @property
    def y_max(self) -> int: return self.bbox[3]


_CROSS4 = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=int)


def extract_buildings(raster: np.ndarray, min_area: int = 6) -> list[Building]:
    # Walls are drawn 8-connected (filled polygon + erosion boundary), so we
    # must label empty cells with 4-connectivity to prevent the flood-fill from
    # leaking diagonally through wall corners.
    H, W = raster.shape
    empty = (raster == 0)

    labeled, n = label(empty, structure=_CROSS4)
    border_labels: set[int] = set()
    for arr in (labeled[0, :], labeled[-1, :], labeled[:, 0], labeled[:, -1]):
        border_labels.update(int(v) for v in np.unique(arr) if v != 0)
    interior_empty = np.zeros_like(empty)
    for lab in range(1, n + 1):
        if lab not in border_labels:
            interior_empty |= (labeled == lab)

    interior_labeled, nb = label(interior_empty, structure=_CROSS4)
    buildings: list[Building] = []
    next_id = 0
    for i in range(1, nb + 1):
        interior = interior_labeled == i
        if interior.sum() < min_area:
            continue
        footprint = binary_dilation(interior, iterations=1)
        ys, xs = np.where(footprint)
        if xs.size == 0:
            continue
        x_min, x_max = int(xs.min()), int(xs.max())
        y_min, y_max = int(ys.min()), int(ys.max())
        area = int(footprint.sum())
        bbox_area = (x_max - x_min + 1) * (y_max - y_min + 1)
        rect = area / max(bbox_area, 1)
        cx = float(xs.mean())
        cy = float(ys.mean())
        boundary = footprint & ~binary_erosion(footprint)
        by, bx = np.where(boundary)
        boundary_xy = np.stack([bx.astype(float), by.astype(float)], axis=1)
        corner_xy = np.array(
            [[x_min, y_min], [x_max, y_min], [x_max, y_max], [x_min, y_max]],
            dtype=float,
        )
        buildings.append(Building(
            id=next_id,
            centroid=np.array([cx, cy]),
            bbox=(x_min, y_min, x_max, y_max),
            area=area,
            bbox_area=bbox_area,
            rectangularity=rect,
            boundary_xy=boundary_xy,
            corner_xy=corner_xy,
        ))
        next_id += 1
    return buildings
