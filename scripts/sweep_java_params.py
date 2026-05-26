"""Sweep Java params, score each combo against Python v3 heatmaps with
several metrics, print a ranked table.

For each (minBuildingsInWindow, houghMinPeakWeight) combo:
1. Run gradle :dumpHeatmaps with --param overrides for all 27 layouts.
2. Load both heatmaps; compute per-pixel L1, RMS (L2), pseudo-Huber(δ=0.1),
   max abs diff, signed mean diff, and Wasserstein distance on value
   histograms (distribution-shape only).
3. Aggregate metrics across all layouts (mean).

Usage:
    uv run python -m scripts.sweep_java_params
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

# Mirror java/env.sh — put project JDK + Gradle on PATH for subprocesses.
_HOME = os.environ["HOME"]
_JAVA_HOME = f"{_HOME}/jdk/jdk-21.0.4+7"
_GRADLE = f"{_HOME}/tools/gradle-8.10.2/bin"
os.environ["JAVA_HOME"] = _JAVA_HOME
os.environ["PATH"] = f"{_JAVA_HOME}/bin:{_GRADLE}:{os.environ['PATH']}"

import numpy as np

from gridness.extract import extract_buildings
from gridness.fixtures import load
from gridness.scoring.v3_hough import V3Params, score_map_v3


PY_PARAMS = V3Params(radius=30.0, stride=8, min_buildings=2)


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


FIXTURE_DIR = Path("data/fixtures_layouts")
PY_CACHE = Path("data/layouts_heatmaps_python")
JAVA_TMP = Path("data/layouts_heatmaps_java_sweep")


def python_heatmap(name: str) -> np.ndarray:
    cache = PY_CACHE / f"{name}.heatmap.txt"
    if cache.exists():
        return _load_text_heatmap(cache)
    fx = load(FIXTURE_DIR / name)
    raster = fx.raster
    H, W = raster.shape
    buildings = extract_buildings(raster.astype(bool), min_area=4)
    result = score_map_v3(buildings, (H, W), params=PY_PARAMS, raster=raster)
    heat = result["gridness"]
    PY_CACHE.mkdir(parents=True, exist_ok=True)
    with open(cache, "w") as f:
        f.write(f"# name={name} field={H}x{W} stride={PY_PARAMS.stride}\n")
        f.write(f"{heat.shape[0]} {heat.shape[1]}\n")
        for row in heat:
            f.write(" ".join(f"{v:.5f}" for v in row) + "\n")
    return heat


def _load_text_heatmap(path: Path) -> np.ndarray:
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


def run_java_dump(out_dir: Path, params: dict) -> None:
    if out_dir.exists():
        shutil.rmtree(out_dir)
    args_parts = ["--dir", str(FIXTURE_DIR.resolve())]
    for k, v in params.items():
        args_parts += ["--param", f"{k}={v}"]
    args_parts.append(str(out_dir.resolve()))
    args_parts.extend(LAYOUTS)
    cmd = ["gradle", "-q", ":dumpHeatmaps", "--args=" + " ".join(args_parts)]
    subprocess.run(cmd, cwd="java", check=True, capture_output=True, text=True)


@dataclass
class Metrics:
    l1: float
    l2: float
    huber: float
    welsch: float  # saturating loss — outlier robust
    max_abs: float
    signed: float
    wasserstein: float
    median_abs: float

    def __str__(self) -> str:
        return (f"L1={self.l1:.4f} L2={self.l2:.4f} Huber={self.huber:.4f} "
                f"Welsch={self.welsch:.4f} max={self.max_abs:.3f} "
                f"signed={self.signed:+.3f} W1={self.wasserstein:.4f} "
                f"med={self.median_abs:.4f}")


def pseudo_huber(e: np.ndarray, delta: float = 0.1) -> np.ndarray:
    return delta * delta * (np.sqrt(1.0 + (e / delta) ** 2) - 1.0)


def welsch(e: np.ndarray, c: float = 0.2) -> np.ndarray:
    """Saturating loss in [0, 1). c is the scale beyond which errors level off.
    A 0.5 difference is ~0.999; a 0.05 difference is ~0.061. Robust to outliers."""
    return 1.0 - np.exp(-(e * e) / (c * c))


def wasserstein_1d(a: np.ndarray, b: np.ndarray) -> float:
    """W1 between two 1D samples (sorted-difference)."""
    af = np.sort(a.ravel())
    bf = np.sort(b.ravel())
    if af.size != bf.size:
        n = min(af.size, bf.size)
        af = af[:n]; bf = bf[:n]
    return float(np.mean(np.abs(af - bf)))


def score_combo(java_dir: Path, verbose: bool = False) -> Metrics:
    l1s, l2s, hubers, welschs, maxes, signeds, w1s, meds = [], [], [], [], [], [], [], []
    per_fixture: list[tuple[str, float, float, float]] = []
    for name in LAYOUTS:
        py = python_heatmap(name)
        jp = java_dir / f"{name}.heatmap.txt"
        if not jp.exists():
            continue
        jv = _load_text_heatmap(jp)
        if jv.shape != py.shape:
            r = min(py.shape[0], jv.shape[0])
            c = min(py.shape[1], jv.shape[1])
            py = py[:r, :c]; jv = jv[:r, :c]
        e = jv - py
        fl1 = float(np.mean(np.abs(e)))
        l1s.append(fl1)
        l2s.append(float(np.sqrt(np.mean(e * e))))
        hubers.append(float(np.mean(pseudo_huber(e, 0.1))))
        welschs.append(float(np.mean(welsch(e, 0.2))))
        maxes.append(float(np.max(np.abs(e))))
        signeds.append(float(np.mean(e)))
        w1s.append(wasserstein_1d(jv, py))
        meds.append(float(np.median(np.abs(e))))
        per_fixture.append((name, float(py.mean()), float(jv.mean()), fl1))
    if verbose:
        per_fixture.sort(key=lambda x: -x[3])
        print("  per-fixture (worst first):")
        for n, pm, jm, l1 in per_fixture:
            print(f"    {n:28s} py={pm:.3f}  java={jm:.3f}  Δ={jm-pm:+.3f}  L1={l1:.3f}")
    return Metrics(
        l1=float(np.mean(l1s)),
        l2=float(np.mean(l2s)),
        huber=float(np.mean(hubers)),
        welsch=float(np.mean(welschs)),
        max_abs=float(np.max(maxes)),
        signed=float(np.mean(signeds)),
        wasserstein=float(np.mean(w1s)),
        median_abs=float(np.mean(meds)),
    )


def main() -> None:
    # Build all the python heatmaps once.
    print("preparing Python heatmaps...")
    for name in LAYOUTS:
        python_heatmap(name)

    sweep = []
    # Sweep hpw at tile=64 and tile=128, find the value that rejects false
    # positives on scattered/organic layouts without hurting grid layouts.
    # Hough max accum in a tile-local Hough scales with tile size (a perfect
    # line of length T contributes T votes), so hpw should too.
    configs = []
    for hpw in [30, 50, 70, 100, 150]:
        configs.append((64, hpw))
    for hpw in [30, 60, 100, 150, 220, 300]:
        configs.append((128, hpw))
    for tile, hpw in configs:
        params = {
            "tileSize": tile, "tileStride": tile,
            "houghMinPeakWeight": hpw,
        }
        tag = f"tile{tile}_hpw{hpw}"
        out_dir = JAVA_TMP / tag
        run_java_dump(out_dir, params)
        m = score_combo(out_dir)
        # also pull per-fixture L1 for the problematic 4 + 4 grid reference layouts
        bad = ["rect_scattered", "rect_rotated_scattered", "organic_walks", "mixed_regions"]
        good = ["grid_uniform", "dense_grid", "grid_rounded_corners", "hexagonal"]
        bad_l1 = []; good_l1 = []
        for n in bad + good:
            py = python_heatmap(n)
            jv = _load_text_heatmap(out_dir / f"{n}.heatmap.txt")
            l1 = float(np.mean(np.abs(jv - py)))
            (bad_l1 if n in bad else good_l1).append((n, l1, float(jv.mean()), float(py.mean())))
        avg_bad = np.mean([x[1] for x in bad_l1])
        avg_good = np.mean([x[1] for x in good_l1])
        print(f"  tile={tile} hpw={hpw:3d}  overall L1={m.l1:.3f}  bad-L1={avg_bad:.3f}  good-L1={avg_good:.3f}")
        for n, l1, jvm, pym in bad_l1:
            print(f"     bad  {n:28s} py={pym:.2f} java={jvm:.2f}  L1={l1:.3f}")
        sweep.append((params, m))

    def show(title, key):
        print(f"\n=== ranked by {title} ===")
        for params, m in sorted(sweep, key=key)[:8]:
            print(f"  {params}  {m}")

    show("L1", lambda x: x[1].l1)
    show("L2 (RMS)", lambda x: x[1].l2)
    show("Pseudo-Huber δ=0.1", lambda x: x[1].huber)
    show("Welsch c=0.2 (outlier-robust soft loss)", lambda x: x[1].welsch)
    show("Wasserstein (distribution-shape)", lambda x: x[1].wasserstein)
    show("median absolute diff", lambda x: x[1].median_abs)
    show("|signed mean diff| (closest to Python's average)", lambda x: abs(x[1].signed))


if __name__ == "__main__":
    main()
