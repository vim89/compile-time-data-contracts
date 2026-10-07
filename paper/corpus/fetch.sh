#!/usr/bin/env bash
#
# Fetch the Avro schema corpus listed in repos.tsv at its pinned commits.
#
# Writes the schema files under schemas/<owner>__<repo>/<path in that repo> and a manifest of what was written.
# The schema files are not committed: they belong to their projects under their own licenses, and the manifest plus
# this script is enough to reproduce the exact set.
#
# Enumeration is git's, not GitHub's tree API. The API caps a recursive listing and reports `truncated: true` when it
# hits the cap, and the previous version of this script neither read that flag nor had any other way to know the
# listing was complete - on `apache/camel` it is in fact truncated, so the population the corpus claimed to enumerate
# was unverified. A blobless shallow fetch of the one pinned commit costs a second or two per repository and `git
# ls-tree -r` over it is the complete list by construction, with no cap to hit.
#
# Every file is identified by its git blob hash, which is what `ls-tree` prints and what `git hash-object` recomputes
# locally. That makes three things checkable rather than assumed. A file already on disk is kept only if it hashes to
# the blob the pinned commit names, so a truncated download or an edited file is refetched instead of measured. A
# fresh download is hashed again and the run fails if it does not match, so a proxy serving something else cannot
# pass. And the hash goes in the manifest, so the corpus the paper measured is identified by content and not only by
# a path and a byte count. The previous version kept any file that was merely nonempty, while its own comment
# claimed it checked the size.
#
# The run also fails if a listed repository contributes no `.avsc` files, which would mean the pin no longer points
# where repos.tsv says. Repositories that genuinely contain none were dropped before fetching and are in
# excluded.tsv.
#
# Requires `git` and `curl`, and public network access. No GitHub authentication and no `gh`.
#
# Usage: paper/corpus/fetch.sh
set -euo pipefail

cd "$(dirname "$0")"

manifest="manifest.tsv"
printf '# repo\tcommit\tstratum\tpath\tbytes\tblob\n' >"$manifest.partial"

# One scratch directory for the whole run, removed on any exit. The fetches below are metadata only - no blobs - so
# nothing in here is large and nothing in here is the corpus.
trees=$(mktemp -d)
trap 'rm -rf "$trees"' EXIT

# The complete list of `.avsc` blobs at one commit, as `<path>\t<blob sha>` lines.
#
# `--depth 1 --filter=blob:none` fetches that commit's trees and nothing else: no history and no file contents. The
# commit is named by SHA rather than by branch, so this is the pinned revision and not today's tip.
list_avsc() {
  local repo="$1" commit="$2" work="$trees/${repo//\//__}"
  git init -q "$work"
  git -C "$work" remote add origin "https://github.com/$repo.git"
  git -C "$work" fetch -q --depth 1 --filter=blob:none origin "$commit"
  git -C "$work" ls-tree -r "$commit" | awk -F'\t' '$2 ~ /\.avsc$/ { split($1, f, " "); print $2 "\t" f[3] }'
}

# `|| [ -n "$line" ]` so a final line without a trailing newline is still read.
while IFS=$'\t' read -r repo commit license stratum || [ -n "${repo:-}" ]; do
  case "$repo" in '#'* | '') continue ;; esac
  : "$license" # recorded in repos.tsv for provenance; not needed to fetch

  echo "== $repo@${commit:0:10}"
  dest="schemas/${repo/\//__}"

  blobs=$(list_avsc "$repo" "$commit")
  count=$(printf '%s' "$blobs" | grep -c . || true)
  if [ "$count" -eq 0 ]; then
    echo "FAIL: $repo@$commit contains no .avsc files." >&2
    echo "      It is in repos.tsv because it had some; a repository with none belongs in excluded.tsv." >&2
    exit 1
  fi

  # Fed by a redirect and not a pipe, so that a failure below exits the script rather than a subshell.
  while IFS=$'\t' read -r path blob; do
    [ -z "$path" ] && continue
    out="$dest/$path"
    mkdir -p "$(dirname "$out")"
    if [ ! -s "$out" ] || [ "$(git hash-object -- "$out")" != "$blob" ]; then
      curl -fsSL "https://raw.githubusercontent.com/$repo/$commit/$path" -o "$out"
    fi
    got=$(git hash-object -- "$out")
    if [ "$got" != "$blob" ]; then
      echo "FAIL: $repo $path hashed to $got, but $commit names blob $blob." >&2
      exit 1
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$repo" "$commit" "$stratum" "$path" "$(wc -c <"$out" | tr -d ' ')" "$blob" \
      >>"$manifest.partial"
  done < <(printf '%s\n' "$blobs")
done <repos.tsv

# Replaced only on success, so an interrupted run cannot leave a manifest that claims fewer files than the corpus has.
mv "$manifest.partial" "$manifest"
echo "== $(($(wc -l <"$manifest") - 1)) schema files in $manifest"
