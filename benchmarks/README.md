# Benchmark harness

This directory contains the small, reproducible benchmark harness for `compile-time-data-contracts`.

The goal is narrow:

- measure compile-time overhead of `SchemaConforms` witness generation on synthetic but representative schemas
- measure runtime overhead of the custom schema comparator against Spark's built-in comparators

This is artifact evidence, not a full performance suite.

## What gets measured

### Compile-time benchmark

- Uses the repo's compiled classes on the classpath
- Generates synthetic Scala sources with nested case classes, `Option`, `List`, and `Map`
- Compares:
  - `baseline`: same schema definitions without witness generation
  - `contract`: same schema definitions plus `summon[SchemaConforms[ProducerN, ContractN, SchemaPolicy.Exact.type]]`
- Runs `scalac` directly to avoid timing SBT startup

### Runtime benchmark

- Uses `ctdc.bench.RuntimeSchemaBenchmark`
- Benchmarks the custom runtime comparator against Spark's built-in comparators on matching nested schemas
- Covers:
  - unordered exact-style matching
  - by-position matching

## Run it

```bash
./benchmarks/run-benchmarks.sh
```

Optional overrides:

```bash
CTDC_BENCH_WARMUPS=1 CTDC_BENCH_RUNS=5 CTDC_RUNTIME_OPS=500000 ./benchmarks/run-benchmarks.sh 2026-04-15-local
```

Useful environment variables:

- `CTDC_BENCH_WARMUPS`
- `CTDC_BENCH_RUNS`
- `CTDC_COMPILE_SIZES`
- `CTDC_RUNTIME_WARMUPS`
- `CTDC_RUNTIME_RUNS`
- `CTDC_RUNTIME_OPS`

## Run it on a second environment

The repo also contains a manual GitHub Actions workflow at
[`/.github/workflows/benchmark-evidence.yml`](../.github/workflows/benchmark-evidence.yml).

That workflow:

- runs the same harness on `ubuntu-latest`
- uploads `benchmarks/results/<run-id>/` as a build artifact
- publishes the generated `summary.md` in the workflow summary

This is meant to give the paper a second environment snapshot, not to create a broad CI performance claim.

## Compare two saved runs

You can compare two saved result directories with:

```bash
./benchmarks/compare-results.sh 2026-04-15-local 2026-04-15-gha-ubuntu-latest \
  benchmarks/results/2026-04-15-cross-env-comparison.md
```

The comparison file includes:

- environment metadata for both runs
- compile-time overhead deltas side by side
- runtime comparator averages side by side

## Output

Each run creates `benchmarks/results/<run-id>/` with:

- `environment.txt`
- `compile.csv`
- `runtime.csv`
- `summary.md`

## Caveats

- Compile numbers depend on the local compiler version and machine
- Runtime numbers are micro-bench style measurements, not end-to-end Spark job timings
- This harness is meant to support paper claims carefully, not to overstate them
