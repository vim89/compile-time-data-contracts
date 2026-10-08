# JOSS submission notes

This file maps each JOSS submission requirement to the thing in this repository that satisfies
it, so an editor or reviewer does not have to search for it. It is not part of the paper.

Paper: [paper.md](paper.md). Bibliography: [paper.bib](paper.bib).

## Submission requirements

| Requirement | Where it is met |
| --- | --- |
| Open source under an OSI-approved licence, as an actual licence file | [LICENSE](../LICENSE), MIT, full text |
| Repository cloneable and browsable without registration | `https://github.com/vim89/compile-time-data-contracts`, public |
| Issue tracker readable without registration, anyone may open an issue | GitHub Issues, open |
| Submitting author is a major contributor with a GitHub account | Sole author of all commits, `vim89` |
| `paper.md` plus BibTeX in the Git repository with the software | [paper.md](paper.md), [paper.bib](paper.bib) |
| Paper does not focus on new research results | The paper's subject is the software. The carrier measurement appears only as the motivation under "Statement of need" and as the design input under "Software design". The analysis itself is a separate companion paper. |
| Author with ORCID and affiliation | Vittal Mirji, ORCID 0009-0005-3376-0457 |
| Installation instructions, automated | [README.md](../README.md), "Build from source" and "Quick start", both the no-credential source build and the published artifacts |
| A runnable example | [README.md](../README.md), the compile-only example and the pipeline example |
| API or functional documentation | [README.md](../README.md), plus scaladoc on every public type |
| Automated tests under continuous integration | 234 tests across four modules, run by `.github/workflows/ci.yml` on every pull request |
| Community guidelines for contributing, reporting issues and seeking support | [CONTRIBUTING.md](../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) |
| Citation metadata | [CITATION.cff](../CITATION.cff) |
| Tagged release and an archive with a DOI | `v0.1.0` tagged; `v0.2.0` tagged and archived on Zenodo at release |
| Conflict of interest disclosure | `paper.md`, "Acknowledgements": no funding, no competing interests |
| Related publications disclosed | A companion paper developing the carrier analysis is stated in `paper.md`, "Research impact statement", and is declared on the submission form |

## Required paper sections

JOSS requires eight sections. All are present in [paper.md](paper.md).

| Section | Present |
| --- | --- |
| Summary, for a non-specialist reader | yes |
| Statement of need, with problem, audience and relation to other work | yes |
| State of the field, with a build-vs-contribute justification | yes, the justification is the last paragraph |
| Software design, with trade-offs and why they matter | yes, four decisions and what each gave up |
| Research impact statement, specific and not aspirational | yes |
| AI usage disclosure | yes, tools, scope and verification |
| Acknowledgements, including financial support | yes |
| References | yes, 13 entries, every one cited |

Word count is 1740, inside the 750 to 1750 range.

## Pre-review screening gates

These four are checked before a submission enters review, and a failure on any one is a desk
rejection. Stated here with the evidence rather than left for the editor to measure.

**1. Sufficient public development history.** The repository has been public since it was
created on 12 September 2025, which is thirteen months. It was never developed privately and
then released. Development spans the period: pull requests merged in October 2025, April 2026
and October 2026, 223 commits, a `v0.1.0` tag and a maintained [CHANGELOG.md](../CHANGELOG.md).

**2. Demonstrated research impact.** The software is used in the author's own research, which
JOSS names as the minimum acceptable signal, and the repository is the reproducible material for
it. The companion paper developing the carrier analysis draws every number from the saved runs
checked in here. There is no adoption by other groups and the paper says so rather than
implying otherwise.

**3. Good open source practices.** JOSS asks single-author projects to show multiple indicators.
Present: a public commit history over thirteen months; a tagged release and a changelog;
234 automated tests under continuous integration on every pull request; a README with
installation, examples and API documentation; a [CONTRIBUTING.md](../CONTRIBUTING.md); and
stated support and governance expectations, namely one maintainer and the issue tracker as the
single support channel. Development has used a pull request workflow throughout, fifteen pull
requests to date, rather than commits direct to the default branch.

**4. Iterative development over time.** Activity is bursty rather than continuous, which JOSS
rates as acceptable for a project with more than six months of public history. The work was
refined across those bursts rather than added at once: the policy set grew, the comparison
engine was extracted and then cross-built for Scala 2.13 after being written for Scala 3, the
three carriers were unified behind one shared check after the policies had been shipped
comparing only the first of them, and the runtime optionality rule was removed after each
candidate was measured and eliminated. [CHANGELOG.md](../CHANGELOG.md) records those revisions
in order, including the ones that were reversed and the one rename it carries deprecated
aliases for.

Not claimed: community engagement beyond the author. JOSS treats this as a positive signal
rather than a gate, and there is none to report.

## On a single author

A single author is not a JOSS obstacle and nothing in the submission needs to be arranged
around it. JOSS accepts single-author papers, there is no minimum author count, and the review
is of the software and the paper. The author list should simply be whoever did the work, which
here is one person.

What a single-author repository does change is the evidence available for the two things a
reviewer would otherwise infer from the team: that the project is maintained, and that its
claims have been checked by someone other than their author. Both are addressed directly rather
than left to inference.

- Maintenance: [CONTRIBUTING.md](../CONTRIBUTING.md) states plainly that there is one
  maintainer and asks for an issue before a large change, so a contributor is not surprised by
  the response time. The issue tracker is the single support channel and is named as such.
- Checked claims: the repository does not ask a reader to trust its claims. Every claim is
  listed with the test or saved run that backs it in [ARTIFACT.md](../ARTIFACT.md), and a claim
  not marked `closed` is explicitly not something the repository proves. The machine-readable
  form of that table, `paper/evidence/claims.json`, is generated from `ARTIFACT.md` rather than
  written beside it, is pinned to the commit it was produced at, and refuses to emit if any
  cited evidence path no longer exists. CI runs that generator on every pull request, so a claim
  cannot keep reading as settled after the file behind it moved. This is the part of peer review
  a second author would normally provide, mechanised instead.

## On substantial scholarly effort

JOSS requires that the submission represent substantial scholarly effort rather than a thin
utility, and excludes minor utility packages, thin API clients and single-function packages. The
gate is about the scope of the work, not the size of the author list. Four things are offered
against it.

1. A new mechanism, not a wrapper. The library decides declared structural conformance between
   two Scala types under an explicit compatibility policy, at compile time, through macro front
   ends written separately for Scala 2.13 and Scala 3 over shared version-agnostic decision
   rules. The comparison is a normalised deep structural shape with a field-path diff, not a
   type equality check.
2. Design driven by measurement rather than assertion. The policy axes and the two-stage split
   are both consequences of what the 2668-cell comparison of four predicate owners found, and
   the harnesses and saved runs behind it are checked in. The design did not begin from an
   intuition about what Spark decides.
3. A negative result that shaped the design. The three carriers of optionality cannot be
   compared usefully at runtime on a Spark schema, because the readers return the permissive
   value for every format that does not record the claim. Each candidate rule was tried and
   eliminated, and the elimination is recorded in [CHANGELOG.md](../CHANGELOG.md) rather than
   quietly worked around.
4. A reproducible evidence trail. Benchmarks, the comparison matrix and the claim ledger are all
   regenerable by documented commands, pinned to a revision, and the revision is part of the
   artifact. A number in the paper can be traced to the commit of the comparison engine it
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
- No adoption outside the author's own work, and no citations. The paper states this.
