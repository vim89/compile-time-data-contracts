# Draft sections 1-3

This file is a prose-first markdown draft for the opening three manuscript sections.
It stays inside the safe claim surface from [evidence-map.md](evidence-map.md) and [ARTIFACT.md](../../ARTIFACT.md).

## 1. Introduction

Schema drift is still discovered too late in many data systems. A producer changes a field name, reorders a record, widens an optional field, or changes the nullability of a nested collection, and the breakage is often found only when a job runs against real data. Spark exposes runtime schema comparators and schema-aware readers, but those checks still happen after the program has been built and after an external boundary has already been crossed. In practice, that means a pipeline can look type-directed in code while the most important compatibility question is postponed until execution time. [Spark 3.5.1 DataType companion](https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/DataType%24.html)

For typed JVM pipelines, a large part of that question is structural and can be asked earlier. If the producer and the sink contract are both represented as Scala types, then field names, nesting, collection shapes, optionality, and defaulted fields are available to the compiler. Scala 3 makes this particularly practical because quoted reflection exposes `TypeRepr`, symbols, and type structure directly to macros. That gives us a concrete path to compute a normalized structural model at compile time and to reject incompatible producer-to-contract pairs before a pipeline ships. [Scala 3 reflection guide](https://docs.scala-lang.org/scala3/guides/macros/reflection.html)

Compile time, however, is not the whole story. Even if application code proves that a declared producer type conforms to a contract, the actual runtime schema can still drift at the data boundary. CSV, JSON, and other external sources are not obligated to respect the Scala types used in the pipeline code. This is where the sink boundary matters. The artifact in this paper therefore combines two checks: a compile-time proof that the declared producer type conforms to the target contract under an explicit policy, and a defensive runtime pin that validates the actual Spark `DataFrame` schema before write. The two layers solve different parts of the same failure mode: the first shifts structural drift detection left into compilation and CI, while the second protects the external boundary where runtime data can still violate assumptions.

This paper presents a small, deliberately narrow framework for that combination. The framework derives a normalized structural representation from Scala 3 case classes, materializes compile-time evidence `SchemaConforms[Out, Contract, P]` under an explicit policy family, derives Spark schemas from the same contract types, and enforces a policy-aware runtime comparator at the sink boundary. The runtime layer follows Spark's documented name-order comparison families where possible, but adds a deeper check for nested collection optionality because that behavior is not covered by Spark's built-in comparators and is a real source of drift in the artifact's tests. [Spark 3.5.1 DataType companion](https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/DataType%24.html)

The goal is not to claim a complete contract system for data engineering. The artifact is structural rather than semantic. It does not model business rules, temporal properties, cross-record invariants, or external schema-registry workflows. That limit matters. Prior work on software contracts shows both that contracts are useful in real systems and that the contract space is much richer than simple boundary shape checking; temporal and effectful contracts, for example, go well beyond the scope of this artifact. [Contracts in Practice](https://arxiv.org/abs/1211.4775), [Trace contracts](https://doi.org/10.1017/S0956796823000096), [Effectful Software Contracts](https://doi.org/10.1145/3632930)

Within that narrower scope, the paper makes three concrete contributions:

1. A Scala 3 macro derivation that computes normalized structural shapes for case-class schemas and rejects incompatible producer-to-contract pairs at compile time under an explicit, tested policy family.
2. A sink-boundary design that pairs compile-time proof with a policy-aware Spark runtime pin, including runtime checks for nested collection optionality that Spark ignores by default.
3. A reproducible artifact with direct compile-time tests, runtime tests, builder-path end-to-end tests, and saved overhead snapshots from local `macOS arm64` and GitHub-hosted `Ubuntu x86_64` environments.

The paper is therefore best read as a mechanism paper with an honest artifact, not as a production-effectiveness paper. What is proven here is that a focused class of structural drift checks can be moved from runtime failure toward compile-time proof and CI, while still retaining a runtime guard at the actual data boundary.

## 2. Background and model

### 2.1 Structural contracts in this paper

The term "data contract" is overloaded. In some systems it includes business semantics, quality thresholds, temporal guarantees, ownership, and operational policy. In this paper, a contract is narrower: it is the structural shape expected at a checked sink boundary. That shape includes field names, field order where the chosen policy cares about order, nesting through product types, sequence and map structure, field optionality, nested collection optionality, and whether a contract field has a default value. It does not include domain constraints such as valid ranges, allowed enum values, uniqueness across records, or temporal properties across events.

This choice is deliberate. The artifact is strongest where the compiler and the Spark schema model have a direct structural correspondence. Scala 3 macros can inspect case-class structure at compile time, and Spark represents row schemas using `StructType`, `StructField`, `ArrayType`, and `MapType`. That makes a focused structural contract model practical to derive and compare from both sides of the pipeline. [Scala 3 reflection guide](https://docs.scala-lang.org/scala3/guides/macros/reflection.html), [Spark StructType docs](https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/StructType.html)

The paper therefore uses the following working definition:

> A structural data contract is a typed description of the record shape expected at a checked boundary, together with a comparison policy that defines what counts as compatible producer-to-contract drift.

This framing leaves room for richer contract systems in future work while keeping the current claims honest.

### 2.2 Policy family

Compatibility is not a single relation. Different boundaries need different answers to "does this producer conform to that contract?" The artifact makes that choice explicit through a small policy family:

- `Exact` and `ExactUnorderedCI`: unordered matching by field name, case-insensitive, with no extras and no missing fields.
- `ExactOrdered`: ordered matching by field name, case-sensitive.
- `ExactOrderedCI`: ordered matching by field name, case-insensitive.
- `ExactByPosition`: by-position matching where field names are ignored.
- `Backward`: contract-by-name subset semantics. Producer extras are allowed; missing contract fields are allowed only when the contract field is optional or has a default value.
- `Forward`: producer-by-name subset semantics. Producer fields must all exist in the contract, while the contract may contain additional fields.
- `Full`: accept all structural combinations. This remains an escape hatch rather than a recommended policy.

These policies are useful precisely because they separate intent from implementation detail. A sink can declare that it wants exact position-sensitive matching, or backward-compatible matching with default relaxations, and the compile-time and runtime layers can then enforce the same declared intent at their respective boundaries.

### 2.3 Why compile-time proof and runtime pin both exist

Compile-time proof answers a question about declared program structure: given producer type `Out`, contract type `R`, and policy `P`, can the compiler prove that `Out` structurally conforms to `R` under `P`? That is a property of code. Runtime pinning answers a different question: does the actual Spark schema reaching the sink still satisfy the declared contract under the same policy? That is a property of runtime data.

The separation matters because data pipelines cross external boundaries. A typed source or transform can be declared against a case class, while the data actually read from files or tables may still drift independently. Compile-time proof cannot see those runtime schemas. Conversely, runtime validation alone comes too late to catch declared drift in the code that wires the pipeline. The artifact therefore treats the two checks as complementary rather than redundant.

Spark's documented comparator surface helps explain the runtime side. Spark exposes:

- `equalsIgnoreCaseAndNullability` for unordered name-based comparison with case-insensitive resolution and ignored field nullability,
- `equalsStructurally` for by-position comparison, and
- `equalsStructurallyByName` for ordered-by-name comparison with a supplied name resolver.

Those comparators are a good semantic baseline for exact-style runtime matching, but they are not enough for the artifact's full policy story. In particular, the artifact needs subset semantics for `Backward` and `Forward`, and it needs nested collection optionality checks for arrays and map values because those are not covered by Spark's default comparators. The runtime layer in this paper therefore follows Spark's documented comparison directions where appropriate and extends them only where the artifact requires stronger boundary checks. [Spark 3.5.1 DataType companion](https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/DataType%24.html)

### 2.4 Relationship to broader contract work

The model here is intentionally smaller than the broader software-contract literature. Empirical work shows that contracts matter in real software projects, while more recent work on effectful and trace-oriented contracts shows that the contract design space extends far beyond record shape compatibility. The right comparison for this paper is therefore not "have we solved contracts?" but "have we built and evidenced a useful compile-time plus runtime mechanism for a focused structural boundary problem?" [Contracts in Practice](https://arxiv.org/abs/1211.4775), [Effectful Software Contracts](https://doi.org/10.1145/3632930), [Trace contracts](https://doi.org/10.1017/S0956796823000096)

## 3. Framework design

### 3.1 Overview

The framework has one central design rule: the same contract type should drive both the compile-time proof path and the runtime sink pin. The compile-time path starts from Scala types and produces a structural witness. The runtime path starts from a Spark `DataFrame` schema and checks it against a `StructType` derived from the same contract type. The typed builder then fuses the two by requiring compile-time evidence at `addSink[R, P]` and by re-checking the actual schema before write.

That design is what prevents the framework from becoming only a macro demo or only a runtime checker. If the compile-time path is not tied to the actual sink boundary, the paper risks proving a property of declarations while ignoring the data path. If the runtime path is not tied back to the same contract type, the paper becomes a Spark schema-checking utility without a compile-time story. The artifact keeps those two sides connected through the contract type `R` and the chosen policy `P`.

### 3.2 Normalized structural model

The compile-time side needs an internal representation that is small enough to reason about and expressive enough to cover the supported shapes. The artifact uses a normalized `TypeShape` model with the following constructors:

- primitive shapes for supported atomic types,
- optional shapes,
- sequence shapes,
- map shapes with atomic keys,
- struct shapes, whose fields carry four pieces of information: field name, nested shape, whether the field has a default value, and whether the field is optional.

This representation is intentionally not user-facing. Its job is to normalize different Scala surface forms into one comparison model. Once two Scala types have been reduced to `TypeShape`, policy-specific comparison becomes a deterministic structural walk instead of ad hoc pattern matching over raw reflection nodes.

The important detail is that optionality is tracked in two places. Field-level optionality is part of a field's own metadata. Nested optionality inside sequences and maps is represented in the nested shape itself. That distinction is what later allows the runtime layer to preserve and compare nested collection optionality instead of flattening it away.

### 3.3 Compile-time evidence materialization

Scala 3 quoted reflection makes it possible to materialize compatibility evidence directly at the call site. The artifact defines a type class `SchemaConforms[Out, Contract, P]`. An inline given macro derives that witness by:

1. inspecting `Out` and `Contract` through `quotes.reflect`,
2. computing normalized shapes for both sides,
3. comparing them under the selected `SchemaPolicy`, and
4. either returning the witness or aborting compilation with a path-rich drift report.

The drift report is part of the design, not a side effect. If the framework only failed with a generic missing-given error, it would be much less useful in practice. The macro therefore records missing fields, extra fields, and mismatches with structural paths so that a failed compile points at the actual drift shape rather than only at the location where evidence was requested.

Policy-specific comparison is then expressed over normalized shapes. Exact-style policies require same-shape compatibility under different name and order sensitivities. `Backward` and `Forward` switch to subset relations. `Backward` additionally relaxes missing contract fields when the contract field is optional or defaulted. `Full` retains the derivation pipeline but accepts every comparison result.

### 3.4 Deriving runtime schemas from the same contract type

The runtime path begins by deriving a Spark `StructType` from the contract case class. This derivation mirrors the supported compile-time shape family: primitives map to Spark atomic types, nested case classes map to nested `StructType`, sequence-like containers map to `ArrayType`, maps with atomic keys map to `MapType`, and `Option[T]` affects field or nested nullability. The key design choice is that default-valued contract fields are recorded in metadata so that runtime subset policies can make the same allowance that compile time makes for `Backward`.

The result is a runtime contract schema that is not manually duplicated. The same Scala contract type `R` is used for both compile-time comparison and runtime schema derivation. That keeps the sink boundary honest and avoids the common failure mode where one contract exists in typed code and a second, loosely synchronized version exists in runtime configuration.

### 3.5 Policy-aware runtime comparison

At runtime the framework selects a `PolicyRuntime[P]` instance. The exact-style policies mirror Spark's documented comparator families:

- unordered-by-name exact matching,
- ordered-by-name exact matching, and
- by-position exact matching.

The artifact does not simply call Spark's built-in comparators and stop there. Instead, it implements a comparator that follows the same broad name-order directions while adding two behaviors that matter for the contract story:

1. nested collection optionality is checked explicitly for arrays and map values, and
2. `Backward` and `Forward` are implemented as real subset relations rather than being collapsed into equality.

This is the most important runtime design decision in the artifact. Without it, the framework would overclaim semantic parity between compile-time and runtime enforcement. With it, the runtime layer becomes a true boundary check for the policies the paper claims to support.

### 3.6 Typed builder fuse

The user-facing enforcement point is the typed builder. A sink is added through `addSink[R, P]`, and that call requires compile-time evidence that the current producer type conforms to contract `R` under policy `P`. If evidence cannot be derived, the pipeline does not compile. If evidence is derived, the built pipeline still performs the runtime schema pin before write.

This builder surface matters because it ties the proof obligation to the checked boundary itself. The compile-time witness is not a separate demonstration step that users can forget to wire in. It is part of the sink-construction path. The runtime pin is not an optional logging step after the fact. It is part of the write path. That fusion is what turns the artifact from a library of helpers into a coherent boundary-enforcement design.

### 3.7 Scope of the design

The framework design is deliberately small. It supports product-like case-class schemas, nested case classes, sequence-like containers, maps with atomic keys, field optionality, nested collection optionality, default-valued contract fields, and a focused policy family. It does not attempt to infer semantic validation rules, reason about temporal contracts, or integrate with external schema registries. Those limits should remain explicit in the manuscript because the strength of the paper comes from a tightly evidenced mechanism, not from pretending the artifact solves the entire contract problem.

## Source notes

Primary sources used for this draft:

- Scala 3 reflection guide: https://docs.scala-lang.org/scala3/guides/macros/reflection.html
- Spark `DataType` comparator docs: https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/DataType%24.html
- Spark `StructType` docs: https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/StructType.html
- Contracts in Practice: https://arxiv.org/abs/1211.4775
- Effectful Software Contracts: https://doi.org/10.1145/3632930
- Trace contracts: https://doi.org/10.1017/S0956796823000096

Repo anchors used while drafting:

- [ContractsCore.scala](../../src/main/scala/ctdc/ContractsCore.scala)
- [SparkCore.scala](../../src/main/scala/ctdc/SparkCore.scala)
- [SchemaConformsSpec.scala](../../src/test/scala/ctdc/SchemaConformsSpec.scala)
- [SparkSchemaSpec.scala](../../src/test/scala/ctdc/SparkSchemaSpec.scala)
- [SparkRuntimeSpec.scala](../../src/test/scala/ctdc/SparkRuntimeSpec.scala)
- [PipelineBuilderSpec.scala](../../src/test/scala/ctdc/PipelineBuilderSpec.scala)
