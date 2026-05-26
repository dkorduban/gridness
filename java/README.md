# gridness (Java)

The Java implementation lives here. Public API:

- `com.dkorduban.gridness.Gridness` — mutable wall grid + smooth
  gridness heatmap, with incremental updates on edits.
- `com.dkorduban.gridness.GridnessParams` — immutable config built via
  `GridnessParams.builder()…build()`.

Everything else (quick start, full API, algorithm walkthrough,
parameter table, presets, build & test commands) lives in the
**[top-level README](../README.md)**.

## Just the commands

```bash
source env.sh            # puts JDK 21 + Gradle on PATH

gradle build             # compile + test
gradle test              # tests only
gradle :jmh              # JMH benchmarks (~5 min)
gradle :jmh -PmatchingConfig=true   # JMH against the high-fidelity preset
gradle :viewer           # Swing live viewer
gradle :dumpHeatmaps     # write per-fixture text heatmaps
```

See [BENCHMARK.md](BENCHMARK.md) for the workload model and per-cell
benchmark analysis.
