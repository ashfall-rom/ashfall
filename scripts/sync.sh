#!/usr/bin/env bash
# AshFall source sync - repo init + repo sync of the shared LineageOS 24.0
# tree ($REMOTE_SRC) on the build host, detached in tmux under the tree lock.
#
#   scripts/sync.sh                 # init (first run) + sync, detached
#   scripts/sync.sh --wait          # ... and block until it finishes
#   scripts/sync.sh --init-only     # only repo init (fast; still under lock)
#   scripts/sync.sh --status        # delegate to job.sh status sync
#   scripts/sync.sh --jobs N        # parallelism (default: nproc on host)
#   scripts/sync.sh --log           # tail the sync log
#
# Re-running is resumable and idempotent: repo sync continues where it stopped.
# The job holds $REMOTE_TREE_LOCK for its whole lifetime, so builds/patches
# waiting on box_sh_locked simply block instead of corrupting the tree.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/common.sh
. "$HERE/lib/common.sh"
load_config

JOB_NAME=sync
JOB_SH="$HERE/remote/job.sh"
JOBS=""
INIT_ONLY=0
WAIT=0
ACTION=start

usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; }

while [[ $# -gt 0 ]]; do
  case $1 in
    --jobs) JOBS=${2:?--jobs needs a value}; shift 2 ;;
    --init-only) INIT_ONLY=1; shift ;;
    --wait) WAIT=1; shift ;;
    --status) ACTION=status; shift ;;
    --log) ACTION=log; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

progress() {
  box_sh "s=$(printf %q "$REMOTE_SRC")
now=\$(du -sh \"\$s\" 2>/dev/null | cut -f1)
have=\$(find \"\$s/.repo/projects\" -name '*.git' 2>/dev/null | wc -l)
want=\$(git -C \"\$s/.repo/manifests\" config --get repo.newprojectcount 2>/dev/null || echo '?')
disk=\$(df -h --output=avail /var/home | tail -1 | tr -d ' ')
echo \"tree:    \$s\"
echo \"size:    \$now on disk\"
echo \"projects: \$have / \$want downloaded\"
echo \"free:    \$disk on /var/home\"" 2>/dev/null | grep -E '^(tree|size|projects|free):' || true
}

case $ACTION in
  status)
    "$JOB_SH" status "$JOB_NAME"
    progress
    exit 0
    ;;
  log) exec "$JOB_SH" log "$JOB_NAME" -f ;;
esac

need ssh base64

# Default parallelism: nproc on the host (the box sees the same CPUs).
if [[ -z $JOBS ]]; then
  JOBS=$(host_sh 'nproc' | tail -1)
  [[ $JOBS =~ ^[0-9]+$ ]] || die "could not detect nproc on $BUILD_HOST"
fi
(( JOBS > 0 && JOBS <= 64 )) || die "unreasonable --jobs value: $JOBS"

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

SYNC_CMD=$(cat <<EOF
set -euo pipefail
export LC_ALL=C
mkdir -p $(printf %q "$REMOTE_SRC")
cd $(printf %q "$REMOTE_SRC")
exec 9>$(printf %q "$REMOTE_TREE_LOCK")
flock 9
echo "[sync] tree lock acquired: $REMOTE_TREE_LOCK"
mkdir -p .repo/local_manifests
if compgen -G $(printf %q "$REMOTE_ROOT/manifests/*.xml") >/dev/null; then
  cp -f $(printf %q "$REMOTE_ROOT/manifests/")*.xml .repo/local_manifests/
  echo "[sync] local manifests: \$(ls .repo/local_manifests | tr '\n' ' ')"
fi
if [ ! -d .repo ]; then
  echo "[sync] repo init -u $LINEAGE_MANIFEST -b $LINEAGE_BRANCH --git-lfs --depth=1"
  repo init -u $(printf %q "$LINEAGE_MANIFEST") -b $(printf %q "$LINEAGE_BRANCH") --git-lfs --depth=1
else
  echo "[sync] existing .repo - refreshing manifest ($LINEAGE_BRANCH)"
  repo init -u $(printf %q "$LINEAGE_MANIFEST") -b $(printf %q "$LINEAGE_BRANCH") --git-lfs --depth=1
fi
$( (( INIT_ONLY )) && echo 'echo "[sync] --init-only: done"; exit 0' )
echo "[sync] repo sync -c -j$JOBS --no-tags --no-clone-bundle --optimized-fetch --prune"
repo sync -c -j$JOBS --no-tags --no-clone-bundle --optimized-fetch --prune
echo "[sync] done: \$(du -sh $(printf %q "$REMOTE_SRC") 2>/dev/null | cut -f1) on disk"
EOF
)

log "starting job '$JOB_NAME' (j=$JOBS) on $BUILD_HOST::$BUILD_BOX"
ASH_JOB_LOG="$LOG_PATH" "$JOB_SH" start "$JOB_NAME" "$SYNC_CMD"
printf 'job:     %s\nlog:     %s\n' "$JOB_NAME" "$LOG_PATH"
printf 'follow:  %s log %s -f\n' "$HERE/remote/job.sh" "$JOB_NAME"

if [[ $WAIT -eq 1 ]]; then
  "$JOB_SH" wait "$JOB_NAME"
fi
