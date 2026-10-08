# JOSS submission notes

This file maps each JOSS submission requirement to the thing in this repository that satisfies
it, so an editor or reviewer does not have to search for it. It is not part of the paper.

Paper: [paper.md](paper.md). Bibliography: [paper.bib](paper.bib).

## Requirements

| Requirement | Where it is met |
| --- | --- |
| Open source under an OSI-approved licence | [LICENSE](../LICENSE), MIT |
| Public repository with version control | `https://github.com/vim89/compile-time-data-contracts` |
| A tagged release and an archive with a DOI | `v0.2.0`, archived on Zenodo at release |
| `paper.md` with the required sections | [paper.md](paper.md) |
| Author with ORCID and affiliation | Vitthal Mirji, ORCID 0009-0005-3376-0457 |
| Statement of need | `paper.md`, "Statement of need" |
| State of the field and comparison to related work | `paper.md`, "State of the field" |
| Installation instructions | [README.md](../README.md), "Quick start", both the no-credential source build and the published artifacts |
| A runnable example | [README.md](../README.md), the compile-only example and the pipeline example |
| API or functional documentation | [README.md](../README.md), plus scaladoc on every public type |
| Automated tests | 234 tests across four modules, run by `.github/workflows/ci.yml` on every pull request |
| Community guidelines for contributing, reporting issues and seeking support | [CONTRIBUTING.md](../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) |
| Citation metadata | [CITATION.cff](../CITATION.cff) |

The one requirement no checklist can discharge is substantial scholarly effort. The rest of
this file is the case for it.

## On a single author

A single author is not a JOSS obstacle and nothing in the submission needs to be arranged
around it. JOSS accepts single-author papers routinely, there is no minimum author count, and
the review is of the software and the paper rather than of the team. The author list should
simply be whoever did the work, which here is one person.

What a single-author repository does change is the evidence available for the two things a
reviewer would otherwise infer from the team: that the project is maintained, and that its
claims have been checked by someone other than their author. Both are addressed directly
rather than left to inference.

- Maintenance: [CONTRIBUTING.md](../CONTRIBUTING.md) states plainly that there is one
  maintainer and asks for an issue before a large change, so a contributor is not surprised by
  the response time. The issue tracker is the single support channel and is named as such.
- Checked claims: the repository does not ask a reader to trust its claims. Every claim is
  listed with the test or saved run that backs it in [ARTIFACT.md](../ARTIFACT.md), and a
  claim not marked `closed` is explicitly not something the repository proves. The
  machine-readable form of that table, `paper/evidence/claims.json`, is generated from
  `ARTIFACT.md` rather than written beside it, is pinned to the commit it was produced at, and
  refuses to emit if any cited evidence path no longer exists. CI runs that generator on every
  pull request, so a claim cannot keep reading as settled after the file behind it moved. This
  is the part of peer review a second author would normally provide, mechanised instead.

## On substantial scholarly effort

JOSS requires that the submission represent substantial scholarly effort rather than a thin
utility. The gate is about the scope of the work, not the size of the author list. Four things
are offered against it.

1. A new mechanism, not a wrapper. The library decides declared structural conformance
   between two Scala types under an explicit compatibility policy, at compile time, through
   macro front ends written separately for Scala 2.13 and Scala 3 over shared version-agnostic
   decision rules. The comparison is a normalised deep structural shape with a field-path diff,
   not a type equality check.
2. A measurement, not an assertion. The repository contains a 2668-cell comparison of four
   schema-equality predicate owners, with the harnesses that produced it and the saved runs
   checked in. It establishes that all ten of the schema-equality configurations Apache Spark
   3.5.6 ships reject a pair of schemas differing only in field order, and that six of the ten
   also accept an optional producer against a required contract. That is a characterisation of
   widely used infrastructure which, as far as the related-work survey in the paper found, was
   not previously written down.
3. A negative result that shaped the design. The three carriers of optionality cannot be
   compared usefully at runtime on a Spark schema, because the readers return the permissive
   value for every format that does not record the claim. Each candidate rule was tried and
   eliminated, and the elimination is recorded in [CHANGELOG.md](../CHANGELOG.md) rather than
   quietly worked around. The two-stage design, compile-time comparison against the Scala
   types plus an opt-in row-level check, is the consequence of that result.
4. A reproducible evidence trail. Benchmarks, the comparison matrix, and the claim ledger are
   all regenerable by documented commands, pinned to a revision, and the revision is part of
   the artifact. A number in the paper can be traced to the commit of the comparison engine it
   belongs to.

## Scope the submission does not claim

Stated here so a reviewer does not have to find it by reading the code.

- The guarantee is structural and declared, scoped to the shapes listed under "Supported
  shapes" in the README. Nothing claims semantic contracts or value-level correctness.
- `SchemaConforms` is a marker trait with no members, so an instance can be written by hand,
  which asserts conformance rather than checking it. The guarantee holds for evidence the
  macro derived, reaching a sink through the checked API.
- No claim is made about production incident reduction or about performance on industrial
  workloads. The measured overhead figures are from the saved benchmark runs on one machine
  and are reported as such.
- The Spark half targets Spark 3.5.x. Spark 4.2.0 is covered by a probe-only module that
  checks whether the characterisation still holds, not by a published artifact.
