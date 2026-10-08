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

arXiv does not run bibtex, so the bundle has to carry a current `main.bbl`, and the script refuses
to build one that is older than `references.bib`, `main.tex` or any section. Refresh it with
`tectonic -X compile main.tex --keep-intermediates`, which is the only way tectonic writes it to
disk.

## Submitting

Categories: `cs.PL` primary, matching the companion paper, with `cs.SE` and `cs.DB` as cross-lists.
The paper is about a language mechanism, the subject it measures is a data-platform schema
boundary, and the requirement it is driven by is a software-engineering one.

The abstract metadata field has to say the same thing as the abstract in the PDF. It is derived from
`main.tex` rather than transcribed, so the two cannot drift:

```bash
python3 paper/scripts/abstract-field.py
```

Current counts, for the comments field: 24 pages, 3 figures, 7 tables.

```
24 pages, 3 figures, 7 tables. Companion to arXiv:2604.16986, which describes the mechanism and the
policy family; this paper is the measurement study of the three carriers of optionality. Harnesses,
saved runs and the full 2668-cell verdict matrix at
https://github.com/vim89/compile-time-data-contracts
```

Once this paper has an identifier, it has to be filled into `\companionpreprint` in
[../arxiv-2604.16986/main.tex](../arxiv-2604.16986/main.tex) before that paper's v2 replacement can
be posted.
