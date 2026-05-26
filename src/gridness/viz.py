"""Visualization helpers."""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

from gridness.extract import Building


def upsample_to_full(score_map: np.ndarray, full_shape: tuple[int, int],
                     stride: int) -> np.ndarray:
    """Replicate-upsample a strided score grid to full resolution."""
    H, W = full_shape
    out = np.zeros(full_shape, dtype=float)
    h, w = score_map.shape
    for j in range(h):
        for i in range(w):
            y0 = j * stride
            x0 = i * stride
            out[y0:y0 + stride, x0:x0 + stride] = score_map[j, i]
    out[:, w * stride:] = out[:, w * stride - 1:w * stride]
    out[h * stride:, :] = out[h * stride - 1:h * stride, :]
    return out


def visualize_result(raster: np.ndarray, buildings: list[Building], result: dict,
                     layout_name: str, expected: dict, git_sha: str,
                     out_path: Path, extra_maps: dict | None = None) -> None:
    """Save a contact-sheet for one layout: input | extracted | heatmaps."""
    H, W = raster.shape
    stride = result["stride"]
    grid_full = upsample_to_full(result["gridness"], (H, W), stride)
    row_full = upsample_to_full(result["rowness"], (H, W), stride)
    shape_full = upsample_to_full(result["shape"], (H, W), stride)
    conf_full = upsample_to_full(result["confidence"], (H, W), stride)

    panels = [
        ("input", raster, "gray_r", None),
        ("buildings", _building_overlay(raster, buildings), None, None),
        ("gridness", grid_full, "viridis", (0, 1)),
        ("rowness", row_full, "viridis", (0, 1)),
        ("shape (rect.)", shape_full, "magma", (0, 1)),
        ("confidence", conf_full, "inferno", None),
    ]

    n = len(panels)
    fig, axes = plt.subplots(1, n, figsize=(2.6 * n, 3.2))
    for ax, (title, im, cmap, vrange) in zip(axes, panels):
        if cmap is None:
            ax.imshow(im)
        else:
            kw = {"cmap": cmap, "interpolation": "nearest"}
            if vrange is not None:
                kw["vmin"], kw["vmax"] = vrange
            mappable = ax.imshow(im, **kw)
            if title not in ("input",):
                plt.colorbar(mappable, ax=ax, fraction=0.046, pad=0.02)
        ax.set_title(title, fontsize=9)
        ax.set_xticks([]); ax.set_yticks([])

    expected_str = expected.get("global", {}).get("gridness", str(expected))
    fig.suptitle(
        f"{layout_name}  |  algo={result['algo']}  |  expected={expected_str}  |  sha={git_sha}",
        fontsize=10,
    )
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=110, bbox_inches="tight")
    plt.close(fig)


def _building_overlay(raster: np.ndarray, buildings: list[Building]) -> np.ndarray:
    H, W = raster.shape
    rgb = np.stack([1 - raster, 1 - raster, 1 - raster], axis=-1).astype(float)
    for b in buildings:
        for x, y in b.boundary_xy:
            xi, yi = int(x), int(y)
            if 0 <= yi < H and 0 <= xi < W:
                rgb[yi, xi] = [1.0, 0.2, 0.2]
    return np.clip(rgb, 0, 1)


def walls_overlay_rgba(walls: np.ndarray) -> np.ndarray:
    """RGBA image where wall cells are opaque black and non-wall cells are
    fully transparent. Meant to be drawn on top of a heatmap via a second
    ax.imshow call with interpolation='nearest' to keep the wall pixels
    crisp."""
    H, W = walls.shape
    rgba = np.zeros((H, W, 4), dtype=float)
    mask = walls.astype(bool)
    rgba[mask, 3] = 1.0
    return rgba
