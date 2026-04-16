# Paper evidence map

This file is the writing-time contract for Paper A.

Use it before changing the abstract, contribution bullets, evaluation claims, venue targeting notes, or outreach copy.

Rule: if a claim is not safe here, do not write it as already proven.

## Current paper posture

- Artifact proof repo: [`compile-time-data-contracts`](../../ARTIFACT.md)
- Industrial context repo: `flowforge`
- Preprint posture: systems/design paper first
- Industrial extension: only if a separate safe evidence pack is ready

## Official source notes that change planning

- MPLR 2026 is already closed.
  Official CFP and dates: abstract deadline `2026-02-27`, paper deadline `2026-03-06`, notification `2026-04-15`.
  Source: https://2026.ecoop.org/home/mplr-2026
- MPLR uses ACM `sigplan`, not `sigconf`.
  The official instructions say to use `acmart` with the `sigplan` option and explicitly say not to use `sigconf`.
  Source: https://2026.ecoop.org/home/mplr-2026
- VLDB 2026 industrial track allows up to 12 pages excluding references and requires at least one non-academic affiliation.
  Source: https://www.vldb.org/2026/call-for-industrial-track.html
- ICDE 2026 industry and application track allows short 6-page and long 12-page papers plus references, uses IEEE format, and requires at least one author with a non-academic affiliation.
  Source: https://icde2026.github.io/cf-industry-track.html
- arXiv assigns the final identifier only when the work is announced.
  Source: https://info.arxiv.org/help/availability.html
- Zenodo lets you reserve a DOI before publication, and Zenodo records are indexed into OpenAIRE.
  Sources:
  https://help.zenodo.org/docs/deposit/describe-records/reserve-doi/
  https://support.zenodo.org/help/en-gb/18-general/169-what-is-openaire

## Safe claims

| ID | Safe wording | Exact evidence |
|----|--------------|----------------|
| C1 | The artifact proves compile-time structural conformance between producer and contract case classes for a focused set of policies and nested shapes. | Claim ledger: [ARTIFACT.md](../../ARTIFACT.md). Core derivation: [ContractsCore.scala](../../src/main/scala/ctdc/ContractsCore.scala). Positive and negative proof tests: [SchemaConformsSpec.scala](../../src/test/scala/ctdc/SchemaConformsSpec.scala). |
| C2 | Compile-time failures surface readable, path-rich drift diagnostics rather than only a generic missing-given error. | [ARTIFACT.md](../../ARTIFACT.md), [ContractsCore.scala](../../src/main/scala/ctdc/ContractsCore.scala), negative checks in [SchemaConformsSpec.scala](../../src/test/scala/ctdc/SchemaConformsSpec.scala). |
| C3 | The artifact derives Spark schemas from the same type model and preserves field optionality plus nested collection optionality for supported shapes. | [SparkCore.scala](../../src/main/scala/ctdc/SparkCore.scala), [SparkSchemaSpec.scala](../../src/test/scala/ctdc/SparkSchemaSpec.scala). |
| C4 | The runtime pin catches exact-style schema drift that Spark ignores by default for nested array and map optionality. | [SparkCore.scala](../../src/main/scala/ctdc/SparkCore.scala), [SparkRuntimeSpec.scala](../../src/test/scala/ctdc/SparkRuntimeSpec.scala). |
| C5 | The sink boundary combines compile-time proof and runtime validation before write on the typed builder path. | Builder path: [SparkCore.scala](../../src/main/scala/ctdc/SparkCore.scala). End-to-end tests: [PipelineBuilderSpec.scala](../../src/test/scala/ctdc/PipelineBuilderSpec.scala). |
| C6 | The runtime layer implements policy-aware subset semantics for `Backward` and `Forward`, using optional and default metadata derived from the contract type. | [SparkCore.scala](../../src/main/scala/ctdc/SparkCore.scala), [SparkSchemaSpec.scala](../../src/test/scala/ctdc/SparkSchemaSpec.scala), [SparkRuntimeSpec.scala](../../src/test/scala/ctdc/SparkRuntimeSpec.scala), [PipelineBuilderSpec.scala](../../src/test/scala/ctdc/PipelineBuilderSpec.scala). |
| C7 | The artifact includes a reproducible harness for compile-time and runtime overhead with saved evidence from local `macOS arm64` and GitHub-hosted `Ubuntu x86_64`. | Harness: [benchmarks/run-benchmarks.sh](../../benchmarks/run-benchmarks.sh), [benchmarks/compare-results.sh](../../benchmarks/compare-results.sh), [RuntimeSchemaBenchmark.scala](../../src/main/scala/ctdc/bench/RuntimeSchemaBenchmark.scala). Saved runs: [local summary](../../benchmarks/results/2026-04-15-local/summary.md), [Ubuntu summary](../../benchmarks/results/2026-04-15-gha-ubuntu-latest/summary.md), [comparison](../../benchmarks/results/2026-04-15-cross-env-comparison.md). |

## Narrow claims only

These are safe only in the narrow form shown here:

- “Usable API” means the README path and typed builder path are teachable and reproducible.
  It does not mean measured productivity improvement.
- “Runtime mirror” means policy-aware runtime checks at the sink boundary.
  It does not mean Spark's built-in comparators already provide the same semantics.
- “Benchmark evidence” means reproducible local plus CI snapshots.
  It does not mean a broad or stable cross-machine performance claim.

## Non-claims

Do not write any of these as already proven by this repo:

- measured developer-productivity gains
- cross-team adoption
- incident reduction in production
- industrial deployment scale
- external schema registry integration
- semantic contracts such as ranges or business rules
- temporal or cross-record contracts
- end-to-end Spark job performance improvements
- stable general overhead claims across machines or organizations

## Exact test anchors to mention in prose

- Compile-time acceptance:
  `Exact accepts unordered case-insensitive field names and ignores nullability`
  `Backward accepts extra producer fields and missing optional/default contract fields`
  `Forward accepts a producer subset of the contract schema`
- Compile-time rejection:
  `ExactOrdered rejects reordered fields with an indexed path in the error`
  `Backward rejects missing required fields with a readable field path`
  `Exact rejects nested optionality drift in sequences`
  `Exact rejects nested optionality drift in map values`
- Runtime acceptance/rejection:
  `PolicyRuntime Backward accepts producer extras and missing optional or defaulted contract fields`
  `PolicyRuntime Forward accepts a producer subset of the contract schema`
  `PolicyRuntime Exact rejects nested optionality drift in arrays and maps`
- End-to-end builder path:
  `PipelineBuilder addSink surfaces compile-time contract drift`
  `PipelineBuilder runtime sink pin rejects a transform that violates the chosen sink policy`

## Writing rules

- Base the abstract and introduction on C1-C5 first.
- Use C6 as a concrete strengthening point, not as the paper's main headline.
- Use C7 as scoped evidence, not as a big performance claim.
- Treat FlowForge as motivation and industrial context only, unless a point is explicitly copied into a separate reviewable evidence pack.
- If a sentence needs the phrase “in production”, “across teams”, or “reduced incidents”, stop and re-check the industrial evidence pack first.

## Venue-fit notes

- Preprint: free to use the strongest honest shape now.
- Workshop-compatible manuscript: use ACM `sigplan` style if you want MPLR compatibility later.
- Industrial-track adaptation later:
  the current artifact supports the technical core, but the industrial narrative still needs a separate safe evidence pack.

Inference:
- `Independent Researcher` is clearly non-academic, but it is still worth emailing VLDB and ICDE industrial-track chairs before assuming the affiliation rule is satisfied exactly as intended by the track.
