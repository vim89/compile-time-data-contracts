# Submission packaging

This note is the practical packaging checklist for the current paper and artifact.

It is based on official arXiv and Zenodo documentation only.

## arXiv

### Official constraints that matter here

- Do not upload auxiliary or generated output files like `.aux`, `.log`, `.toc`, or generated PDF outputs.
  arXiv says `.bbl` and `.ind` are the exceptions.
- For PDFLaTeX submissions, figures must already be in supported formats such as `.pdf`, `.png`, or `.jpg`.
  arXiv does not do figure conversion for you.
- If you use BibTeX or Biber, include the required `.bib` files or a pre-generated `.bbl`.
  If a `.bbl` is uploaded, its name must match the main `.tex` file.
- Hidden files and directories are deleted upon announcement.
  Do not depend on them even if preview happens to work.

### Current paper status against that checklist

- `paper/main.tex` is the root manuscript file: `pass`
- figures referenced by the manuscript are already `.png`: `pass`
- bibliography is standard BibTeX via `references.bib`: `pass`
- local Overleaf state file `paper/.olcli.json` is ignored and must stay out of the source bundle: `pass`
- `docs/internals/` is ignored and must not be part of any upload: `pass`
- optional hardening step before final upload:
  generate `main.bbl` and include it as `paper/main.bbl`

### Local bundle command

Use the local arXiv bundle helper:

```bash
paper/scripts/build-arxiv-bundle.sh
```

That creates a clean source bundle under `paper/dist/arxiv/` with only:

- `main.tex`
- `references.bib`
- `sections/*.tex`
- `figures/*.png`

This is the right starting point for arXiv submission packaging.

### Final arXiv preflight

1. Build the bundle with `paper/scripts/build-arxiv-bundle.sh`
2. If desired, add `main.bbl` to the bundle after a final local or Overleaf bibliography build
3. Upload the bundle to arXiv preview
4. Read the arXiv processing log carefully before announcement

## Zenodo

### Official constraints that matter here

- A Zenodo record consists of metadata, files, and a DOI.
- Zenodo registers a DOI on publication by default.
- Zenodo also lets you reserve a DOI in the draft record before publication, so you can include it in files ahead of time.
- Zenodo can archive GitHub releases once the repository is connected in the GitHub integration.
- Zenodo indexes records into OpenAIRE, which improves EU-wide discoverability.

### Recommended release shape for this project

Record type:

- software for the artifact release
- publication for the preprint, if you deposit the paper separately

Suggested software record contents:

- source snapshot from the GitHub release
- benchmark result snapshots from `benchmarks/results/`
- `ARTIFACT.md`
- paper-facing evidence notes from `docs/paper/` if you want them archived with the artifact

Suggested metadata:

- title aligned with the repo and paper title
- creator: `Vitthal Mirji`
- affiliation: `Independent Researcher, Fulda, Germany`
- description: short artifact summary, scope, and explicit non-claims
- keywords: `Scala 3`, `Spark`, `data contracts`, `schema drift`, `macros`
- related identifiers:
  - GitHub repository URL
  - arXiv identifier after preprint announcement

### Zenodo release flow

1. Create or sync the GitHub release you want to archive
2. In Zenodo, connect the GitHub repository if it is not already enabled
3. Reserve the DOI in Zenodo if you need it before publishing
4. Publish the Zenodo record
5. Add the published DOI back into the repo README, artifact docs, and preprint metadata as needed

### Draft release note

Title:

`compile-time-data-contracts artifact for policy-aware compile-time contracts in typed JVM and Spark pipelines`

Body:

- Clean reference artifact for compile-time structural contract checks on Scala 3 case classes
- Includes Spark schema derivation, policy-aware runtime sink checks, typed builder-path enforcement, and saved benchmark evidence
- Scope is structural compatibility only
- Does not claim semantic contracts, production incident reduction, or broad industrial performance results

## Official sources

- arXiv TeX submissions:
  https://info.arxiv.org/help/submit_tex.html
- Zenodo DOI guide:
  https://help.zenodo.org/docs/deposit/describe-records/reserve-doi/
- Zenodo GitHub integration:
  https://help.zenodo.org/docs/github/enable-repository/
- Zenodo and OpenAIRE:
  https://support.zenodo.org/help/en-gb/18-general/169-what-is-openaire
