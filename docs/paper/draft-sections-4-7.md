# Draft sections 4-7

This file continues the prose-first manuscript draft from [draft-sections-1-3.md](draft-sections-1-3.md).
It stays inside the safe claim surface from [evidence-map.md](evidence-map.md), [ARTIFACT.md](../../ARTIFACT.md), and [industrial-evidence-pack.md](industrial-evidence-pack.md).

## 4. Artifact and evaluation

### 4.1 Artifact scope

The artifact evaluated in this paper is the `compile-time-data-contracts` repository, not the larger framework work that motivated it. The repo is deliberately small. It contains the compile-time policy model, the Scala 3 macro derivation for `SchemaConforms`, Spark schema derivation from contract types, a policy-aware runtime comparator, a typed builder path that enforces sink checks, and a reproducible benchmark harness. The evaluation is therefore a mechanism evaluation: it asks whether the artifact proves the structural properties it claims to prove, not whether it already delivers industrial-scale outcomes.

The safe evaluation claims are narrow:

- compile-time structural conformance is proven for the tested shapes and policies,
- runtime checking catches exact-style nested optionality drift that Spark ignores by default,
- runtime subset semantics for `Backward` and `Forward` are exercised directly,
- the builder path enforces compile-time and runtime checks at the sink boundary, and
- the benchmark harness reproduces across one local environment and one GitHub-hosted Ubuntu environment.

Claims outside that surface, such as developer-productivity gains or incident reduction, are intentionally excluded.

### 4.2 Compile-time proof coverage

Compile-time coverage is centered on direct witness generation and compile-fail behavior. The tests exercise positive conformance for exact-style, backward-compatible, and forward-compatible policies, and they exercise rejection paths for reordering, missing required fields, and nested optionality drift. The shape family covered by those tests includes nested case classes, sequences, maps, optional fields, and defaulted contract fields.

This is the right level of evidence for the compile-time story. The paper does not need to claim full language-level completeness. It needs to show that the artifact's supported shape family is real, policy-aware, and tested on both success and failure paths. The compile-fail cases also matter because they demonstrate that the artifact surfaces path-rich drift reports rather than only a generic missing-given failure.

### 4.3 Runtime policy coverage

The runtime layer is evaluated separately because it has a different job. The compile-time witness proves compatibility for declared Scala types. The runtime layer validates the actual Spark schema that reaches the sink. The runtime tests therefore focus on three questions:

1. Does the exact-style runtime pin reject nested collection optionality drift that Spark ignores by default?
2. Do `Backward` and `Forward` behave as true subset relations at runtime rather than collapsing back into equality?
3. Does the runtime comparator use metadata derived from the contract type to allow missing defaulted fields where the policy says that is acceptable?

The current tests answer all three in the supported shape family. This is especially important for `Backward` and `Forward`, because the clean artifact now makes those semantics explicit instead of leaving them as documentation-level intentions.

### 4.4 Builder-path end-to-end checks

The builder-path tests are the most operational part of the artifact evaluation. They show that the paper is not only about a macro witness in isolation. `PipelineBuilder.addSink[R, P]` requires compile-time evidence for the current producer type, and the built pipeline still validates the actual `DataFrame` schema before write. The end-to-end tests cover:

- a compile-time rejection at the sink when the declared producer type drifts,
- a green path where the declared transform output and the runtime schema both satisfy the sink policy,
- a red path where the declared transform type passes compile time but the actual runtime schema violates the sink policy, and
- no-transform `Backward` and `Forward` paths that exercise runtime subset semantics directly.

These tests are the strongest answer to the question, "is the proof path tied to the real data path?" For the clean artifact, the answer is yes.

### 4.5 Benchmark harness and saved snapshots

The benchmark harness is intentionally small. It measures compile-time witness-generation overhead on synthetic but representative schemas and runtime comparator overhead on nested `StructType` comparisons. It does not claim end-to-end Spark job performance.

The saved snapshots show:

- compile-time delta on local `macOS arm64`: `+0.270s`, `+0.397s`, and `+0.513s` for `10`, `25`, and `50` schema pairs,
- compile-time delta on GitHub-hosted `Ubuntu x86_64`: `+0.847s`, `+1.000s`, and `+1.880s` for the same sizes,
- runtime exact unordered matching in the low-microsecond range per schema comparison on both environments, and
- runtime exact-by-position matching below microsecond scale on both environments.

The right interpretation is limited but useful: the harness reproduces across two environments and shows that the mechanism is measurable and stable enough to package as artifact evidence. The wrong interpretation would be a broad claim about low overhead across machines or organizations. The paper should state that limit explicitly.

## 5. Industrial context and lessons

The larger FlowForge effort is useful in this paper only as industrial context. It informed the motivation, the application-side API shape, and the cautionary lessons that led to the clean artifact split. It does not serve as proof for the artifact's closed claims.

Two lessons matter most. First, typed examples can drift away from the real execution path surprisingly easily. In FlowForge's template and advanced sample code, the typed source story is compelling, but the actual written dataset path is not always the same path as the value used to satisfy the compile-time witness. That is exactly the kind of gap a paper can accidentally overclaim if it treats demos as evidence. Second, framework narratives naturally pull in connectors, evolution, observability, and product positioning long before the enforcement path is fully closed. The ground-reality report in FlowForge already shows that problem clearly.

That is why the paper evaluates the small artifact directly. FlowForge still contributes value here, but the value is different: it shows why the problem matters, where typed pipeline design becomes operationally interesting, and how easy it is for a framework story to get ahead of the actual checked data path. Those are useful lessons for Section 5. They are not substitutes for the direct proof in Sections 3 and 4.

## 6. Related work

This paper sits at the intersection of software contracts, static-versus-dynamic checking, and typed data-pipeline boundaries. The closest background sources for the implementation itself are the Scala 3 reflection documentation and the Spark schema-comparator APIs, because the artifact is built directly on those mechanisms. Those sources are implementation background, not related-work contributions in the research sense. [Scala 3 reflection guide](https://docs.scala-lang.org/scala3/guides/macros/reflection.html), [Spark `DataType` docs](https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/DataType%24.html)

The broader contract literature provides the right contrast. Empirical work such as *Contracts in Practice* shows that contract disciplines remain relevant in real software, which supports the general motivation for making contract intent explicit. More expressive systems such as *Effectful Software Contracts* and *Trace contracts* show how much richer the contract space can become when effects, histories, or traces are part of the property being checked. By comparison, this artifact is much narrower: it checks only structural compatibility at a typed boundary. That narrowness is not a defect in the paper as long as it is stated plainly. [Contracts in Practice](https://arxiv.org/abs/1211.4775), [Effectful Software Contracts](https://doi.org/10.1145/3632930), [Trace contracts](https://doi.org/10.1017/S0956796823000096)

There is also a useful static-versus-dynamic comparison point. *Dynamic Contract Analysis for Parallel Programming Models* treats dynamic contracts as complementary to static analysis rather than as a complete replacement for it. That general lesson aligns with this paper's design choice: compile-time proof and runtime checking do different jobs and should be combined at the boundary instead of being presented as competitors. [Dynamic Contract Analysis for Parallel Programming Models](https://arxiv.org/abs/2603.03023)

Finally, work outside the JVM ecosystem also helps position the contribution. P3317R0 argues for contracts that can often be resolved at compile time so that runtime cost can be reduced or removed. The artifact here is not a direct analogue of that C++ proposal, but it shares the same directional idea: when part of a contract question is structural and visible to the compiler, deferring everything to runtime is unnecessary. [Compile time resolved contracts (P3317R0)](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2024/p3317r0.pdf)

## 7. Limitations

The first limitation is scope. The artifact checks structural compatibility, not business semantics. It can prove that two typed schemas match under a policy, but it does not know whether an `age` field must be positive, whether a `currency` field must come from an approved domain, or whether two records must satisfy a temporal or cross-row invariant. Those are different kinds of contract problems.

The second limitation is supported-shape breadth. The artifact intentionally focuses on product-like case-class schemas, nested products, sequence-like containers, maps with atomic keys, optional fields, nested collection optionality, and defaulted contract fields. That is enough for the mechanism paper, but it is not a full schema system for all Spark or Scala data representations.

The third limitation is ecosystem reach. There is no external schema-registry integration, no industrial deployment pack in the clean repo, and no measured user-study evidence about productivity or usability. The builder API may be teachable, but the paper should not convert that into a measured productivity claim.

The fourth limitation is evaluation breadth. The benchmark results come from two saved snapshots on one local machine and one GitHub-hosted Ubuntu runner. They show reproducibility of the harness, not a stable cross-machine baseline. The runtime numbers are micro-bench results for schema comparison, not end-to-end Spark job measurements.

The final limitation is the role of FlowForge. FlowForge informed the motivation, application-side realism, and overclaim risk analysis, but it does not serve as proof for the clean artifact's closed claims. That separation is a strength for honesty, but it also means the paper stops short of industrial-effectiveness claims for now.
