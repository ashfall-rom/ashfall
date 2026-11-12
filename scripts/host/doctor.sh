#!/usr/bin/env bash
# AshFall build-host doctor: verify SSH, box, deps, disk, RAM, ccache, tree.
#
#   scripts/host/doctor.sh          # human-readable report, exit 1 on failure
#   scripts/host/doctor.sh --quiet  # only problems + summary
#
# Read-only: makes no changes on the host. Safe to run during a sync (the tree
# lock is probed non-blockingly and reported, never taken).
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../lib/common.sh
. "$HERE/../lib/common.sh"
load_config

# Overridable only so tests can probe a scratch lock instead of the shared tree.
TREE_LOCK=${ASH_TREE_LOCK:-$REMOTE_TREE_LOCK}

QUIET=0
case ${1:-} in
  --quiet | -q) QUIET=1 ;;
  -h | --help)
    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"
    exit 0
    ;;
  '') ;;
  *) die "unknown argument: $1 (try --help)" ;;
esac

FAILS=0
WARNS=0
okc() { [[ $QUIET -eq 1 ]] || printf '[ ok ] %s\n' "$*"; }
warnc() {
  printf '[warn] %s\n' "$*"
  WARNS=$((WARNS + 1))
}
failc() {
  printf '[FAIL] %s\n' "$*"
  FAILS=$((FAILS + 1))
}
info() { [[ $QUIET -eq 1 ]] || printf '       %s\n' "$*"; }

printf 'AshFall doctor - %s :: %s\n' "$BUILD_HOST" "$BUILD_BOX"

# --------------------------------------------------------------------------
# 1. Host reachability + hardware
# --------------------------------------------------------------------------
# shellcheck disable=SC2016 # $ expands on the build host (escaped where local)
HOST_OUT=$(host_sh "echo \"hostname=\$(hostname)\"
echo \"kernel=\$(uname -r)\"
echo \"nproc=\$(nproc)\"
echo \"mem_gb=\$(awk '/MemTotal/{printf \"%d\", \$2/1024/1024}' /proc/meminfo)\"
echo \"disk_total=\$(df -BG --output=size $(printf %q "$REMOTE_ROOT") 2>/dev/null | tail -1 | tr -dc 0-9)\"
echo \"disk_free=\$(df -BG --output=avail $(printf %q "$REMOTE_ROOT") 2>/dev/null | tail -1 | tr -dc 0-9)\"
echo \"distrobox=\$(command -v distrobox >/dev/null && echo yes || echo no)\"
echo \"podman=\$(command -v podman >/dev/null && echo yes || echo no)\"" 2>/dev/null) || {
  failc "SSH to $BUILD_HOST failed (check key/network: ssh $BUILD_HOST)"
  printf '\nsummary: %d failed, %d warnings\n' "$FAILS" "$WARNS"
  exit 1
}
declare -A H=()
while IFS='=' read -r k v; do [[ -n $k ]] && H[$k]=$v; done <<<"$HOST_OUT"

# df needs the actual path, so it is interpolated locally (not in the remote script).
read -r DISK_TOTAL DISK_FREE <<<"$(host_sh "df -BG --output=size,avail $(printf %q "$REMOTE_ROOT") 2>/dev/null | tail -1" | tr -dc '0-9 ' | tr -s ' ')"
H[disk_total]=$DISK_TOTAL
H[disk_free]=$DISK_FREE

okc "ssh $BUILD_HOST reachable (${H[hostname]:-?}, kernel ${H[kernel]:-?})"

if [[ ${H[nproc]:-0} =~ ^[0-9]+$ ]] && ((H[nproc] >= 8)); then
  okc "cpu: ${H[nproc]} threads"
else
  warnc "cpu: only ${H[nproc]:-?} threads (builds will be slow)"
fi
if [[ ${H[mem_gb]:-0} =~ ^[0-9]+$ ]] && ((H[mem_gb] >= 16)); then
  okc "ram: ${H[mem_gb]} GiB"
else
  failc "ram: ${H[mem_gb]:-?} GiB (AOSP needs >= 16 GiB; 32+ recommended)"
fi
if [[ ${H[disk_free]:-0} =~ ^[0-9]+$ ]] && ((H[disk_free] >= 200)); then
  okc "disk: ${H[disk_free]} GiB free of ${H[disk_total]} GiB on $REMOTE_ROOT"
else
  failc "disk: only ${H[disk_free]:-?} GiB free on $REMOTE_ROOT (want >= 200 GiB: tree ~110 GiB + ccache/out)"
fi
if [[ ${H[distrobox]:-no} == yes && ${H[podman]:-no} == yes ]]; then
  okc "distrobox + podman present on host (nothing installed on Silverblue)"
else
  failc "distrobox/podman missing on host"
fi

# --------------------------------------------------------------------------
# 2. Box + toolchain + tree (one container round-trip)
# --------------------------------------------------------------------------
PKGS="bc bison build-essential ccache curl flex g++-multilib gcc-multilib git
git-lfs gnupg gperf imagemagick lib32readline-dev lib32z1-dev libelf-dev
liblz4-tool lz4 libsdl1.2-dev libssl-dev libxml2 libxml2-utils lzop pngcrush
rsync schedtool squashfs-tools xsltproc zip zlib1g-dev python3
python-is-python3 tmux"
PKGS=$(printf '%s\n' "$PKGS" | sort -u | tr '\n' ' ')

BOX_OUT=$(box_sh "
echo 'box=ok'
missing=''
for p in $PKGS; do dpkg-query -W -f='\${Status}' \"\$p\" 2>/dev/null | grep -q 'install ok installed' || missing=\"\$missing \$p\"; done
echo \"missing_pkgs=\$missing\"
echo \"repo=\$(repo --version 2>/dev/null | awk '/launcher version/{print \$NF; exit}' || true)\"
missing_cmds=''
for c in tmux flock ccache git-lfs rsync schedtool lzop pngcrush python3; do command -v \$c >/dev/null || missing_cmds=\"\$missing_cmds \$c\"; done
echo \"missing_cmds=\$missing_cmds\"
echo \"git_name=\$(git config --global user.name 2>/dev/null || true)\"
echo \"git_email=\$(git config --global user.email 2>/dev/null || true)\"
echo \"ccache_dir=$(printf %q "$REMOTE_CCACHE")\"
echo \"ccache_max=\$(CCACHE_DIR=$(printf %q "$REMOTE_CCACHE") ccache --get-config=max_size 2>/dev/null || echo unknown)\"
echo \"ccache_used=\$(du -sh $(printf %q "$REMOTE_CCACHE") 2>/dev/null | cut -f1 || echo 0)\"
echo \"src_exists=\$([ -d $(printf %q "$REMOTE_SRC") ] && echo yes || echo no)\"
echo \"src_size=\$(du -sh $(printf %q "$REMOTE_SRC") 2>/dev/null | cut -f1 || echo 0)\"
echo \"manifest=\$(git -C $(printf %q "$REMOTE_SRC")/.repo/manifests config --get branch.default.merge 2>/dev/null | sed 's|refs/heads/||' || echo unknown)\"
echo \"projects=\$(find $(printf %q "$REMOTE_SRC")/.repo/projects -name '*.git' 2>/dev/null | wc -l)\"
lf=$(printf %q "$TREE_LOCK")
if [ ! -e \"\$lf\" ]; then echo 'lock=none'
else
  flock -n \"\$lf\" -c true 2>/dev/null
  echo \"lock_rc=\$?\"
fi
" 2>/dev/null) || true
declare -A B=()
while IFS='=' read -r k v; do
  [[ -n $k && $k =~ ^[a-z_]+$ ]] && B[$k]=$v
done <<<"$BOX_OUT"

if [[ ${B[box]:-} == ok ]]; then
  okc "box '$BUILD_BOX' reachable (distrobox enter)"
else
  failc "box '$BUILD_BOX' not reachable - run scripts/host/bootstrap.sh"
  printf '\nsummary: %d failed, %d warnings\n' "$FAILS" "$WARNS"
  exit 1
fi

if [[ -z ${B[missing_pkgs]:-} ]]; then
  okc "apt deps: all present"
else
  failc "apt deps missing:${B[missing_pkgs]}"
fi
if [[ ${B[repo]:-missing} != missing && -n ${B[repo]:-} ]]; then
  okc "repo launcher ${B[repo]}"
else
  failc "repo launcher missing in box - run scripts/host/bootstrap.sh"
fi
if [[ ${B[box]:-} == ok ]]; then
  if [[ -n ${B[missing_cmds]:-} ]]; then
    failc "tools missing in box:${B[missing_cmds]}"
  else
    okc "tmux/flock/ccache/git-lfs/rsync/schedtool/lzop/pngcrush/python3 present"
  fi
fi
if [[ -n ${B[git_name]:-} && -n ${B[git_email]:-} ]]; then
  okc "git identity: ${B[git_name]} <${B[git_email]}>"
else
  warnc "git identity incomplete (name='${B[git_name]:-}' email='${B[git_email]:-}') - bootstrap.sh sets it"
fi
if [[ ${B[box]:-} != ok ]]; then
  :
elif [[ ${B[ccache_max]:-unknown} != unknown ]]; then
  okc "ccache: $REMOTE_CCACHE max=${B[ccache_max]} used=${B[ccache_used]:-0}"
else
  failc "ccache not configured - run scripts/host/bootstrap.sh"
fi

# --------------------------------------------------------------------------
# 3. Tree state + lock + sync job
# --------------------------------------------------------------------------
if [[ ${B[box]:-} != ok ]]; then
  :
elif [[ ${B[src_exists]:-no} == yes ]]; then
  okc "tree: $REMOTE_SRC (${B[src_size]:-?}, ${B[projects]:-0} projects)"
  if [[ -n ${B[manifest]:-} ]]; then
    okc "manifest: ${B[manifest]} (${B[projects]:-0} projects checked out)"
  else
    warnc "manifest: branch unknown (repo init incomplete?)"
  fi
else
  warnc "tree: $REMOTE_SRC missing - run scripts/sync.sh"
fi
if [[ ${B[lock]:-} == none ]]; then
  okc "tree lock: no lock file yet ($TREE_LOCK)"
else
  case ${B[lock_rc]:-?} in
    0) okc "tree lock: free" ;;
    1) okc "tree lock: held (a sync/build is running; other mutators will block)" ;;
    *) warnc "tree lock: unknown (lock probe failed on $TREE_LOCK, flock rc=${B[lock_rc]:-?})" ;;
  esac
fi

if host_sh "test -d $(printf %q "$REMOTE_ROOT/jobs/sync")" >/dev/null 2>&1; then
  STATUS=$(box_sh "cat $(printf %q "$REMOTE_ROOT/jobs/sync/status") 2>/dev/null || echo unknown" | tail -1)
  EXIT=$(box_sh "cat $(printf %q "$REMOTE_ROOT/jobs/sync/exit") 2>/dev/null || echo -" | tail -1)
  LAST=$(box_sh "tail -c 4000 $(printf %q "$REMOTE_ROOT/jobs/sync/log") 2>/dev/null" | tr '\r' '\n' | grep -v '^$' | tail -3 | tr '\n' ' ')
  case $STATUS in
    running)
      if box_sh "tmux has-session -t ashfall-sync 2>/dev/null" >/dev/null 2>&1; then
        okc "sync job: running (exit pending); last: ${LAST: -160}"
      else
        warnc "sync job: status running but tmux session is gone (container restarted?) - re-run scripts/sync.sh"
      fi
      ;;
    done) okc "sync job: done (exit $EXIT); last: ${LAST: -160}" ;;
    failed) failc "sync job: failed (exit $EXIT); last: ${LAST: -200}" ;;
    *) warnc "sync job: $STATUS (exit $EXIT)" ;;
  esac
else
  warnc "no sync job yet - run scripts/sync.sh"
fi

# --------------------------------------------------------------------------
printf '\nsummary: %d failed, %d warnings\n' "$FAILS" "$WARNS"
((FAILS == 0)) || exit 1
