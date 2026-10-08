---
title: 'Three carriers, one bit: compile-time data contracts for Scala and Spark schema equality'
tags:
  - Scala
  - Apache Spark
  - data contracts
  - schema evolution
  - compile-time verification
  - macros
authors:
  - name: Vittal Mirji
    orcid: 0009-0005-3376-0457
    affiliation: 1
affiliations:
  - name: Independent researcher, Stuttgart, Germany
    index: 1
date: 8 October 2026
bibliography: paper.bib
---

# Summary

A data pipeline breaks when the shape of its input or output changes and nothing notices until
the job runs. The usual defence compares two schemas at the boundary, at runtime, after the job
has already started. `compile-time-data-contracts` (ctdc) is a Scala library that moves that
comparison into the compiler. The author declares a contract as an ordinary Scala data type. A
macro walks the producing type and the contract type, computes a normalised deep structural
shape of each, compares the two under a named compatibility policy, and stops the build with a
field-path diff when they do not conform. The typed pipeline builder demands that evidence
before it will connect an output, so a pipeline whose output no longer matches its declared
contract fails to compile rather than failing at midnight.

The library has two halves, answering different questions. `ctdc-core` carries no Apache Spark
dependency and is cross-built for Scala 2.13 and Scala 3; it decides conformance against the
Scala types, where whether a field may be absent is still stated. `ctdc-spark` pins the same
policy at the output against a live Spark schema, and adds an opt-in row-level check for the
question a schema comparison can no longer answer once a file reader has produced the schema.
The intended users are researchers who need reproducible data pipelines and engineers
maintaining long-lived Spark jobs.

# Statement of need

Two ordinary pipeline requirements are enough to separate the comparison predicates a pipeline
would otherwise rely on. First, a pair of schemas differing only in field order describes the
same data and must be accepted. Second, a producer whose field is optional, against a contract
whose field is required, is drift and must be rejected.

Apache Spark exposes a family of schema-equality predicates [@spark_datatype;
@spark_structtype] whose members disagree about three separate carriers of optionality:
`StructField.nullable`, `ArrayType.containsNull` and `MapType.valueContainsNull`. To find out
which member satisfies the two requirements, the repository's harnesses compare four predicate
owners (Spark's shipped configurations, the ctdc runtime pin, the ctdc policy engine, and Avro
writer/reader resolution as an external baseline) over a 2668-cell matrix of schema pairs, with
the saved runs checked in. All ten configurations Spark 3.5.6 ships fail the first requirement,
because every comparator zips the two field lists positionally; six of the ten also fail the
second. Choosing a stricter member of the family does not trade one failure for the other, so an
author who wants both requirements met cannot get there by configuring Spark. Avro writer/reader
resolution [@avro_spec; @avro_compatibility] satisfies both, but only after a Spark schema has
been converted to an Avro schema, so what it decides is the converter's output; it is also the
only owner in the matrix that returns no verdict at all on some cells, on twelve of its own one
hundred and sixteen.

ctdc closes that gap, and closes it before a cluster is paid for.

# State of the field

Typed Spark wrappers raise the level at which a pipeline is written. Frameless [@frameless]
gives a typed `Dataset` API with compile-time column checking, and iskra [@iskra] gives typed
`DataFrame` operations on Scala 3. Both check the operations a pipeline performs; neither states
a contract that a producing type must conform to under a named compatibility policy. Chimney
[@chimney] derives transformations between case classes and reports at compile time when none
exists, but its subject is the transformation rather than a compatibility direction.

Deequ [@deequ] and Great Expectations [@great_expectations] are complementary: they check
values rather than declared shape, and only once the data exists. Table formats enforce their
own evolution rules on write [@delta_lake; @apache_iceberg], which covers the table boundary and
not the in-process boundary between a Scala type and the frame being written. Confluent Schema
Registry [@avro_compatibility] is the closest analogue in spirit, and ctdc borrows its
vocabulary of compatibility directions deliberately, with one correction: a policy named `Full`
there means compatible in both directions and is the strictest setting, so the ctdc policy that
compares nothing is named `Unchecked`.

Contributing the mechanism to one of those projects was considered and rejected on boundary
grounds. The decision ctdc makes is about two Scala types and happens before any of those
libraries has a runtime; Frameless and iskra own the operation, Deequ and Great Expectations own
the values, and the table formats own the table. A conformance check placed inside any of them
would reach only pipelines already written against it, so the reusable part, `ctdc-core`, is
kept free of all those dependencies, including Spark.

# Software design

Four decisions shape the library, and each gave something up.

The first is where to compare. The repository's measurement also establishes a negative result
about the runtime option: Spark's readers return the permissive value for each of the three
carriers on every format that does not record the claim, so a read schema cannot be
distinguished from one whose producer actually stated that nulls are possible. Reading
`{"tags":["a","b"]}` as `case class Nested(tags: List[String])` fails a strict carrier
comparison, because the JSON reader reports `containsNull = true` for every array. Each
candidate runtime rule was tried and eliminated: requiring agreement fails every CSV or JSON
pipeline, allowing only strictness rejects exactly the common case, and inverting the direction
leaves a check that can never fire. The information is gone before a `DataFrame` exists. ctdc
therefore checks optionality at compile time, where it is still stated, and asks the carriers'
question of the rows when a runtime answer is wanted. The cost is two stages instead of one, and
a row-level check that is opt-in because it is not free.

The second is how policies are represented. Nine policies are singleton types (`Exact`,
`ExactUnordered`, `ExactUnorderedCI`, `ExactOrdered`, `ExactOrderedCI`, `ExactByPosition`,
`Backward`, `Forward`, `Unchecked`), and `ComparisonRules` decomposes them into four independent
axes rather than nine hand-written comparators. One axis, `Optionality`, governs all three
carriers through a single shared check, so a policy means the same thing whether fields are
matched by name or by position and whether the carrier sits on a field, a sequence element or a
map value. This is the design response to what the measurement found: a family whose members
disagree about the carriers is a family where one shared `Boolean` was asked to name three
different things.

The third is that the decision rules are version-agnostic Scala, and only shape extraction is
written twice, with quotes reflection on Scala 3 [@scala3_reflection] and `scala.reflect.macros`
on 2.13. Two macro front ends are a maintenance cost paid deliberately: it buys a core that
works on both Scala versions still in production use, and their parity is tested rather than
assumed.

The fourth is that the evidence of conformance is a marker trait with no members. Conformance
costs nothing at runtime and nothing at the call site, but an instance can also be written by
hand, which asserts conformance rather than checking it. The guarantee is therefore stated
narrowly: it holds for macro-derived evidence reaching an output through the checked API. Making
the evidence unforgeable was considered and would have required a sealed abstract member and a
privileged constructor, which closes the type to the extension points the policy axes exist to
support.

# Research impact statement

The software is used in the author's own research and the repository is the reproducible
material for it: the 2668-cell matrix, the harnesses that produced it, and the saved runs are
checked in and regenerable by documented commands. A companion paper developing the carrier
analysis in full draws every number from those runs.

The evidence a reader can check without taking the author's word for it is organised as a claim
ledger. Every claim the project makes is listed with the test or saved run that backs it in
`ARTIFACT.md`; a claim not marked `closed` is explicitly not something the project proves. The
machine-readable form of that table is generated from `ARTIFACT.md` rather than written beside
it, is pinned to the commit it was produced at, and refuses to emit if any cited evidence path
no longer exists. Continuous integration runs that generator on every pull request, so a claim
cannot keep reading as settled after the file behind it has moved. A number in the paper can be
traced to the commit of the comparison engine it belongs to.

Measured overhead is 0.37 to 0.83 seconds of additional compile time for 10 to 50 contract
pairs, and 115 to 7412 nanoseconds per runtime comparison, from saved benchmark runs on a single
machine and reported as such. Readiness for use beyond the author rests on the published
cross-built artifacts, a source build needing no credentials, 234 tests under continuous
integration, and stated contribution and support channels. No claim is made of adoption by
other groups, of citations, or of reduced production incidents; none has happened yet.

# AI usage disclosure

Generative AI was used in the preparation of this project, within the limits stated here.

Tools: Anthropic Claude models, accessed through the Claude Code command-line tool.

Where it was used. Literature and documentation search; review of and feedback on work the
author had already written; and drafting and copy-editing of prose in the documentation and in
this paper.

Where it was not used. The implementation, the architecture and the engineering decisions are
the author's. The problem framing, the choice of the three carriers as the object of study, the
two-stage design, the decomposition of policies into independent axes, the elimination of each
candidate runtime rule, and the decision of what the project does and does not claim were made
by the author. No design or implementation decision in this software originated from a model.

Verification: the author reviewed and accepted every line in the repository. No measured number
in this paper or in `ARTIFACT.md` is taken from model output. Each is produced by a harness that
is checked in and rerunnable, recorded in a saved run, and pinned to the commit of the engine
that produced it by the claim ledger described above, which continuous integration regenerates
on every pull request. Correctness of behaviour is held by the 234-test suite, which includes
tests that pin the negative results so a later change cannot quietly reverse them.

# Acknowledgements

This work received no funding and the author declares no competing interests. The Apache Spark
source and issue tracker were used as primary evidence for the behaviour of the predicate
family; the imprecise documentation of `MapType.valueContainsNull` that the carrier analysis
turns on was corrected upstream by the Spark project in September 2025 [@spark_53717].

# References
