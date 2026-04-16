# Paper package

This directory holds the paper-writing package for the `compile-time-data-contracts` artifact.

Files:

- [evidence-map.md](evidence-map.md): safe claims, non-claims, and exact repo evidence
- [outline.md](outline.md): manuscript skeleton from closed claims only
- [figures-notes.md](figures-notes.md): first figure and table definitions
- [figures-mermaid.md](figures-mermaid.md): Mermaid-ready draft figures
- [draft-sections-1-3.md](draft-sections-1-3.md): first prose draft for Sections 1-3
- [draft-sections-4-7.md](draft-sections-4-7.md): first prose draft for Sections 4-7
- [industrial-evidence-pack.md](industrial-evidence-pack.md): FlowForge-derived motivation, caveats, and what not to overclaim
- [literature-matrix.md](literature-matrix.md): primary-source-only reading matrix
- [reviewer-risk-pass.md](reviewer-risk-pass.md): strict reviewer-style pass notes for the current draft
- [submission-packaging.md](submission-packaging.md): arXiv and Zenodo packaging checklist grounded in official docs

Writing rule:

- start with `evidence-map.md`
- then draft against `outline.md`
- use `draft-sections-1-3.md` as the current prose baseline for the opening sections
- use `draft-sections-4-7.md` as the current prose baseline for the remaining core sections
- use `figures-mermaid.md` for the first diagram drafts
- use `industrial-evidence-pack.md` only for motivation and limitations unless a stronger safe pack exists

Overleaf-facing local scaffold:

- [../../paper/README.md](../../paper/README.md)

Current Overleaf state:

- project `paper` exists and is locally bound through `paper/.olcli.json`
- the ACM/SIGPLAN scaffold is synced
- remote compile succeeds and PDF fetch works
