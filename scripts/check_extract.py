"""Render each layout with its extracted buildings overlaid."""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

from gridness.extract import extract_buildings


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--layouts-dir", type=Path, default=Path("data/layouts"))
    parser.add_argument("--out", type=Path, default=Path("data/extract_check.png"))
    args = parser.parse_args()

    npys = sorted(args.layouts_dir.glob("*.npy"))
    n = len(npys)
    cols = 4
    rows = (n + cols - 1) // cols
    fig, axes = plt.subplots(rows, cols, figsize=(cols * 3, rows * 3))
    for i, p in enumerate(npys):
        raster = np.load(p)
        ax = axes.flat[i]
        bs = extract_buildings(raster)
        ax.imshow(raster, cmap="gray_r", interpolation="nearest")
        for b in bs:
            x_min, y_min, x_max, y_max = b.bbox
            ax.add_patch(plt.Rectangle((x_min - 0.5, y_min - 0.5),
                                       x_max - x_min + 1, y_max - y_min + 1,
                                       fill=False, edgecolor="red", linewidth=0.6))
            ax.plot(b.centroid[0], b.centroid[1], "b.", markersize=2)
        ax.set_title(f"{p.stem}\n n={len(bs)}", fontsize=8)
        ax.set_xticks([]); ax.set_yticks([])
    for j in range(n, rows * cols):
        axes.flat[j].axis("off")
    fig.tight_layout()
    fig.savefig(args.out, dpi=120, bbox_inches="tight")
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()
