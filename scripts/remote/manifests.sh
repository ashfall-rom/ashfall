#!/usr/bin/env bash
# Mirror this repo's local manifests into the shared tree, exactly.
#
#   manifests.sh <mirror-dir> <tree-dir>
#
# Copies every <mirror-dir>/*.xml into <tree-dir>/.repo/local_manifests/ and
# removes *.xml files there that no longer exist in the mirror, so deleting a
# manifest in the repo also disables it in the tree (repo would otherwise keep
# applying the stale project definition). Non-XML files are left alone.
#
# Runs inside the build box; invoked by scripts/sync.sh under the tree lock.
set -euo pipefail
mirror=${1:?usage: manifests.sh <mirror-dir> <tree-dir>}
tree=${2:?usage: manifests.sh <mirror-dir> <tree-dir>}
mirror=${mirror%/}
dst="$tree/.repo/local_manifests"

[[ -d $mirror ]] || { echo "manifests: no mirror dir: $mirror" >&2; exit 1; }
mkdir -p "$dst"

shopt -s nullglob
for f in "$mirror"/*.xml; do
  cp -f "$f" "$dst/"
done
for f in "$dst"/*.xml; do
  [[ -f "$mirror/$(basename "$f")" ]] || rm -f "$f"
done

files=$(cd "$dst" && find . -maxdepth 1 -name '*.xml' -printf '%f ' 2>/dev/null || true)
shopt -u nullglob
printf 'local manifests: %s\n' "${files:-none}"
