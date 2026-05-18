"""Run a scoring algorithm over the synthetic dataset and log results."""

from __future__ import annotations

import argparse
from pathlib import Path

from gridness.eval import run_experiment
from gridness.scoring.common import V1Params
from gridness.scoring.v1_axis import score_map_v1


ALGOS = {
    "v1": (score_map_v1, V1Params),
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--algo", choices=list(ALGOS.keys()), required=True)
    parser.add_argument("--tag", default="baseline")
    parser.add_argument("--dataset-dir", type=Path, default=Path("data/layouts"))
    parser.add_argument("--out-dir", type=Path, default=Path("experiments"))
    parser.add_argument("--allow-dirty", action="store_true")
    args = parser.parse_args()

    fn, ParamsCls = ALGOS[args.algo]
    params = ParamsCls()
    run_experiment(fn, params, args.dataset_dir, args.out_dir,
                   tag=args.tag, algo_name=args.algo,
                   strict_clean=not args.allow_dirty)


if __name__ == "__main__":
    main()
