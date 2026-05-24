"""Generate the text-format layout fixtures used by Java tests and benchmarks.

Outputs to data/fixtures/<name>.{txt,json}. Run from repo root:
    uv run python -m scripts.generate_fixtures
"""

from __future__ import annotations

from pathlib import Path
import random

import numpy as np

from gridness.fixtures import save


# ----- primitives -----

def hollow_rect(raster: np.ndarray, x: int, y: int, w: int, h: int) -> list[tuple[int, int]]:
    """Draw a hollow rect into raster. Returns the list of wall cells set."""
    cells: list[tuple[int, int]] = []
    H, W = raster.shape
    for xx in range(x, x + w):
        for yy in (y, y + h - 1):
            if 0 <= xx < W and 0 <= yy < H and not raster[yy, xx]:
                raster[yy, xx] = True
                cells.append((xx, yy))
    for yy in range(y, y + h):
        for xx in (x, x + w - 1):
            if 0 <= xx < W and 0 <= yy < H and not raster[yy, xx]:
                raster[yy, xx] = True
                cells.append((xx, yy))
    return cells


# ----- fixture builders -----

def fx_grid_uniform(H: int = 256, W: int = 256, base: int = 12, gap: int = 4, margin: int = 8) -> dict:
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    period = base + gap
    for y in range(margin, H - margin - base + 1, period):
        for x in range(margin, W - margin - base + 1, period):
            buildings.append(hollow_rect(raster, x, y, base, base))
    return dict(name="grid_uniform_256", raster=raster, expected="high", buildings=buildings)


def fx_scattered(H: int = 256, W: int = 256, n: int = 30, seed: int = 42) -> dict:
    rng = random.Random(seed)
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    placed = attempts = 0
    while placed < n and attempts < n * 50:
        attempts += 1
        w = 6 + rng.randrange(8)
        h = 6 + rng.randrange(8)
        x = 4 + rng.randrange(W - w - 8)
        y = 4 + rng.randrange(H - h - 8)
        ok = True
        for yy in range(max(0, y - 2), min(H, y + h + 2)):
            for xx in range(max(0, x - 2), min(W, x + w + 2)):
                if raster[yy, xx]:
                    ok = False
                    break
            if not ok:
                break
        if not ok:
            continue
        buildings.append(hollow_rect(raster, x, y, w, h))
        placed += 1
    return dict(name="scattered_256", raster=raster, expected="low", buildings=buildings)


def fx_mega_in_grid(H: int = 256, W: int = 256) -> dict:
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    # One 100x100 hollow rect + 8 small ones inside it.
    x0, y0, s = 70, 70, 100
    buildings.append(hollow_rect(raster, x0, y0, s, s))
    for dx in (10, 30, 50, 70):
        for dy in (10, 78):
            buildings.append(hollow_rect(raster, x0 + dx, y0 + dy, 12, 12))
    return dict(name="mega_in_grid_256", raster=raster, expected="medium", buildings=buildings)


def fx_longhouses_block(
    H: int, W: int, *, name: str,
    house_w: int, house_h: int, street: int, margin: int,
) -> dict:
    """Tiled block of longhouses (house_w x house_h), street-separated."""
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    period_x = house_w + street
    period_y = house_h + street
    for y in range(margin, H - margin - house_h + 1, period_y):
        for x in range(margin, W - margin - house_w + 1, period_x):
            buildings.append(hollow_rect(raster, x, y, house_w, house_h))
    return dict(name=name, raster=raster, expected="high", buildings=buildings)


def fx_two_districts(H: int = 256, W: int = 512) -> dict:
    """Left half: uniform grid. Right half: scattered."""
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    # Left: grid
    for y in range(8, H - 20, 16):
        for x in range(8, W // 2 - 8, 16):
            buildings.append(hollow_rect(raster, x, y, 12, 12))
    # Right: scattered
    rng = random.Random(7)
    placed = attempts = 0
    while placed < 25 and attempts < 25 * 50:
        attempts += 1
        w = 6 + rng.randrange(8)
        h = 6 + rng.randrange(8)
        x = W // 2 + 4 + rng.randrange(W // 2 - w - 8)
        y = 4 + rng.randrange(H - h - 8)
        ok = True
        for yy in range(max(0, y - 2), min(H, y + h + 2)):
            for xx in range(max(0, x - 2), min(W, x + w + 2)):
                if raster[yy, xx]:
                    ok = False
                    break
            if not ok:
                break
        if not ok:
            continue
        buildings.append(hollow_rect(raster, x, y, w, h))
        placed += 1
    return dict(name="two_districts_256x512", raster=raster, expected="mixed", buildings=buildings)


def fx_four_districts(H: int = 512, W: int = 512) -> dict:
    """Four quadrants with different layouts."""
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    # NW: uniform 12x12 grid.
    for y in range(8, H // 2 - 12, 16):
        for x in range(8, W // 2 - 12, 16):
            buildings.append(hollow_rect(raster, x, y, 12, 12))
    # NE: longhouses 22x60 stacked.
    margin = 8
    for y in range(margin, H // 2 - 60 + 1, 64):
        for x in range(W // 2 + margin, W - 22 - margin + 1, 26):
            buildings.append(hollow_rect(raster, x, y, 22, 60))
    # SW: rotated-looking scattered.
    rng = random.Random(11)
    placed = 0
    attempts = 0
    while placed < 20 and attempts < 20 * 50:
        attempts += 1
        w = 8 + rng.randrange(12)
        h = 8 + rng.randrange(12)
        x = 4 + rng.randrange(W // 2 - w - 8)
        y = H // 2 + 4 + rng.randrange(H // 2 - h - 8)
        ok = True
        for yy in range(max(0, y - 2), min(H, y + h + 2)):
            for xx in range(max(0, x - 2), min(W, x + w + 2)):
                if raster[yy, xx]:
                    ok = False
                    break
            if not ok:
                break
        if not ok:
            continue
        buildings.append(hollow_rect(raster, x, y, w, h))
        placed += 1
    # SE: 12x60 longhouses with wide streets.
    for y in range(H // 2 + margin, H - 60 + 1, 66):
        for x in range(W // 2 + margin, W - 12 - margin + 1, 18):
            buildings.append(hollow_rect(raster, x, y, 12, 60))
    return dict(name="four_districts_512", raster=raster, expected="mixed", buildings=buildings)


def fx_city_768(seed: int = 3) -> dict:
    """768x768 mixed city: grid core + longhouse fringe + scattered outskirts."""
    H = W = 768
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    # Core grid in [64,512]x[64,512]
    for y in range(64, 512 - 12, 16):
        for x in range(64, 512 - 12, 16):
            buildings.append(hollow_rect(raster, x, y, 12, 12))
    # Longhouse fringe along the right side
    for y in range(64, 700 - 80, 84):
        for x in range(540, 740 - 22, 26):
            buildings.append(hollow_rect(raster, x, y, 22, 80))
    # Longhouse fringe along the bottom
    for y in range(540, 740 - 60, 64):
        for x in range(64, 520 - 12, 18):
            buildings.append(hollow_rect(raster, x, y, 12, 60))
    # Scattered outskirts (sparse, top strip).
    rng = random.Random(seed)
    placed = 0
    attempts = 0
    while placed < 40 and attempts < 40 * 50:
        attempts += 1
        w = 6 + rng.randrange(10)
        h = 6 + rng.randrange(10)
        x = 8 + rng.randrange(W - w - 16)
        y = 8 + rng.randrange(56 - h)
        ok = True
        for yy in range(max(0, y - 2), min(H, y + h + 2)):
            for xx in range(max(0, x - 2), min(W, x + w + 2)):
                if raster[yy, xx]:
                    ok = False
                    break
            if not ok:
                break
        if not ok:
            continue
        buildings.append(hollow_rect(raster, x, y, w, h))
        placed += 1
    return dict(name="city_768", raster=raster, expected="mixed", buildings=buildings)


def fx_grid_768(H: int = 768, W: int = 768) -> dict:
    """Pure 12x12 grid for full-field benchmarks."""
    raster = np.zeros((H, W), dtype=bool)
    buildings: list[list[tuple[int, int]]] = []
    for y in range(8, H - 16, 16):
        for x in range(8, W - 16, 16):
            buildings.append(hollow_rect(raster, x, y, 12, 12))
    return dict(name="grid_768", raster=raster, expected="high", buildings=buildings)


# ----- entry point -----

def all_fixtures() -> list[dict]:
    return [
        fx_grid_uniform(),
        fx_scattered(),
        fx_mega_in_grid(),
        # Longhouse blocks: 12xN and 22xN at N=60 and N=100.
        fx_longhouses_block(160, 256, name="longhouses_12x60",
                            house_w=12, house_h=60, street=6, margin=8),
        fx_longhouses_block(160, 256, name="longhouses_22x60",
                            house_w=22, house_h=60, street=6, margin=8),
        fx_longhouses_block(240, 320, name="longhouses_12x100",
                            house_w=12, house_h=100, street=6, margin=8),
        fx_longhouses_block(240, 320, name="longhouses_22x100",
                            house_w=22, house_h=100, street=6, margin=8),
        fx_two_districts(),
        fx_four_districts(),
        fx_grid_768(),
        fx_city_768(),
    ]


def main() -> None:
    out = Path("data/fixtures")
    out.mkdir(parents=True, exist_ok=True)
    for fx in all_fixtures():
        name = fx.pop("name")
        save(out / name, name=name, **fx)
        H, W = fx["raster"].shape
        nb = len(fx["buildings"])
        nw = int(fx["raster"].sum())
        print(f"  {name:32s} shape={H}x{W:<4} buildings={nb:<4} walls={nw}")


if __name__ == "__main__":
    main()
