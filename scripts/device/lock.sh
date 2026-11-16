#!/usr/bin/env bash
# AshFall device lease: one owner at a time for the RP5 (build host) and for the
# emulator (this VM).
#
# Why: two workers on the RP5 at once has already cost this project a build
# (#50/#51 vs #52 in docs/HANDOFF.md §0), and a second `fastboot` session while
# another one writes bootloader partitions wedges the ABL (issue #54).
#
# The RP5 hangs off the build host, so its lease lives on the build host
# ($REMOTE_ROOT/locks/device.lease) and is shared by every worker, container and
# script that reaches the device.  The emulator runs on this VM, so its lease
# lives locally (--local, $ASH_LOCAL_LOCK_DIR/emulator.lease).
#
# Every adb/fastboot call that goes through scripts/device/lib/device.sh
# (`dev_sh`, and therefore dev_adb/dev_fastboot/the flash scripts) is refused
# with a clear message while another owner holds the lease.  Raw `ssh ... adb`
# cannot be intercepted - always take the lease before touching the device.
#
# Usage:
#   scripts/device/lock.sh status  [--local] [--name NAME]
#   scripts/device/lock.sh acquire <owner> [--ttl S] [--local] [--name NAME]
#   scripts/device/lock.sh renew   <owner> [--ttl S] [--local] [--name NAME]
#   scripts/device/lock.sh release <owner> [--local] [--name NAME]
#   scripts/device/lock.sh steal   <owner> [--ttl S] [--local] [--name NAME]
#   scripts/device/lock.sh run     <owner> [--ttl S] [--wait S] [--local]
#                                  [--name NAME] -- <command...>
#
#   status    print the holder and the remaining time (exit 0 either way)
#   acquire   take the lease; exit 1 (and say who holds it) when it is busy
#   renew     push the expiry out (only the current owner may renew)
#   release   drop the lease (only the current owner may release)
#   steal     force-take an expired or abandoned lease; prints the old owner
#   run       acquire (waiting up to --wait seconds), run the command, release
#
# Options:
#   --local        use the emulator lease on this VM instead of the RP5 lease
#   --name NAME    lease name (default: device on the build host, emulator here)
#   --ttl S        lease lifetime in seconds (default: 1800); pick a value
#                  longer than the longest single command (a userdata erase is
#                  ~351 s, a vendor_boot flash batch up to ~10 min)
#   --wait S       how long `run` waits for a busy lease (default: 300)
#   -h, --help     this help
#
# The owner name is free-form ([A-Za-z0-9._-], e.g. worker-121, verifier-124,
# orchestrator-flash) and is what the guard prints when it refuses.
#
# Examples:
#   scripts/device/lock.sh run worker-124 --ttl 3600 -- scripts/device/run.sh adb devices
#   scripts/device/lock.sh acquire orchestrator-flash --ttl 3600
#   scripts/device/lock.sh run emulator --local -- adb install -r app-debug.apk
#
# Environment:
#   ASH_DEVICE_OWNER       the guard in device.sh accepts this owner's lease
#   ASH_DEVICE_LOCK_BYPASS 1 = skip the guard (read-only triage only)
#   ASH_LOCAL_LOCK_DIR     where the local (emulator) leases live
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
need ssh

DEFAULT_TTL=1800
DEFAULT_WAIT=300
LOCAL_ROOT=${ASH_LOCAL_LOCK_DIR:-/tmp/opencode/locks}

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

MODE=host
NAME=""
TTL=$DEFAULT_TTL
WAIT=$DEFAULT_WAIT
OWNER=""
declare -a CMD=()

die_usage() { printf 'error: %s\n\n' "$1" >&2; usage >&2; exit 2; }

# _sh <bash command string>: run on the machine that owns the lease.
_sh() {
  if [[ $MODE == host ]]; then
    host_sh "$1"
  else
    bash -c "$1"
  fi
}

lease_dir() {
  if [[ $MODE == host ]]; then
    printf '%s/locks/%s.lease' "$REMOTE_ROOT" "$NAME"
  else
    printf '%s/%s.lease' "$LOCAL_ROOT" "$NAME"
  fi
}

now() { date +%s; }

# lease_read: "<owner> <since> <until> <host>" or empty when no lease exists.
lease_read() {
  local dir out
  dir=$(lease_dir)
  out=$(_sh "cat $(printf %q "$dir/owner") $(printf %q "$dir/since") \
      $(printf %q "$dir/until") $(printf %q "$dir/host") 2>/dev/null" 2>/dev/null || true)
  printf '%s' "$out" | paste -sd ' ' -
}

# lease_owner: just the owner string (empty when free).
lease_owner() {
  local dir out
  dir=$(lease_dir)
  out=$(_sh "cat $(printf %q "$dir/owner") 2>/dev/null" 2>/dev/null || true)
  printf '%s' "$(printf '%s' "$out" | tr -d '[:space:]')"
}

# lease_mkdir <owner> <since> <until>: creates the lease atomically and writes
# its contents; returns non-zero when the directory already exists.
lease_mkdir() {
  local owner=$1 since=$2 until=$3 dir
  dir=$(lease_dir)
  _sh "mkdir -p $(printf %q "$(dirname "$dir")") 2>/dev/null; \
      mkdir $(printf %q "$dir") 2>/dev/null || exit 1; \
      printf '%s\n' $(printf %q "$owner") > $(printf %q "$dir/owner"); \
      printf '%s\n' $(printf %q "$since") > $(printf %q "$dir/since"); \
      printf '%s\n' $(printf %q "$until") > $(printf %q "$dir/until"); \
      printf '%s\n' $(printf %q "AshFall") > $(printf %q "$dir/host")"
}

lease_rm() {
  local dir
  dir=$(lease_dir)
  _sh "rm -rf $(printf %q "$dir")"
}

fmt_delta() {
  local s=$1
  ((s < 0)) && s=0
  if ((s >= 3600)); then
    printf '%dh %02dm' $((s / 3600)) $(((s % 3600) / 60))
  elif ((s >= 60)); then
    printf '%dm %02ds' $((s / 60)) $((s % 60))
  else
    printf '%ds' "$s"
  fi
}

print_holder() {
  local info owner since until
  info=$(lease_read)
  if [[ -z $info ]]; then
    printf 'lease %s: free\n' "$NAME"
    return 0
  fi
  owner=${info%% *}
  info=${info#* }
  since=${info%% *}
  info=${info#* }
  until=${info%% *}
  now=$(now)
  printf 'lease %s: busy\n' "$NAME"
  printf '  owner:      %s\n' "${owner:-unknown}"
  if [[ $since =~ ^[0-9]+$ ]]; then
    printf '  held for:   %s\n' "$(fmt_delta $((now - since)))"
  fi
  if [[ $until =~ ^[0-9]+$ ]]; then
    if ((until > now)); then
      printf '  expires in: %s\n' "$(fmt_delta $((until - now)))"
    else
      printf '  expired:    %s ago (stale, steal it)\n' "$(fmt_delta $((now - until)))"
    fi
  fi
  [[ $MODE == host ]] && printf '  where:      %s:%s\n' "$BUILD_HOST" "$(lease_dir)"
  [[ $MODE == local ]] && printf '  where:      %s\n' "$(lease_dir)"
  return 0
}

busy() {
  local info owner until now
  info=$(lease_read)
  if [[ -n $info ]]; then
    owner=${info%% *}
    info=${info#* }
    info=${info#* }
    until=${info%% *}
    now=$(now)
    if [[ $until =~ ^[0-9]+$ ]] && ((until > now)); then
      printf 'busy: lease %s is held by %s (expires in %s)\n' \
        "$NAME" "${owner:-unknown}" "$(fmt_delta $((until - now)))" >&2
    else
      printf 'busy: lease %s is held by %s (stale)\n' \
        "$NAME" "${owner:-unknown}" >&2
    fi
  else
    printf 'busy: lease %s is held (holder unknown)\n' "$NAME" >&2
  fi
  printf 'hint: wait with: scripts/device/lock.sh run %s ... -- <command>\n' \
    "${ASH_DEVICE_OWNER:-<owner>}" >&2
  return 1
}

do_acquire() {
  local owner=$1 ttl=$2 since until info until_ts
  for _attempt in 1 2 3; do
    since=$(now)
    until=$((since + ttl))
    if lease_mkdir "$owner" "$since" "$until" && [[ $(lease_owner) == "$owner" ]]; then
      printf 'acquired lease %s as %s (ttl %ss)\n' "$NAME" "$owner" "$ttl"
      return 0
    fi
    # A lease exists (or raced us): take it over only when it has expired.
    info=$(lease_read)
    until_ts=$(printf '%s' "$info" | awk '{print $3}')
    if [[ -n $info && $until_ts =~ ^[0-9]+$ ]] && ((until_ts <= $(now))); then
      warn "lease $NAME expired; taking it over"
      lease_rm
      continue
    fi
    busy || true
    return 1
  done
  busy || true
  return 1
}

do_renew() {
  local owner=$1 ttl=$2 dir
  if [[ $(lease_owner) != "$owner" ]]; then
    printf 'not the lease owner: %s\n' "$(lease_owner)" >&2
    return 1
  fi
  dir=$(lease_dir)
  _sh "printf '%s\n' $(printf %q "$(( $(now) + ttl ))") > $(printf %q "$dir/until")"
  printf 'renewed lease %s as %s (ttl %ss)\n' "$NAME" "$owner" "$ttl"
}

do_release() {
  local owner=$1 holder
  holder=$(lease_owner)
  if [[ -z $holder ]]; then
    printf 'lease %s already free\n' "$NAME"
    return 0
  fi
  if [[ $holder != "$owner" ]]; then
    printf 'refusing to release lease %s held by %s (use steal)\n' "$NAME" "$holder" >&2
    return 1
  fi
  lease_rm
  printf 'released lease %s (was %s)\n' "$NAME" "$owner"
}

do_steal() {
  local owner=$1 ttl=$2 previous
  previous=$(lease_owner)
  if [[ -n $previous && $previous == "$owner" ]]; then
    lease_rm
  elif [[ -n $previous ]]; then
    printf 'stealing lease %s from %s\n' "$NAME" "$previous"
    lease_rm
  fi
  do_acquire "$owner" "$ttl"
}

do_run() {
  local owner=$1 ttl=$2 wait=$3 deadline rc=0
  shift 3
  deadline=$(( $(now) + wait ))
  until do_acquire "$owner" "$ttl" >/dev/null 2>&1; do
    if (( $(now) >= deadline )); then
      busy || true
      printf 'gave up after %ss\n' "$wait" >&2
      return 1
    fi
    sleep 2
  done
  printf 'running under lease %s as %s: %s\n' "$NAME" "$owner" "$*" >&2
  # The guard in lib/device.sh accepts the calls the child makes only when it
  # can match this name against the lease holder; without the export every
  # adb/fastboot call inside the command died with "device busy" even though
  # the lease was ours.
  export ASH_DEVICE_OWNER=$owner
  trap 'do_release "'"$owner"'" >/dev/null 2>&1 || true' INT TERM
  "$@" || rc=$?
  trap - INT TERM
  do_release "$owner" >/dev/null || true
  return $rc
}

main() {
  local cmd=${1:-}
  (($#)) || { usage; exit 2; }
  shift
  [[ -n $cmd ]] || { usage; exit 2; }
  case $cmd in
    -h|--help) usage; exit 0 ;;
    status|acquire|renew|release|steal|run) ;;
    *) die_usage "unknown command: $cmd" ;;
  esac

  local positional=()
  while (($#)); do
    case $1 in
      --local) MODE=local ;;
      --host) MODE=host ;;
      --name) [[ $# -ge 2 ]] || die_usage "--name needs a value"; NAME=$2; shift ;;
      --ttl) [[ $# -ge 2 ]] || die_usage "--ttl needs a value"; TTL=$2; shift ;;
      --wait) [[ $# -ge 2 ]] || die_usage "--wait needs a value"; WAIT=$2; shift ;;
      -h|--help) usage; exit 0 ;;
      --) shift; CMD=("$@"); break ;;
      -*) die_usage "unknown option: $1" ;;
      *) positional+=("$1") ;;
    esac
    shift
  done

  if [[ -z $NAME ]]; then
    if [[ $MODE == host ]]; then NAME=device; else NAME=emulator; fi
  fi
  [[ $NAME =~ ^[A-Za-z0-9._-]+$ ]] || die_usage "invalid lease name: $NAME"
  [[ $TTL =~ ^[0-9]+$ ]] || die_usage "--ttl must be seconds"
  [[ $WAIT =~ ^[0-9]+$ ]] || die_usage "--wait must be seconds"

  if [[ $cmd == run ]]; then
    ((${#CMD[@]})) || die_usage "run needs: <owner> -- <command...>"
  fi

  if [[ $cmd != status ]]; then
    ((${#positional[@]})) || die_usage "$cmd needs an owner"
    OWNER=${positional[0]}
    [[ $OWNER =~ ^[A-Za-z0-9._-]+$ ]] || die_usage "invalid owner: $OWNER"
    ((${#positional[@]} <= 1)) || die_usage "unexpected argument: ${positional[1]}"
  fi

  case $cmd in
    status) print_holder ;;
    acquire) do_acquire "$OWNER" "$TTL" ;;
    renew) do_renew "$OWNER" "$TTL" ;;
    release) do_release "$OWNER" ;;
    steal) do_steal "$OWNER" "$TTL" ;;
    run) do_run "$OWNER" "$TTL" "$WAIT" "${CMD[@]}" ;;
  esac
}

main "$@"
