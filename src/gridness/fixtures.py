"""Text-format layout fixtures shared between Python and Java.

Two-file format per fixture:
- <name>.txt: ASCII raster. '#' = wall, '.' = empty. Lines starting with ';'
  are header/comments (key=value pairs after the ';' get parsed into meta).
  Width = max non-comment line length; short rows are padded with '.'.
- <name>.buildings.txt: one line per logical building, each line is
  space-separated `x,y` integer pairs naming the wall cells of that building.

The buildings file is what the build/dismantle benchmark uses to add or
remove buildings as units (one "tick" = a few cells from each in-progress
building). It's the generator's ground truth — not extracted from the raster.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np


@dataclass(frozen=True)
class Fixture:
    name: str
    raster: np.ndarray  # bool[H, W]
    expected: str
    buildings: list[list[tuple[int, int]]]


def save(path_no_ext: Path, name: str, raster: np.ndarray, expected: str,
         buildings: list[list[tuple[int, int]]]) -> None:
    raster = np.asarray(raster, dtype=bool)
    H, W = raster.shape
    path_no_ext.parent.mkdir(parents=True, exist_ok=True)
    with open(path_no_ext.with_suffix(".txt"), "w") as f:
        f.write(f"; name={name} shape={H}x{W} expected={expected}\n")
        for y in range(H):
            f.write("".join("#" if raster[y, x] else "." for x in range(W)))
            f.write("\n")
    with open(path_no_ext.parent / f"{path_no_ext.name}.buildings.txt", "w") as f:
        for cells in buildings:
            f.write(" ".join(f"{int(x)},{int(y)}" for x, y in cells))
            f.write("\n")


def load(path_no_ext: Path) -> Fixture:
    raster, meta = load_raster_with_meta(path_no_ext.with_suffix(".txt"))
    bpath = path_no_ext.parent / f"{path_no_ext.name}.buildings.txt"
    buildings: list[list[tuple[int, int]]] = []
    if bpath.exists():
        for line in bpath.read_text().splitlines():
            line = line.strip()
            if not line:
                continue
            cells = []
            for token in line.split():
                x, y = token.split(",")
                cells.append((int(x), int(y)))
            buildings.append(cells)
    return Fixture(
        name=meta.get("name", path_no_ext.stem),
        raster=raster,
        expected=meta.get("expected", ""),
        buildings=buildings,
    )


def load_raster_with_meta(txt_path: Path) -> tuple[np.ndarray, dict[str, str]]:
    rows: list[str] = []
    meta: dict[str, str] = {}
    for raw in txt_path.read_text().splitlines():
        if raw.startswith(";"):
            for tok in raw.lstrip(";").strip().split():
                if "=" in tok:
                    k, v = tok.split("=", 1)
                    meta[k] = v
            continue
        if raw.strip() == "":
            continue
        rows.append(raw)
    W = max(len(r) for r in rows)
    H = len(rows)
    out = np.zeros((H, W), dtype=bool)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                out[y, x] = True
    return out, meta


def load_raster(txt_path: Path) -> np.ndarray:
    return load_raster_with_meta(txt_path)[0]
