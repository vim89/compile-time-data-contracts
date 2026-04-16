# Paper outline

This outline is constrained by the closed artifact claims only.

## Positioning

- Paper type: systems/design preprint with a small artifact and explicit limits
- Core audience: PL-adjacent Scala engineers, JVM runtime researchers, and data-systems engineers
- Primary story: move schema mismatch detection from runtime job execution toward compile time and CI
- Secondary story: keep a defensive runtime pin because external data boundaries still matter

## Format note

- For a workshop-compatible draft, use `acmart` with the `sigplan` option, not `sigconf`.
- MPLR 2026 is already missed, so the immediate target is the preprint.
- The next peer-review paths are MPLR 2027 or industrial tracks such as VLDB / ICDE 2027 after adaptation.

## Working title options

1. Shift schema drift left: policy-aware compile-time contracts for typed JVM and Spark pipelines
2. Catch schema drift before runtime: compile-time structural data contracts in Scala 3
3. Enforce sink contracts earlier: compile-time structural compatibility for typed Spark pipelines
4. Make contract drift a compile error: a Scala 3 macro framework for typed data pipelines
5. Tie contract proof to the sink boundary: compile-time and runtime checks for typed Spark pipelines

## Abstract skeleton

Sentence 1:
State the practical problem: schema drift and boundary mismatches are often found late in data pipelines.

Sentence 2:
State the technical idea: derive structural shapes from Scala 3 case classes and prove producer-to-contract compatibility under explicit policies at compile time.

Sentence 3:
State the runtime complement: derive Spark schemas from the same type model and enforce a policy-aware runtime pin at the sink boundary.

Sentence 4:
State the artifact scope: small typed pipeline builder, readable compile-time drift diagnostics, policy-aware runtime subset checks, reproducible benchmark harness.

Sentence 5:
State what the evaluation actually covers: compile-time proof coverage, runtime policy behavior, and saved local plus CI overhead snapshots.

Sentence 6:
State the limit: this artifact proves the mechanism and evidence path, not industrial scale or incident reduction.

## Contribution bullets

Use only these three as headline contributions:

1. A Scala 3 macro derivation that computes normalized structural shapes and rejects incompatible producer-to-contract pairs under a tested policy set.
2. A sink-boundary design that combines compile-time contract proof with a policy-aware Spark runtime pin, including behavior that Spark ignores by default for nested collection optionality.
3. A reproducible artifact with direct compile-time tests, runtime tests, builder-path tests, and two saved overhead snapshots across local and CI environments.

Optional fourth bullet, only if needed:

4. A separation-of-concerns lesson from FlowForge: framework stories become misleading unless the typed proof path is tied to the actual data path.

## Section plan

### 1. Introduction

Goal:
- make the late-drift problem concrete
- explain why ordinary runtime checks and CI do not fully solve the boundary problem
- position the paper as a small honest mechanism paper, not a complete industrial platform paper

Must include:
- one concrete practitioner problem statement
- the compile-time plus runtime boundary idea
- a short preview of the closed claims

Must not include:
- incident-reduction numbers unless separately proven
- broad claims about all data quality problems

### 2. Background and model

Goal:
- define what a structural data contract means here
- define the policy family precisely
- explain why compile-time proof and runtime pin both exist

Subsections:
- structural contracts vs semantic or temporal contracts
- policy definitions:
  `Exact`, `ExactOrdered`, `ExactOrderedCI`, `ExactByPosition`, `Backward`, `Forward`, `Full`
- Scala 3 reflection model used by the artifact
- Spark runtime comparator model and where it is insufficient

### 3. Framework design

Goal:
- explain the actual mechanism, not just the UX

Subsections:
- normalized `TypeShape`
- policy-driven comparison
- compile-time evidence materialization with `SchemaConforms`
- Spark schema derivation from the same contract type
- runtime comparator and subset semantics
- typed builder path and sink-boundary fuse

### 4. Artifact and evaluation

Goal:
- prove what the repo proves

Subsections:
- compile-time proof coverage
- runtime policy coverage
- builder-path end-to-end checks
- benchmark harness and saved snapshots

Evaluation claims allowed:
- compile-time conformance is proven for the tested shapes and policies
- runtime pin catches nested optionality drift that Spark ignores
- subset semantics for `Backward` and `Forward` are exercised end to end
- the harness reproduces on local `macOS arm64` and GitHub-hosted `Ubuntu x86_64`

Evaluation claims not allowed:
- generalized low-overhead claim
- industrial-scale throughput claim
- user-study productivity claim

### 5. Industrial context and lessons

Goal:
- use FlowForge carefully

What goes here:
- why the clean artifact was split out
- how framework/product demos can drift away from the real data path
- what the application-side examples taught about motivation, edge cases, and overclaim risk

What does not go here:
- closed artifact proof
- industrial performance claims

### 6. Related work

Buckets:
- language-level and software-contract systems
- empirical contract practice
- effectful and trace-oriented contract work
- dynamic contract analysis
- compile-time contract directions in other languages

### 7. Limitations

Must say clearly:
- structural rather than semantic contracts
- no schema registry integration
- no temporal or cross-record constraints
- no measured developer-productivity evidence
- no industrial metrics proven by this repo
- benchmark results are two scoped snapshots, not a cross-machine baseline

### 8. Conclusion

Close with:
- what is proven
- why the compile-time plus sink-boundary combination matters
- where the work could grow next without overstating today’s result

## Artifact section notes

- Link to the public repo.
- Point to `ARTIFACT.md` as the writing-time source of truth.
- State exact supported shape family.
- State the supported policy family.
- State the benchmark scope and saved environments.

## Limitations section notes

Put these in full sentences, not in footnotes:

- This artifact checks structural compatibility, not business semantics.
- Runtime checking exists because external data boundaries can still violate assumptions after compile time.
- The FlowForge material informs motivation and caveats, but does not serve as artifact proof.

## Drafting order

1. Section 2
2. Section 3
3. Section 4
4. Section 1
5. Section 6
6. Section 7
7. Section 8
8. Section 5

This order keeps the prose tied to closed claims first.

## Overleaf note

I cannot create the actual Overleaf project from this repo without account access.
The right next manual action is:

1. open the official ACM proceedings template in Overleaf
2. switch the document class option to `sigplan`
3. create sections matching this outline
4. paste the abstract skeleton and contribution bullets first
