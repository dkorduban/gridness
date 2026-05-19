"""Side-by-side comparison of V1, V2, V3 across all layouts.

Produces:
  - data/comparison_grid.png : grid of heatmaps, one row per layout, one col per algo
  - data/comparison_table.csv : per-layout mean/median per algo
"""

from __future__ import annotations

import csv
import json
import subprocess
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

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


def main() -> None:
    sha = git_sha()
    out_dir = Path("data")
    out_dir.mkdir(exist_ok=True)
    layouts_dir = Path("data/layouts")
    layout_files = sorted(layouts_dir.glob("*.npy"))

    n_rows = len(layout_files)
    n_cols = 2 + len(ALGOS)  # input + buildings + 3 algos
    fig, axes = plt.subplots(n_rows, n_cols, figsize=(2.6 * n_cols, 2.2 * n_rows))

    table = []
    for ri, p in enumerate(layout_files):
        meta = json.loads(p.with_suffix(".json").read_text())
        raster = np.load(p)
        buildings = extract_buildings(raster)
        expected = meta["expected"].get("global", {}).get("gridness", "regional")
        axes[ri, 0].imshow(raster, cmap="gray_r", interpolation="nearest")
        axes[ri, 0].set_title(f"{meta['name']}\n(exp={expected}, n_b={len(buildings)})", fontsize=8)
        axes[ri, 0].set_xticks([]); axes[ri, 0].set_yticks([])

        # buildings overlay
        rgb = np.stack([1 - raster, 1 - raster, 1 - raster], axis=-1).astype(float)
        for b in buildings:
            for x, y in b.boundary_xy:
                xi, yi = int(x), int(y)
                if 0 <= yi < raster.shape[0] and 0 <= xi < raster.shape[1]:
                    rgb[yi, xi] = [1.0, 0.2, 0.2]
        axes[ri, 1].imshow(np.clip(rgb, 0, 1))
        axes[ri, 1].set_title("buildings", fontsize=8)
        axes[ri, 1].set_xticks([]); axes[ri, 1].set_yticks([])

        row = {"layout": meta["name"], "expected": expected, "n_buildings": len(buildings)}
        for ci, (name, fn, params, needs_raster) in enumerate(ALGOS):
            try:
                result = fn(buildings, raster.shape, params, raster=raster) if needs_raster else fn(buildings, raster.shape, params)
                gridness_full = upsample_to_full(result["gridness"], raster.shape, result["stride"])
                conf = upsample_to_full(result["confidence"], raster.shape, result["stride"])
                mask = conf > (conf.max() * 0.1 if conf.max() > 0 else 0)
                mean = float(gridness_full[mask].mean()) if mask.any() else 0.0
                row[f"{name}_mean"] = round(mean, 3)
                ax = axes[ri, 2 + ci]
                im = ax.imshow(gridness_full, cmap="viridis", vmin=0, vmax=1)
                ax.set_title(f"{name}\nmean={mean:.3f}", fontsize=8)
                ax.set_xticks([]); ax.set_yticks([])
            except Exception as e:
                row[f"{name}_mean"] = None
                axes[ri, 2 + ci].set_title(f"{name}\nFAILED", fontsize=8)
                axes[ri, 2 + ci].axis("off")
        table.append(row)

    fig.suptitle(f"Gridness comparison — V1 / V2 / V3 — sha={sha}", fontsize=12)
    fig.tight_layout(rect=[0, 0, 1, 0.99])
    fig.savefig(out_dir / "comparison_grid.png", dpi=110, bbox_inches="tight")
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
