"""V3 scoring — affine grid-line snapping with Hough-derived candidate frames.

Differences vs V2:
  - Candidate frames come from peaks in a per-window Hough transform of the wall raster,
    not an exhaustive theta_a x theta_b grid sweep.
  - Adds a complexity penalty (unique lines per explained building) per PROBLEM.md.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from skimage.transform import hough_line, hough_line_peaks

from gridness.extract import Building
from gridness.scoring.common import cluster_and_score_fast, gaussian_weights


@dataclass
class V3Params:
    radius: float = 50.0
    sigma_frac: float = 0.5
    stride: int = 8
    min_buildings: int = 4
    # Hough
    hough_n_peaks: int = 6           # top N angle peaks to use
    hough_min_distance_deg: float = 5.0
    hough_theta_step_deg: float = 2.0  # resolution of angle search
    hough_global: bool = True        # use raster-global Hough (fast) — set False for per-window
    # Frame validity
    min_angle_sin: float = 0.34       # reject frames with |sin angle| < this (i.e., > ~70deg from collinear)
    # Cluster
    cluster_tolerance: float = 2.5
    min_distinct_buildings: int = 2
    required_lines_per_axis: int = 3
    # Complexity penalty: penalize line/building ratio above complexity_floor.
    # Disabled by default — Hough-derived candidate frames already restrict the search to
    # data-supported directions, so additional complexity penalty over-penalized real grids.
    complexity_lambda: float = 1.0
    complexity_floor: float = 0.5
    enable_complexity: bool = False
    # Shape
    shape_floor: float = 0.85
    shape_weight: float = 0.15


def _hough_angles(raster: np.ndarray, params: V3Params) -> np.ndarray:
    """Return array of dominant wall angles in radians, in [0, pi)."""
    thetas = np.deg2rad(np.arange(0.0, 180.0, params.hough_theta_step_deg))
    h, theta, d = hough_line(raster.astype(bool), theta=thetas)
    accum, ang, dist = hough_line_peaks(
        h, theta, d,
        num_peaks=params.hough_n_peaks * 4,  # over-fetch then dedupe by angle
        min_distance=max(1, int(params.hough_min_distance_deg / params.hough_theta_step_deg)),
    )
    if ang.size == 0:
        return np.array([0.0, np.pi / 2])
    # Bin into angle clusters and keep top N strongest
    ang_mod = np.mod(ang, np.pi)
    accum_by_angle: dict = {}
    bin_size = np.deg2rad(params.hough_min_distance_deg)
    for a, w in zip(ang_mod, accum):
        key = round(a / bin_size)
        accum_by_angle[key] = accum_by_angle.get(key, 0.0) + float(w)
    if not accum_by_angle:
        return np.array([0.0, np.pi / 2])
    pairs = sorted(accum_by_angle.items(), key=lambda kv: -kv[1])[: params.hough_n_peaks]
    angles = np.array([k * bin_size for k, _ in pairs])
    return angles


def _build_frames_from_angles(angles: np.ndarray, params: V3Params) -> list[np.ndarray]:
    """Build (a, b) frames from all pairs of angles where they're not parallel."""
    frames: list[np.ndarray] = []
    seen: set[tuple[int, int]] = set()
    for i in range(len(angles)):
        for j in range(len(angles)):
            if i == j:
                continue
            a_ang = angles[i]
            b_ang = angles[j]
            # canonicalize order
            key = (min(i, j), max(i, j))
            if key in seen:
                continue
            seen.add(key)
            a = np.array([np.cos(a_ang), np.sin(a_ang)])
            b = np.array([np.cos(b_ang), np.sin(b_ang)])
            sin_ang = abs(a[0] * b[1] - a[1] * b[0])
            if sin_ang < params.min_angle_sin:
                continue
            frames.append(np.stack([a, b], axis=1))
    if not frames:
        frames.append(np.eye(2))
    return frames


def _build_extents_cache(buildings: list[Building], frames: list[np.ndarray]) -> np.ndarray:
    F = len(frames); N = len(buildings)
    out = np.zeros((F, N, 4), dtype=float)
    for fi, M in enumerate(frames):
        Minv = np.linalg.inv(M)
        for ni, b in enumerate(buildings):
            xy = b.boundary_xy
            uv = xy @ Minv.T
            u = uv[:, 0]; v = uv[:, 1]
            out[fi, ni, 0] = np.percentile(u, 5)
            out[fi, ni, 1] = np.percentile(u, 95)
            out[fi, ni, 2] = np.percentile(v, 5)
            out[fi, ni, 3] = np.percentile(v, 95)
    return out


def _score_one_frame(extents_for_frame_for_sel: np.ndarray,
                      weights: np.ndarray, bids: np.ndarray,
                      p: V3Params) -> tuple[float, float]:
    """Returns (score, row_score)."""
    u_vals = np.concatenate([extents_for_frame_for_sel[:, 0], extents_for_frame_for_sel[:, 1]])
    v_vals = np.concatenate([extents_for_frame_for_sel[:, 2], extents_for_frame_for_sel[:, 3]])
    w = np.concatenate([weights, weights])
    b = np.concatenate([bids, bids])

    u_score, u_valid, _u_centers = cluster_and_score_fast(
        u_vals, w, b, p.cluster_tolerance, p.min_distinct_buildings, p.required_lines_per_axis
    )
    v_score, v_valid, _v_centers = cluster_and_score_fast(
        v_vals, w, b, p.cluster_tolerance, p.min_distinct_buildings, p.required_lines_per_axis
    )

    U_support = min(u_valid / p.required_lines_per_axis, 1.0)
    V_support = min(v_valid / p.required_lines_per_axis, 1.0)
    two_axis = float(np.sqrt(max(U_support, 0) * max(V_support, 0)))
    edge_snap = float(np.sqrt(max(u_score, 0) * max(v_score, 0)))

    # complexity: lines per building. A grid has many buildings per line (low ratio);
    # an overfit organic layout has many isolated lines (high ratio).
    n_buildings = bids.size
    if n_buildings == 0:
        return 0.0, 0.0
    n_lines = u_valid + v_valid
    ratio = n_lines / max(n_buildings, 1)
    if p.enable_complexity:
        complexity_penalty = float(np.exp(-p.complexity_lambda * max(ratio - p.complexity_floor, 0.0)))
    else:
        complexity_penalty = 1.0

    score = edge_snap * two_axis * complexity_penalty
    row = max(u_score, v_score)
    return float(np.clip(score, 0, 1)), float(row)


def score_map_v3(buildings: list[Building], shape: tuple[int, int],
                  params: V3Params | None = None,
                  raster: np.ndarray | None = None) -> dict:
    if params is None:
        params = V3Params()
    H, W = shape

    if len(buildings) == 0 or raster is None:
        zero = np.zeros((H // params.stride + 1, W // params.stride + 1))
        return {"gridness": zero, "rowness": zero, "shape": zero, "confidence": zero,
                "sample_xs": np.arange(0, W, params.stride),
                "sample_ys": np.arange(0, H, params.stride),
                "stride": params.stride, "shape_full": shape, "algo": "v3_hough",
                "params": params, "hough_angles_deg": np.array([])}

    angles = _hough_angles(raster, params)
    frames = _build_frames_from_angles(angles, params)
    extents = _build_extents_cache(buildings, frames)  # (F, N, 4)

    centroids = np.array([b.centroid for b in buildings])
    rect = np.array([b.rectangularity for b in buildings])
    sigma = params.radius * params.sigma_frac

    stride = params.stride
    ys = np.arange(stride // 2, H, stride)
    xs = np.arange(stride // 2, W, stride)
    gridness = np.zeros((ys.size, xs.size))
    rowness = np.zeros_like(gridness)
    shape_map = np.zeros_like(gridness)
    confidence = np.zeros_like(gridness)
    best_frame_idx = np.full_like(gridness, -1, dtype=float)

    for j, py in enumerate(ys):
        for i, px in enumerate(xs):
            dx = centroids[:, 0] - px
            dy = centroids[:, 1] - py
            d = np.sqrt(dx * dx + dy * dy)
            sel = d <= params.radius
            if sel.sum() < params.min_buildings:
                continue
            idx = np.where(sel)[0]
            w = gaussian_weights(d[sel], sigma)
            confidence[j, i] = float(w.sum())
            shape_map[j, i] = float(np.average(rect[idx], weights=w))

            best = 0.0; best_row = 0.0; bi = -1
            for fi in range(len(frames)):
                score, row = _score_one_frame(
                    extents[fi, idx, :], w, idx.astype(np.int64), params
                )
                if score > best:
                    best = score; bi = fi
                if row > best_row:
                    best_row = row
            final = best * (params.shape_floor + params.shape_weight * shape_map[j, i])
            gridness[j, i] = float(np.clip(final, 0, 1))
            rowness[j, i] = best_row
            best_frame_idx[j, i] = bi

    return {
        "gridness": gridness, "rowness": rowness, "shape": shape_map,
        "confidence": confidence, "best_frame_idx": best_frame_idx,
        "sample_xs": xs, "sample_ys": ys, "stride": stride,
        "shape_full": shape, "algo": "v3_hough", "params": params,
        "hough_angles_deg": np.rad2deg(angles),
        "n_frames": len(frames),
    }
