# Figure sources

This directory holds manuscript figure sources.

Current state:

- Mermaid source is stored as `.mmd`
- manuscript-ready PNG renders already exist for the first two figures

Render locally with Mermaid CLI:

```bash
npx -y @mermaid-js/mermaid-cli -i paper/figures/fig01-architecture.mmd -o paper/figures/fig01-architecture.png
npx -y @mermaid-js/mermaid-cli -i paper/figures/fig02-policy-family.mmd -o paper/figures/fig02-policy-family.png
```

The canonical figure wording now lives in the local-only prep docs under `docs/internals/prep-docs/`.
