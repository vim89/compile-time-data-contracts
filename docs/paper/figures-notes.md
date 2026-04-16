# Figures and tables notes

This file defines the first paper-quality visuals to make.

Do not add decorative figures.
Every figure or table must answer a reviewer question.

## Figure 1: compile-time proof plus runtime pin architecture

Purpose:
- show the pipeline boundary where compile-time proof stops and runtime data still matters

Core message:
- the same contract type drives both compile-time `SchemaConforms` and runtime `SparkSchema` / `PolicyRuntime`

Suggested layout:

1. Producer type `Out`
2. Contract type `R`
3. Scala 3 macro path:
   `Out` + `R` + policy `P` -> normalized shape -> `SchemaConforms[Out, R, P]`
4. Typed builder fuse:
   `addSink[R, P]` requires the compile-time witness
5. Runtime path:
   DataFrame schema -> `SparkSchema[R]` + `PolicyRuntime[P]` -> sink write allowed or rejected

Source anchors:
- [ContractsCore.scala](../../src/main/scala/ctdc/ContractsCore.scala)
- [SparkCore.scala](../../src/main/scala/ctdc/SparkCore.scala)
- [PipelineBuilderSpec.scala](../../src/test/scala/ctdc/PipelineBuilderSpec.scala)

What to emphasize in the caption:
- compile-time catches typed drift in code
- runtime pin protects the actual Spark schema at the sink boundary

## Figure 2: policy matrix

Purpose:
- make the policy family legible in one glance

Columns:
- policy
- compile-time comparison mode
- runtime comparison mode
- extras allowed?
- missing fields allowed?
- name sensitivity
- order sensitivity
- nested optionality checked?

Recommended rows:

| policy | compile-time shape rule | runtime rule | producer extras | missing contract fields | name sensitivity | order sensitivity | nested collection optionality |
|--------|-------------------------|--------------|-----------------|-------------------------|------------------|------------------|-------------------------------|
| `Exact` | unordered by name | unordered by name | no | no | case-insensitive | no | yes |
| `ExactOrdered` | ordered by name | ordered by name | no | no | case-sensitive | yes | yes |
| `ExactOrderedCI` | ordered by name | ordered by name | no | no | case-insensitive | yes | yes |
| `ExactByPosition` | by position | by position | no | no | ignored | yes by position | yes |
| `Backward` | contract subset of producer, with optional/default relaxations | same subset direction | yes | optional/default only | case-sensitive | no | yes |
| `Forward` | producer subset of contract | same subset direction | no | yes | case-sensitive | no | yes |
| `Full` | accept all | accept all | yes | yes | n/a | n/a | n/a |

Source anchors:
- policy definitions in [ContractsCore.scala](../../src/main/scala/ctdc/ContractsCore.scala)
- runtime mappings in [SparkCore.scala](../../src/main/scala/ctdc/SparkCore.scala)
- runtime tests in [SparkRuntimeSpec.scala](../../src/test/scala/ctdc/SparkRuntimeSpec.scala)

## Table 1: benchmark summary

Purpose:
- show the measured scope without overselling it

Caption guidance:
- “Saved local and CI benchmark snapshots for the artifact head. These numbers show reproducible harness output, not a general cross-machine performance baseline.”

Suggested table:

### Compile-time overhead

| schema pairs | local delta (s) | local delta (%) | GitHub-hosted Ubuntu delta (s) | GitHub-hosted Ubuntu delta (%) |
|--------------|----------------:|----------------:|-------------------------------:|-------------------------------:|
| 10 | 0.270 | 11.8 | 0.847 | 12.6 |
| 25 | 0.397 | 13.5 | 1.000 | 11.1 |
| 50 | 0.513 | 13.9 | 1.880 | 16.6 |

### Runtime comparator averages

| benchmark | local ns/op | GitHub-hosted Ubuntu ns/op | Ubuntu / local |
|-----------|------------:|---------------------------:|---------------:|
| custom exact by position match | 116.82 | 180.55 | 1.55 |
| custom exact unordered match | 4736.41 | 8149.74 | 1.72 |
| Spark equalsIgnoreCaseAndNullability | 278.92 | 331.42 | 1.19 |
| Spark equalsStructurally | 332.13 | 380.36 | 1.15 |

Source anchors:
- [local summary](../../benchmarks/results/2026-04-15-local/summary.md)
- [Ubuntu summary](../../benchmarks/results/2026-04-15-gha-ubuntu-latest/summary.md)
- [comparison](../../benchmarks/results/2026-04-15-cross-env-comparison.md)

What not to imply:
- no end-to-end Spark-job benchmark
- no stable “low overhead everywhere” claim
- no claim that custom unordered exact is faster than Spark’s ignore-case comparator

## Optional table 2: evidence-to-section map

Purpose:
- keep the writing disciplined

Columns:
- manuscript section
- allowed claims
- exact repo evidence
- excluded claims

This can stay internal if the main paper gets tight on space.
