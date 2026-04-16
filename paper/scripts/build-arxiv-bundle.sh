#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="${1:-$root_dir/dist/arxiv}"

rm -rf "$out_dir"
mkdir -p "$out_dir/sections" "$out_dir/figures"

cp "$root_dir/main.tex" "$out_dir/"
cp "$root_dir/references.bib" "$out_dir/"
if [ -f "$root_dir/main.bbl" ]; then
  cp "$root_dir/main.bbl" "$out_dir/"
fi
cp "$root_dir/sections/"*.tex "$out_dir/sections/"
cp "$root_dir/figures/"*.png "$out_dir/figures/"

if find "$out_dir" -name '.*' -print -quit | grep -q .; then
  echo "Hidden files found in arXiv bundle: $out_dir" >&2
  exit 1
fi

echo "Built arXiv bundle at: $out_dir"
