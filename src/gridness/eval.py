"""Experiment orchestration: run algorithms over the dataset, log results."""

from __future__ import annotations

import json
import subprocess
import time
from dataclasses import asdict, is_dataclass
from datetime import datetime
from pathlib import Path

import numpy as np

from gridness.extract import extract_buildings
from gridness.viz import visualize_result


def git_sha(short: bool = True) -> str:
    try:
        out = subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip()
        return out[:7] if short else out
    except Exception:
        return "nogit"


def repo_is_clean() -> bool:
    try:
        out = subprocess.check_output(["git", "status", "--porcelain"]).decode().strip()
        return out == ""
    except Exception:
        return False


def _serialize(o):
    if is_dataclass(o):
        return {k: _serialize(v) for k, v in asdict(o).items()}
    if isinstance(o, np.ndarray):
        return o.tolist()
    if isinstance(o, (np.floating, np.integer)):
        return o.item()
    if isinstance(o, dict):
        return {k: _serialize(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_serialize(x) for x in o]
    return o


def aggregate_stats(result: dict, expected: dict, full_shape: tuple[int, int]) -> dict:
    """Per-layout summary stats. Falls back gracefully on empty maps."""
    g = result["gridness"]
    conf = result["confidence"]
    confident = conf > (conf.max() * 0.1 if conf.max() > 0 else 0)
    if not confident.any():
        return {"mean": 0.0, "median": 0.0, "p10": 0.0, "p90": 0.0, "covered": 0.0}
    g_c = g[confident]
    return {
        "mean": float(g_c.mean()),
        "median": float(np.median(g_c)),
        "p10": float(np.percentile(g_c, 10)),
        "p90": float(np.percentile(g_c, 90)),
        "covered": float(confident.sum() / confident.size),
    }


def region_stats(result: dict, expected: dict) -> dict | None:
    if "regions" not in expected:
        return None
    g = result["gridness"]
    conf = result["confidence"]
    stride = result["stride"]
    sample_xs = result["sample_xs"]
    sample_ys = result["sample_ys"]
    out = []
    confident = conf > (conf.max() * 0.1 if conf.max() > 0 else 0)
    for r in expected["regions"]:
        x, y, w, h = r["bbox"]
        in_x = (sample_xs >= x) & (sample_xs < x + w)
        in_y = (sample_ys >= y) & (sample_ys < y + h)
        mask = np.outer(in_y, in_x) & confident
        if not mask.any():
            out.append({"expected": r["gridness"], "bbox": [x, y, w, h], "mean": 0.0})
            continue
        g_sel = g[mask]
        out.append({
            "expected": r["gridness"],
            "bbox": [x, y, w, h],
            "mean": float(g_sel.mean()),
            "median": float(np.median(g_sel)),
        })
    return out


def run_experiment(algo_fn, params, dataset_dir: Path, out_dir: Path,
                   tag: str, algo_name: str, strict_clean: bool = True) -> dict:
    if strict_clean and not repo_is_clean():
        raise RuntimeError("Working tree is dirty. Commit before running an experiment.")
    sha = git_sha()
    exp_id = f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{algo_name}_{tag}_{sha}"
    exp_dir = out_dir / exp_id
    exp_dir.mkdir(parents=True, exist_ok=True)
    figures_dir = exp_dir / "figures"
    figures_dir.mkdir(exist_ok=True)

    layouts = sorted(dataset_dir.glob("*.npy"))

    summary = {
        "exp_id": exp_id,
        "git_sha": sha,
        "algo": algo_name,
        "tag": tag,
        "started_at": datetime.utcnow().isoformat() + "Z",
        "params": _serialize(params),
        "per_layout": {},
    }
    print(f"[{exp_id}]")
    t0 = time.time()
    for p in layouts:
        meta_p = p.with_suffix(".json")
        meta = json.loads(meta_p.read_text())
        raster = np.load(p)
        t_layout = time.time()
        try:
            buildings = extract_buildings(raster)
            try:
                result = algo_fn(buildings, raster.shape, params, raster=raster)
            except TypeError:
                result = algo_fn(buildings, raster.shape, params)
            stats = aggregate_stats(result, meta["expected"], raster.shape)
            regions = region_stats(result, meta["expected"])
            visualize_result(raster, buildings, result, meta["name"],
                             meta["expected"], sha,
                             figures_dir / f"{meta['name']}.png")
            elapsed = time.time() - t_layout
            print(f"  {meta['name']:30s} n_b={len(buildings):4d}  "
                  f"mean={stats['mean']:.3f} cov={stats['covered']:.2f}  "
                  f"t={elapsed:.1f}s  exp={meta['expected'].get('global', {}).get('gridness', 'regional')}")
            summary["per_layout"][meta["name"]] = {
                "n_buildings": len(buildings),
                "stats": stats,
                "regions": regions,
                "expected": meta["expected"],
                "elapsed_s": elapsed,
                "status": "ok",
            }
        except Exception as e:
            import traceback
            tb = traceback.format_exc()
            print(f"  {meta['name']:30s} FAILED: {e}")
            summary["per_layout"][meta["name"]] = {
                "status": "failed",
                "error": str(e),
                "traceback": tb,
            }
    summary["total_elapsed_s"] = time.time() - t0
    summary["finished_at"] = datetime.utcnow().isoformat() + "Z"

    (exp_dir / "summary.json").write_text(json.dumps(summary, indent=2, default=_serialize))
    log_path = out_dir / "log.jsonl"
    with open(log_path, "a") as f:
        f.write(json.dumps({k: v for k, v in summary.items() if k != "per_layout"}, default=_serialize) + "\n")
    print(f"=> {exp_dir}  ({summary['total_elapsed_s']:.1f}s)")
    return summary
