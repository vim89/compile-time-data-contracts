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
the job runs. The usual defence is a comparison of two schemas at the boundary, at runtime,
after the job has already started. `compile-time-data-contracts` (ctdc) moves that comparison
into the compiler. A macro walks a producer case class and a contract case class, computes a
normalised deep structural shape of each, compares the two under a named policy, and aborts
compilation with a field-path diff when they do not conform. The typed pipeline builder
requires that evidence before it will wire a sink, so a pipeline whose output no longer matches
its declared contract fails to build rather than failing at midnight.

Two halves answer different questions. `ctdc-core` (no Spark dependency, cross-built for Scala
2.13 and Scala 3) decides conformance against the Scala types, where optionality is still
stated. `ctdc-spark` pins the same policy at the sink against a live `StructType`, and adds an
opt-in row-level check for the question a schema comparison can no longer answer once a reader
has produced the schema.

ctdc also carries a measurement of what the existing runtime defence decides. Apache Spark
exposes a family of schema-equality predicates [@spark_datatype; @spark_structtype] whose
members disagree about three separate carriers of optionality: `StructField.nullable`,
`ArrayType.containsNull`, and `MapType.valueContainsNull`. The repository contains a 2668-cell
comparison matrix over four predicate owners (Spark's shipped configurations, the ctdc runtime
pin, the ctdc policy engine, and Avro writer/reader resolution as an external baseline), with
the saved runs and the harnesses that produced them.

# Statement of need

Two ordinary pipeline requirements are enough to separate these predicates. First, a pair of
schemas differing only in field order describes the same data and must be accepted. Second, a
producer whose field is optional, against a contract whose field is required, is drift and must
be rejected.

All ten schema-equality configurations Spark 3.5.6 ships fail the first requirement, because
every comparator zips the two field lists positionally; six of the ten also fail the second.
Choosing a stricter member of the family does not trade one failure for the other, so an author
who wants both requirements met cannot get there by configuring Spark. Avro writer/reader
resolution [@avro_spec; @avro_compatibility] satisfies both, but only after a `StructType` has
been converted to an Avro schema, so what it decides is the converter's output; it is also the
only owner in the matrix that returns no verdict at all on some cells, on twelve of its own
one hundred and sixteen.

ctdc is split across two stages rather than being a better runtime comparator for a second,
less visible reason. Spark's readers return the permissive value for each of the three carriers
on every format that does not record the claim, so a read schema cannot be distinguished from
one whose producer actually stated that nulls are possible. Reading
`{"tags":["a","b"]}` as `case class Nested(tags: List[String])` fails a strict carrier
comparison, because the JSON reader reports `containsNull = true` for every array. Each
candidate runtime rule was tried and eliminated: requiring agreement fails every CSV or JSON
pipeline, allowing only strictness rejects exactly the common case, and inverting the direction
leaves a check that can never fire. The information is gone before a `DataFrame` exists, so
ctdc checks optionality where it is still stated and asks the carriers' question of the rows
when a runtime answer is wanted.

# State of the field

Typed Spark wrappers raise the level at which a pipeline is written. Frameless [@frameless]
gives a typed `Dataset` API with compile-time column checking, and iskra [@iskra] gives typed
`DataFrame` operations on Scala 3. Both check the operations a pipeline performs; neither
states a contract that a producer type must conform to under a named compatibility policy.
Chimney [@chimney] derives transformations between case classes and reports at compile time
when none exists, but its subject is the transformation rather than a compatibility
direction.

Deequ [@deequ] and Great Expectations [@great_expectations] are complementary: they check
values rather than declared shape, and only once the data exists. Table formats enforce their
own evolution rules on write [@delta_lake; @apache_iceberg], which covers the table boundary
and not the in-process boundary between a Scala type and the frame being written. Confluent
Schema Registry [@avro_compatibility] is the closest analogue in spirit, and ctdc borrows its
vocabulary of compatibility directions deliberately, with one correction: a policy named `Full`
there means compatible in both directions and is the strictest setting, so the ctdc policy that
compares nothing is named `Unchecked`.

Missing from that set, and provided here, is a mechanism deciding declared structural
conformance between two Scala types under an explicit policy before the build completes, and a
measurement of the predicate family a pipeline would otherwise rely on.

# Functionality and limitations

Nine policies are singleton types (`Exact`, `ExactUnordered`, `ExactUnorderedCI`,
`ExactOrdered`, `ExactOrderedCI`, `ExactByPosition`, `Backward`, `Forward`, `Unchecked`), and
`ComparisonRules` decomposes them into four independent axes. One axis, `Optionality`, governs
all three carriers through a single shared check, so a policy means the same thing whether
fields are matched by name or position and whether the carrier sits on a field, a sequence
element, or a map value. The decision rules are version-agnostic Scala; only shape extraction
is written twice, with quotes reflection on Scala 3 [@scala3_reflection] and
`scala.reflect.macros` on 2.13, and parity between them is tested separately.

The guarantee is structural and declared, scoped to the shapes the macro supports.
`SchemaConforms` is a marker trait with no members, so an instance can also be written by hand,
which asserts conformance rather than checking it; the guarantee holds for macro-derived
evidence reaching a sink through the checked API. Nothing claims semantic contracts or
value-level correctness. Measured overhead is 0.37 to 0.83 seconds of additional compile time
for 10 to 50 contract pairs, and 115 to 7412 nanoseconds per runtime comparison. Every claim is
listed with its evidence in `ARTIFACT.md`, whose machine-readable form is generated rather than
written, pinned to the commit it was produced at, and emitted only if every path it cites still
exists.

# Acknowledgements

This work received no funding. The Apache Spark source and issue tracker were used as primary
evidence for the behaviour of the predicate family, including a documentation defect in
`MapType.valueContainsNull` that was corrected upstream [@spark_53717].

# References
