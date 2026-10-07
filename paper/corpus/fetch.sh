#!/usr/bin/env bash
#
# Fetch the Avro schema corpus listed in repos.tsv at its pinned commits.
#
# Writes the schema files under schemas/<owner>__<repo>/<path in that repo> and a manifest of what was written.
# The schema files are not committed: they belong to their projects under their own licenses, and the manifest plus
# this script is enough to reproduce the exact set. Re-running is idempotent - a file already present at the right
# size is left alone - so a partial run can be resumed without re-downloading what it already has.
#
# Requires `gh` authenticated with any account; only public read access is used. Nothing here writes to GitHub.
#
# Usage: paper/corpus/fetch.sh
set -euo pipefail

cd "$(dirname "$0")"

manifest="manifest.tsv"
printf '# repo\tcommit\tstratum\tpath\tbytes\n' >"$manifest.partial"

# `|| [ -n "$line" ]` so a final line without a trailing newline is still read.
while IFS=$'\t' read -r repo commit license stratum || [ -n "${repo:-}" ]; do
  case "$repo" in '#'* | '') continue ;; esac
  : "$license" # recorded in repos.tsv for provenance; not needed to fetch

  echo "== $repo@${commit:0:10}"
  dest="schemas/${repo/\//__}"

  # One tree call per repository rather than one per file. The pinned commit is passed through, so the listing is of
  # that commit and not of whatever the default branch points at today.
  paths=$(gh api "repos/$repo/git/trees/$commit?recursive=1" \
    --jq '.tree[] | select(.type == "blob") | select(.path | endswith(".avsc")) | .path')

  while IFS= read -r path; do
    [ -z "$path" ] && continue
    out="$dest/$path"
    mkdir -p "$(dirname "$out")"
    if [ ! -s "$out" ]; then
      curl -fsSL "https://raw.githubusercontent.com/$repo/$commit/$path" -o "$out"
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$repo" "$commit" "$stratum" "$path" "$(wc -c <"$out" | tr -d ' ')" \
      >>"$manifest.partial"
  done <<<"$paths"
done <repos.tsv

# Replaced only on success, so an interrupted run cannot leave a manifest that claims fewer files than the corpus has.
mv "$manifest.partial" "$manifest"
echo "== $(($(wc -l <"$manifest") - 1)) schema files in $manifest"
