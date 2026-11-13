#!/usr/bin/env bash
# shellcheck shell=bash
#
# AshFall build pipeline (issue #4).
#
# Runs FROM THIS CHECKOUT (the orchestrator VM). It pushes the checkout to the
# build host, then launches the actual build as a detached job inside the build
# container (distrobox `ashfall-build`), holding the shared tree lock for the
# whole overlay/patch/build window:
#
#   ws_push -> overlay aosp/ -> patches.sh apply -> source build/envsetup.sh
#           -> lunch <product>-<release>-<variant> -> m systemimage
#           -> collect $REMOTE_ARTIFACTS/<build-id>/
#
# Usage:
#   scripts/build.sh [--clean] [--jobs N] [--variant userdebug] [--product P]
#                    [--xz] [--dry-run] [--build-id ID] [--local-tree DIR]
#   scripts/build.sh --fetch <build-id> [--wait]
#   scripts/build.sh --help
#
# See docs/building.md.

set -euo pipefail

ASH_ROOT=${ASH_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
export ASH_ROOT
# shellcheck source=scripts/lib/common.sh
. "$ASH_ROOT/scripts/lib/common.sh"

DO_CLEAN=0
JOBS=""
VARIANT=""
PRODUCT=""
DO_XZ=0
FS="erofs"
DRY_RUN=0
BUILD_ID=""
FETCH_ID=""
FETCH_WAIT=0
LOCAL_TREE=""

usage() {
  cat >&2 <<'EOF'
Usage: scripts/build.sh [options]
       scripts/build.sh --fetch <build-id> [--wait]

Build options:
  --clean            wipe <tree>/out before building (full rebuild; ccache
                     still helps). Default: incremental build in the shared tree.
  --jobs N           parallel jobs (default: BUILD_JOBS from config, else nproc)
  --variant V        build variant (default: GSI_VARIANT, userdebug)
  --product P        product (default: GSI_PRODUCT, ashfall_gsi_arm64)
  --fs erofs|ext4    system.img filesystem (default: erofs; ext4 is the
                     fallback for kernels without EROFS)
  --xz               also produce system.img.xz in the artifact directory
  --build-id ID      explicit build id (default: <utc>-<product>-<variant>-<sha>)
  --dry-run          print the plan (paths, lunch release, commands) and exit;
                     touches nothing
  --local-tree DIR   run the same pipeline against a tree on THIS machine
                     (testing only; skips ssh/rsync/ws_push)
  -h, --help         this help

Image size budget (stock super group leaves ~4.2 GiB for system.img):
  ASH_IMAGE_WARN_GIB  warn above this many GiB (default 3.8)
  ASH_IMAGE_MAX_GIB   fail above this many GiB (default 4.2)

Artifacts:
  --fetch <build-id> rsync $REMOTE_ARTIFACTS/<build-id> into out/<build-id>
  --wait             with --fetch: wait for the job to finish first

The build runs detached; build.sh prints the build id, log path and how to
attach. Everything is logged under $REMOTE_LOGS/<build-id>/.
EOF
  exit "${1:-0}"
}

# --- arguments --------------------------------------------------------------

while (( $# > 0 )); do
  case $1 in
    --clean) DO_CLEAN=1; shift ;;
    --jobs) [[ $# -ge 2 ]] || die "--jobs needs a number"; JOBS=$2; shift 2 ;;
    --jobs=*) JOBS=${1#*=}; shift ;;
    --variant) [[ $# -ge 2 ]] || die "--variant needs a value"; VARIANT=$2; shift 2 ;;
    --variant=*) VARIANT=${1#*=}; shift ;;
    --product) [[ $# -ge 2 ]] || die "--product needs a value"; PRODUCT=$2; shift 2 ;;
    --product=*) PRODUCT=${1#*=}; shift ;;
    --fs) [[ $# -ge 2 ]] || die "--fs needs erofs or ext4"; FS=$2; shift 2 ;;
    --fs=*) FS=${1#*=}; shift ;;
    --xz) DO_XZ=1; shift ;;
    --build-id) [[ $# -ge 2 ]] || die "--build-id needs a value"; BUILD_ID=$2; shift 2 ;;
    --build-id=*) BUILD_ID=${1#*=}; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --local-tree) [[ $# -ge 2 ]] || die "--local-tree needs a directory"; LOCAL_TREE=$2; shift 2 ;;
    --local-tree=*) LOCAL_TREE=${1#*=}; shift ;;
    --fetch) [[ $# -ge 2 ]] || die "--fetch needs a build id"; FETCH_ID=$2; shift 2 ;;
    --fetch=*) FETCH_ID=${1#*=}; shift ;;
    --wait) FETCH_WAIT=1; shift ;;
    -h|--help) usage 0 ;;
    *) die "unknown argument '$1' (see --help)" ;;
  esac
done

load_config

[[ -z $VARIANT ]] && VARIANT=$GSI_VARIANT
[[ -z $PRODUCT ]] && PRODUCT=$GSI_PRODUCT
[[ -n $JOBS ]] || JOBS=$BUILD_JOBS

case $VARIANT in
  user|userdebug|eng) ;;
  *) die "unsupported variant '$VARIANT' (user|userdebug|eng)" ;;
esac
[[ -n $PRODUCT ]] || die "no product: pass --product or set GSI_PRODUCT"
[[ -z $JOBS || $JOBS =~ ^[0-9]+$ ]] || die "--jobs must be a number (got '$JOBS')"
case $FS in
  erofs|ext4) ;;
  *) die "unsupported --fs '$FS' (erofs|ext4)" ;;
esac
ASH_IMAGE_WARN_GIB=${ASH_IMAGE_WARN_GIB:-3.8}
ASH_IMAGE_MAX_GIB=${ASH_IMAGE_MAX_GIB:-4.2}
for v in ASH_IMAGE_WARN_GIB ASH_IMAGE_MAX_GIB; do
  [[ ${!v} =~ ^[0-9]+([.][0-9]+)?$ ]] || die "$v must be a number in GiB (got '${!v}')"
done
awk -v w="$ASH_IMAGE_WARN_GIB" -v m="$ASH_IMAGE_MAX_GIB" 'BEGIN { exit !(w <= m) }' ||
  die "ASH_IMAGE_WARN_GIB ($ASH_IMAGE_WARN_GIB) must be <= ASH_IMAGE_MAX_GIB ($ASH_IMAGE_MAX_GIB)"

# --- helpers ----------------------------------------------------------------

need rsync
if [[ -z $LOCAL_TREE ]]; then need ssh; fi

GIT_SHA=$(git -C "$ASH_ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo nogit)
GIT_BRANCH=$(git -C "$ASH_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)
GIT_DIRTY=""
ID_DIRTY=""
if [[ -n $(git -C "$ASH_ROOT" status --porcelain 2>/dev/null) ]]; then
  GIT_DIRTY="+dirty"
  ID_DIRTY="-dirty"
fi

make_id() {
  printf '%s-%s-%s-%s%s\n' \
    "$(date -u +%Y%m%d-%H%M%S)" "$PRODUCT" "$VARIANT" "$GIT_SHA" "$ID_DIRTY"
}

validate_id() {
  [[ $1 =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "invalid build id '$1'"
}

# --- fetch mode -------------------------------------------------------------

do_fetch() {
  local id=$1 dst status_rc="" log_tail
  validate_id "$id"
  dst="$LOCAL_ARTIFACTS/$id"
  if (( FETCH_WAIT )); then
    log "waiting for build $id to finish (log: $REMOTE_LOGS/$id/build.log)"
    while ! host_sh "test -f $(printf %q "$REMOTE_LOGS/$id/status")"; do
      host_sh "test -f $(printf %q "$REMOTE_LOGS/$id/build.log") && tail -n 1 $(printf %q "$REMOTE_LOGS/$id/build.log") || true" >&2 || true
      sleep 30
    done
  fi
  status_rc=$(host_sh "cat $(printf %q "$REMOTE_LOGS/$id/status") 2>/dev/null" | tr -d '[:space:]' || true)
  if [[ -n $status_rc ]]; then
    log "job exit code: $status_rc"
    [[ $status_rc == 0 ]] || warn "build $id failed (rc=$status_rc); pulling partial artifacts"
  else
    warn "no status file for $id yet (build still running? use --wait)"
  fi
  host_sh "test -d $(printf %q "$REMOTE_ARTIFACTS/$id")" ||
    die "no artifacts at $REMOTE_ARTIFACTS/$id on the build host (is the build id right, is the job done?)"
  mkdir -p "$dst"
  pull_artifacts "$REMOTE_ARTIFACTS/$id" "$dst" >/dev/null
  ok "artifacts in $dst"
  ( cd "$dst" && ls -lh && { [[ -f system.img.sha256 ]] && echo "sha256: $(cat system.img.sha256)" || true; } ) >&2
  if [[ ! -f $dst/system.img ]]; then
    log_tail=$(host_sh "tail -n 20 $(printf %q "$REMOTE_LOGS/$id/build.log") 2>/dev/null" || true)
    warn "no system.img in the artifacts - the build did not finish"
    [[ -n $log_tail ]] && printf '%s\n' "$log_tail" | sed 's/^/  log| /' >&2
    return 1
  fi
  [[ -z $status_rc || $status_rc == 0 ]] || return 1
}

if [[ -n $FETCH_ID ]]; then
  do_fetch "$FETCH_ID"
  exit 0
fi

# --- plan -------------------------------------------------------------------

if [[ -z $BUILD_ID ]]; then
  BUILD_ID=$(make_id)
  if [[ -n $LOCAL_TREE ]]; then BUILD_ID=$BUILD_ID-local; fi
fi
validate_id "$BUILD_ID"

if [[ -n $LOCAL_TREE ]]; then
  MODE=local
  TREE=$(cd "$LOCAL_TREE" && pwd)
  WS=$ASH_ROOT
  OUT_ROOT=$ASH_ROOT/out
  JOB_DIR=$OUT_ROOT/.tmp/$BUILD_ID
  BUILD_LOG=$JOB_DIR/build.log
  BUILD_STATUS=$JOB_DIR/status
  ARTIFACT_DIR=$OUT_ROOT/$BUILD_ID
  TREE_LOCK=$OUT_ROOT/.tree.lock
else
  MODE=remote
  TREE=$REMOTE_SRC
  WS=$REMOTE_WS/$(printf '%s' "$GIT_BRANCH" | tr '/' '-')
  OUT_ROOT=$LOCAL_ARTIFACTS
  JOB_DIR=$REMOTE_LOGS/$BUILD_ID
  BUILD_LOG=$REMOTE_LOGS/$BUILD_ID/build.log
  BUILD_STATUS=$REMOTE_LOGS/$BUILD_ID/status
  ARTIFACT_DIR=$REMOTE_ARTIFACTS/$BUILD_ID
  TREE_LOCK=$REMOTE_TREE_LOCK
fi

# Overlay directories that are mirrored with --delete (only ever our own dirs,
# never the tree root).
OVERLAY_DELETE_DIRS="vendor/ashfall"

print_plan() {
  cat >&2 <<EOF

AshFall GSI build plan
  mode          : $MODE
  build id      : $BUILD_ID
  product       : $PRODUCT
  variant       : $VARIANT
  system fs     : $FS (ext4 fallback via --fs ext4)
  release       : ${LUNCH_RELEASE:-auto-detect (vendor/lineage/vars/aosp_target_release, then release configs)}
  jobs          : ${JOBS:-auto (nproc)}
  clean build   : $([[ $DO_CLEAN == 1 ]] && echo yes || echo no)
  xz            : $([[ $DO_XZ == 1 ]] && echo yes || echo no)
  image budget  : warn > ${ASH_IMAGE_WARN_GIB} GiB, fail > ${ASH_IMAGE_MAX_GIB} GiB
  ashfall commit: $GIT_SHA$GIT_DIRTY ($GIT_BRANCH)
  ashfall ver   : $ASH_VERSION
  ws            : $WS
  tree          : $TREE
  artifacts     : $ARTIFACT_DIR
  log           : $BUILD_LOG
  overlay       : $WS/aosp/ -> $TREE (rsync --delete for: $OVERLAY_DELETE_DIRS)
  patches       : $WS/scripts/patches.sh apply --tree $TREE

Steps inside the job (holding $TREE_LOCK):
  1. rsync overlay, patches.sh apply  (lock: $TREE_LOCK)
  2. source build/envsetup.sh; lunch $PRODUCT-<release>-$VARIANT
  3. assert BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE == $FS
  4. m -j<jobs> systemimage
  5. sha256/build.prop/installed-files/system_image_info.txt -> artifacts
EOF
}

# --- remote preflight -------------------------------------------------------

preflight() {
  local box_names
  log "checking build host $BUILD_HOST"
  host_sh 'true' || die "cannot ssh to $BUILD_HOST"
  host_sh "test -d $(printf %q "$TREE/.repo")" ||
    die "no synced LineageOS tree at $TREE on $BUILD_HOST (issue #1: scripts/sync.sh)"
  host_sh "test -f $(printf %q "$TREE/build/envsetup.sh")" ||
    die "$TREE/build/envsetup.sh missing - tree is not usable yet (sync still running?)"
  host_sh "test -f $(printf %q "$TREE/vendor/lineage/build/target/product/lineage_gsi_arm64.mk")" ||
    die "$TREE is not usable yet: vendor/lineage/build/target/product/lineage_gsi_arm64.mk is missing (sync still running? see issue #1)"
  box_names=$(host_sh "distrobox list 2>/dev/null" | awk -F'|' 'NF>=2 {gsub(/^[ \t]+|[ \t]+$/,"",$2); print $2}' || true)
  if ! grep -qx -- "$BUILD_BOX" <<<"$box_names"; then
    die "distrobox container '$BUILD_BOX' not found on $BUILD_HOST (issue #1: scripts/host/)"
  fi
  box_sh "command -v repo >/dev/null" ||
    die "'repo' is not installed in the build box $BUILD_BOX (issue #1: scripts/host/)"
  # Building on a tree that is still being synced produces hours of confusing
  # failures and races with the sync writer; refuse instead.
  # Only python3 processes are inspected: pgrep -f over the whole command line
  # would also match the shell that runs this very probe (it contains both the
  # pattern and the tree path), which made the guard a false positive.
  if host_sh "pgrep -x python3 -a 2>/dev/null | grep -F -- $(printf %q "--repo-dir=$TREE/.repo") | grep -F -q -- '-- sync'"; then
    if [[ ${ASH_ALLOW_UNSYNCED:-0} != 1 ]]; then
      die "a 'repo sync' is running on $BUILD_HOST; refusing to build a half-synced tree (wait for issue #1, or set ASH_ALLOW_UNSYNCED=1 if you really mean it)"
    fi
    warn "repo sync is running and ASH_ALLOW_UNSYNCED=1 is set; building anyway"
  fi
  if ! box_sh "flock -n $(printf %q "$REMOTE_TREE_LOCK") true" 2>/dev/null; then
    warn "the shared tree lock is held (sync or another build); this job will queue behind it"
  fi
  ok "host, tree and container are present"
}

# --- run script -------------------------------------------------------------

write_run_script() {
  local dir=$1 run=$1/run.sh
  mkdir -p "$dir"
  {
    cat <<EOF
#!/usr/bin/env bash
# Generated by scripts/build.sh ($BUILD_ID) - do not edit, it is overwritten.
export ASH_TREE=$(printf %q "$TREE")
export ASH_WS=$(printf %q "$WS")
export ASH_BUILD_ID=$(printf %q "$BUILD_ID")
export ASH_PRODUCT=$(printf %q "$PRODUCT")
export ASH_VARIANT=$(printf %q "$VARIANT")
export ASH_RELEASE=$(printf %q "$LUNCH_RELEASE")
export ASH_JOBS=$(printf %q "$JOBS")
export ASH_VERSION=$(printf %q "$ASH_VERSION")
export ASH_VERSION_STR=$(printf %q "$ASH_VERSION")
export ASH_GIT_COMMIT=$(printf %q "$GIT_SHA$GIT_DIRTY")
export ASH_GIT_BRANCH=$(printf %q "$GIT_BRANCH")
export ASH_ARTIFACT_DIR=$(printf %q "$ARTIFACT_DIR")
export ASH_LOG=$(printf %q "$BUILD_LOG")
export ASH_STATUS=$(printf %q "$BUILD_STATUS")
export ASH_TREE_LOCK=$(printf %q "$TREE_LOCK")
export ASH_CCACHE=$(printf %q "$REMOTE_CCACHE")
export ASH_CCACHE_SIZE=$(printf %q "$CCACHE_SIZE")
export ASH_CLEAN=$DO_CLEAN
export ASH_SYSTEM_FS=$(printf %q "$FS")
export ASH_IMAGE_WARN_GIB=$(printf %q "$ASH_IMAGE_WARN_GIB")
export ASH_IMAGE_MAX_GIB=$(printf %q "$ASH_IMAGE_MAX_GIB")
export ASH_XZ=$DO_XZ
export ASH_OVERLAY_DELETE_DIRS=$(printf %q "$OVERLAY_DELETE_DIRS")
export ASH_MIN_FREE_GB=$(printf %q "${ASH_MIN_FREE_GB:-60}")
export LC_ALL=C
export TZ=UTC
EOF
    cat <<'RUNSH'

set -euo pipefail

mkdir -p "$(dirname "$ASH_LOG")" "$(dirname "$ASH_STATUS")" "$ASH_ARTIFACT_DIR"
exec >>"$ASH_LOG" 2>&1
trap 'rc=$?; printf "%s\n" "$rc" > "$ASH_STATUS"; printf "\n==> job finished rc=%s at %s\n" "$rc" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"' EXIT

ts()   { date -u +%Y-%m-%dT%H:%M:%SZ; }
step() { printf '\n===== [%s] %s =====\n' "$(ts)" "$*"; }
info() { printf '[%s] %s\n' "$(ts)" "$*"; }
warn() { printf '[%s] WARNING: %s\n' "$(ts)" "$*"; }
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

BUILD_START=$(date +%s)
step "AshFall GSI build $ASH_BUILD_ID"
info "product=$ASH_PRODUCT variant=$ASH_VARIANT release=${ASH_RELEASE:-auto} jobs=${ASH_JOBS:-auto} fs=$ASH_SYSTEM_FS"
info "ashfall=$ASH_GIT_COMMIT ($ASH_GIT_BRANCH) version=$ASH_VERSION_STR"
info "tree=$ASH_TREE ws=$ASH_WS"
info "host=$(uname -a)"
info "date=$(date -u)"

# --- preconditions ----------------------------------------------------------
step "preconditions"
for c in git rsync python3; do
  command -v "$c" >/dev/null 2>&1 || die "missing command in the build box: $c"
done
[[ -f "$ASH_TREE/build/envsetup.sh" ]] || die "no LineageOS tree at $ASH_TREE (build/envsetup.sh missing)"
[[ -f "$ASH_TREE/vendor/lineage/build/target/product/lineage_gsi_arm64.mk" ]] || \
  die "$ASH_TREE is not LineageOS 24.0+ (vendor/lineage/build/target/product/lineage_gsi_arm64.mk missing)"

avail_kb=$(df -Pk "$ASH_TREE" | awk 'NR==2 {print $4}')
avail_gb=$(( ${avail_kb:-0} / 1024 / 1024 ))
info "free space on the tree filesystem: ${avail_gb} GiB (need ${ASH_MIN_FREE_GB} GiB)"
(( avail_gb >= ASH_MIN_FREE_GB )) || die "not enough free space on $ASH_TREE (${avail_gb} GiB < ${ASH_MIN_FREE_GB} GiB); override with ASH_MIN_FREE_GB"

# --- tree lock (held for overlay + patch + build) ---------------------------
step "tree lock"
mkdir -p "$(dirname "$ASH_TREE_LOCK")"
exec 9>"$ASH_TREE_LOCK"
if ! flock -n 9; then
  info "tree lock is busy (another build/sync); waiting..."
  flock 9
fi
info "lock held: $ASH_TREE_LOCK"
export ASH_TREE_LOCK_HELD=1
LOCK_ACQUIRED=$(ts)
info "lock acquired at $LOCK_ACQUIRED"

# --- overlay ---------------------------------------------------------------
step "overlay $ASH_WS/aosp -> $ASH_TREE"
if [[ -d "$ASH_WS/aosp" ]] && [[ -n $(ls -A "$ASH_WS/aosp" 2>/dev/null) ]]; then
  rsync -a "$ASH_WS/aosp/" "$ASH_TREE/"
  for d in $ASH_OVERLAY_DELETE_DIRS; do
    [[ -d "$ASH_WS/aosp/$d" ]] || continue
    info "mirroring $d with --delete"
    rsync -a --delete "$ASH_WS/aosp/$d/" "$ASH_TREE/$d/"
  done
  ( cd "$ASH_WS/aosp" && find . -type f -printf '  %p\n' | LC_ALL=C sort )
else
  info "warning: $ASH_WS/aosp is empty or missing; nothing overlaid"
fi

# --- patches ---------------------------------------------------------------
step "patches"
bash "$ASH_WS/scripts/patches.sh" apply --tree "$ASH_TREE"

# --- envsetup / lunch ------------------------------------------------------
step "envsetup + lunch"
cd "$ASH_TREE"
set +u  # build/envsetup.sh and lunch are not set -u clean
# shellcheck disable=SC1091
source build/envsetup.sh

get_var() { get_build_var "$1" 2>/dev/null | tr -d '\r' | tail -n1; }

try_lunch() { # try_lunch <product> <release> <variant>
  local p=$1 r=$2 v=$3 got
  info "lunch $p-$r-$v"
  if ! lunch "$p-$r-$v" >/dev/null 2>&1; then
    info "  lunch failed"
    return 1
  fi
  got=$(get_var TARGET_PRODUCT)
  [[ $got == "$p" ]] || { info "  lunched '$got' instead of '$p'"; return 1; }
  # lunch exports TARGET_RELEASE, but get_build_var does not expose it on
  # lineage-24.0 (core/release_config.mk consumes and unsets it while make
  # parses), so verify the value lunch just put in the environment.
  [[ ${TARGET_RELEASE:-} == "$r" ]] ||
    { info "  release is '${TARGET_RELEASE:-}' instead of '$r'"; return 1; }
  return 0
}

# Prints "<release>\t<source>" or nothing. LineageOS 24.0 keeps the release it
# builds with in vendor/lineage/vars/aosp_target_release (e.g.
# "aosp_target_release=cp2a"); the release configs themselves live in
# build/release (AOSP, pinned by the manifest) and vendor/lineage/release.
detect_release() {
  local f=$ASH_TREE/vendor/lineage/vars/aosp_target_release found
  if [[ -s $f ]]; then
    # format: aosp_target_release=cp2a
    found=$(sed -n 's/^[[:space:]]*[a-zA-Z_]*release[[:space:]]*=[[:space:]]*//p' "$f" | tr -d '[:space:]' | head -n1)
    if [[ -n $found ]]; then printf '%s\tvendor/lineage/vars/aosp_target_release\n' "$found"; return 0; fi
  fi
  # LineageOS-specific release configs take precedence over AOSP's.
  found=$({ ls -1 "$ASH_TREE"/vendor/lineage/release/release_configs/*.textproto 2>/dev/null || true; } |
    sed -n 's#.*/\([^/]*\)\.textproto#\1#p' | grep -Ev '^(user|userdebug|eng|trunk_staging)$' |
    LC_ALL=C sort -u | tail -n1)
  if [[ -z $found ]]; then
    found=$({ ls -1 "$ASH_TREE"/build/release/release_configs/*.textproto 2>/dev/null || true; } |
      sed -n 's#.*/\([^/]*\)\.textproto#\1#p' | grep -Ev '^(user|userdebug|eng|trunk_staging)$' |
      LC_ALL=C sort -u | tail -n1)
  fi
  [[ -n $found ]] && printf '%s\tguessed from release_configs/*.textproto\n' "$found"
  return 0
}

# lineage-24.0's lunch() only accepts <product>-<release>-<variant>: the legacy
# two-part form is rejected with "Invalid lunch combo" before any release
# defaulting, so every attempt below names a release explicitly (issue #13).
LUNCH_OK=0
candidates=()
if [[ -n $ASH_RELEASE ]]; then
  candidates+=("$ASH_RELEASE")
  info "release from config: $ASH_RELEASE"
else
  detected=$(detect_release || true)
  if [[ -n $detected ]]; then
    candidates+=("${detected%%$'\t'*}")
    info "auto-detected release config: ${detected%%$'\t'*} (from ${detected#*$'\t'})"
  fi
fi
# AOSP always ships trunk_staging; it is the safest last resort even though
# LineageOS itself builds with its own release config.
candidates+=("trunk_staging")

ordered=()
declare -A _seen=()
for c in "${candidates[@]}"; do
  [[ -n $c ]] || continue
  [[ -n ${_seen[$c]:-} ]] && continue
  _seen[$c]=1
  ordered+=("$c")
done

for c in "${ordered[@]}"; do
  if try_lunch "$ASH_PRODUCT" "$c" "$ASH_VARIANT"; then
    LUNCH_OK=1
    RELEASE_USED=$c
    [[ $c == "${ordered[0]}" ]] || info "release fallback: $c"
    break
  fi
  info "release '$c' rejected by the tree; trying the next candidate"
done
(( LUNCH_OK )) || \
  die "cannot lunch $ASH_PRODUCT-<release>-$ASH_VARIANT: no usable release config (tried: ${ordered[*]}); set LUNCH_RELEASE in config/ashfall.conf"


TARGET_DEVICE_USED=$(get_var TARGET_DEVICE)
TARGET_ARCH_USED=$(get_var TARGET_ARCH)
SYSTEM_FS=$(get_var BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE)
SOONG_SYSIMG=$(get_var USE_SOONG_DEFINED_SYSTEM_IMAGE)
PRODUCT_OUT=$(get_var PRODUCT_OUT)

info "TARGET_PRODUCT=$ASH_PRODUCT TARGET_RELEASE=$RELEASE_USED TARGET_BUILD_VARIANT=$ASH_VARIANT"
info "TARGET_DEVICE=$TARGET_DEVICE_USED TARGET_ARCH=$TARGET_ARCH_USED"
info "USE_SOONG_DEFINED_SYSTEM_IMAGE=${SOONG_SYSIMG:-<unset>}"
info "BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE=${SYSTEM_FS:-<unset>}"
[[ -n $PRODUCT_OUT ]] || die "PRODUCT_OUT is empty - lunch did not complete"

# The image must be the filesystem we asked for: the product mk maps
# ASH_SYSTEM_FS to GSI_FILE_SYSTEM_TYPE, and BoardConfigGsiCommon.mk turns that
# into BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE. A silent fallback to the tree default
# (ext4) would be a broken build for a `--fs erofs` run, so fail early.
[[ $SYSTEM_FS == "$ASH_SYSTEM_FS" ]] || \
  die "system image filesystem is '${SYSTEM_FS:-unset}', expected '$ASH_SYSTEM_FS' (set via --fs / ASH_SYSTEM_FS)"
info "'$ASH_SYSTEM_FS' system image confirmed by the build system"

# --- ccache ---------------------------------------------------------------
step "ccache"
if command -v ccache >/dev/null 2>&1; then
  export USE_CCACHE=1
  export CCACHE_EXEC
  CCACHE_EXEC=$(command -v ccache)
  export CCACHE_DIR="$ASH_CCACHE"
  mkdir -p "$CCACHE_DIR"
  ccache --max-size="$ASH_CCACHE_SIZE" >/dev/null
  info "ccache dir=$CCACHE_DIR max-size=$ASH_CCACHE_SIZE"
  ccache --show-stats | sed -n '1,12p'
else
  info "warning: ccache is not installed in the build box; building without it"
fi

# --- clean ----------------------------------------------------------------
if [[ $ASH_CLEAN == 1 ]]; then
  step "clean: removing the build output directory"
  # OUT_DIR may be absolute or relative; never delete anything outside the
  # tree (a shared tree must not lose e.g. the repo checkout).
  OUT_DIR_USED=$(get_var OUT_DIR)
  [[ -n $OUT_DIR_USED ]] || die "--clean: cannot determine OUT_DIR"
  case $OUT_DIR_USED in
    /*) ;;
    *) OUT_DIR_USED="$ASH_TREE/$OUT_DIR_USED" ;;
  esac
  TREE_REAL=$(realpath -m "$ASH_TREE")
  OUT_REAL=$(realpath -m "$OUT_DIR_USED")
  [[ $OUT_REAL == "$TREE_REAL"/* && $OUT_REAL != "$TREE_REAL" ]] ||
    die "--clean: refusing to wipe '$OUT_REAL' (outside $ASH_TREE)"
  info "removing $OUT_REAL"
  rm -rf "$OUT_REAL"
  info "out/ wiped"
fi

# --- build ----------------------------------------------------------------
BUILD_STARTED=$(date +%s)
step "build: m systemimage"
if [[ -n $ASH_JOBS ]]; then JOBFLAG=(-j "$ASH_JOBS"); else JOBFLAG=(-j "$(nproc)"); fi
info "m ${JOBFLAG[*]} systemimage"
m "${JOBFLAG[@]}" systemimage || die "m systemimage failed"
BUILD_FINISHED=$(date +%s)
info "systemimage done in $((BUILD_FINISHED - BUILD_STARTED))s"

step "optional: vbmeta"
if m "${JOBFLAG[@]}" vbmetaimage; then
  info "vbmeta image built"
else
  info "vbmetaimage not available for this target (expected for a GSI) - continuing"
fi

# --- collect --------------------------------------------------------------
step "collect artifacts -> $ASH_ARTIFACT_DIR"
SYSTEM_IMG="$PRODUCT_OUT/system.img"
[[ -f $SYSTEM_IMG ]] || die "no $SYSTEM_IMG after a successful build"
cp -f "$PRODUCT_OUT/system.img" "$ASH_ARTIFACT_DIR/system.img"
( cd "$ASH_ARTIFACT_DIR" && sha256sum system.img > system.img.sha256 )

for f in \
  "$PRODUCT_OUT/system/build.prop" \
  "$PRODUCT_OUT/installed-files.txt" \
  "$PRODUCT_OUT/installed-files.json" \
  "$PRODUCT_OUT/obj/PACKAGING/system_intermediates/system_image_info.txt" \
  "$PRODUCT_OUT/obj/PACKAGING/system_intermediates/file_list.txt" \
  "$PRODUCT_OUT/vbmeta.img"
do
  [[ -f $f ]] || continue
  cp -f "$f" "$ASH_ARTIFACT_DIR/$(basename "$f")"
done

# The GSI forces product/system_ext under /system, and Android 17 keeps their
# build.prop at <partition>/etc/build.prop. ro.ashfall.version/build.id live in
# the *product* one, so collect them under distinct names and verify they made
# it into the image.
ASHFALL_VERSION_IN_IMAGE=""
for part in product system_ext; do
  src="$PRODUCT_OUT/system/$part/etc/build.prop"
  [[ -f $src ]] || continue
  cp -f "$src" "$ASH_ARTIFACT_DIR/$part-build.prop"
  info "collected $part build.prop ($src)"
done
PRODUCT_PROPS="$PRODUCT_OUT/system/product/etc/build.prop"
if [[ -f $PRODUCT_PROPS ]]; then
  ASHFALL_VERSION_IN_IMAGE=$(sed -n 's/^ro\.ashfall\.version=//p' "$PRODUCT_PROPS" | head -n1)
  ASHFALL_BUILD_ID_IN_IMAGE=$(sed -n 's/^ro\.ashfall\.build\.id=//p' "$PRODUCT_PROPS" | head -n1)
  info "image props: ro.ashfall.version=${ASHFALL_VERSION_IN_IMAGE:-<missing>} ro.ashfall.build.id=${ASHFALL_BUILD_ID_IN_IMAGE:-<missing>}"
  [[ -n $ASHFALL_VERSION_IN_IMAGE ]] ||
    die "ro.ashfall.version is missing from $PRODUCT_PROPS (product makefiles not applied?)"
  grep -q 'product/etc/build.prop' "$PRODUCT_OUT/installed-files.txt" 2>/dev/null ||
    warn "product/etc/build.prop is not in installed-files.txt; the image may not carry the AshFall props"
else
  warn "no $PRODUCT_PROPS; product partition properties were not generated"
fi

# Independent proof of the filesystem, not just the make variable: read the
# superblock magic (ext4: 0xEF53 at +1080, EROFS: 0xE0F5E1E2 at +1024). This
# does not depend on file(1)'s magic database.
image_fs() {
  python3 - "$1" <<'IMGFS'
import sys

path = sys.argv[1]
try:
    with open(path, "rb") as f:
        f.seek(1024)
        sb = f.read(64)
except OSError:
    print("unreadable")
    sys.exit(0)

if sb[:4] in (b"\xe2\xe1\xf5\xe0", b"\xe0\xf5\xe1\xe2"):
    print("erofs")
elif len(sb) >= 58 and sb[56:58] == b"\x53\xef":
    print("ext4")
else:
    print("unknown")
IMGFS
}

IMG_SIZE=$(stat -c %s "$ASH_ARTIFACT_DIR/system.img")
IMG_GIB=$(awk -v b="$IMG_SIZE" 'BEGIN { printf "%.2f", b / 1073741824 }')
IMG_FS=$(image_fs "$ASH_ARTIFACT_DIR/system.img")
IMG_TYPE=$(file -b "$ASH_ARTIFACT_DIR/system.img" 2>/dev/null || echo "$IMG_FS (file(1) not installed)")
info "system.img: $IMG_FS, ${IMG_GIB} GiB ($IMG_SIZE bytes)"
info "file(1): $IMG_TYPE"
[[ $IMG_FS == "$ASH_SYSTEM_FS" ]] ||
  die "system.img is '$IMG_FS', expected '$ASH_SYSTEM_FS' (wrong GSI_FILE_SYSTEM_TYPE?)"

# Size budget: the stock super group is 6.0 GiB and a GSI replaces system_a,
# leaving ~4.2 GiB for system.img (docs/stock-analysis.md, docs/PLAN.md).
IMAGE_SIZE_CHECK=ok
if awk -v g="$IMG_GIB" -v m="$ASH_IMAGE_MAX_GIB" 'BEGIN { exit !(g > m) }'; then
  die "system.img is ${IMG_GIB} GiB, over the ${ASH_IMAGE_MAX_GIB} GiB budget (super has ~4.2 GiB for system); shrink the build or raise ASH_IMAGE_MAX_GIB deliberately"
elif awk -v g="$IMG_GIB" -v w="$ASH_IMAGE_WARN_GIB" 'BEGIN { exit !(g > w) }'; then
  IMAGE_SIZE_CHECK=warning
  warn "system.img is ${IMG_GIB} GiB, above the ${ASH_IMAGE_WARN_GIB} GiB comfort limit (hard limit ${ASH_IMAGE_MAX_GIB} GiB)"
else
  info "system.img fits the size budget (${IMG_GIB} GiB <= ${ASH_IMAGE_WARN_GIB} GiB)"
fi

if [[ $ASH_XZ == 1 ]]; then
  step "xz system.img"
  command -v xz >/dev/null 2>&1 || die "--xz requested but xz is not installed"
  xz -T0 -6 -k -f "$ASH_ARTIFACT_DIR/system.img"
fi

BUILD_END=$(date +%s)
{
  echo "build_id=$ASH_BUILD_ID"
  echo "product=$ASH_PRODUCT"
  echo "variant=$ASH_VARIANT"
  echo "release=$RELEASE_USED"
  echo "target_device=$TARGET_DEVICE_USED"
  echo "target_arch=$TARGET_ARCH_USED"
  echo "system_filesystem=$SYSTEM_FS"
  echo "system_filesystem_requested=$ASH_SYSTEM_FS"
  echo "system_filesystem_detected=$IMG_FS"
  echo "use_soong_defined_system_image=${SOONG_SYSIMG:-}"
  echo "ashfall_commit=$ASH_GIT_COMMIT"
  echo "ashfall_branch=$ASH_GIT_BRANCH"
  echo "ashfall_version=$ASH_VERSION_STR"
  echo "jobs=${ASH_JOBS:-$(nproc)}"
  echo "clean=$ASH_CLEAN"
  echo "xz=$ASH_XZ"
  echo "hostname=$(hostname)"
  echo "kernel=$(uname -r)"
  echo "started_utc=$(date -u -d "@$BUILD_START" +%Y-%m-%dT%H:%M:%SZ)"
  echo "finished_utc=$(date -u -d "@$BUILD_END" +%Y-%m-%dT%H:%M:%SZ)"
  echo "wall_time_s=$((BUILD_END - BUILD_START))"
  echo "build_time_s=$((BUILD_FINISHED - BUILD_STARTED))"
  echo "system_img_bytes=$IMG_SIZE"
  echo "system_img_gib=$IMG_GIB"
  echo "image_budget_warn_gib=$ASH_IMAGE_WARN_GIB"
  echo "image_budget_max_gib=$ASH_IMAGE_MAX_GIB"
  echo "image_size_check=$IMAGE_SIZE_CHECK"
  echo "system_img_sha256=$(cut -d' ' -f1 "$ASH_ARTIFACT_DIR/system.img.sha256")"
  echo "system_img_type=$IMG_TYPE"
  echo "ro_ashfall_version_in_image=${ASHFALL_VERSION_IN_IMAGE:-}"
} > "$ASH_ARTIFACT_DIR/build-info.txt"

command -v ccache >/dev/null 2>&1 && ccache --show-stats >> "$ASH_ARTIFACT_DIR/build-info.txt" || true
ls -l "$ASH_ARTIFACT_DIR" >> "$ASH_ARTIFACT_DIR/build-info.txt"
df -h "$ASH_TREE" >> "$ASH_ARTIFACT_DIR/build-info.txt" 2>&1 || true

# The log is kept under $REMOTE_LOGS; keep a copy next to the artifacts.
cp -f "$ASH_LOG" "$ASH_ARTIFACT_DIR/build.log" 2>/dev/null || true

# latest symlink is only meaningful for remote artifacts
ln -sfn "$ASH_BUILD_ID" "$(dirname "$ASH_ARTIFACT_DIR")/latest" 2>/dev/null || true

step "done"
cat "$ASH_ARTIFACT_DIR/build-info.txt"
info "artifacts: $ASH_ARTIFACT_DIR"
RUNSH
  } > "$run"
  chmod +x "$run"
}

# --- launch -----------------------------------------------------------------

launch_remote() {
  local run_local=$1/run.sh sess
  sess="ashfall-$BUILD_ID"

  log "pushing checkout to $REMOTE_WS"
  local ws_path; ws_path=$(ws_push)
  [[ $ws_path == "$WS" ]] || warn "ws_push used $ws_path, expected $WS"

  host_sh "mkdir -p $(printf %q "$JOB_DIR")"
  rsync -a -e "ssh ${ASH_SSH_OPTS[*]}" "$run_local" "$BUILD_HOST:$JOB_DIR/run.sh" >&2

  # Detached runner: scripts/remote/job.sh (issue #1) is not on this base, so
  # build.sh carries its own minimal tmux + log + status runner. Do NOT wrap
  # run.sh in another process that holds $REMOTE_TREE_LOCK: run.sh flocks it
  # itself for the whole overlay/patch/build window, and a nested flock on the
  # same file from a different process would deadlock. When #1 merges, either
  # call job.sh with a "do not lock" option or keep this launcher.
  if box_sh "command -v tmux >/dev/null 2>&1"; then
    log "starting detached tmux session $sess"
    box_sh "tmux kill-session -t $(printf %q "$sess") 2>/dev/null || true; tmux new-session -d -s $(printf %q "$sess") $(printf %q "bash $JOB_DIR/run.sh")"
    box_sh "tmux has-session -t $(printf %q "$sess") 2>/dev/null" ||
      die "tmux session $sess vanished right after start (see $BUILD_LOG)"
  else
    warn "tmux is not installed in the build box; using setsid"
    box_sh "cd $(printf %q "$JOB_DIR") && setsid nohup bash $(printf %q "$JOB_DIR/run.sh") >/dev/null 2>&1 < /dev/null &"
  fi

  ok "build $BUILD_ID started detached"
  cat >&2 <<EOF

  build id : $BUILD_ID
  log      : $BUILD_HOST:$BUILD_LOG
  follow   : scripts/build.sh --fetch $BUILD_ID --wait
  attach   : ssh $BUILD_HOST 'distrobox enter $BUILD_BOX -- tmux attach -t $sess'
  status   : ssh $BUILD_HOST 'cat $BUILD_STATUS'
  artifacts: $BUILD_HOST:$ARTIFACT_DIR
EOF
}

launch_local() {
  log "running pipeline locally against $TREE (test mode)"
  bash "$JOB_DIR/run.sh"
  ok "local build finished, see $BUILD_LOG"
  [[ -f $BUILD_STATUS ]] && log "status: $(cat "$BUILD_STATUS")"
}

# --- main -------------------------------------------------------------------

print_plan

if (( DRY_RUN )); then
  log "dry run: nothing executed"
  exit 0
fi

mkdir -p "$OUT_ROOT"

if [[ $MODE == local ]]; then
  write_run_script "$JOB_DIR"
  launch_local
  exit 0
fi

# Render the job script first: if preflight fails, the plan and the exact
# remote script are still on disk for inspection.
LOCAL_JOB=$ASH_ROOT/out/.tmp/$BUILD_ID
write_run_script "$LOCAL_JOB"
preflight
launch_remote "$LOCAL_JOB"
