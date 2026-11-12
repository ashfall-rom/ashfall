#!/usr/bin/env bash
# shellcheck shell=bash
#
# AshFall patch pipeline (issue #4).
#
# Applies the .patch files tracked in this repo (patches/<tree-path>/*.patch)
# to the shared LineageOS tree at $REMOTE_SRC, or to any other tree given with
# --tree. Runs on the build host inside the build container (invoked by
# scripts/build.sh) and locally against any git checkout for testing.
#
# Every patched project is reset to its manifest revision before applying, so
# `apply` is idempotent and always reproduces the same tree. The baseline is
# recorded in <project>/.git/ashfall-patches-base and reused for plain git
# checkouts (tests) where no repo manifest exists.
#
# Exit status: 0 on success, 1 on any patch conflict or resolution error.

set -euo pipefail

ASH_ROOT=${ASH_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
export ASH_ROOT
# shellcheck source=scripts/lib/common.sh
. "$ASH_ROOT/scripts/lib/common.sh"

TREE=""
PATCHES_DIR=""
REV=""
DO_CLEAN=1
KEEP_CONFLICTS=0
ACTION=""

usage() {
  cat >&2 <<'EOF'
Usage: scripts/patches.sh {apply|reset|status} [options]

  apply    reset every patched project to its baseline, then `git am --3way`
           the patches in file order (idempotent)
  reset    restore every patched project to its pristine baseline
  status   report per project: applied/total patches, baseline, HEAD

Options:
  --tree DIR        Android tree to operate on (default: $REMOTE_SRC from
                    config/ashfall.conf)
  --patches DIR     patch root (default: <repo>/patches); for tests
  --rev REV         baseline revision for every project instead of the repo
                    manifest revision (testing / pinning)
  --no-clean        keep untracked files (skip `git clean -fdq`)
  --keep-conflicts  leave the failed `git am` state in place for inspection
  -h, --help        this help

Patch layout: patches/<tree-path>/*.patch, e.g.
  patches/frameworks/base/0001-....patch   ->  <tree>/frameworks/base
EOF
  exit "${1:-0}"
}

# --- discovery --------------------------------------------------------------

relpath() { printf '%s\n' "${2#"$1"/}"; }

# One tree-relative project path per line, sorted and unique.
list_projects() {
  local -a patches=()
  [[ -d $PATCHES_DIR ]] || return 0
  mapfile -t -d '' patches < <(find "$PATCHES_DIR" -type f -name '*.patch' -print0 | LC_ALL=C sort -z)
  (( ${#patches[@]} )) || return 0
  local f
  for f in "${patches[@]}"; do relpath "$PATCHES_DIR" "$(dirname "$f")"; done | LC_ALL=C sort -u
}

# "<n>\t<project path>" per project, sorted.
list_projects_numbered() {
  local rel n=0
  while IFS= read -r rel; do
    n=$((n + 1))
    printf '%s\t%s\n' "$n" "$rel"
  done < <(list_projects)
}

# Sorted patch files of a project, one per line.
project_patches() {
  local rel=$1
  [[ -d $PATCHES_DIR/$rel ]] || return 0
  find "$PATCHES_DIR/$rel" -maxdepth 1 -type f -name '*.patch' -print0 |
    LC_ALL=C sort -z |
    while IFS= read -r -d '' f; do printf '%s\n' "$f"; done
}

project_dir() { printf '%s\n' "$TREE/$1"; }

require_project() {
  local rel=$1 dir
  dir=$(project_dir "$rel")
  [[ -d $dir ]] || die "patch project '$rel' does not exist in tree $TREE (expected $dir)"
  git -C "$dir" rev-parse --git-dir >/dev/null 2>&1 ||
    die "patch project '$rel' is not a git checkout ($dir)"
}

gitdir() { git -C "$(project_dir "$1")" rev-parse --absolute-git-dir; }

# --- baseline resolution ----------------------------------------------------

# Declared manifest revision for a project, via `repo manifest` (the manifest's
# revisions, not the current HEADs, so a patched checkout does not poison the
# baseline). Prints nothing and returns 1 when no repo manifest is available.
manifest_revision() {
  local rel=$1 repo_cmd xml out rc=0
  if [[ -x $TREE/.repo/repo/repo ]]; then
    repo_cmd=$TREE/.repo/repo/repo
  elif command -v repo >/dev/null 2>&1; then
    repo_cmd=$(command -v repo)
  else
    return 1
  fi
  command -v python3 >/dev/null 2>&1 || die "python3 is required to read the repo manifest"

  xml=$(mktemp "${TMPDIR:-/tmp}/ashfall-manifest.XXXXXX")
  if ! ( cd "$TREE" && "$repo_cmd" manifest -o "$xml" ) >/dev/null 2>&1; then
    rm -f "$xml"
    return 1
  fi
  out=$(python3 - "$xml" "$rel" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, want = sys.argv[1], sys.argv[2].strip("/")
root = ET.parse(path).getroot()
default = root.find("default")
default_rev = default.get("revision") if default is not None else None

found = None
for proj in root.iter("project"):
    p = proj.get("path") or proj.get("name")
    if p and p.strip("/") == want:
        found = proj  # last one wins (extend-project)
if found is None:
    sys.exit(3)
rev = found.get("revision") or default_rev
if not rev:
    sys.exit(4)
print(rev)
PY
  ) || rc=$?
  rm -f "$xml"
  [[ $rc -eq 0 && -n $out ]] || return 1
  printf '%s\n' "$out"
}

# Resolve a revision/ref to a commit in the project.
resolve_rev() {
  local dir=$1 rev=$2 sha branch
  if sha=$(git -C "$dir" rev-parse --verify --quiet "$rev^{commit}"); then
    printf '%s\n' "$sha"; return 0
  fi
  branch=${rev#refs/heads/}
  branch=${branch#refs/}
  sha=$(git -C "$dir" for-each-ref --format='%(objectname)' \
    "refs/remotes/*/$branch" "refs/heads/$branch" 2>/dev/null | head -n1)
  [[ -n $sha ]] && { printf '%s\n' "$sha"; return 0; }
  return 1
}

base_file() { printf '%s\n' "$(gitdir "$1")/ashfall-patches-base"; }

# Prints a baseline commit for a project.
resolve_base() {
  local rel=$1 dir rev bf
  dir=$(project_dir "$rel")

  if [[ -n $REV ]]; then
    rev=$REV
  elif [[ -d $TREE/.repo ]]; then
    rev=$(manifest_revision "$rel") ||
      die "project '$rel': cannot read the repo manifest revision (run inside the synced tree, or pass --rev REV)"
  else
    bf=$(base_file "$rel")
    if [[ -s $bf ]]; then
      rev=$(head -n1 "$bf")
    elif git -C "$dir" rev-parse --verify --quiet 'HEAD^{commit}' >/dev/null; then
      rev=$(git -C "$dir" rev-parse 'HEAD^{commit}')
    else
      die "project '$rel': no baseline (no repo manifest, no recorded base, empty repo); pass --rev REV"
    fi
  fi

  resolve_rev "$dir" "$rev" ||
    die "project '$rel': baseline '$rev' does not exist in $dir"
}

store_base() { printf '%s\n' "$2" > "$(base_file "$1")"; }

# --- git helpers ------------------------------------------------------------

GIT=(
  git -c user.name=AshFall
  -c user.email=ashfall@localhost
  -c advice.detachedHead=false
)

am_in_progress() { [[ -d "$(gitdir "$1")/rebase-apply" ]]; }

abort_am() {
  if am_in_progress "$1"; then
    warn "project '$1': aborting in-progress git am"
    git -C "$(project_dir "$1")" am --abort >/dev/null 2>&1 || true
  fi
}

reset_project() { # <rel> <base>
  local rel=$1 base=$2 dir
  dir=$(project_dir "$rel")
  abort_am "$rel"
  log "reset $rel -> ${base:0:12}"
  git -C "$dir" reset --hard --quiet "$base"
  (( DO_CLEAN )) && git -C "$dir" clean -fdq
}

patch_subject() {
  local subj
  subj=$(awk '/^Subject: /{ sub(/^Subject: \[PATCH[^]]*\] */, ""); sub(/\r$/, ""); print; exit }' "$1")
  printf '%s\n' "${subj:-$(basename "$1")}"
}

applied_subjects() { # <rel> <base>
  git -C "$(project_dir "$1")" log --format=%s --no-merges "$2..HEAD" 2>/dev/null || true
}

# --- actions ----------------------------------------------------------------

conflict_report() { # <rel> <base> <amlog> <patch>...
  local rel=$1 base=$2 amlog=$3; shift 3
  local dir; dir=$(project_dir "$rel")
  {
    echo
    echo "=============================================================="
    echo "PATCH CONFLICT"
    echo "  project : $rel"
    echo "  dir     : $dir"
    echo "  base    : $base"
    echo "  patches :"
    printf '    %s\n' "$@"
    if am_in_progress "$rel"; then
      echo "  conflicting files:"
      git -C "$dir" diff --name-only --diff-filter=U 2>/dev/null | sed 's/^/    /' || true
    fi
    echo "  git am output (tail):"
    tail -n 30 "$amlog" 2>/dev/null | sed 's/^/    /' || true
    echo "--------------------------------------------------------------"
    echo "patches/ is owned by issue #5: fix the patch, then rerun."
    echo "To clear the project manually:"
    echo "  git -C $dir am --abort"
    echo "  git -C $dir reset --hard $base"
    echo "=============================================================="
  } >&2
}

do_apply() {
  local rel base dir amlog n_applied n_expected
  local -a files=()

  while IFS=$'\t' read -r _ rel; do
    require_project "$rel"
    dir=$(project_dir "$rel")
    mapfile -t files < <(project_patches "$rel")
    (( ${#files[@]} )) || continue

    base=$(resolve_base "$rel")
    reset_project "$rel" "$base"
    store_base "$rel" "$base"

    amlog=$(mktemp "${TMPDIR:-/tmp}/ashfall-am.XXXXXX")
    log "apply $rel: ${#files[@]} patch(es) on ${base:0:12}"
    if ! "${GIT[@]}" -C "$dir" am --3way --empty=stop "${files[@]}" >"$amlog" 2>&1; then
      conflict_report "$rel" "$base" "$amlog" "${files[@]}"
      rm -f "$amlog"
      if (( KEEP_CONFLICTS )); then
        warn "leaving git am state in $dir (--keep-conflicts)"
      else
        abort_am "$rel"
        git -C "$dir" reset --hard --quiet "$base"
        (( DO_CLEAN )) && git -C "$dir" clean -fdq
      fi
      return 1
    fi
    rm -f "$amlog"

    # git am also reports success for a patch that it considers already
    # applied, silently producing no commit. We use one commit per patch file,
    # so the counts must match or the tree would not contain what patches/
    # promises (and every later build would be non-reproducible).
    n_expected=${#files[@]}
    n_applied=$(git -C "$dir" rev-list --count "$base..HEAD")
    if (( n_applied != n_expected )); then
      {
        echo
        echo "=============================================================="
        echo "PATCH SERIES INCOMPLETE"
        echo "  project : $rel"
        echo "  dir     : $dir"
        echo "  base    : $base"
        echo "  expected: $n_expected commit(s), one per patch file"
        echo "  produced: $n_applied commit(s)"
        echo "  A patch is empty, already applied, or an mbox containing"
        echo "  several commits. Use one git-format-patch commit per file."
        echo "  patches :"
        printf '    %s\n' "${files[@]}"
        echo "=============================================================="
      } >&2
      abort_am "$rel" || true
      git -C "$dir" reset --hard --quiet "$base"
      (( DO_CLEAN )) && git -C "$dir" clean -fdq
      return 1
    fi
    ok "$rel: $n_applied patch(es) applied"
    continue
  done < <(list_projects_numbered)

  return 0
}

do_reset() {
  local rel base
  while IFS=$'\t' read -r _ rel; do
    require_project "$rel"
    base=$(resolve_base "$rel")
    reset_project "$rel" "$base"
    store_base "$rel" "$base"
    ok "$rel: at ${base:0:12}"
  done < <(list_projects_numbered)
}

do_status() {
  local rel base dir head subj subjects_out applied_total=0 projects=0 incomplete=0
  local -a files=() subjects=()
  while IFS=$'\t' read -r _ rel; do
    require_project "$rel"
    dir=$(project_dir "$rel")
    mapfile -t files < <(project_patches "$rel")
    (( ${#files[@]} )) || continue
    projects=$((projects + 1))

    if ! base=$(resolve_base "$rel" 2>/dev/null); then
      warn "$rel: ${#files[@]} patch(es), baseline UNKNOWN"
      incomplete=1
      continue
    fi
    subjects_out=$(applied_subjects "$rel" "$base")
    head=$(git -C "$dir" rev-parse --short=12 HEAD 2>/dev/null || echo '?')
    mapfile -t subjects < <(for subj in "${files[@]}"; do patch_subject "$subj"; done)

    applied_total=0
    for subj in "${subjects[@]}"; do
      if grep -Fxq -- "$subj" <<<"$subjects_out"; then
        applied_total=$((applied_total + 1))
      else
        break
      fi
    done

    printf '%-42s %d/%d applied  base %s  head %s%s\n' \
      "$rel" "$applied_total" "${#files[@]}" "${base:0:12}" "$head" \
      "$(am_in_progress "$rel" && echo '  [git am in progress]' || true)"
    if (( applied_total != ${#files[@]} )); then
      incomplete=1
      local i=0
      for subj in "${subjects[@]}"; do
        i=$((i + 1))
        (( i > applied_total )) && printf '    missing: %s\n' "$subj"
      done
    fi
  done < <(list_projects_numbered)

  if (( projects == 0 )); then
    ok "no patches in $PATCHES_DIR (nothing to do)"
  elif (( incomplete )); then
    warn "some projects are not fully patched (see above)"
  else
    ok "all patch projects are fully applied"
  fi
  return 0
}

# --- main -------------------------------------------------------------------

(( $# > 0 )) || usage 1
ACTION=$1; shift
case $ACTION in
  apply|reset|status) ;;
  -h|--help|help) usage 0 ;;
  *) die "unknown action '$ACTION' (use apply|reset|status; --help)" ;;
esac

while (( $# > 0 )); do
  case $1 in
    --tree) [[ $# -ge 2 ]] || die "--tree needs a directory"; TREE=$2; shift 2 ;;
    --tree=*) TREE=${1#*=}; shift ;;
    --patches) [[ $# -ge 2 ]] || die "--patches needs a directory"; PATCHES_DIR=$2; shift 2 ;;
    --patches=*) PATCHES_DIR=${1#*=}; shift ;;
    --rev) [[ $# -ge 2 ]] || die "--rev needs a revision"; REV=$2; shift 2 ;;
    --rev=*) REV=${1#*=}; shift ;;
    --no-clean) DO_CLEAN=0; shift ;;
    --keep-conflicts) KEEP_CONFLICTS=1; shift ;;
    -h|--help) usage 0 ;;
    *) die "unknown argument '$1' (see --help)" ;;
  esac
done

if [[ -z $TREE ]]; then
  load_config
  TREE=$REMOTE_SRC
fi
[[ -d $TREE ]] || die "tree directory not found: $TREE"
TREE=$(cd "$TREE" && pwd)

if [[ -z $PATCHES_DIR ]]; then
  PATCHES_DIR=$ASH_ROOT/patches
  if [[ ! -d $PATCHES_DIR ]]; then
    warn "no patches directory yet ($PATCHES_DIR) - nothing to do"
    exit 0
  fi
else
  [[ -d $PATCHES_DIR ]] || die "patch directory not found: $PATCHES_DIR"
  PATCHES_DIR=$(cd "$PATCHES_DIR" && pwd)
fi

need git

case $ACTION in
  apply)
    log "patches: $PATCHES_DIR -> $TREE"
    do_apply
    ok "all patches applied"
    ;;
  reset)
    log "reset patched projects in $TREE"
    do_reset
    ok "patched projects are pristine"
    ;;
  status)
    do_status
    ;;
esac
