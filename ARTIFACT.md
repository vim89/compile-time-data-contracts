# Artifact evidence matrix

This file is the paper-facing claim ledger for `compile-time-data-contracts`.

Use it before writing or revising the abstract, contributions, evaluation, or artifact section.

Rule: if a claim is not marked `closed` here, do not write it in the paper as already proven by this repo.

## Scope of this artifact

- Clean reference implementation for compile-time structural contract checks on Scala 3 case classes
- Spark schema derivation and runtime schema pinning
- Small typed pipeline builder and demo code
- Paper artifact evidence, not industrial proof

## Status legend

- `closed`: backed by code and direct tests in this repo
- `partial`: implemented or demonstrated, but not yet covered tightly enough to state as fully proven
- `open`: not yet proven by this repo

## Claim matrix

| ID | Claim | Status | Evidence in repo | Current limit |
| --- | --- | --- | --- | --- |
| C1 | The artifact proves compile-time structural conformance between producer and contract case classes under tested policies, including nested case classes, sequences, maps, and nested optionality. | `closed` | [src/main/scala/ctdc/ContractsCore.scala](src/main/scala/ctdc/ContractsCore.scala), [src/test/scala/ctdc/SchemaConformsSpec.scala](src/test/scala/ctdc/SchemaConformsSpec.scala) | Coverage is tight for `Exact`, `Backward`, `Forward`, `ExactOrdered`, and `ExactByPosition`. `ExactOrderedCI`, `ExactUnorderedCI`, and `Full` are implemented but not separately asserted here. |
| C2 | Compile-time failures surface readable, path-rich drift diagnostics instead of a generic missing-given failure. | `closed` | [src/main/scala/ctdc/ContractsCore.scala](src/main/scala/ctdc/ContractsCore.scala), negative checks in [src/test/scala/ctdc/SchemaConformsSpec.scala](src/test/scala/ctdc/SchemaConformsSpec.scala) | The tests assert key snippets, not full golden error text. Message wording can still evolve. |
| C3 | Spark schema derivation preserves field optionality and nested collection optionality for supported shapes. | `closed` | [src/main/scala/ctdc/SparkCore.scala](src/main/scala/ctdc/SparkCore.scala), [src/test/scala/ctdc/SparkSchemaSpec.scala](src/test/scala/ctdc/SparkSchemaSpec.scala) | Supported shape set is still intentionally small: primitives, nested case classes, sequences, maps with atomic keys, and `Option`. |
| C4 | The runtime pin catches exact-style schema drift, including nested array and map optionality that Spark ignores by default. | `closed` | [src/main/scala/ctdc/SparkCore.scala](src/main/scala/ctdc/SparkCore.scala), [src/test/scala/ctdc/SparkRuntimeSpec.scala](src/test/scala/ctdc/SparkRuntimeSpec.scala) | This is a custom comparator that follows Spark name/order semantics and adds the nested optionality check. It is not literally Spark's built-in comparator. |
| C5 | The sink boundary combines compile-time proof and runtime validation before write. | `closed` | Sink wiring in [src/main/scala/ctdc/SparkCore.scala](src/main/scala/ctdc/SparkCore.scala), builder tests in [src/test/scala/ctdc/PipelineBuilderSpec.scala](src/test/scala/ctdc/PipelineBuilderSpec.scala) | The strongest direct evidence is for the typed `PipelineBuilder` path, not every possible caller surface. |
| C6 | The artifact demonstrates policy-aware runtime behavior beyond the default exact-style path. | `partial` | [src/main/scala/ctdc/SparkCore.scala](src/main/scala/ctdc/SparkCore.scala), [src/test/scala/ctdc/SparkRuntimeSpec.scala](src/test/scala/ctdc/SparkRuntimeSpec.scala), [src/test/scala/ctdc/PipelineBuilderSpec.scala](src/test/scala/ctdc/PipelineBuilderSpec.scala) | `ExactByPosition` is covered end-to-end. Other runtime policy variants are implemented but not yet individually tested. |
| C7 | The artifact proves usability or low overhead in a measurable way. | `open` | None yet | We do not yet have compile-time overhead numbers, runtime overhead numbers, or a user-study-style usability story. |
| C8 | The artifact proves industrial effectiveness, deployment scale, or incident reduction. | `open` | None in this repo | Those claims must come from separate evidence packs, not from this clean repo alone. |

## What this repo does not currently prove

- Semantic contracts such as ranges, domain constraints, or business rules
- Temporal or cross-record constraints
- Runtime subset semantics for `Backward` and `Forward`
- External schema registry integration
- Benchmark-based claims about compile time or runtime overhead
- Industrial metrics, deployment counts, or incident reduction claims

## Evidence inventory

### Compile-time proof

- [src/main/scala/ctdc/ContractsCore.scala](src/main/scala/ctdc/ContractsCore.scala): policy model, normalized type shape, macro derivation, and drift rendering
- [src/test/scala/ctdc/SchemaConformsSpec.scala](src/test/scala/ctdc/SchemaConformsSpec.scala): positive and negative compile-time coverage
- [src/test/scala/ctdc/PipelineBuilderSpec.scala](src/test/scala/ctdc/PipelineBuilderSpec.scala): builder-surface compile gate at `addSink`

### Runtime proof

- [src/main/scala/ctdc/SparkCore.scala](src/main/scala/ctdc/SparkCore.scala): Spark schema derivation, runtime schema comparator, schema pin, typed sink path
- [src/test/scala/ctdc/SparkSchemaSpec.scala](src/test/scala/ctdc/SparkSchemaSpec.scala): schema derivation checks
- [src/test/scala/ctdc/SparkRuntimeSpec.scala](src/test/scala/ctdc/SparkRuntimeSpec.scala): runtime drift checks and policy-aware write path
- [src/test/scala/ctdc/PipelineBuilderSpec.scala](src/test/scala/ctdc/PipelineBuilderSpec.scala): end-to-end green/red sink-boundary checks through `PipelineBuilder`

### Example surface

- [src/main/scala/ctdc/CtdcPoc.scala](src/main/scala/ctdc/CtdcPoc.scala): compile-only and pipeline demo code
- [README.md](README.md): public artifact description and quick-start examples

## Paper-safe wording

These are safe summary lines for the current repo state:

- The artifact proves compile-time structural contract conformance for a focused set of Scala 3 case-class schemas.
- The artifact derives Spark schemas from the same type model and enforces a runtime schema pin.
- The runtime pin includes a custom check for nested collection optionality, because Spark ignores that drift by default.

These are not yet safe as proven claims from this repo:

- The approach has low compile-time or runtime overhead.
- The approach improves developer productivity in measured terms.
- The approach reduces incidents in production.
- The approach is validated across multiple real teams or systems.

## Next evidence to add

1. A small benchmark for compile/build overhead.
2. A small benchmark for runtime comparator overhead.
3. Individual runtime tests for the remaining non-default policies.
4. A separate industrial evidence pack outside this repo.

## Note on FlowForge

`flowforge` is useful as a semantic source and a motivation source, but it is not this artifact.
Do not treat FlowForge implementation history as proof for claims marked `closed` here unless that evidence is copied into a separate, reviewable pack.
