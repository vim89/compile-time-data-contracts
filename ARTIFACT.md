# Artifact evidence matrix

This file is the paper-facing claim ledger for `compile-time-data-contracts`.

Use it before writing or revising the abstract, contributions, evaluation, or artifact section.

Rule: if a claim is not marked `closed` here, do not write it in the paper as already proven by this repo.

## Scope of this artifact

- Clean reference implementation for compile-time structural contract checks on Scala 2.13 and Scala 3 case classes
- Spark schema derivation and runtime schema pinning
- Small typed pipeline builder and demo code
- Paper artifact evidence, not industrial proof

## Status legend

- `closed`: backed by code and direct tests in this repo
- `partial`: implemented or demonstrated, but not yet covered tightly enough to state as fully proven
- `open`: not yet proven by this repo

## Claim matrix

Artifact claims are numbered `AC*` and research claims, in `paper/RESEARCH-DESIGN.md`, are numbered `RC*`. The two
sets were both numbered `C1..Cn` and said different things under the same label. A claim cited without its prefix
is now ambiguous on its face, which is the point.

`paper/evidence/claims.json` is this table in machine-readable form, pinned to the revision it was generated at.
Regenerate it with `python3 paper/scripts/claims_ledger.py`; it fails rather than emits if any evidence path in the
table does not exist, so a claim cannot keep pointing at a file that was moved or deleted.

| ID | Claim                                                                                                                                                                                             | Status   | Evidence in repo                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | Current limit                                                                                                                                                                                                                                                                                                                                                      |
|----|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC1 | The artifact proves compile-time structural conformance between producer and contract case classes under tested policies, including nested case classes, sequences, maps, and nested optionality. | `closed` | [modules/core/src/main/scala/ctdc](modules/core/src/main/scala/ctdc), [modules/core/src/test/scala-3/ctdc/SchemaConformsSpec.scala](modules/core/src/test/scala-3/ctdc/SchemaConformsSpec.scala)                                                                                                                                                                                                                                                                                                                                                                                                                                                         | Coverage now directly exercises `Exact`, `Backward`, `Forward`, `ExactOrdered`, `ExactOrderedCI`, `ExactUnordered`, `ExactUnorderedCI`, `ExactByPosition`, and `Unchecked`. The claim is still structural only; semantic contracts remain out of scope.                                                                                                                                   |
| AC2 | Compile-time failures surface readable, path-rich drift diagnostics instead of a generic missing-given failure.                                                                                   | `closed` | [modules/core/src/main/scala/ctdc](modules/core/src/main/scala/ctdc), negative checks in [modules/core/src/test/scala-3/ctdc/SchemaConformsSpec.scala](modules/core/src/test/scala-3/ctdc/SchemaConformsSpec.scala)                                                                                                                                                                                                                                                                                                                                                                                                                                      | The tests assert key snippets, not full golden error text. Message wording can still evolve.                                                                                                                                                                                                                                                                       |
| AC3 | Spark schema derivation preserves field optionality and nested collection optionality for supported shapes.                                                                                       | `closed` | [modules/spark/src/main/scala/ctdc/SparkCore.scala](modules/spark/src/main/scala/ctdc/SparkCore.scala), [modules/spark/src/test/scala/ctdc/SparkSchemaSpec.scala](modules/spark/src/test/scala/ctdc/SparkSchemaSpec.scala)                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Supported shape set is still intentionally small: primitives, nested case classes, sequences, maps with atomic keys, and `Option`.                                                                                                                                                                                                                                 |
| AC4 | The runtime pin catches exact-style schema drift in field names, field order and leaf types. It does not check any of the three carriers of optionality.                                                                       | `closed` | [modules/spark/src/main/scala/ctdc/SparkCore.scala](modules/spark/src/main/scala/ctdc/SparkCore.scala), [modules/spark/src/test/scala/ctdc/SparkRuntimeSpec.scala](modules/spark/src/test/scala/ctdc/SparkRuntimeSpec.scala)                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | This is a custom comparator that follows Spark name/order semantics. It is not literally Spark's built-in comparator. The three carriers are compared in the macro and dropped here, because a `StructType` a reader produced cannot distinguish a stated claim from a defaulted one; `assertNoForbiddenNulls` answers that question from the rows instead.                                                                                                                                                                                                        |
| AC5 | The sink boundary combines compile-time proof and runtime validation before write.                                                                                                                | `closed` | Sink wiring in [modules/spark/src/main/scala/ctdc/SparkCore.scala](modules/spark/src/main/scala/ctdc/SparkCore.scala), builder tests in [modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala](modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala)                                                                                                                                                                                                                                                                                                                                                                                                                               | The strongest direct evidence is for the typed `PipelineBuilder` path, not every possible caller surface.                                                                                                                                                                                                                                                          |
| AC6 | The artifact demonstrates policy-aware runtime behavior beyond the default exact-style path.                                                                                                      | `closed` | [modules/spark/src/main/scala/ctdc/SparkCore.scala](modules/spark/src/main/scala/ctdc/SparkCore.scala), [modules/spark/src/test/scala/ctdc/SparkRuntimeSpec.scala](modules/spark/src/test/scala/ctdc/SparkRuntimeSpec.scala), [modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala](modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala)                                                                                                                                                                                                                                                                                                                                                                     | `ExactByPosition`, `ExactOrdered`, `ExactOrderedCI`, `ExactUnordered`, `ExactUnorderedCI`, `Backward`, `Forward`, and `Unchecked` are directly exercised. Backward runtime allowance for missing fields depends on metadata derived from the contract type; manually constructed expected `StructType` schemas without `ctdc.hasDefault` metadata fall back to `nullable`-only allowance. |
| AC7 | The artifact measures compile-time proof overhead and runtime comparator overhead with a reproducible harness and saved evidence from two environments and two revisions.                                           | `closed` | [benchmarks/run-benchmarks.sh](benchmarks/run-benchmarks.sh), [benchmarks/compare-results.sh](benchmarks/compare-results.sh), [benchmarks/README.md](benchmarks/README.md), [modules/probe/src/main/scala/ctdc/bench/RuntimeSchemaBenchmark.scala](modules/probe/src/main/scala/ctdc/bench/RuntimeSchemaBenchmark.scala), [benchmarks/results/2026-04-15-local/summary.md](benchmarks/results/2026-04-15-local/summary.md), [benchmarks/results/2026-04-15-gha-ubuntu-latest/summary.md](benchmarks/results/2026-04-15-gha-ubuntu-latest/summary.md), [benchmarks/results/2026-04-15-cross-env-comparison.md](benchmarks/results/2026-04-15-cross-env-comparison.md), [benchmarks/results/2026-10-08-local/summary.md](benchmarks/results/2026-10-08-local/summary.md) | This is still an artifact proof, not a statistically rigorous cross-machine baseline or an end-to-end Spark performance study. Three snapshots: `macOS arm64` local and `GitHub-hosted Ubuntu x86_64` at the April head, and `macOS arm64` local again at the current head after the engine and comparator rewrites, which the figures show cost roughly twice as much compile time. Only the local pair is comparable across revisions, and only the April pair across environments.                                                                                                                                                                 |
| AC8 | The artifact proves industrial effectiveness, deployment scale, or incident reduction.                                                                                                            | `open`   | None in this repo                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Those claims must come from separate evidence packs, not from this clean repo alone.                                                                                                                                                                                                                                                                               |

## What this repo does not currently prove

- Semantic contracts such as ranges, domain constraints, or business rules
- Temporal or cross-record constraints
- External schema registry integration
- Stable or broadly generalizable cross-machine performance claims about compile time or runtime overhead
- User-study-style productivity or usability claims
- Industrial metrics, deployment counts, or incident reduction claims

## Evidence inventory

### Compile-time proof

- [modules/core/src/main/scala/ctdc](modules/core/src/main/scala/ctdc): policy model, normalized type
  shape, and drift rendering
- [modules/core/src/main/scala-2/ctdc](modules/core/src/main/scala-2/ctdc) and
  [modules/core/src/main/scala-3/ctdc](modules/core/src/main/scala-3/ctdc): the per-Scala-version macro
  derivation front ends
- [modules/core/src/test/scala-3/ctdc/SchemaConformsSpec.scala](modules/core/src/test/scala-3/ctdc/SchemaConformsSpec.scala): positive and negative
  compile-time coverage
- [modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala](modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala): builder-surface
  compile gate at `addSink`

### Runtime proof

- [modules/spark/src/main/scala/ctdc/SparkCore.scala](modules/spark/src/main/scala/ctdc/SparkCore.scala): Spark schema derivation, runtime schema
  comparator, schema pin, typed sink path
- [modules/spark/src/test/scala/ctdc/SparkSchemaSpec.scala](modules/spark/src/test/scala/ctdc/SparkSchemaSpec.scala): schema derivation checks,
  including default-field metadata used by runtime subset semantics
- [modules/spark/src/test/scala/ctdc/SparkRuntimeSpec.scala](modules/spark/src/test/scala/ctdc/SparkRuntimeSpec.scala): runtime drift checks and
  policy-aware write path
- [modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala](modules/spark/src/test/scala/ctdc/PipelineBuilderSpec.scala): end-to-end green/red
  sink-boundary checks through `PipelineBuilder`

### Example surface

- [README.md](README.md): public artifact description and quick-start examples

### Benchmark evidence

- [benchmarks/run-benchmarks.sh](benchmarks/run-benchmarks.sh): reproducible compile-time and runtime benchmark harness
- [benchmarks/compare-results.sh](benchmarks/compare-results.sh): renders a saved side-by-side comparison between two
  benchmark runs
- [benchmarks/README.md](benchmarks/README.md): benchmark scope, parameters, and caveats
- [.github/workflows/benchmark-evidence.yml](.github/workflows/benchmark-evidence.yml): GitHub-hosted Ubuntu runner path
  for second-environment benchmark snapshots
- [modules/probe/src/main/scala/ctdc/bench/RuntimeSchemaBenchmark.scala](modules/probe/src/main/scala/ctdc/bench/RuntimeSchemaBenchmark.scala):
  runtime comparator micro-benchmark
- [benchmarks/results/2026-04-15-local/summary.md](benchmarks/results/2026-04-15-local/summary.md): saved local
  `macOS arm64` snapshot with environment metadata
- [benchmarks/results/2026-04-15-gha-ubuntu-latest/summary.md](benchmarks/results/2026-04-15-gha-ubuntu-latest/summary.md):
  saved `GitHub-hosted Ubuntu x86_64` snapshot with environment metadata
- [benchmarks/results/2026-04-15-cross-env-comparison.md](benchmarks/results/2026-04-15-cross-env-comparison.md): saved
  local-vs-CI comparison for the same artifact head

## Regenerating the evidence

Every command below is run from the repository root, and every one writes to the path where the committed evidence
already is, so a re-run that agrees produces no diff and a re-run that disagrees produces one. Only `paper/corpus/fetch.sh`
needs network access, and only the `spark4` run needs a second Spark version resolved.

```sh
# the test gate: core on 2.13 and 3, the Spark pin, the harnesses
sbt -batch clean +core/test spark/test probe/test

# the comparator matrix at the pinned Spark 3.5.6, and the same suite re-run at 4.2.0
sbt -batch 'probe/runMain ctdc.probe.ComparatorMatrix paper/evidence/comparator-matrix.csv'
SPARK_LOCAL_IP=127.0.0.1 sbt -batch \
  'spark4/runMain ctdc.probe.ComparatorMatrix paper/evidence/comparator-matrix-spark4.csv'

# the predicate bodies at v3.5.6, v4.0.4, v4.1.3 and v4.2.0, hashed from a Spark checkout
python3 paper/scripts/spark_predicate_hashes.py --repo /path/to/apache/spark

# what a reader does to the three carriers, as a transcript
sbt -batch 'probe/runMain ctdc.probe.NullabilityConsequence paper/evidence/nullability-consequence.txt'

# the corpus: fetch needs network, the walk does not
paper/corpus/fetch.sh
sbt -batch 'probe/runMain ctdc.probe.CorpusRelevance'

# the benchmark snapshots
SPARK_LOCAL_IP=127.0.0.1 ./benchmarks/run-benchmarks.sh 2026-10-08-local

# the claim ledger, pinned to the commit it was generated at
python3 paper/scripts/claims_ledger.py
```

Record the commit before running any of this, and compare it with the `revision` field in
`paper/evidence/claims.json`. That field is what pins the evidence: the numbers in the paper belong to one revision
of the comparison engine, and a matrix regenerated after a change to it is a different measurement rather than a
failed reproduction.

The harnesses live in the unpublished `probe` module rather than in `ctdc-spark`, because one of them reaches two
`private[sql]` comparators from a subpackage of `org.apache.spark.sql`, which is surface no pipeline should resolve.
`spark4` is not aggregated by the root project either, so an ordinary `sbt test` does not resolve a second Spark.

## Paper-safe wording

These are safe summary lines for the current repo state:

- The artifact proves compile-time structural contract conformance for a focused set of case-class schemas, on both
  Scala 2.13 and Scala 3.
- The artifact derives Spark schemas from the same type model and enforces a runtime schema pin.
- The runtime pin compares field names, field order and leaf types, and deliberately compares none of the three
  carriers of optionality, because a reader-produced `StructType` does not retain the claim. `assertNoForbiddenNulls`
  is the opt-in row-level replacement.
- The runtime pin also implements structural subset semantics for `Backward` and `Forward`, using optional and
  default markers derived from the contract type.
- The artifact includes a reproducible benchmark harness, a saved local snapshot, a saved GitHub-hosted Ubuntu snapshot,
  and a saved comparison between them.

These are not yet safe as fully proven claims from this repo:

- The approach has low overhead across machines or build environments.
- The approach improves developer productivity in measured terms.
- The approach reduces incidents in production.
- The approach is validated across multiple real teams or systems.

## Next evidence to add

1. A third snapshot on another Linux host or CI runner to see how stable the cross-environment pattern stays.
2. A separate industrial evidence pack outside this repo.

## Note on FlowForge

[`flowforge`](https://github.com/vim89/flowforge) is useful as a semantic source and a motivation source, but it is not
this artifact.
Do not treat FlowForge implementation history as proof for claims marked `closed` here unless that evidence is copied
into a separate, reviewable pack.
