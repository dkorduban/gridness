"""Side-by-side comparison: walls | Python v3 heatmap | Java heatmap, per
fixture. Same shared colormap, same [0, 1] color range, so the two
implementations can be visually A/B'd.

Requires:
- Python prototype (gridness.scoring.v3_hough)
- Java heatmap text files in data/fixtures_heatmaps_java/<name>.heatmap.txt
  (produced by `gradle :dumpHeatmaps --args="<outdir> <fixture>..."`).

Output: data/comparison_grid.png
"""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import LinearSegmentedColormap

from gridness.extract import extract_buildings
from gridness.fixtures import load
from gridness.scoring.v3_hough import V3Params, score_map_v3


# Same 5 stops as com.gridness.viz.GridnessViewer.colormap().
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


# Match the Java defaults: radius=30, sampleStride=8, minBuildings=2.
PARAMS = V3Params(radius=30.0, stride=8, min_buildings=2)
PARAM_OVERRIDES = {
    "longhouses_22x60": replace(PARAMS, radius=60.0),
}


FIXTURES = [
    "grid_uniform_256",
    "scattered_256",
    "mega_in_grid_256",
    "longhouses_12x60",
    "longhouses_22x60",
    "longhouses_12x100",
    "longhouses_22x100",
    "two_districts_256x512",
    "four_districts_512",
    "grid_768",
    "city_768",
]


def load_java_heatmap(path: Path) -> tuple[np.ndarray, dict]:
    meta = {}
    with open(path) as f:
        for line in f:
            if line.startswith("#"):
                for tok in line.lstrip("#").strip().split():
                    if "=" in tok:
                        k, v = tok.split("=", 1)
                        meta[k] = v
                continue
            # First non-comment is "ny nx"
            ny, nx = (int(x) for x in line.split())
            break
        rows = []
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append([float(x) for x in line.split()])
    arr = np.array(rows)
    return arr, meta


def score_python(fx, override_params=None):
    raster = fx.raster
    H, W = raster.shape
    buildings = extract_buildings(raster.astype(bool), min_area=4)
    params = override_params or PARAMS
    result = score_map_v3(buildings, (H, W), params=params, raster=raster)
    return result["gridness"], params


def main() -> None:
    fx_dir = Path("data/fixtures")
    java_dir = Path("data/fixtures_heatmaps_java")
    if not java_dir.exists():
        print(f"missing {java_dir}; run gradle :dumpHeatmaps first.")
        return

    n = len(FIXTURES)
    fig, axes = plt.subplots(n, 3, figsize=(15, 4.0 * n))
    if n == 1:
        axes = axes[np.newaxis, :]

    for i, name in enumerate(FIXTURES):
        fx = load(fx_dir / name)
        H, W = fx.raster.shape
        py_heat, py_params = score_python(fx, PARAM_OVERRIDES.get(name))
        py_mean = float(py_heat.mean())

        java_path = java_dir / f"{name}.heatmap.txt"
        if not java_path.exists():
            print(f"  missing Java dump for {name}; skipping that column")
            java_heat = None
            java_mean = float("nan")
        else:
            java_heat, _ = load_java_heatmap(java_path)
            java_mean = float(java_heat.mean())

        ax_walls = axes[i, 0]
        ax_walls.imshow(fx.raster, cmap="gray_r", interpolation="nearest")
        ax_walls.set_title(f"{name}\n{W}×{H}  walls={int(fx.raster.sum())}", fontsize=10)
        ax_walls.set_xticks([]); ax_walls.set_yticks([])

        ax_py = axes[i, 1]
        ax_py.imshow(py_heat, cmap=SHARED_CMAP, vmin=0.0, vmax=1.0,
                     interpolation="bilinear", extent=(0, W, H, 0))
        ax_py.set_title(f"Python v3   R={py_params.radius:.0f}   mean={py_mean:.2f}",
                        fontsize=10)
        ax_py.set_xticks([]); ax_py.set_yticks([])

        ax_jv = axes[i, 2]
        if java_heat is not None:
            ax_jv.imshow(java_heat, cmap=SHARED_CMAP, vmin=0.0, vmax=1.0,
                         interpolation="bilinear", extent=(0, W, H, 0))
            ax_jv.set_title(f"Java        R={py_params.radius:.0f}   mean={java_mean:.2f}",
                            fontsize=10)
        else:
            ax_jv.set_title("Java (no dump)")
        ax_jv.set_xticks([]); ax_jv.set_yticks([])

        print(f"  {name:30s}  py={py_mean:.3f}  java={java_mean:.3f}  Δ={java_mean - py_mean:+.3f}")

    fig.subplots_adjust(left=0.02, right=0.96, top=0.98, bottom=0.02, hspace=0.30, wspace=0.05)
    cbar_ax = fig.add_axes([0.97, 0.06, 0.012, 0.88])
    fig.colorbar(plt.cm.ScalarMappable(cmap=SHARED_CMAP), cax=cbar_ax, label="gridness")

    out_path = Path("data/comparison_grid.png")
    fig.savefig(out_path, dpi=110, bbox_inches="tight")
    print(f"\nwrote {out_path}")


if __name__ == "__main__":
    main()
