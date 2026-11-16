#!/usr/bin/env bash
# shellcheck shell=bash
#
# Static check of the diff paths in patches/ (issue #221).
#
# `scripts/patches.sh` applies each patch with `git -C <tree>/<project> am`, so
# every path in a patch file is relative to that project root. A patch copied
# from a whole-tree `git format-patch` keeps an extra AOSP prefix
# (a/frameworks/base/packages/SystemUI/... in
# patches/frameworks/base/0002-*.patch); plain `git am` then fails with
# "does not exist in index", and `git am --3way` only papers over it by
# reconstructing the base tree from the index blobs.
#
# This lint reads only the patch files (no tree, no network): for every
# `diff --git` header it rejects a path that is already inside the project
# directory, an absolute path, and a `---`/`+++` pair that disagrees with the
# header. Run it after editing a patch and before pushing.
#
# Exit status: 0 when every patch is project-relative, 1 otherwise.

set -euo pipefail

ASH_ROOT=${ASH_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
export ASH_ROOT
# shellcheck source=scripts/lib/common.sh
. "$ASH_ROOT/scripts/lib/common.sh"

PATCHES_DIR=$ASH_ROOT/patches
ISSUES=0
PATCHES=0
HEADERS=0

usage() {
  cat >&2 <<'EOF'
Usage: scripts/patches-lint.sh [--patches DIR]

Reports diff paths that repeat the patch's project prefix (the extra
`a/frameworks/base/...` prefix of issue #221), absolute paths, and `---`/`+++`
lines that disagree with their `diff --git` header.  The default patch root is
<repo>/patches; DIR is for tests and scratch trees.

  --patches DIR   patch root to check
  -h, --help      this help
EOF
  exit "${1:-0}"
}

issue() { printf '%s: %s\n' "$1" "$2" >&2; ISSUES=$((ISSUES + 1)); }

# check_patch <project-rel> <patch-file>
# project-rel is the patch's directory relative to the patch root; it is also
# the path inside the Android tree, e.g. "frameworks/base".
check_patch() {
  local project=$1 file=$2
  local rel=${file#"$PATCHES_DIR"/}
  local line lineno=0 a="" b="" header_lineno=0 nested=""
  local in_hunk=0 p

  while IFS= read -r line || [[ -n $line ]]; do
    lineno=$((lineno + 1))

    if [[ $line =~ ^diff[[:space:]]--git[[:space:]]a/(.+)[[:space:]]b/(.+)$ ]]; then
      a=${BASH_REMATCH[1]}
      b=${BASH_REMATCH[2]}
      header_lineno=$lineno
      in_hunk=0
      HEADERS=$((HEADERS + 1))

      if [[ $a == /* || $b == /* ]]; then
        issue "$rel:$lineno" "absolute diff path (a/$a b/$b)"
      fi
      # The nested-prefix defect: the path already starts with the project
      # directory, so `git am` looks for <project>/<project>/...
      nested=""
      if [[ -n $project ]]; then
        if [[ $a == "$project" || $a == "$project"/* ]]; then nested=$a; fi
        if [[ $b == "$project" || $b == "$project"/* ]]; then nested=${nested:-$b}; fi
      fi
      if [[ -n $nested ]]; then
        issue "$rel:$lineno" \
          "nested project prefix in diff path '$nested' (expected '${nested#"$project"/}')"
      fi
      continue
    fi

    if [[ $line == "diff --git "* ]]; then
      # git quotes paths with spaces or non-ASCII; unquoting is not worth a
      # bespoke parser, but the header still needs a human look.
      warn "$rel:$lineno: skipping quoted/malformed diff header (check it manually)"
      header_lineno=0
      in_hunk=0
      continue
    fi

    # Every line after a hunk header is hunk content: a removed line starting
    # with "--" or an added line starting with "++" must not be read as a
    # ---/+++ file header.
    if [[ $line == @@* ]]; then
      in_hunk=1
      continue
    fi
    if (( in_hunk )); then
      continue
    fi

    # `git am` strips the a/ and b/ prefixes; they must agree with the header.
    # A new file has no base side (/dev/null) and a deleted file no new side.
    if [[ $line == ---\ * && $header_lineno -gt 0 && -n $a ]]; then
      p=${line#--- }
      if [[ $p != /dev/null && $p != "a/$a" ]]; then
        issue "$rel:$lineno" "'--- $p' does not match 'diff --git a/$a'"
      fi
      continue
    fi
    if [[ $line == +++\ * && $header_lineno -gt 0 && -n $b ]]; then
      p=${line#+++ }
      if [[ $p != /dev/null && $p != "b/$b" ]]; then
        issue "$rel:$lineno" "'+++ $p' does not match 'diff --git b/$b'"
      fi
      continue
    fi
  done <"$file"
}

while (( $# > 0 )); do
  case $1 in
    --patches) [[ $# -ge 2 ]] || die "--patches needs a directory"; PATCHES_DIR=$2; shift 2 ;;
    --patches=*) PATCHES_DIR=${1#*=}; shift ;;
    -h|--help) usage 0 ;;
    *) die "unknown argument '$1' (see --help)" ;;
  esac
done

[[ -d $PATCHES_DIR ]] || die "patch directory not found: $PATCHES_DIR"
PATCHES_DIR=$(cd "$PATCHES_DIR" && pwd)

mapfile -t files < <(find "$PATCHES_DIR" -type f -name '*.patch' -print0 |
  LC_ALL=C sort -z | while IFS= read -r -d '' f; do printf '%s\n' "$f"; done)
(( ${#files[@]} )) || { warn "no patches in $PATCHES_DIR (nothing to check)"; exit 0; }

for file in "${files[@]}"; do
  project=$(dirname "${file#"$PATCHES_DIR"/}")
  [[ $project == . ]] && project=""
  PATCHES=$((PATCHES + 1))
  check_patch "$project" "$file"
done

if (( ISSUES > 0 )); then
  die "$ISSUES diff-path problem(s) in $PATCHES patch(es) (see above)"
fi
ok "$PATCHES patch(es), $HEADERS diff header(s): every path is project-relative"
