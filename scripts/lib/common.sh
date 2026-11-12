# shellcheck shell=bash
# AshFall shared shell library (CONTRACT - extend by adding functions; do not
# change the semantics of existing ones without the orchestrator).
#
# Usage from any script in scripts/**:
#   . "$(dirname "$0")/<..>/lib/common.sh"   # or: . "$ASH_ROOT/scripts/lib/common.sh"
#   load_config
#
# Provides:
#   ASH_ROOT                 repo root (absolute)
#   log/ok/warn/die          logging to stderr
#   need <cmd>...            die unless every command exists
#   load_config              source config/ashfall.conf (+ .local.conf)
#   host_sh <cmd>            run a bash command on the build host (outside the box)
#   box_sh <cmd>             run a bash command inside the build distrobox
#   box_sh_locked <cmd>      box_sh, holding REMOTE_TREE_LOCK (use for anything
#                            that mutates REMOTE_SRC: sync, patch, build)
#   ws_push [name]           rsync this checkout to $REMOTE_WS/<name> (default:
#                            current branch, '/' -> '-'); prints the remote path
#   pull_artifacts <remote-path> [local-dir]   rsync back to LOCAL_ARTIFACTS

set -o pipefail

ASH_ROOT=${ASH_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
export ASH_ROOT

if [[ -t 2 ]]; then _c() { printf '\033[%sm' "$1"; }; else _c() { :; }; fi
log()  { printf '%s==>%s %s\n' "$(_c 1\;34)" "$(_c 0)" "$*" >&2; }
ok()   { printf '%s ok%s %s\n'  "$(_c 1\;32)" "$(_c 0)" "$*" >&2; }
warn() { printf '%swarn%s %s\n' "$(_c 1\;33)" "$(_c 0)" "$*" >&2; }
die()  { printf '%s err%s %s\n' "$(_c 1\;31)" "$(_c 0)" "$*" >&2; exit 1; }

need() { local c; for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "missing command: $c"; done; }

load_config() {
  # shellcheck source=/dev/null
  . "$ASH_ROOT/config/ashfall.conf"
  if [[ -f $ASH_ROOT/config/ashfall.local.conf ]]; then
    # shellcheck source=/dev/null
    . "$ASH_ROOT/config/ashfall.local.conf"
  fi
}

ASH_SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30)

# host_sh <bash command string>
host_sh() {
  ssh "${ASH_SSH_OPTS[@]}" "$BUILD_HOST" "bash -lc $(printf %q "$*")"
}

# box_sh <bash command string>
box_sh() {
  local inner; inner=$(printf %q "$*")
  ssh "${ASH_SSH_OPTS[@]}" "$BUILD_HOST" \
    "distrobox enter --name $(printf %q "$BUILD_BOX") -- bash -lc $inner"
}

# box_sh_locked <bash command string>  (blocks until the tree lock is free)
box_sh_locked() {
  box_sh "mkdir -p $(printf %q "$REMOTE_ROOT") && flock $(printf %q "$REMOTE_TREE_LOCK") bash -lc $(printf %q "$*")"
}

ws_push() {
  local name=${1:-$(git -C "$ASH_ROOT" rev-parse --abbrev-ref HEAD | tr '/' '-')}
  local dest="$REMOTE_WS/$name"
  host_sh "mkdir -p $(printf %q "$dest")" >/dev/null
  rsync -a --delete --exclude=.git --exclude=/out/ --exclude=/work/ \
    -e "ssh ${ASH_SSH_OPTS[*]}" "$ASH_ROOT/" "$BUILD_HOST:$dest/" >&2
  printf '%s\n' "$dest"
}

pull_artifacts() {
  local src=${1:?remote path}; local dst=${2:-$LOCAL_ARTIFACTS/$(basename "$src")}
  mkdir -p "$dst"
  rsync -a --progress -e "ssh ${ASH_SSH_OPTS[*]}" "$BUILD_HOST:$src/" "$dst/" >&2
  printf '%s\n' "$dst"
}
