# Three carriers, one bit

"Three carriers, one bit: optionality in schema-equality checking, measured in Spark", Vittal
Mirji. Not submitted yet. The intended arXiv primary category is `cs.PL`, matching
[arxiv-2604.16986/](../arxiv-2604.16986/README.md), which this paper is the companion to.

It is a measurement study of how the three carriers of optionality behave in schema-equality
checking: `StructField.nullable`, `ArrayType.containsNull`, and `MapType.valueContainsNull`. The
other paper describes the mechanism and the policy family.

## Layout

- `main.tex` uses `acmart` with the `sigplan` option, and `nonacm` for local drafting
- `00README.json` records the intended top-level source and the submission-target compiler metadata
- `sections/` holds sections 1-8, one file each
- `figures/` holds the Mermaid sources and the rendered PNG and PDF assets
- `main.bbl` and `main.pdf` are build output and are ignored by git

The evidence the paper cites is one level up, under `paper/evidence/`, `paper/corpus/` and
`paper/scripts/`, because it is shared and because the paper cites it by repo-relative path.

## Building

```bash
cd paper/three-carriers && tectonic -X compile main.tex
paper/scripts/build-arxiv-bundle.sh
```

The bundle script defaults to this directory. It flattens `sections/` into the top level and
rewrites the `\input` paths to match, because arXiv wants a flat source tree.
