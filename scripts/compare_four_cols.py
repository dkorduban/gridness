"""5-column fused-overlay comparison per layout:
  Python v3 | Java tile=32 | tile=64 hpw=38 | tile=128 hpw=45 | tile=256

Each panel shows the gridness heatmap with walls overlaid in black, so
structure and score are visible in one picture.

tile=64/128 hpw values were tuned by sweep_java_params.py to suppress false
positives on scattered/organic layouts. tile=32 and tile=256 use their
respective shipped defaults / matching-config values.

Output: data/comparison_grid_4cols.png
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import LinearSegmentedColormap

from gridness.fixtures import load
from gridness.viz import walls_overlay_rgba


SHARED_CMAP = LinearSegmentedColormap.from_list(
    "gridness_shared",
    [(0.00, "#0a1a40"), (0.25, "#00b4d8"), (0.50, "#90e000"),
     (0.75, "#ffd400"), (1.00, "#c81010")],
)

LAYOUTS = [
    "tiny_grid", "grid_uniform", "dense_grid",
    "grid_streets_w1", "grid_streets_w2", "grid_streets_w3",
    "grid_streets_w4", "grid_streets_w5", "grid_streets_w6",
    "grid_streets_w7", "grid_streets_w8", "grid_streets_w9",
    "grid_streets_mixed", "grid_mixed_sizes", "grid_variable_rows",
    "grid_with_holes", "grid_rotated_30", "grid_sheared",
    "grid_rounded_corners", "grid_nonrect_buildings", "row_only",
    "hexagonal", "rect_scattered", "rect_rotated_scattered",
    "organic_walks", "mixed_regions", "two_districts",
]

JAVA_CONFIGS = [
    ("tile=32",        Path("data/layouts_heatmaps_java")),
    ("tile=64 hpw=38", Path("data/layouts_heatmaps_java_sweep/tile64_hpw38")),
    ("tile=128 hpw=45", Path("data/layouts_heatmaps_java_sweep/tile128_hpw45")),
    ("tile=256",       Path("data/layouts_heatmaps_java_matching")),
]


def load_heatmap(path: Path) -> np.ndarray:
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


def main() -> None:
    fx_dir = Path("data/fixtures_layouts")
    py_dir = Path("data/layouts_heatmaps_python")

    n_rows = len(LAYOUTS)
    n_cols = 1 + len(JAVA_CONFIGS)
    fig, axes = plt.subplots(n_rows, n_cols, figsize=(2.3 * n_cols, 2.4 * n_rows))

    summary: dict[str, list[float]] = {label: [] for label, _ in JAVA_CONFIGS}

    def draw_fused(ax, heat, walls_rgba, title, H, W):
        ax.imshow(heat, cmap=SHARED_CMAP, vmin=0, vmax=1,
                  interpolation="bilinear", extent=(0, W, H, 0))
        ax.imshow(walls_rgba, interpolation="nearest", extent=(0, W, H, 0))
        ax.set_title(title, fontsize=9)
        ax.set_xticks([]); ax.set_yticks([])

    for i, name in enumerate(LAYOUTS):
        fx = load(fx_dir / name)
        H, W = fx.raster.shape
        walls = fx.raster.astype(bool)
        walls_rgba = walls_overlay_rgba(walls)
        py = load_heatmap(py_dir / f"{name}.heatmap.txt")

        draw_fused(axes[i, 0], py, walls_rgba, f"{name}\nPython m={py.mean():.2f}", H, W)

        line_parts = [f"{name:28s}  py={py.mean():.3f}"]
        for c, (label, dir_) in enumerate(JAVA_CONFIGS):
            jv = load_heatmap(dir_ / f"{name}.heatmap.txt")
            l1 = float(np.mean(np.abs(jv - py)))
            draw_fused(axes[i, 1 + c], jv, walls_rgba,
                       f"{label}  m={jv.mean():.2f}  L1={l1:.2f}", H, W)
            summary[label].append(l1)
            line_parts.append(f"{label}={jv.mean():.3f}(L1={l1:.3f})")
        print("  " + "  ".join(line_parts))

    fig.subplots_adjust(left=0.02, right=0.96, top=0.98, bottom=0.02,
                        hspace=0.35, wspace=0.04)
    cbar_ax = fig.add_axes([0.97, 0.06, 0.008, 0.88])
    fig.colorbar(plt.cm.ScalarMappable(cmap=SHARED_CMAP), cax=cbar_ax, label="gridness")

    out = Path("data/comparison_grid_4cols.png")
    fig.savefig(out, dpi=110, bbox_inches="tight")
    print(f"\nwrote {out}")
    print("\nmean L1 vs Python (over 27 fixtures):")
    for label, ls in summary.items():
        print(f"  {label}: {np.mean(ls):.4f}")


if __name__ == "__main__":
    main()
