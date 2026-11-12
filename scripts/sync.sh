#!/usr/bin/env bash
# AshFall source sync - repo init + repo sync of the shared LineageOS 24.0
# tree ($REMOTE_SRC) on the build host, detached in tmux under the tree lock.
#
#   scripts/sync.sh                 # init (first run) + sync, detached
#   scripts/sync.sh --wait          # ... and block until it finishes
#   scripts/sync.sh --init-only     # only repo init (fast; still under lock)
#   scripts/sync.sh --status        # sync job state + tree/project progress
#   scripts/sync.sh --jobs N        # parallelism (default: nproc on host)
#   scripts/sync.sh --retries N     # git fetch retries on transient errors (5)
#   scripts/sync.sh --force         # restart the job even if it is running
#   scripts/sync.sh --log           # tail the sync log
#
# Re-running is resumable: repo sync continues where it stopped (also after
# googlesource HTTP 429 rate limits - failed repos are fetched on the next run).
# The job holds $REMOTE_TREE_LOCK for its whole lifetime, so builds/patches
# waiting on box_sh_locked simply block instead of corrupting the tree.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/common.sh
. "$HERE/lib/common.sh"
# shellcheck source=remote/lib.sh
. "$HERE/remote/lib.sh"
load_config

JOB_NAME=sync
JOB_SH="$HERE/remote/job.sh"
JOBS=""
RETRIES=5
INIT_ONLY=0
WAIT=0
FORCE=0
ACTION=start

usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; }

while [[ $# -gt 0 ]]; do
  case $1 in
    --jobs) JOBS=${2:?--jobs needs a value}; shift 2 ;;
    --retries) RETRIES=${2:?--retries needs a value}; shift 2 ;;
    --init-only) INIT_ONLY=1; shift ;;
    --wait) WAIT=1; shift ;;
    --force) FORCE=1; shift ;;
    --status) ACTION=status; shift ;;
    --log) ACTION=log; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

job_status_field() {
  box_sh "d=$(printf %q "$REMOTE_ROOT/jobs/$JOB_NAME"); if [ -d \"\$d\" ]; then cat \"\$d/status\" 2>/dev/null || echo unknown; else echo unknown; fi" | tail -1
}

progress() {
  box_sh "s=$(printf %q "$REMOTE_SRC")
now=\$(du -sh \"\$s\" 2>/dev/null | cut -f1)
have=\$(find \"\$s/.repo/projects\" -name '*.git' 2>/dev/null | wc -l)
old=\$(git -C \"\$s/.repo/manifests\" config --get repo.existingprojectcount 2>/dev/null || echo 0)
new=\$(git -C \"\$s/.repo/manifests\" config --get repo.newprojectcount 2>/dev/null || echo 0)
disk=\$(df -h --output=avail $(printf %q "$REMOTE_ROOT") | tail -1 | tr -d ' ')
echo \"tree:     \$s\"
echo \"size:     \$now on disk\"
echo \"projects: \$have checked out (\$((old + new)) in manifest)\"
echo \"free:     \$disk on the build-host filesystem\"" 2>/dev/null |
    grep -E '^(tree|size|projects|free):' || true
}

case $ACTION in
  status)
    if [[ $(job_status_field) == 'unknown' ]]; then
      printf 'job:      %s\nstatus:   not started (run scripts/sync.sh)\n' "$JOB_NAME"
    else
      "$JOB_SH" status "$JOB_NAME"
    fi
    progress
    exit 0
    ;;
  log)
    [[ $(job_status_field) != 'unknown' ]] || die "no sync job yet - run scripts/sync.sh"
    exec "$JOB_SH" log "$JOB_NAME" -f
    ;;
esac

need ssh base64 rsync

# Already running? Do not start a second sync unless --force.
if [[ $(job_status_field) == running ]] && [[ $FORCE -eq 0 ]]; then
  log "sync job is already running - not starting another one"
  progress
  if [[ $WAIT -eq 1 ]]; then
    exec "$JOB_SH" wait "$JOB_NAME"
  fi
  printf 'follow:  %s log %s -f\n         %s --status\n' "$JOB_SH" "$JOB_NAME" "$HERE/sync.sh"
  exit 0
fi

# Default parallelism: nproc on the host (the box sees the same CPUs).
if [[ -z $JOBS ]]; then
  JOBS=$(host_sh 'nproc' | tail -1)
  [[ $JOBS =~ ^[0-9]+$ ]] || die "could not detect nproc on $BUILD_HOST"
fi
((JOBS > 0 && JOBS <= 64)) || die "unreasonable --jobs value: $JOBS"
[[ $RETRIES =~ ^[0-9]+$ ]] || die "--retries must be a number"

if ! host_sh "command -v distrobox >/dev/null" >/dev/null 2>&1; then
  die "distrobox missing on $BUILD_HOST - run scripts/host/bootstrap.sh"
fi
if ! box_sh "command -v repo >/dev/null && command -v tmux >/dev/null && command -v flock >/dev/null" >/dev/null 2>&1; then
  die "box '$BUILD_BOX' is not ready (repo/tmux/flock missing) - run scripts/host/bootstrap.sh"
fi

# Mirror this repo's local manifests to the host, then into the tree under lock.
log "mirroring manifests/ -> $REMOTE_ROOT/manifests"
rsync -a --delete -e "ssh ${ASH_SSH_OPTS[*]}" "$HERE/../manifests/" "$BUILD_HOST:$REMOTE_ROOT/manifests/" >&2

TS=$(date -u +%Y%m%dT%H%M%SZ)
LOG_PATH="$REMOTE_LOGS/sync-$TS.log"

SYNC_CMD=$(
  cat <<EOF
set -euo pipefail
export LC_ALL=C
mkdir -p $(printf %q "$REMOTE_SRC")
cd $(printf %q "$REMOTE_SRC")
exec 9>$(printf %q "$REMOTE_TREE_LOCK")
flock 9
echo "[sync] tree lock acquired: $REMOTE_TREE_LOCK"
if [ ! -f .repo/manifest.xml ]; then
  echo "[sync] first run: repo init -u $LINEAGE_MANIFEST -b $LINEAGE_BRANCH --git-lfs --depth=1"
else
  echo "[sync] existing tree - refreshing manifest ($LINEAGE_BRANCH)"
fi
repo init -u $(printf %q "$LINEAGE_MANIFEST") -b $(printf %q "$LINEAGE_BRANCH") --git-lfs --depth=1
echo "[sync] mirroring local manifests (exact)"
bash $(printf %q "$REMOTE_ROOT/bin/sync-manifests.sh") $(printf %q "$REMOTE_ROOT/manifests") $(printf %q "$REMOTE_SRC")
$( ((INIT_ONLY)) && echo 'echo "[sync] --init-only: done"; exit 0' )
echo "[sync] repo sync -c -j$JOBS --retry-fetches=$RETRIES --no-tags --no-clone-bundle --optimized-fetch --prune"
repo sync -c -j$JOBS --retry-fetches=$RETRIES --no-tags --no-clone-bundle --optimized-fetch --prune
echo "[sync] done: \$(du -sh $(printf %q "$REMOTE_SRC") 2>/dev/null | cut -f1) on disk"
EOF
)

log "starting job '$JOB_NAME' (j=$JOBS, retries=$RETRIES) on $BUILD_HOST::$BUILD_BOX"
ash_upload_file "$HERE/remote/manifests.sh" "$REMOTE_ROOT/bin/sync-manifests.sh"
start_args=()
[[ $FORCE -eq 1 ]] && start_args+=(--force)
ASH_JOB_LOG="$LOG_PATH" "$JOB_SH" start "$JOB_NAME" "$SYNC_CMD" "${start_args[@]}"
printf 'job:     %s\nlog:     %s\n' "$JOB_NAME" "$LOG_PATH"
printf 'follow:  %s log %s -f\n' "$JOB_SH" "$JOB_NAME"

if [[ $WAIT -eq 1 ]]; then
  "$JOB_SH" wait "$JOB_NAME"
fi
