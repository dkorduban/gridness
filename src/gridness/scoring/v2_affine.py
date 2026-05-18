"""V2 scoring — affine grid-line snapping.

For each sample point p:
  1. Take buildings within R, gaussian-weighted.
  2. For each candidate affine frame F = (a, b):
       - Reject near-parallel a/b (|sin angle| < threshold).
       - Project each building's boundary pixels into (u, v) coords.
       - Robust extents (5/95 percentile) per axis per building.
       - Cluster U and V observations separately with tolerance tau.
       - Compute score from edge_snap, shared_line, two_axis, complexity, gap_bonus, angle.
  3. Final = best_frame_score * (shape_floor + shape_weight * mean_rectangularity).

We pre-compute per-building per-frame extents once (frame-only dependent, point-independent),
then loop only candidate frames + nearby buildings inside the per-point loop.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from gridness.extract import Building
from gridness.scoring.common import (
    ClusterScoreParams,
    cluster_and_score_fast,
    coordinate_cluster_score,
    gaussian_weights,
)


@dataclass
class V2Params:
    radius: float = 50.0
    sigma_frac: float = 0.5
    stride: int = 8
    min_buildings: int = 4
    # Frame search space — both step grids must include 0 and 90 respectively to test
    # axis-aligned and orthogonal grids exactly.
    theta_a_step_deg: float = 5.0      # search a-angle in [0, 90) -> 18 angles, includes 0
    theta_b_offset_deg_min: float = 70.0
    theta_b_offset_deg_max: float = 110.0
    theta_b_step_deg: float = 5.0      # offsets {70..110}, includes 90
    min_angle_sin: float = 0.25       # reject too-parallel frames
    # Cluster
    cluster_tolerance: float = 2.5
    min_distinct_buildings: int = 2
    required_lines_per_axis: int = 3
    # Score weighting
    use_complexity_penalty: bool = True
    use_gap_bonus: bool = True
    gap_bonus_weight: float = 0.15
    shape_floor: float = 0.85
    shape_weight: float = 0.15


def _make_candidate_frames(p: V2Params) -> list[np.ndarray]:
    """Return list of 2x2 matrices M = [a | b] with unit columns."""
    frames = []
    theta_as = np.arange(0.0, 90.0, p.theta_a_step_deg)
    deltas = np.arange(p.theta_b_offset_deg_min, p.theta_b_offset_deg_max + 1e-6, p.theta_b_step_deg)
    for ta in theta_as:
        for d in deltas:
            tb = ta + d
            a = np.array([np.cos(np.deg2rad(ta)), np.sin(np.deg2rad(ta))])
            b = np.array([np.cos(np.deg2rad(tb)), np.sin(np.deg2rad(tb))])
            sin_ang = abs(a[0] * b[1] - a[1] * b[0])
            if sin_ang < p.min_angle_sin:
                continue
            frames.append(np.stack([a, b], axis=1))  # column vectors
    return frames


def _build_extents_cache(buildings: list[Building], frames: list[np.ndarray],
                         u_lo: float = 5.0, u_hi: float = 95.0) -> np.ndarray:
    """Per (frame, building) compute (u_min, u_max, v_min, v_max) at 5/95 percentile.

    Returns array of shape (F, N, 4).
    """
    F = len(frames)
    N = len(buildings)
    out = np.zeros((F, N, 4), dtype=float)
    for fi, M in enumerate(frames):
        # We want q = M^{-1} x for each pixel x. M = [a b], M^{-1} is 2x2.
        Minv = np.linalg.inv(M)
        for ni, b in enumerate(buildings):
            xy = b.boundary_xy  # (P, 2)
            uv = xy @ Minv.T  # (P, 2)
            u = uv[:, 0]
            v = uv[:, 1]
            out[fi, ni, 0] = np.percentile(u, u_lo)
            out[fi, ni, 1] = np.percentile(u, u_hi)
            out[fi, ni, 2] = np.percentile(v, u_lo)
            out[fi, ni, 3] = np.percentile(v, u_hi)
    return out


def _score_frame(extents: np.ndarray, weights: np.ndarray, building_ids: np.ndarray,
                 p: V2Params) -> tuple[float, dict]:
    """Score one frame given per-building extents and weights for nearby buildings."""
    u_vals = np.concatenate([extents[:, 0], extents[:, 1]])
    v_vals = np.concatenate([extents[:, 2], extents[:, 3]])
    w = np.concatenate([weights, weights])
    bids = np.concatenate([building_ids, building_ids])

    u_score, u_valid, u_centers = cluster_and_score_fast(
        u_vals, w, bids, p.cluster_tolerance, p.min_distinct_buildings, p.required_lines_per_axis
    )
    v_score, v_valid, v_centers = cluster_and_score_fast(
        v_vals, w, bids, p.cluster_tolerance, p.min_distinct_buildings, p.required_lines_per_axis
    )

    U_support = min(u_valid / p.required_lines_per_axis, 1.0)
    V_support = min(v_valid / p.required_lines_per_axis, 1.0)
    two_axis = float(np.sqrt(max(U_support, 0) * max(V_support, 0)))

    edge_snap_like = float(np.sqrt(max(u_score, 0) * max(v_score, 0)))

    if p.use_complexity_penalty:
        # complexity = fraction of observations that fall into valid shared clusters.
        # cluster_and_score_fast already encodes this in its explained_mass component, so
        # re-deriving it here would double-count. Use simple count-based proxy.
        complexity = 1.0  # already captured in u_score / v_score
    else:
        complexity = 1.0

    if p.use_gap_bonus and len(u_centers) >= 2 and len(v_centers) >= 2:
        gap_bonus = (_gap_regularity_arr(u_centers) + _gap_regularity_arr(v_centers)) / 2.0
    else:
        gap_bonus = 0.5

    score = edge_snap_like * two_axis * complexity * (1.0 - p.gap_bonus_weight + p.gap_bonus_weight * gap_bonus)
    return float(np.clip(score, 0.0, 1.0)), {
        "u_score": u_score, "v_score": v_score,
        "u_clusters_n": u_valid, "v_clusters_n": v_valid,
        "gap_bonus": gap_bonus,
    }


def _gap_regularity_arr(centers: np.ndarray) -> float:
    """Score how regular spacings between sorted cluster centers are. 1=regular, 0=not."""
    if centers.size < 3:
        return 0.5
    c = np.sort(centers)
    gaps = np.diff(c)
    if gaps.size == 0:
        return 0.5
    base = float(np.median(gaps))
    if base <= 0:
        return 0.0
    ratios = gaps / base
    nearest = np.round(ratios)
    residuals = np.abs(ratios - nearest)
    return float(np.exp(-(residuals ** 2).mean() * 8.0))


def score_map_v2(buildings: list[Building], shape: tuple[int, int],
                  params: V2Params | None = None) -> dict:
    if params is None:
        params = V2Params()
    H, W = shape

    if len(buildings) == 0:
        zero = np.zeros((H // params.stride + 1, W // params.stride + 1))
        return {
            "gridness": zero, "rowness": zero, "shape": zero, "confidence": zero,
            "sample_xs": np.arange(0, W, params.stride),
            "sample_ys": np.arange(0, H, params.stride),
            "stride": params.stride, "shape_full": shape, "algo": "v2_affine",
            "best_frame_angle_a": zero, "params": params,
        }

    centroids = np.array([b.centroid for b in buildings])
    rectangularity = np.array([b.rectangularity for b in buildings])
    sigma = params.radius * params.sigma_frac

    frames = _make_candidate_frames(params)
    frame_angles_a = np.array([np.arctan2(M[1, 0], M[0, 0]) for M in frames])  # angle of column 0
    frame_angles_b = np.array([np.arctan2(M[1, 1], M[0, 1]) for M in frames])
    extents_cache = _build_extents_cache(buildings, frames)  # (F, N, 4)

    stride = params.stride
    ys = np.arange(stride // 2, H, stride)
    xs = np.arange(stride // 2, W, stride)
    gridness = np.zeros((ys.size, xs.size), dtype=float)
    rowness = np.zeros_like(gridness)
    shape_map = np.zeros_like(gridness)
    confidence = np.zeros_like(gridness)
    best_angle = np.zeros_like(gridness)

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

            # shape
            shape_map[j, i] = float(np.average(rectangularity[idx], weights=w))

            best = 0.0
            best_row_like = 0.0
            best_a = 0.0
            for fi in range(len(frames)):
                ext = extents_cache[fi, idx, :]  # (n_sel, 4)
                score, breakdown = _score_frame(ext, w, idx.astype(int), params)
                if score > best:
                    best = score
                    best_a = float(frame_angles_a[fi])
                # row-like: max of u_score and v_score (single-axis rowness)
                row_like = max(breakdown["u_score"], breakdown["v_score"])
                if row_like > best_row_like:
                    best_row_like = row_like
            final = best * (params.shape_floor + params.shape_weight * shape_map[j, i])
            gridness[j, i] = float(np.clip(final, 0.0, 1.0))
            rowness[j, i] = float(best_row_like)
            best_angle[j, i] = best_a

    return {
        "gridness": gridness, "rowness": rowness, "shape": shape_map,
        "confidence": confidence, "best_angle": best_angle,
        "sample_xs": xs, "sample_ys": ys, "stride": stride,
        "shape_full": shape, "algo": "v2_affine", "params": params,
    }
