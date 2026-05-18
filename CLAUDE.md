# CLAUDE.md — Gridness project autonomous-dev rules

This project is being built **fully autonomously** by Claude. These rules exist so you do not get stuck and so experiments are reproducible later.

## Project goal

Implement and tune algorithms that score the *gridness* of a 2D city layout (Song of Syx style) — see PROBLEM.md for the algorithm spec. Validate on a synthetic dataset of ~15 labeled layouts before any tuning on real data (real data not yet available).

## Autonomous-dev rules

### Rule 1: every experiment must be reproducible
- Before running any experiment that writes to `experiments/`, the working tree MUST be clean (`git status --porcelain` empty).
- Each experiment record (in `experiments/log.jsonl`) MUST include `git_sha`, `algo_version`, `params`, `dataset_version`, `timestamp`, `output_dir`.
- Each output image MUST have the short git SHA rendered into the figure title or a corner annotation.
- If you need to tweak code while iterating, commit each tweak (small commits are fine) — never run an experiment from a dirty tree.

### Rule 2: timebox computation
- Per-layout scoring for an algorithm version must complete in **< 60 seconds wall-clock** on a 200×200 raster. If it doesn't, downsample the heatmap stride (e.g. score every Nth pixel and bilinear-upsample), or use a sparser candidate-frame set.
- The full eval (all algos × all layouts) must complete in **< 5 minutes** wall-clock. If it doesn't, parallelize via `multiprocessing.Pool` or reduce the dataset to the discriminative subset.
- A param sweep that doesn't terminate in **< 15 minutes** is too coarse — coarsen the grid.

### Rule 3: test on tiny inputs first
- Every new algorithm function gets exercised on a 30×30 raster in a `__main__` block or smoke test before being run on the full dataset.
- Before any param sweep, dry-run a single (algorithm, layout, params) combo end-to-end.

### Rule 4: fail forward, not in loops
- If an algorithm crashes or produces all-NaN/all-zero output on layout X, log the failure to `experiments/log.jsonl` with `status="failed"` and the traceback, then move on to the next layout. Don't block the whole eval on one broken case.
- If a param sweep produces a clearly degenerate optimum (e.g., score saturates at 1.0 everywhere), don't keep tuning — log the finding and adjust the algorithm or the loss.

### Rule 5: visual eval is the source of truth
- Numerical aggregate stats are useful but lie easily. The eval harness MUST produce a side-by-side visual grid per layout: input raster | extracted buildings | gridness heatmap | (rowness heatmap if applicable). Render these to `experiments/<exp_id>/figures/`.
- A "good" result is one a human (Pilot Pirx, the user) would agree with on inspection. Encode that intuition in `data/layouts/<layout>.json` as `expected_score` with values "high", "medium", "low", or a per-region dict.

### Rule 6: descent on weird results
- If a layout's score badly contradicts its expected label, first inspect the visualization, then either: (a) adjust algorithm parameters and re-run, (b) add a diagnostic plot showing the intermediate state (e.g., candidate frames, line clusters), (c) flag in the report as a known failure mode if you can't fix it within ~3 iterations.
- After 3 unsuccessful iterations on the same issue, log the issue, move on, mention it in the final report.

### Rule 7: commit cadence
- Commit after each phase, and after each successful experiment run. Commits should be small and tell a coherent story when read top-to-bottom.
- Never `git push` (no remote configured anyway).
- Never `--no-verify`. Don't force pushes. Don't reset history.

### Rule 8: deterministic by default
- Every random function takes a `seed` arg, default `0`.
- The dataset generator is fully deterministic given seed.
- Eval and scoring are deterministic — no random sampling unless explicitly seeded.

## How to run

```bash
# regenerate the synthetic dataset
uv run python -m scripts.generate_dataset

# extract buildings for a layout (sanity)
uv run python -m scripts.extract_one --layout grid_uniform

# run full eval
uv run python -m scripts.run_experiment --algo v2 --tag baseline

# param sweep
uv run python -m scripts.sweep_params --algo v2
```

## Layout of the repo

```
gridness/
  PROBLEM.md           — original problem statement (read-only)
  CLAUDE.md            — this file
  REPORT.md            — final findings (written at end)
  pyproject.toml
  src/gridness/
    __init__.py
    generate.py        — synthetic layout generator
    extract.py         — building extraction
    scoring/
      __init__.py
      v1_axis.py       — axis-aligned grid-line clustering
      v2_affine.py     — affine grid-line snapping
      common.py        — clustering, weighting, helpers
    eval.py            — experiment orchestration
    viz.py             — plotting helpers
  scripts/
    generate_dataset.py
    run_experiment.py
    sweep_params.py
  data/
    layouts/           — <name>.npy + <name>.json metadata
  experiments/
    log.jsonl          — one JSON line per experiment
    <exp_id>/
      figures/         — per-layout visualizations
      summary.json     — aggregate stats
      params.json      — frozen params for this experiment
```

## Things NOT to do

- Don't implement the full algorithm in one shot and only test at the end — build the pipeline bottom-up and visualize at each step.
- Don't add ML/training/regression heads. This is pure algorithmic + parameter tuning.
- Don't bring in real Song of Syx data — none exists yet. The user will provide it later.
- Don't write multiple-page docstrings, comments explaining what the code does, or planning markdown files inside `src/`. Keep code surfaces tight.
- Don't optimize before correctness. Profile only if Rule 2 timeboxes break.
