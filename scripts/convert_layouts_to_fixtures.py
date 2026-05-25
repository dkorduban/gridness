"""Convert the original .npy + .json layouts under data/layouts/ into the
text-format fixtures under data/fixtures_layouts/ that the Java loader
understands. Buildings sidecar is left empty (the Java HeatmapDumper /
Python scoring both re-extract buildings from the raster directly).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from gridness.fixtures import save


def main() -> None:
    src = Path("data/layouts")
    dst = Path("data/fixtures_layouts")
    dst.mkdir(parents=True, exist_ok=True)
    for npy in sorted(src.glob("*.npy")):
        name = npy.stem
        meta_path = npy.with_suffix(".json")
        meta = json.loads(meta_path.read_text()) if meta_path.exists() else {}
        expected = meta.get("expected", {})
        if isinstance(expected, dict):
            expected = expected.get("global", {}).get("gridness", "regional")
        raster = np.load(npy).astype(bool)
        save(dst / name, name=name, raster=raster, expected=str(expected), buildings=[])
        H, W = raster.shape
        print(f"  {name:32s}  shape={H}x{W}  walls={int(raster.sum())}")


if __name__ == "__main__":
    main()
