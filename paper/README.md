# Paper scaffold

This directory is the local Overleaf-facing manuscript scaffold for the `compile-time-data-contracts` paper.

It is bound to the Overleaf project `paper` through [paper/.olcli.json](.olcli.json).

## Local sources of truth

- manuscript claim surface: [../docs/paper/evidence-map.md](../docs/paper/evidence-map.md)
- outline: [../docs/paper/outline.md](../docs/paper/outline.md)
- prose drafts: [../docs/paper/draft-sections-1-3.md](../docs/paper/draft-sections-1-3.md), [../docs/paper/draft-sections-4-7.md](../docs/paper/draft-sections-4-7.md)
- figure notes: [../docs/paper/figures-notes.md](../docs/paper/figures-notes.md)
- Mermaid figure drafts: [../docs/paper/figures-mermaid.md](../docs/paper/figures-mermaid.md)

## `olcli` workflow

The CLI is sync-oriented. Project creation happens in the browser first, then local work is pulled, edited, and pushed.

Useful commands:

```bash
npx -y @aloth/olcli sync paper
npx -y @aloth/olcli push paper --all
npx -y @aloth/olcli pdf paper -o /tmp/paper.pdf
npx -y @aloth/olcli output bbl --project paper -o paper/output.bbl
```

## Current status

- `main.tex` uses `acmart` with the `sigplan` option and `nonacm` for local drafting
- Sections 1-8 exist as separate files under `paper/sections/`
- The first prose pass covers Sections 1-8, with the strongest polish so far in Sections 1-4 and 7-8
- Mermaid source and rendered PNG/PDF assets exist under `paper/figures/`
- The scaffold is synced to the Overleaf project `paper`
- Remote Overleaf compilation succeeds and PDF download works via `olcli pdf`
- `olcli output bbl` and `olcli output log` are currently returning false `not found` responses even though `output --list` advertises them; treat that as a CLI issue, not a manuscript issue

## Next edits

1. tighten Sections 4-8 in TeX against the current compiled PDF
2. trim remaining overfull/underfull warnings where they harm layout
3. fetch `output.bbl` later through browser or a fixed CLI path when needed for arXiv packaging
