# arXiv:2604.16986 [cs.PL]

"Shift schema drift left: policy-aware compile-time contracts for typed JVM and Spark pipelines",
Vittal Mirji, announced 18 April 2026. Seven pages, two figures, one table.

These sources started as the tree at commit `6c5399f` (2026-04-23), byte for byte, which is the state
the announced v1 was built from. They now carry the v2 corrections described below. The figures are
kept here too because `fig02-policy-family` has since changed in the current paper.

The body still describes the artifact as it was evaluated, at commit `6c5399f`. Where a statement was
true then and is not true of the current artifact, the divergence is flagged where it occurs. The
alternative, rewriting the description to match `0.2.0`, would make the paper describe code its own
evaluation never ran.

## Why it is a separate directory

This is a different paper from the one in `paper/`, not an earlier draft of it. This one describes
the mechanism and the policy family. `paper/` is a measurement study of how the three carriers of
optionality behave in schema-equality checking. The section filenames collide between the two, which
is why they cannot share `paper/sections/`.

## What 0.2.0 made inaccurate

One claim in the announced abstract no longer holds for the artifact, not two. The abstract said the
runtime comparator "adds a nested-collection-optionality check Spark's built-in comparators omit and
implements structural subset semantics for backward- and forward-compatible field sets".

- The optionality check is wrong as attributed. `SparkCore.scala:63` builds the pin's rules as
  `ComparisonRules.of(policy).ignoringOptionality`, which drops all three carriers. The capability
  was not deleted, it moved: `ComparisonRules.scala:151-161` gives every policy except `Unchecked` a
  non-`Ignored` optionality axis at compile time, and `ShapeDiff.scala:90-94` is where the sequence
  and map carriers are compared. The reason for the move is in the 0.2.0 entry of `CHANGELOG.md`:
  Spark's readers return the permissive value for each carrier on every format that does not record
  the claim, so the pin would compare a reader default rather than a producer's claim.
- The subset semantics are intact. `SparkCore.scala:91-104` still implements `missingTolerated` and
  `extraTolerated` per tolerance, so `Backward` still allows producer extras and optional or
  defaulted contract omissions, and `Forward` still allows the contract to hold more fields. What
  changed for these two policies is the optionality direction at compile time, which is a different
  axis from the field-set subsetting the abstract describes.

Correcting this needs a v2 replacement on this identifier, not a new submission. arXiv's own rule is
to replace rather than resubmit for a correction.

## v2 replacement

What changed in the sources, all of it surgical:

- `main.tex`: the abstract sentence above is split, the optionality clause is restated as a
  compile-time-only check with the reader-default reason, and it points at the companion paper. A
  short "Note on version 2" paragraph follows `\maketitle`. Two macros are added: `\companionpreprint`
  and `\carriermoved`.
- `01-introduction.tex`: the contribution bullet says the subset semantics are unchanged and the
  optionality check is compile-time only. The §1 assertion carries `\carriermoved`.
- `02-background-and-model.tex`: the claim that field-level optionality is ignored under all
  non-`Full` policies is marked as inverted by 0.2.0, with the `Full` to `Unchecked` rename noted.
- `03-framework-design.tex`: "the runtime layer" becomes "the comparison" where the sentence is about
  the internal representation, and the parity judgement gets a two-sentence correction.
- `04-artifact-and-evaluation.tex`: the artifact scope names commit `6c5399f`, which scopes the whole
  section to the evaluated code.

Before submitting, in order:

1. Fill in `\companionpreprint` in `main.tex`. The placeholder is `arXiv:XXXX.XXXXX`, which is not a
   well-formed identifier, so it will not pass unnoticed. It appears twice in the PDF.
2. Replace the abstract field on arXiv with the revised abstract. The abstract field and the PDF
   abstract have to match.
3. Set the comments field to the text below.
4. Update the page count. v1 is seven pages; the corrected paper is eight, because the references
   move onto a new page once the note is added.

Comments field for the replacement:

```
v2: corrects the attribution of the nested-collection-optionality check, which release 0.2.0 of the
artifact moved from the runtime sink pin to the compile-time comparison, because Spark's readers
return the permissive value for each carrier on every format that does not record the claim. The
design, evaluation and benchmarks are unchanged and describe the repository at commit 6c5399f. The
measurement study of the carriers is a companion paper, arXiv:XXXX.XXXXX. 8 pages, 2 figures, 1
table. Code at https://github.com/vim89/compile-time-data-contracts
```

## Building

There is no bundle script here. `paper/scripts/build-arxiv-bundle.sh` resolves its root from its own
location, so it only builds `paper/`. Point it at this directory, or copy these sources into a
scratch root, if a v2 bundle is needed.
