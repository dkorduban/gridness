"""Side-by-side comparison of V1, V2, V3 across all layouts.

Produces:
  - data/comparison_grid.png : grid of heatmaps, one row per layout, one col per algo
  - data/comparison_table.csv : per-layout mean/median per algo
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from scipy.ndimage import binary_dilation

from gridness.eval import git_sha
from gridness.extract import extract_buildings
from gridness.scoring.common import V1Params
from gridness.scoring.v1_axis import score_map_v1
from gridness.scoring.v2_affine import V2Params, score_map_v2
from gridness.scoring.v3_hough import V3Params, score_map_v3
from gridness.viz import upsample_to_full


ALGOS = [
    ("V1 axis", score_map_v1, V1Params(), False),
    ("V2 affine", score_map_v2, V2Params(), False),
    ("V3 Hough", score_map_v3, V3Params(), True),
]

# Display upscale: each raster pixel becomes UPSCALE x UPSCALE display pixels.
# This is the key fix for "walls getting lost" at the small per-panel size.
UPSCALE = 3
PANEL_W = 3.2
PANEL_H = 3.2
DPI = 180
# Thicken walls in the raster + boundary panels so single-pixel features survive.
WALL_DILATE = 1


def upscale_nearest(img: np.ndarray, k: int) -> np.ndarray:
    """Block-replicate a 2D array by integer factor k. Preserves sharp edges."""
    return np.repeat(np.repeat(img, k, axis=0), k, axis=1)


def render_input(raster: np.ndarray) -> np.ndarray:
    walls = raster.astype(bool)
    if WALL_DILATE > 0:
        walls = binary_dilation(walls, iterations=WALL_DILATE)
    return upscale_nearest(walls.astype(np.uint8), UPSCALE)


def render_buildings(raster: np.ndarray, buildings) -> np.ndarray:
    H, W = raster.shape
    walls = raster.astype(bool)
    if WALL_DILATE > 0:
        walls = binary_dilation(walls, iterations=WALL_DILATE)
    boundary = np.zeros((H, W), dtype=bool)
    for b in buildings:
        ys = b.boundary_xy[:, 1].astype(int)
        xs = b.boundary_xy[:, 0].astype(int)
        m = (ys >= 0) & (ys < H) & (xs >= 0) & (xs < W)
        boundary[ys[m], xs[m]] = True
    if WALL_DILATE > 0:
        boundary = binary_dilation(boundary, iterations=WALL_DILATE)
    rgb = np.ones((H, W, 3), dtype=float)
    rgb[walls] = [0.1, 0.1, 0.1]
    rgb[boundary] = [0.95, 0.25, 0.25]
    return upscale_nearest(rgb, UPSCALE)


def main() -> None:
    sha = git_sha()
    out_dir = Path("data")
    out_dir.mkdir(exist_ok=True)
    layouts_dir = Path("data/layouts")
    layout_files = sorted(layouts_dir.glob("*.npy"))

    n_rows = len(layout_files)
    n_cols = 2 + len(ALGOS)
    fig, axes = plt.subplots(n_rows, n_cols,
                             figsize=(PANEL_W * n_cols, PANEL_H * n_rows))

    table = []
    for ri, p in enumerate(layout_files):
        meta = json.loads(p.with_suffix(".json").read_text())
        raster = np.load(p)
        buildings = extract_buildings(raster)
        expected = meta["expected"].get("global", {}).get("gridness", "regional")

        axes[ri, 0].imshow(render_input(raster), cmap="gray_r", interpolation="nearest")
        axes[ri, 0].set_title(f"{meta['name']}\n(exp={expected}, n_b={len(buildings)})", fontsize=10)
        axes[ri, 0].set_xticks([]); axes[ri, 0].set_yticks([])

        axes[ri, 1].imshow(render_buildings(raster, buildings), interpolation="nearest")
        axes[ri, 1].set_title("extracted buildings", fontsize=10)
        axes[ri, 1].set_xticks([]); axes[ri, 1].set_yticks([])

        row = {"layout": meta["name"], "expected": expected, "n_buildings": len(buildings)}
        for ci, (name, fn, params, needs_raster) in enumerate(ALGOS):
            try:
                result = (fn(buildings, raster.shape, params, raster=raster)
                          if needs_raster else fn(buildings, raster.shape, params))
                gridness_full = upsample_to_full(result["gridness"], raster.shape, result["stride"])
                conf = upsample_to_full(result["confidence"], raster.shape, result["stride"])
                mask = conf > (conf.max() * 0.1 if conf.max() > 0 else 0)
                mean = float(gridness_full[mask].mean()) if mask.any() else 0.0
                row[f"{name}_mean"] = round(mean, 3)
                ax = axes[ri, 2 + ci]
                # Heatmaps stay at native resolution (block-replicated already); bilinear smooths.
                ax.imshow(upscale_nearest(gridness_full, UPSCALE),
                          cmap="viridis", vmin=0, vmax=1, interpolation="bilinear")
                ax.set_title(f"{name}\nmean={mean:.3f}", fontsize=10)
                ax.set_xticks([]); ax.set_yticks([])
            except Exception:
                row[f"{name}_mean"] = None
                axes[ri, 2 + ci].set_title(f"{name}\nFAILED", fontsize=10)
                axes[ri, 2 + ci].axis("off")
        table.append(row)

    fig.tight_layout()
    # tall figures: place title above all axes with figure.text rather than suptitle
    top = 1.0 - (0.5 / (PANEL_H * n_rows))  # ~one half-panel inch above the grid
    fig.text(0.5, top, f"Gridness comparison — V1 / V2 / V3 — sha={sha}",
             ha="center", va="bottom", fontsize=16)
    fig.subplots_adjust(top=1.0 - 1.0 / (PANEL_H * n_rows))
    fig.savefig(out_dir / "comparison_grid.png", dpi=DPI, bbox_inches="tight")
    plt.close(fig)

    csv_path = out_dir / "comparison_table.csv"
    with open(csv_path, "w", newline="") as f:
        cols = ["layout", "expected", "n_buildings", "V1 axis_mean", "V2 affine_mean", "V3 Hough_mean"]
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for row in table:
            w.writerow(row)
    print(f"-> {out_dir / 'comparison_grid.png'}")
    print(f"-> {csv_path}")


if __name__ == "__main__":
    main()
