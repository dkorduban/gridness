"""Helpers shared by all scoring algorithms."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from gridness.extract import Building


@dataclass
class ClusterScoreParams:
    tolerance: float = 2.0          # tau — width of a "shared line" cluster, in pixels
    min_distinct_buildings: int = 2 # cluster needs this many distinct buildings to count
    required_clusters: int = 3      # axis_count_score is clamped to (n / required_clusters)


@dataclass
class V1Params:
    radius: float = 40.0
    sigma_frac: float = 0.5  # gaussian sigma = radius * sigma_frac
    stride: int = 4
    min_buildings: int = 4
    edge_weight: float = 1.0
    center_weight: float = 0.35
    layout_grid_weight: float = 0.75  # how much sqrt(x*y) contributes vs max(x,y)
    cluster: ClusterScoreParams = None
    shape_floor: float = 0.75
    shape_weight: float = 0.25

    def __post_init__(self) -> None:
        if self.cluster is None:
            self.cluster = ClusterScoreParams()


def cluster_1d(values: np.ndarray, weights: np.ndarray, building_ids: np.ndarray,
               tolerance: float) -> list[dict]:
    """Single-link 1D clustering with gap=tolerance.

    Returns list of clusters; each cluster is a dict {center, weight, building_ids (set), residual_sum, n_obs}.
    """
    if values.size == 0:
        return []
    order = np.argsort(values)
    v = values[order]
    w = weights[order]
    bid = building_ids[order]
    clusters: list[dict] = []
    cur_v = [v[0]]
    cur_w = [w[0]]
    cur_b = [int(bid[0])]
    last = v[0]
    for i in range(1, len(v)):
        if v[i] - last <= tolerance:
            cur_v.append(v[i]); cur_w.append(w[i]); cur_b.append(int(bid[i]))
        else:
            clusters.append(_finalize_cluster(cur_v, cur_w, cur_b))
            cur_v, cur_w, cur_b = [v[i]], [w[i]], [int(bid[i])]
        last = v[i]
    clusters.append(_finalize_cluster(cur_v, cur_w, cur_b))
    return clusters


def _finalize_cluster(values, weights, bids) -> dict:
    v = np.asarray(values, float)
    w = np.asarray(weights, float)
    total = w.sum()
    center = float((v * w).sum() / max(total, 1e-9))
    residual_sum = float((w * np.abs(v - center)).sum())
    return {
        "center": center,
        "weight": float(total),
        "building_ids": set(bids),
        "residual_sum": residual_sum,
        "n_obs": len(values),
    }


def coordinate_cluster_score(values: np.ndarray, weights: np.ndarray, building_ids: np.ndarray,
                              params: ClusterScoreParams) -> tuple[float, list[dict]]:
    if values.size == 0 or weights.sum() <= 1e-9:
        return 0.0, []
    clusters = cluster_1d(values, weights, building_ids, params.tolerance)
    valid = [c for c in clusters if len(c["building_ids"]) >= params.min_distinct_buildings]
    if not valid:
        return 0.0, []
    explained_weight = sum(c["weight"] for c in valid)
    total_weight = float(weights.sum())
    explained_mass = explained_weight / max(total_weight, 1e-9)
    cluster_count_score = min(len(valid) / params.required_clusters, 1.0)
    residual_sum = sum(c["residual_sum"] for c in valid)
    avg_residual = residual_sum / max(explained_weight, 1e-9)
    tightness = max(0.0, 1.0 - avg_residual / params.tolerance)
    return explained_mass * cluster_count_score * tightness, valid


def gaussian_weights(distances: np.ndarray, sigma: float) -> np.ndarray:
    return np.exp(-(distances ** 2) / (2.0 * sigma ** 2))
