#!/usr/bin/env bash
# Adds files to a GitHub release, skipping any the release already has: a file that's there was
# built (and signed) by whoever made the release, and is never replaced.
#   scripts/upload-missing.sh TAG FILE...      (uses gh; GH_TOKEN and GITHUB_REPOSITORY in Actions)
set -euo pipefail
tag="$1"; shift
repo=(); [ -n "${GITHUB_REPOSITORY:-}" ] && repo=(-R "$GITHUB_REPOSITORY")
have=$(gh release view "$tag" "${repo[@]}" --json assets --jq '.assets[].name')
for f in "$@"; do
  name=$(basename "$f")
  if grep -qxF "$name" <<<"$have"; then
    echo "Already in $tag: $name"
  else
    gh release upload "$tag" "$f" "${repo[@]}"
  fi
done
