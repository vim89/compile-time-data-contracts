# Paper scaffold

This directory is the manuscript scaffold for `compile-time-data-contracts`. It holds two papers,
each in its own directory, and the tooling and evidence they share.

| Directory | Paper |
|---|---|
| [three-carriers/](three-carriers/README.md) | "Three carriers, one bit: optionality in schema-equality checking, measured in Spark". The current one, not yet submitted. |
| [arxiv-2604.16986/](arxiv-2604.16986/README.md) | "Shift schema drift left: policy-aware compile-time contracts for typed JVM and Spark pipelines". Announced on arXiv, awaiting a v2 replacement. |

They are separate papers rather than two drafts of one. The section filenames are the same in both,
which is why they cannot share a `sections/` directory.

## What stays at this level

- `scripts/` builds the arXiv bundles, fetches the `.bbl` from Overleaf, and generates the claim
  ledger and the Spark predicate hashes
- `evidence/` holds the generated evidence files the current paper cites by repo-relative path
- `corpus/` holds the corpus manifest and fetch script, but not the schema payloads
- `.olcli.json` is the local Overleaf binding, and is ignored by git

Both scripts that need to know which paper they are working on take it as a `PAPER_DIR`
environment variable, a directory name under `paper/`, defaulting to `three-carriers`.

## `olcli` workflow

The CLI is sync-oriented. Project creation happens in the browser first, then local work is pulled,
edited, and pushed.

```bash
npx -y @aloth/olcli sync paper
npx -y @aloth/olcli push paper --all
npx -y @aloth/olcli pdf paper -o /tmp/paper.pdf
node paper/scripts/fetch-overleaf-bbl.mjs
paper/scripts/build-arxiv-bundle.sh
PAPER_DIR=arxiv-2604.16986 paper/scripts/build-arxiv-bundle.sh
```

Notes on this setup that still hold:

- the local scaffold is bound to the Overleaf project `paper`, and `olcli` upload/push works
- remote compilation and PDF download work via `olcli pdf`; remote logs show `pdfTeX` on TeX Live 2025
- `olcli output log` confirms the manuscript reads `./output.bbl` during compile
- `olcli output bbl` is flaky in this setup, so `paper/scripts/fetch-overleaf-bbl.mjs` is the
  reliable local fallback

## Building locally

`tectonic` is the local source of truth for refreshed submission artifacts between remote syncs. It
runs bibtex itself.

```bash
cd paper/three-carriers && tectonic -X compile main.tex
```

`tectonic` does not write the `.bbl` to disk unless asked, and the arXiv bundler refuses a `.bbl`
older than the sources that can change it, so refresh it with the sources:

```bash
cd paper/three-carriers && tectonic -X compile main.tex --keep-intermediates
```

That also leaves `main.aux`, `main.log`, `main.blg` and `main.out` behind; they are build output and
are not tracked.
