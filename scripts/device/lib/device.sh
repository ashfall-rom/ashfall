# shellcheck shell=bash
# AshFall device helper library: runs adb/fastboot on the build host inside the
# dedicated `ashfall-dev` distrobox container (the RP5 is USB-connected there).
#
# Sourced by scripts/device/*.sh after scripts/lib/common.sh + load_config.
# Everything remote goes through host_sh (see docs/PLAN.md conventions).
#
# Container ownership: `ashfall-dev` belongs to the device-tooling issue (#3).
# Do not use ashfall-build (builds) or ashfall-fw (firmware) from here.
#
# Overridable via config/ashfall.local.conf or the environment:
#   DEV_BOX=ashfall-dev
#   DEV_BOX_IMAGE=docker.io/library/ubuntu:24.04
#   DEV_BOX_PACKAGES="adb fastboot usbutils"

DEV_BOX=${DEV_BOX:-ashfall-dev}
DEV_BOX_IMAGE=${DEV_BOX_IMAGE:-docker.io/library/ubuntu:24.04}
DEV_BOX_PACKAGES=${DEV_BOX_PACKAGES:-adb fastboot usbutils}

# --- quoting helpers ---------------------------------------------------------
# _dq <string> -> single shell-quoted string (printf %q)
_dq() { printf '%q' "$*"; }

# _cmd <arg>... -> one shell command string with every argument quoted
_cmd() {
  local out='' a
  for a in "$@"; do out+="$(printf '%q' "$a") "; done
  printf '%s' "${out% }"
}

# --- container ---------------------------------------------------------------
# dev_sh <command string>: run a bash command inside $DEV_BOX on the build host.
dev_sh() {
  local inner; inner=$(_dq "$1")
  host_sh "timeout 1800 distrobox enter --name $(_dq "$DEV_BOX") --no-tty -- bash -lc $inner"
}

# host_sh_tty: like host_sh but allocates a remote TTY (for interactive shells).
host_sh_tty() {
  ssh -tt "${ASH_SSH_OPTS[@]}" "$BUILD_HOST" "bash -lc $(printf %q "$*")"
}

dev_interactive_sh() {
  local inner; inner=$(_dq "$1")
  host_sh_tty "distrobox enter --name $(_dq "$DEV_BOX") -- bash -lc $inner"
}

dev_exists() {
  [[ $(host_sh "podman container exists $(_dq "$DEV_BOX") && echo yes || echo no") == yes ]]
}

dev_create() {
  log "creating distrobox container '$DEV_BOX' from $DEV_BOX_IMAGE (USB passthrough)"
  host_sh "distrobox create --yes --name $(_dq "$DEV_BOX") --image $(_dq "$DEV_BOX_IMAGE") \
    --additional-flags $(_dq "--device=/dev/bus/usb")" >&2
}

# dev_ensure [--force-install]
# Idempotent: creates the container if missing, installs adb/fastboot/usbutils
# if they are not on PATH inside it. First run needs network + a few minutes.
dev_ensure() {
  local force=${1:-}
  if ! dev_exists; then
    dev_create
  fi
  # First `distrobox enter` provisions the container user; if a previous
  # provisioning was interrupted it fails here. Tell the user how to recover.
  if ! dev_sh 'id >/dev/null'; then
    die "cannot enter container '$DEV_BOX' (interrupted provisioning? run: scripts/device/run.sh recreate)"
  fi
  if [[ $force != --force-install ]] && dev_sh 'command -v adb >/dev/null && command -v fastboot >/dev/null'; then
    return 0
  fi
  log "installing $DEV_BOX_PACKAGES in '$DEV_BOX' (apt)"
  dev_sh "sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq && \
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $DEV_BOX_PACKAGES" >&2 ||
    die "package install failed in '$DEV_BOX'"
  dev_sh 'command -v adb >/dev/null && command -v fastboot >/dev/null' ||
    die "adb/fastboot still missing in '$DEV_BOX'"
}

dev_recreate() {
  log "recreating '$DEV_BOX'"
  host_sh "distrobox rm --force $(_dq "$DEV_BOX") >/dev/null 2>&1 || true"
  dev_create
  dev_ensure --force-install
}

# --- device state ------------------------------------------------------------
# dev_adb_devices: "<serial> <state>" lines (state: device|recovery|sideload|unauthorized|offline)
dev_adb_devices() {
  dev_sh 'timeout 25 adb devices 2>/dev/null' | awk 'NR>1 && NF>=2 {print $1, $2}'
}

# dev_fastboot_devices: "<serial> <state>" lines
dev_fastboot_devices() {
  dev_sh 'timeout 25 fastboot devices 2>/dev/null' | awk 'NF>=2 {print $1, $2}'
}

# dev_usb_ids: raw `lsusb` lines for Android/Qualcomm vendor IDs (for reports)
dev_usb_ids() {
  host_sh 'lsusb 2>/dev/null | grep -E "18d1:|05c6:|2dc8:" || true'
}

# dev_mode: prints one of none|fastboot|fastbootd|android|recovery|sideload|
#           unauthorized|offline|ambiguous
dev_mode() {
  local fb adb
  fb=$(dev_fastboot_devices || true)
  adb=$(dev_adb_devices || true)
  local nfb nadb
  nfb=$(printf '%s' "$fb" | grep -c . || true)
  nadb=$(printf '%s' "$adb" | grep -c . || true)
  if (( nfb > 1 || nadb > 1 )); then echo ambiguous; return 0; fi
  if (( nfb == 1 )); then
    DEV_SERIAL=${fb%% *}
    if [[ $(dev_fb_value is-userspace) == yes ]]; then
      echo fastbootd
    else
      echo fastboot
    fi
    return 0
  fi
  if (( nadb == 1 )); then
    case ${adb#* } in
      device) echo android ;;
      recovery) echo recovery ;;
      sideload) echo sideload ;;
      unauthorized) echo unauthorized ;;
      offline) echo offline ;;
      *) echo unknown ;;
    esac
    return 0
  fi
  if dev_sh 'timeout 25 adb devices 2>/dev/null' | grep -q unauthorized; then
    echo unauthorized; return 0
  fi
  echo none
}

# dev_serial: the single connected serial (empty if none/ambiguous)
dev_serial() {
  local s
  s=$({ dev_fastboot_devices; dev_adb_devices; } | awk 'NF {print $1}' | sort -u)
  [[ $(printf '%s\n' "$s" | grep -c .) == 1 ]] && printf '%s\n' "$s"
}

# dev_resolve_serial [hint]: set DEV_SERIAL from hint/env or autodetect; dies on ambiguity.
dev_resolve_serial() {
  local hint=${1:-${ANDROID_SERIAL:-}}
  if [[ -n $hint ]]; then
    DEV_SERIAL=$hint
  else
    DEV_SERIAL=$(dev_serial || true)
  fi
  [[ -n ${DEV_SERIAL:-} ]] || die "no device found via adb/fastboot on $BUILD_HOST (check USB cable + mode)"
  printf '%s\n' "$DEV_SERIAL"
}

# dev_fastboot <args...>: read-only fastboot against $DEV_SERIAL
dev_fastboot() {
  dev_sh "$(_cmd timeout 60 fastboot -s "$DEV_SERIAL" "$@")"
}

# The RP5 bootloader intermittently answers a fresh fastboot session with
# "GetVar Variable Not found" / "unknown command" for variables that exist a
# moment later (observed 2026-11-12, retries fix it). Never trust one shot.
FB_RETRY=${FB_RETRY:-3}

# fb_parse <var> : extract "<var>: value" from fastboot output on stdin
fb_parse() {
  awk -v key="$1" '
    { l=$0; sub(/^\(bootloader\)[ \t]*/, "", l) }
    index(l, key ":")==1 { v=substr(l, length(key)+2); sub(/^[ \t]+/,"",v); sub(/[ \t]+$/,"",v); print v; exit }
  '
}

# dev_fb_getvar <var> [attempts]: raw output of the first non-flaky attempt.
# Returns non-zero if every attempt reported a fastboot failure.
dev_fb_getvar() {
  local var=$1 tries=${2:-$FB_RETRY} i out=''
  for ((i = 1; i <= tries; i++)); do
    out=$(dev_fastboot getvar "$var" 2>&1 || true)
    if ! grep -qE "FAILED \(remote: '(GetVar Variable Not found|unknown command)'\)" <<<"$out"; then
      printf '%s\n' "$out"
      return 0
    fi
    sleep 0.4
  done
  printf '%s\n' "$out"
  return 1
}

# dev_fb_value <var>: value of a single getvar (empty when unavailable).
# Never fails: callers run under `set -e`/`pipefail` and a flaky bootloader
# must degrade to "unknown", not kill the script.
dev_fb_value() {
  dev_fb_getvar "$1" 2>/dev/null | fb_parse "$1" || true
}

# dev_adb <args...>: adb against $DEV_SERIAL
dev_adb() {
  dev_sh "$(_cmd timeout 120 adb -s "$DEV_SERIAL" "$@")"
}
