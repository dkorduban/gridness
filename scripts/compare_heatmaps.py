"""Render walls + Python-v3 gridness heatmap for every fixture, side by side,
using the same 5-stop colormap as the Java Swing viewer (so the two are
visually comparable).

Output: data/fixtures_heatmaps.png (and one per-fixture PNG under
data/fixtures_heatmaps/).
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


# Same 5 stops as com.gridness.viz.GridnessViewer.colormap()
SHARED_CMAP = LinearSegmentedColormap.from_list(
    "gridness_shared",
    [
        (0.00, "#0a1a40"),  # dark navy
        (0.25, "#00b4d8"),  # cyan
        (0.50, "#90e000"),  # green
        (0.75, "#ffd400"),  # yellow
        (1.00, "#c81010"),  # red
    ],
)


# Match the Java defaults so the comparison is apples-to-apples:
# Java: radius=30, sampleStride=8, minBuildingsInWindow=2.
PARAMS = V3Params(radius=30.0, stride=8, min_buildings=2)

# Longhouses with sparse vertical packing need wider R or they degenerate
# (same finding as the Java tests). Override per-fixture if needed.
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


def score_fixture(fx_name: str, fx_dir: Path):
    fx = load(fx_dir / fx_name)
    raster = fx.raster
    H, W = raster.shape
    buildings = extract_buildings(raster.astype(bool), min_area=4)
    params = PARAM_OVERRIDES.get(fx_name, PARAMS)
    result = score_map_v3(buildings, (H, W), params=params, raster=raster)
    return fx, result, params


def main() -> None:
    fx_dir = Path("data/fixtures")
    out_dir = Path("data/fixtures_heatmaps")
    out_dir.mkdir(parents=True, exist_ok=True)

    # Compose as a 2-fixtures-per-row grid: 4 columns
    # (fixture1_walls, fixture1_heatmap, fixture2_walls, fixture2_heatmap).
    n = len(FIXTURES)
    cols_per_fixture = 2
    fixtures_per_row = 2
    n_rows = (n + fixtures_per_row - 1) // fixtures_per_row
    n_cols = cols_per_fixture * fixtures_per_row
    fig, axes = plt.subplots(n_rows, n_cols, figsize=(16, 3.4 * n_rows))
    if n_rows == 1:
        axes = axes[np.newaxis, :]

    for i, name in enumerate(FIXTURES):
        try:
            fx, result, params = score_fixture(name, fx_dir)
        except FileNotFoundError:
            print(f"  skipping {name} (fixture not found)")
            continue
        heat = result["gridness"]
        H, W = fx.raster.shape
        mean_score = float(heat.mean())

        r = i // fixtures_per_row
        c_base = (i % fixtures_per_row) * cols_per_fixture
        ax_walls = axes[r, c_base]
        ax_heat = axes[r, c_base + 1]

        ax_walls.imshow(fx.raster, cmap="gray_r", interpolation="nearest")
        ax_walls.set_title(f"{name}\n{W}×{H} walls={int(fx.raster.sum())}", fontsize=9)
        ax_walls.set_xticks([]); ax_walls.set_yticks([])

        ax_heat.imshow(heat, cmap=SHARED_CMAP, vmin=0.0, vmax=1.0,
                       interpolation="bilinear",
                       extent=(0, W, H, 0))
        ax_heat.set_title(f"v3  R={params.radius:.0f}  mean={mean_score:.2f}", fontsize=9)
        ax_heat.set_xticks([]); ax_heat.set_yticks([])

        # Save individual fixture image too.
        single = plt.figure(figsize=(11, 5))
        ax1 = single.add_subplot(1, 2, 1)
        ax1.imshow(fx.raster, cmap="gray_r", interpolation="nearest")
        ax1.set_title(f"{name}  walls")
        ax1.set_xticks([]); ax1.set_yticks([])
        ax2 = single.add_subplot(1, 2, 2)
        im = ax2.imshow(heat, cmap=SHARED_CMAP, vmin=0.0, vmax=1.0,
                        interpolation="bilinear",
                        extent=(0, W, H, 0))
        ax2.set_title(f"v3 heatmap  R={params.radius:.0f} mean={mean_score:.2f}")
        ax2.set_xticks([]); ax2.set_yticks([])
        single.colorbar(im, ax=ax2, fraction=0.046, pad=0.04)
        single.tight_layout()
        single.savefig(out_dir / f"{name}.png", dpi=110, bbox_inches="tight")
        plt.close(single)

        print(f"  {name:30s}  shape={H}×{W}  mean_gridness={mean_score:.3f}")

    # Hide unused subplots if n is odd.
    for j in range(n, n_rows * fixtures_per_row):
        r = j // fixtures_per_row
        c_base = (j % fixtures_per_row) * cols_per_fixture
        axes[r, c_base].axis("off")
        axes[r, c_base + 1].axis("off")

    fig.subplots_adjust(left=0.02, right=0.96, top=0.97, bottom=0.02, hspace=0.35, wspace=0.1)
    cbar_ax = fig.add_axes([0.97, 0.05, 0.012, 0.9])
    fig.colorbar(plt.cm.ScalarMappable(cmap=SHARED_CMAP), cax=cbar_ax, label="gridness")

    composite_path = Path("data/fixtures_heatmaps.png")
    fig.savefig(composite_path, dpi=110, bbox_inches="tight")
    print(f"\nwrote {composite_path}")
    print(f"per-fixture PNGs in {out_dir}/")


if __name__ == "__main__":
    main()
