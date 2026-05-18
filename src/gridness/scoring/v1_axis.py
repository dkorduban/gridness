"""V1 scoring — axis-aligned edge-line clustering.

For each sample point p:
  - take buildings within radius R, gaussian-weighted
  - collect x_min/x_max/x_center and y_min/y_max/y_center as observations
  - 1D-cluster each axis; score = explained_mass * cluster_count * tightness
  - layout = 0.75*sqrt(x*y) + 0.25*max(x,y)
  - final = layout * (0.75 + 0.25 * mean_rectangularity)
"""

from __future__ import annotations

import numpy as np

from gridness.extract import Building
from gridness.scoring.common import (
    V1Params,
    coordinate_cluster_score,
    gaussian_weights,
)


def _features_for_building(b: Building, weight: float, edge_w: float, center_w: float):
    cx_center = (b.x_min + b.x_max) / 2.0
    cy_center = (b.y_min + b.y_max) / 2.0
    xs = np.array([b.x_min, b.x_max, cx_center])
    ys = np.array([b.y_min, b.y_max, cy_center])
    ws = np.array([weight * edge_w, weight * edge_w, weight * center_w])
    bids = np.full(3, b.id, dtype=int)
    return xs, ys, ws, bids


def score_map_v1(buildings: list[Building], shape: tuple[int, int],
                  params: V1Params | None = None) -> dict:
    if params is None:
        params = V1Params()
    H, W = shape
    centroids = np.array([b.centroid for b in buildings])  # (N, 2)
    sigma = params.radius * params.sigma_frac

    stride = params.stride
    ys = np.arange(stride // 2, H, stride)
    xs = np.arange(stride // 2, W, stride)
    grid_y, grid_x = np.meshgrid(ys, xs, indexing="ij")

    gridness = np.zeros_like(grid_x, dtype=float)
    rowness = np.zeros_like(grid_x, dtype=float)
    confidence = np.zeros_like(grid_x, dtype=float)
    shape_map = np.zeros_like(grid_x, dtype=float)

    for j in range(grid_y.shape[0]):
        for i in range(grid_x.shape[1]):
            px, py = float(grid_x[j, i]), float(grid_y[j, i])
            if centroids.size == 0:
                continue
            dx = centroids[:, 0] - px
            dy = centroids[:, 1] - py
            d = np.sqrt(dx * dx + dy * dy)
            sel = d <= params.radius
            if sel.sum() < params.min_buildings:
                continue
            nearby = [buildings[k] for k in np.where(sel)[0]]
            w = gaussian_weights(d[sel], sigma)
            confidence[j, i] = float(w.sum())

            x_vals, y_vals, w_vals, b_vals = [], [], [], []
            shape_acc, shape_w = 0.0, 0.0
            for bb, ww in zip(nearby, w):
                xs_b, ys_b, ws_b, bids_b = _features_for_building(
                    bb, ww, params.edge_weight, params.center_weight
                )
                x_vals.append(xs_b); y_vals.append(ys_b)
                w_vals.append(ws_b); b_vals.append(bids_b)
                shape_acc += ww * bb.rectangularity
                shape_w += ww
            x_vals = np.concatenate(x_vals)
            y_vals = np.concatenate(y_vals)
            w_vals = np.concatenate(w_vals)
            b_vals = np.concatenate(b_vals)

            x_score, _ = coordinate_cluster_score(x_vals, w_vals, b_vals, params.cluster)
            y_score, _ = coordinate_cluster_score(y_vals, w_vals, b_vals, params.cluster)
            grid_layout = float(np.sqrt(x_score * y_score))
            row_layout = float(max(x_score, y_score))
            layout = (params.layout_grid_weight * grid_layout
                      + (1 - params.layout_grid_weight) * row_layout)

            local_shape = shape_acc / max(shape_w, 1e-9)
            final = layout * (params.shape_floor + params.shape_weight * local_shape)

            gridness[j, i] = float(np.clip(final, 0.0, 1.0))
            rowness[j, i] = row_layout
            shape_map[j, i] = local_shape

    return {
        "gridness": gridness,
        "rowness": rowness,
        "shape": shape_map,
        "confidence": confidence,
        "sample_xs": xs,
        "sample_ys": ys,
        "stride": stride,
        "shape_full": shape,
        "algo": "v1_axis",
        "params": params,
    }
