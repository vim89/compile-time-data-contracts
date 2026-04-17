#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="${1:-$root_dir/dist/arxiv}"
dist_dir="$(cd "$(dirname "$out_dir")" && pwd)"
zip_path="${2:-$dist_dir/compile-time-data-contracts-arxiv.zip}"

section_names=()
while IFS= read -r section; do
  section_names+=("$section")
done < <(
  sed -n 's/^[[:space:]]*\\input{sections\/\([^}]*\)}[[:space:]]*$/\1/p' "$root_dir/main.tex"
)

if [ "${#section_names[@]}" -eq 0 ]; then
  echo "No paper sections found in $root_dir/main.tex" >&2
  exit 1
fi

rm -rf "$out_dir"
mkdir -p "$out_dir/sections" "$out_dir/figures"

cp "$root_dir/main.tex" "$out_dir/"
cp "$root_dir/references.bib" "$out_dir/"
if [ -f "$root_dir/main.bbl" ]; then
  cp "$root_dir/main.bbl" "$out_dir/"
fi

for section in "${section_names[@]}"; do
  cp "$root_dir/sections/$section.tex" "$out_dir/sections/"
done

cp "$root_dir/figures/"*.pdf "$out_dir/figures/"

if find "$out_dir" -name '.*' -print -quit | grep -q .; then
  echo "Hidden files found in arXiv bundle: $out_dir" >&2
  exit 1
fi

rm -f "$zip_path"
(
  cd "$out_dir"
  zip -qr "$zip_path" .
)

echo "Built arXiv bundle at: $out_dir"
echo "Built arXiv zip at: $zip_path"
