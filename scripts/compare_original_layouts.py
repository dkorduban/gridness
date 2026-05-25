"""Side-by-side comparison on the ORIGINAL 27 layouts (data/layouts/*.npy):
walls | Python v3 heatmap | Java heatmap, per layout. Same shared colormap.

The original comparison_grid.png (commit 13360bf) used these same layouts
with V1/V2/V3 — this script replaces it with a Python-v3 vs Java side-by-side
for the same input set.

Prereqs:
1. uv run python -m scripts.convert_layouts_to_fixtures  (one-shot)
2. gradle :dumpHeatmaps --args="--dir data/fixtures_layouts data/layouts_heatmaps_java <names...>"

Output: data/comparison_grid.png
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import LinearSegmentedColormap

from gridness.extract import extract_buildings
from gridness.fixtures import load
from gridness.scoring.v3_hough import V3Params, score_map_v3


SHARED_CMAP = LinearSegmentedColormap.from_list(
    "gridness_shared",
    [
        (0.00, "#0a1a40"),
        (0.25, "#00b4d8"),
        (0.50, "#90e000"),
        (0.75, "#ffd400"),
        (1.00, "#c81010"),
    ],
)


# Match Java defaults: radius=30, sampleStride=8, minBuildings=2.
PARAMS = V3Params(radius=30.0, stride=8, min_buildings=2)


LAYOUTS = [
    "tiny_grid",
    "grid_uniform",
    "dense_grid",
    "grid_streets_w1", "grid_streets_w2", "grid_streets_w3",
    "grid_streets_w4", "grid_streets_w5", "grid_streets_w6",
    "grid_streets_w7", "grid_streets_w8", "grid_streets_w9",
    "grid_streets_mixed",
    "grid_mixed_sizes",
    "grid_variable_rows",
    "grid_with_holes",
    "grid_rotated_30",
    "grid_sheared",
    "grid_rounded_corners",
    "grid_nonrect_buildings",
    "row_only",
    "hexagonal",
    "rect_scattered",
    "rect_rotated_scattered",
    "organic_walks",
    "mixed_regions",
    "two_districts",
]


def load_java_heatmap(path: Path) -> np.ndarray:
    with open(path) as f:
        for line in f:
            if line.startswith("#"):
                continue
            ny, nx = (int(x) for x in line.split())
            break
        rows = []
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append([float(x) for x in line.split()])
    return np.array(rows)


def score_python(fx):
    raster = fx.raster
    H, W = raster.shape
    buildings = extract_buildings(raster.astype(bool), min_area=4)
    result = score_map_v3(buildings, (H, W), params=PARAMS, raster=raster)
    return result["gridness"]


def main() -> None:
    fx_dir = Path("data/fixtures_layouts")
    java_dir = Path("data/layouts_heatmaps_java")

    # 2 layouts per row, 3 columns each (walls | py | java) = 6 cols.
    layouts_per_row = 2
    n = len(LAYOUTS)
    n_rows = (n + layouts_per_row - 1) // layouts_per_row
    n_cols = 3 * layouts_per_row
    fig, axes = plt.subplots(n_rows, n_cols, figsize=(15, 2.7 * n_rows))
    if n_rows == 1:
        axes = axes[np.newaxis, :]

    for i, name in enumerate(LAYOUTS):
        fx_path = fx_dir / name
        fx = load(fx_path)
        H, W = fx.raster.shape

        py_heat = score_python(fx)
        py_mean = float(py_heat.mean())

        java_path = java_dir / f"{name}.heatmap.txt"
        java_heat = load_java_heatmap(java_path) if java_path.exists() else None
        java_mean = float(java_heat.mean()) if java_heat is not None else float("nan")

        r = i // layouts_per_row
        c_base = (i % layouts_per_row) * 3

        ax_w = axes[r, c_base]
        ax_w.imshow(fx.raster, cmap="gray_r", interpolation="nearest")
        ax_w.set_title(f"{name}", fontsize=9)
        ax_w.set_xticks([]); ax_w.set_yticks([])

        ax_py = axes[r, c_base + 1]
        ax_py.imshow(py_heat, cmap=SHARED_CMAP, vmin=0.0, vmax=1.0,
                     interpolation="bilinear", extent=(0, W, H, 0))
        ax_py.set_title(f"py mean={py_mean:.2f}", fontsize=9)
        ax_py.set_xticks([]); ax_py.set_yticks([])

        ax_jv = axes[r, c_base + 2]
        if java_heat is not None:
            ax_jv.imshow(java_heat, cmap=SHARED_CMAP, vmin=0.0, vmax=1.0,
                         interpolation="bilinear", extent=(0, W, H, 0))
            ax_jv.set_title(f"java mean={java_mean:.2f}", fontsize=9)
        else:
            ax_jv.set_title("java (no dump)")
        ax_jv.set_xticks([]); ax_jv.set_yticks([])

        print(f"  {name:28s}  py={py_mean:.3f}  java={java_mean:.3f}  Δ={java_mean - py_mean:+.3f}")

    # Hide unused trailing subplots if n is odd.
    for j in range(n, n_rows * layouts_per_row):
        r = j // layouts_per_row
        c_base = (j % layouts_per_row) * 3
        for c in range(3):
            axes[r, c_base + c].axis("off")

    fig.subplots_adjust(left=0.02, right=0.96, top=0.97, bottom=0.02, hspace=0.40, wspace=0.05)
    cbar_ax = fig.add_axes([0.97, 0.06, 0.012, 0.88])
    fig.colorbar(plt.cm.ScalarMappable(cmap=SHARED_CMAP), cax=cbar_ax, label="gridness")

    out_path = Path("data/comparison_grid.png")
    fig.savefig(out_path, dpi=110, bbox_inches="tight")
    print(f"\nwrote {out_path}")


if __name__ == "__main__":
    main()
