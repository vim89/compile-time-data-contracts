# Paper scaffold

This directory is the local Overleaf-facing manuscript scaffold for the `compile-time-data-contracts` paper.

It is bound to the Overleaf project `paper` through [paper/.olcli.json](.olcli.json).

## Local sources of truth

The detailed paper prep package has been moved to local-only internal docs under `docs/internals/prep-docs/`.
Those files are intentionally ignored by git at this stage.

## `olcli` workflow

The CLI is sync-oriented. Project creation happens in the browser first, then local work is pulled, edited, and pushed.

Useful commands:

```bash
npx -y @aloth/olcli sync paper
npx -y @aloth/olcli push paper --all
npx -y @aloth/olcli pdf paper -o /tmp/paper.pdf
npx -y @aloth/olcli output bbl --project paper -o paper/output.bbl
node paper/scripts/fetch-overleaf-bbl.mjs
paper/scripts/build-arxiv-bundle.sh
```

## Current status

- `main.tex` uses `acmart` with the `sigplan` option and `nonacm` for local drafting
- `00README.json` records the intended top-level source and current submission-target compiler metadata
- Sections 1-8 exist as separate files under `paper/sections/`
- The first prose pass covers Sections 1-8, with the strongest polish so far in Sections 1-4 and 7-8
- Mermaid source and rendered PNG/PDF assets exist under `paper/figures/`
- The local scaffold is bound to the Overleaf project `paper`, and `olcli` upload/push currently works again in this environment
- Remote Overleaf compilation and PDF download work via `olcli pdf`; current remote logs show `pdfTeX` on TeX Live 2025
- Local `tectonic` output remains the source of truth for refreshed submission artifacts between remote syncs
- `olcli output log` works and confirms the manuscript reads `./output.bbl` during compile
- `olcli output bbl` is still flaky in this setup, so `paper/scripts/fetch-overleaf-bbl.mjs` is the reliable local fallback
- detailed submission and release prep notes are kept in local-only internal docs

## Next edits

1. tighten Sections 4-8 in TeX against the current compiled PDF
2. trim remaining overfull/underfull warnings where they harm layout
3. fetch `output.bbl` later through browser or a fixed CLI path when needed for arXiv packaging
