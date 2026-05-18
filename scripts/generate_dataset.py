"""Generate the synthetic dataset and save .npy + .json metadata + a contact-sheet PNG."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

from gridness.generate import ALL_LAYOUTS


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("data/layouts"))
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--contact-sheet", type=Path, default=Path("data/contact_sheet.png"))
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    args.contact_sheet.parent.mkdir(parents=True, exist_ok=True)

    layouts = [fn(seed=args.seed) for fn in ALL_LAYOUTS]
    for lo in layouts:
        np.save(args.out / f"{lo.name}.npy", lo.raster)
        with open(args.out / f"{lo.name}.json", "w") as f:
            json.dump({"name": lo.name, "expected": lo.expected, "notes": lo.notes,
                       "shape": list(lo.raster.shape)}, f, indent=2)
        print(f"  {lo.name}: shape={lo.raster.shape}, walls={int(lo.raster.sum())}")

    # contact sheet
    n = len(layouts)
    cols = 4
    rows = (n + cols - 1) // cols
    fig, axes = plt.subplots(rows, cols, figsize=(cols * 3, rows * 3))
    for i, lo in enumerate(layouts):
        ax = axes.flat[i]
        ax.imshow(lo.raster, cmap="gray_r", interpolation="nearest")
        expected = lo.expected.get("global", {}).get("gridness", "regional")
        ax.set_title(f"{lo.name}\n(exp={expected})", fontsize=8)
        ax.set_xticks([]); ax.set_yticks([])
    for j in range(n, rows * cols):
        axes.flat[j].axis("off")
    fig.tight_layout()
    fig.savefig(args.contact_sheet, dpi=120, bbox_inches="tight")
    print(f"contact sheet -> {args.contact_sheet}")


if __name__ == "__main__":
    main()
