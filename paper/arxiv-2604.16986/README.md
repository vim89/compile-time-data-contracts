# arXiv:2604.16986 [cs.PL]

"Shift schema drift left: policy-aware compile-time contracts for typed JVM and Spark pipelines",
Vittal Mirji, announced 18 April 2026. Seven pages, two figures, one table.

These sources are the tree at commit `6c5399f` (2026-04-23), byte for byte. That is the state the
announced v1 was built from, so this directory is the record of what was submitted rather than a
working copy. The figures are kept here too because `fig02-policy-family` has since changed in the
current paper.

## Why it is a separate directory

This is a different paper from the one in `paper/`, not an earlier draft of it. This one describes
the mechanism and the policy family. `paper/` is a measurement study of how the three carriers of
optionality behave in schema-equality checking. The section filenames collide between the two, which
is why they cannot share `paper/sections/`.

## What 0.2.0 made inaccurate

Two claims in the announced abstract no longer hold for the artifact:

- a nested-collection-optionality check that Spark's built-in comparators omit. The runtime pin no
  longer compares any of the three carriers. See the 0.2.0 entry in `CHANGELOG.md`.
- structural subset semantics for backward- and forward-compatible field sets. `Backward` and
  `Forward` now reject the relaxing direction.

Correcting these needs a v2 replacement on this identifier, not a new submission. arXiv's own rule
is to replace rather than resubmit for a correction.

## Building

There is no bundle script here. `paper/scripts/build-arxiv-bundle.sh` resolves its root from its own
location, so it only builds `paper/`. Point it at this directory, or copy these sources into a
scratch root, if a v2 bundle is needed.
