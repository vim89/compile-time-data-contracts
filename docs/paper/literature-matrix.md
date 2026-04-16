# Literature matrix

Primary-source-only working matrix for the paper.

Use this to decide what belongs in Section 2 and Section 6.

## Reading rule

- Prefer original papers, official documentation, or official venue pages.
- Do not cite blogs, summaries, or vendor explainers when a primary source exists.

## Matrix

| Bucket | Primary source | What it gives | How to use it in the paper | Caution |
|--------|----------------|---------------|----------------------------|---------|
| Scala 3 macro mechanism | Scala 3 reflection docs: https://docs.scala-lang.org/scala3/guides/macros/reflection.html | Official explanation of `quotes.reflect`, `TypeRepr`, symbols, and macro reflection structure | Background for implementation details, not as related-work research | This is technical documentation, not a research contribution citation |
| Spark runtime semantics | Spark 3.5 DataType comparator docs: https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/DataType$.html | Official comparator API surface: unordered ignore-case comparison, structural by-position comparison, and structural by-name comparison | Background for the runtime pin design and why a custom comparator is needed | Cite carefully; comparator docs do not claim nested collection optionality checking |
| Compile-time contracts direction | Jonas Persson, *Compile time resolved contracts* (P3317R0), WG21, 2024: https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2024/p3317r0.pdf | Argues for designing contracts so that many checks can be resolved at compile time and runtime cost can be elided | Related-work contrast: contract systems moving toward compile-time resolution in another language ecosystem | C++ proposal context is not directly about typed Spark/JVM pipelines |
| Empirical contract practice | Estler et al., *Contracts in Practice*, arXiv:1211.4775: https://arxiv.org/abs/1211.4775 | Empirical evidence that contracts are used in real projects and remain stable relative to implementations | Related-work anchor for why contracts matter in practice | Not about data contracts or compile-time schema compatibility |
| Effectful contracts | Moy, Dimoulas, Felleisen, *Effectful Software Contracts*, PACMPL POPL 2024, DOI: https://doi.org/10.1145/3632930 | Shows contract systems extending beyond pure functional properties into effectful settings | Related-work contrast for contract expressiveness and semantics | Different host language model and research objective |
| Trace and temporal contracts | Moy and Felleisen, *Trace contracts*, JFP 2023: https://www.cambridge.org/core/journals/journal-of-functional-programming/article/trace-contracts/4AF1C7361751839FF7E2DEBC65A050EE | Contracts over sequences of values across calls, not just single boundary shapes | Limitations contrast: our artifact does structural shape compatibility, not temporal or cross-call contracts | Much richer expressiveness than this artifact currently targets |
| Dynamic contract analysis | Oraji, Hück, Bischof, *Dynamic Contract Analysis for Parallel Programming Models*, arXiv:2603.03023: https://arxiv.org/abs/2603.03023 | Shows reuse of a contract language at runtime and positions dynamic checking as complementary to static analysis | Related-work point for static plus dynamic verification combinations | HPC and parallel-programming context, not JVM/Spark data pipelines |
| Open-science infrastructure | European Commission Open Science portal: https://research-and-innovation.ec.europa.eu/open-science | Official EU open-science context for preprints, repositories, reproducibility, and FAIR practices | Publication strategy and motivation for Zenodo/OpenAIRE discoverability | This is infrastructure context, not technical related work |

## Likely Section 2 use

Use these for background and problem framing:

- Scala 3 reflection docs
- Spark comparator docs
- Contracts in Practice

## Likely Section 6 use

Use these for related work contrast:

- P3317R0
- Contracts in Practice
- Effectful Software Contracts
- Trace contracts
- Dynamic Contract Analysis for Parallel Programming Models

## Key relationship to this artifact

- This artifact is strongest on compile-time structural compatibility for typed case-class schemas.
- It is weaker on semantic, temporal, and effectful contract expressiveness.
- The paper should say that clearly instead of pretending to solve the whole contract space.

## Open reading gaps

Still worth checking before drafting Section 6:

1. whether there is a strong primary paper specifically on data contracts in data engineering rather than general software contracts
2. whether there is a primary paper on schema-evolution compatibility that would sharpen the contrast with `Backward` and `Forward`
3. whether there is a stronger primary source for industrial contract systems on the JVM
