# shellcheck shell=bash
# Shared helpers for the stock-firmware tooling (issue #2).
#
# Sourced by scripts/firmware/*.sh after scripts/lib/common.sh + load_config.
# All real work happens on the build host inside the dedicated distrobox
# container "$FW_BOX" (never on the immutable Fedora host, never in another
# worker's container). Scripts re-exec themselves there over SSH.

FW_BOX=${FW_BOX:-ashfall-fw}
FW_BOX_IMAGE=${FW_BOX_IMAGE:-docker.io/library/ubuntu:24.04}
FW_DL=${FW_DL:-$REMOTE_FIRMWARE/dl}
FW_IMG=${FW_IMG:-$REMOTE_FIRMWARE/img}
FW_SUPER=${FW_SUPER:-$FW_IMG/super}
FW_RAMDISK=${FW_RAMDISK:-$FW_IMG/vendor_ramdisk}
FW_VENDOR_DUMP=${FW_VENDOR_DUMP:-$FW_IMG/vendordump}
FW_LOG=${FW_LOG:-$REMOTE_FIRMWARE/logs}

# Packages the fw container needs. android-sdk-libsparse-utils has simg2img;
# lpunpack/lpdump are provided by our own scripts/firmware/lpunpack.py.
FW_PKGS="p7zip-full lz4 cpio python3 file xxd android-sdk-libsparse-utils e2fsprogs erofs-utils device-tree-compiler curl ca-certificates git rsync"

# fw_host <command>: run a command on the build host. In the remote phase we
# are already on the build host, so run it locally (never SSH to ourselves).
fw_host() {
  if [[ ${ASH_FW_REMOTE:-0} == 1 ]]; then
    bash -lc "$*"
  else
    host_sh "$*"
  fi
}

# fw_sh <command>: run a bash command inside the fw container on the build host.
fw_sh() {
  local inner
  inner=$(printf %q "$*")
  fw_host "distrobox enter --name $(printf %q "$FW_BOX") -- bash -lc $inner"
}

# fw_remote_reentry <script-name> [args...]: if we are not already running on
# the build host, rsync this checkout there and re-exec the script inside it.
fw_remote_reentry() {
  local script=$1
  shift
  if [[ ${ASH_FW_REMOTE:-0} == 1 ]]; then
    return 0
  fi
  need rsync
  local ws
  ws=$(ws_push) || die "could not push checkout to the build host"
  log "running $script on $BUILD_HOST ($ws)"
  local cmd
  cmd="ASH_ROOT=$(printf %q "$ws") ASH_FW_REMOTE=1 bash $(printf %q "$ws/scripts/firmware/$script")"
  (($#)) && cmd+=" $(printf '%q ' "$@")"
  host_sh "$cmd"
  exit $?
}

# ensure_fw_box: create the fw container if needed and install its packages.
# Idempotent; safe to call from every script.
ensure_fw_box() {
  local marker=$REMOTE_FIRMWARE/.fw-box-ready
  if fw_host "distrobox list --no-color | awk -F'|' '{gsub(/ /,\"\",\$2); print \$2}' | grep -qx $(printf %q "$FW_BOX")"; then
    if fw_host "test -f $(printf %q "$marker")"; then
      return 0
    fi
    log "fw container exists, installing packages"
  else
    log "creating distrobox container $FW_BOX ($FW_BOX_IMAGE)"
    fw_host "distrobox create --name $(printf %q "$FW_BOX") --image $(printf %q "$FW_BOX_IMAGE") --yes" >&2
  fi
  fw_host "mkdir -p $(printf %q "$REMOTE_FIRMWARE")"
  fw_sh "sudo env DEBIAN_FRONTEND=noninteractive apt-get update -qq && \
    sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends $FW_PKGS" >&2
  fw_host "touch $(printf %q "$marker")"
  ok "fw container ready"
}

# fw_need_host_tools <cmd...>: die unless the tools exist (on the host side
# after re-entry this is the container, checked separately).
fw_need_container_tools() {
  local missing
  missing=$(fw_sh "for c in $*; do command -v \$c >/dev/null || printf '%s ' \$c; done")
  [[ -z $missing ]] || die "missing in $FW_BOX: $missing"
}

# fw_archive_volumes: print the archive volume paths in order (one per line).
# Handles split .7z.001..N and single-file archives.
fw_archive_volumes() {
  local base=$STOCK_FW_URL i n
  if [[ $base == *.001 ]]; then
    base=${base%.001}
  fi
  if [[ -f $FW_DL/fw.7z.001 ]]; then
    for i in 1 2 3 4 5 6 7 8 9; do
      [[ -f $FW_DL/fw.7z.$(printf '%03d' "$i") ]] && printf '%s\n' "$FW_DL/fw.7z.$(printf '%03d' "$i")"
    done
    return 0
  fi
  # Not downloaded yet: mirror the upstream release naming.
  for i in 1 2 3 4 5 6 7 8 9; do
    n=$(printf '%03d' "$i")
    if curl -fsIL --max-time 20 "$base.$n" >/dev/null 2>&1; then
      printf '%s\n' "$FW_DL/fw.7z.$n"
    else
      break
    fi
  done
}
