#!/usr/bin/env bash
# AshFall: bail-out path back to stock Android on the RP5.
#
# Re-flashes the stock OS images from $REMOTE_FIRMWARE (or --images DIR) as in
# docs/reference/rp5-recovery-guide.md §8: boot, init_boot, vendor_boot, dtbo,
# recovery, vbmeta, vbmeta_system, vm-linux, vm-system (+ splash, super).
# Use this when a GSI leaves the device bootlooping and you want stock back.
#
# It NEVER flashes anything from the guide's NEVER-flash list (§13):
# persist, modemst*, fsg/fsc, devinfo, frp/keystore/secdata/storsec, cdt/ddr,
# nvdata*, qpdata*, *_gpt_header/_last_parti, userdata, metadata, misc.
# Erasing userdata/metadata (--wipe) is allowed; flashing them is not.
#
# Runs in the bootloader's fastboot (NOT fastbootd), all adb/fastboot calls via
# the build host's ashfall-dev container.
#
# Usage:
#   scripts/device/restore-stock.sh [options]
#
# Options:
#   --images DIR        stock image directory on the build host
#                       (default: $REMOTE_FIRMWARE)
#   --slot a|b|both     slots to flash (default: both)
#   --no-super          skip the 12 GiB super image (keeps current system)
#   --no-splash         skip splash
#   --wipe              erase userdata + metadata (needed if /data is unusable;
#                       userdata erase takes ~6 minutes)
#   --set-active a|b    mark this slot active (default: leave as is)
#   --reboot            reboot to Android when done (default: stay in fastboot)
#   --skip-size-check   do not verify image sizes against partition sizes
#   --serial SERIAL     device serial (default: the only one connected)
#   --yes               skip the interactive confirmation
#   --dry-run           print the plan, write nothing (read-only checks only)
#   --no-device         plan without a device (only with --dry-run)
#   -h, --help          this help
#
# Exit codes: 0 success, 1 preflight/refusal, 2 usage, 3 flash failure.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
# shellcheck source=SCRIPTDIR/lib/device.sh
. "$HERE/lib/device.sh"

NEVER_FLASH_RE='^(persist|modemst[12]|fsg|fsc|mdm1m9kefs[0-9a-z]*|devinfo|frp|keystore|secdata|ssd|storsec|cdt|ddr|mdmddr|nvdata[12]|nvram|qpdata[0-9a-z]*|rawdump|reserve[0-9a-z]*|ssign|apdp|msadp|spunvm|uefivarstore|vm-data|vm-keystore|.*_gpt_header|.*_last_parti|metadata|userdata|misc)$'

IMAGES=""
SLOTS="both"
DO_SUPER=1
DO_SPLASH=1
WIPE=0
SET_ACTIVE=""
REBOOT=0
SIZE_CHECK=1
SERIAL=""
ASSUME_YES=0
DRY_RUN=0
NO_DEVICE=0

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }
die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

while (($#)); do
  case $1 in
    --images) IMAGES=${2:?--images needs a dir}; shift 2 ;;
    --slot) SLOTS=${2:?--slot needs a|b|both}; shift 2 ;;
    --no-super) DO_SUPER=0; shift ;;
    --no-splash) DO_SPLASH=0; shift ;;
    --wipe) WIPE=1; shift ;;
    --set-active) SET_ACTIVE=${2:?--set-active needs a|b}; shift 2 ;;
    --reboot) REBOOT=1; shift ;;
    --skip-size-check) SIZE_CHECK=0; shift ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-device) NO_DEVICE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die_usage "unknown option: $1" ;;
  esac
done

[[ $SLOTS == a || $SLOTS == b || $SLOTS == both ]] || die_usage "--slot must be a, b or both"
[[ -z $SET_ACTIVE || $SET_ACTIVE == a || $SET_ACTIVE == b ]] || die_usage "--set-active must be a or b"
[[ -n $IMAGES ]] || IMAGES=$REMOTE_FIRMWARE

TS=$(date -u +%Y%m%dT%H%M%SZ)
LOG_DIR="$LOCAL_ARTIFACTS/device/$TS"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/restore-stock.log"
exec > >(tee -a "$LOG") 2>&1

# --- helpers -----------------------------------------------------------------
fb() { dev_sh "$(_cmd timeout 3600 fastboot -s "$DEV_SERIAL" "$@")"; }
host_file_exists() { [[ $(host_sh "test -f $(_dq "$1") && echo yes || echo no") == yes ]]; }
host_size() { host_sh "stat -c %s $(_dq "$1")"; }
human() { numfmt --to=iec-i --suffix=B "$1" 2>/dev/null || printf '%s bytes' "$1"; }

assert_flashable() {
  local name=$1
  [[ $name =~ $NEVER_FLASH_RE ]] && die "refusing to flash '$name': NEVER-flash list (guide §13)"
  return 0
}

# find_image <partition> <slot>: prints a host path or empty
find_image() {
  local part=$1 slot=$2 cand
  local -a cands=()
  if [[ -n $slot ]]; then
    cands+=("$IMAGES/lun4_${part}_${slot}.bin" "$IMAGES/lun0_${part}_${slot}.bin"
            "$IMAGES/${part}_${slot}.img"      "$IMAGES/${part}_${slot}.bin")
  fi
  cands+=("$IMAGES/lun4_${part}.bin" "$IMAGES/lun0_${part}.bin"
          "$IMAGES/${part}.img"      "$IMAGES/${part}.bin")
  for cand in "${cands[@]}"; do
    if host_file_exists "$cand"; then printf '%s' "$cand"; return 0; fi
  done
  return 0
}

# Read-only preflight: device must be in the bootloader's fastboot.
preflight() {
  local mode
  if ((NO_DEVICE)); then
    warn "--no-device: skipping device preflight (plan only)"
    return 0
  fi
  mode=$(dev_mode)
  log "device mode: $mode"
  case $mode in
    fastboot) ;;
    fastbootd)
      die "device is in fastbootd; physical partitions need the bootloader: run 'fastboot reboot bootloader' first"
      ;;
    none|ambiguous|unauthorized|offline)
      die "no usable fastboot device on $BUILD_HOST (mode: $mode)"
      ;;
    *)
      die "device is in '$mode'; run 'adb reboot bootloader' first (this script never reboots for you in dry-run)"
      ;;
  esac
  DEV_SERIAL=$(dev_resolve_serial "$SERIAL")
  local product unlocked
  product=$(dev_fb_value product)
  unlocked=$(dev_fb_value unlocked)
  [[ $product == "$DEVICE_FASTBOOT_PRODUCT" ]] ||
    die "refusing: product '$product' != '$DEVICE_FASTBOOT_PRODUCT' (wrong device?)"
  [[ $unlocked == yes ]] || die "refusing: bootloader not unlocked ('$unlocked')"
  ok "device $DEV_SERIAL: $product, unlocked"
}

# build plan: array of "partition|image|size"
PLAN=()
resolve_plan() {
  local slots=() slot part img size
  case $SLOTS in both) slots=(a b) ;; *) slots=("$SLOTS") ;; esac
  local -a parts=(boot init_boot vendor_boot dtbo recovery vbmeta vbmeta_system vm-linux vm-system)
  for slot in "${slots[@]}"; do
    for part in "${parts[@]}"; do
      img=$(find_image "$part" "$slot")
      if [[ -z $img ]]; then
        case $part in
          recovery|vm-linux|vm-system|vbmeta_system) warn "missing optional image for ${part}_${slot} (skipped)" ;;
          *) die "missing stock image for ${part}_${slot} in $IMAGES" ;;
        esac
        continue
      fi
      size=$(host_size "$img")
      PLAN+=("${part}_${slot}|${img}|${size}")
    done
  done
  if ((DO_SPLASH)); then
    img=$(find_image splash '')
    if [[ -n $img ]]; then PLAN+=("splash|${img}|$(host_size "$img")")
    else warn "missing splash image (skipped)"; fi
  fi
  if ((DO_SUPER)); then
    img=$(find_image super '')
    if [[ -n $img ]]; then PLAN+=("super|${img}|$(host_size "$img")")
    else die "missing super image in $IMAGES (use --no-super to keep the current system)"; fi
  fi
  ((${#PLAN[@]})) || die "nothing to flash found in $IMAGES"
}

print_plan() {
  local entry part img size partsize
  printf '\n================ PLAN ================\n'
  printf 'images dir:  %s\n' "$IMAGES"
  printf 'device:      %s (fastboot/bootloader)\n' "${DEV_SERIAL:-unknown}"
  printf 'slots:       %s\n' "$SLOTS"
  printf 'wipe:        %s   set-active: %s   reboot: %s\n' "$((WIPE))" "${SET_ACTIVE:-no}" "$((REBOOT))"
  printf '\nflashes:\n'
  for entry in "${PLAN[@]}"; do
    IFS='|' read -r part img size <<<"$entry"
    partsize=''
    if [[ -n ${DEV_SERIAL:-} ]]; then
      case $part in
        super|splash) partsize=$(dev_fb_value "partition-size:$part") ;;
        *) partsize=$(dev_fb_value "partition-size:$part") ;;
      esac
    fi
    printf '  %-18s <- %s (%s)' "$part" "$img" "$(human "$size")"
    [[ -n $partsize ]] && printf '  [partition %s]' "$partsize"
    printf '\n'
    if [[ $SIZE_CHECK == 1 && $partsize =~ ^0x[0-9a-fA-F]+$ ]]; then
      if ((size > partsize)); then
        die "image for $part is larger than the partition ($(human "$size") > $partsize); refusing (--skip-size-check to override)"
      fi
    fi
  done
  if ((WIPE)); then
    printf '\n  erase userdata (slow, ~6 min) + erase metadata\n'
  fi
  [[ -n $SET_ACTIVE ]] && printf '  set-active %s\n' "$SET_ACTIVE"
  ((REBOOT)) && printf '  reboot\n'
  printf '======================================\n'
}

# --- main --------------------------------------------------------------------
preflight
resolve_plan
print_plan

if ((DRY_RUN)); then
  log "dry-run: no device writes performed"
  exit 0
fi
if ((!ASSUME_YES)); then
  printf '\nThis flashes STOCK firmware images over the current system. Type "restore" to continue: '
  read -r answer
  [[ $answer == restore ]] || { log "aborted"; exit 1; }
fi

fails=0
for entry in "${PLAN[@]}"; do
  IFS='|' read -r part img size <<<"$entry"
  assert_flashable "${part%_a}"
  assert_flashable "${part%_b}"
  printf '\n==> flash %s <- %s (%s)\n' "$part" "$img" "$(human "$size")"
  printf '    + fastboot flash %s %s\n' "$part" "$img"
  if fb flash "$part" "$img"; then
    ok "$part"
  else
    warn "$part FAILED (continuing)"
    fails=$((fails + 1))
  fi
done

if ((WIPE)); then
  printf '\n==> erase userdata (slow)\n    + fastboot erase userdata\n'
  fb erase userdata || { warn "erase userdata failed"; fails=$((fails + 1)); }
  printf '\n==> erase metadata\n    + fastboot erase metadata\n'
  fb erase metadata || { warn "erase metadata failed"; fails=$((fails + 1)); }
fi

if [[ -n $SET_ACTIVE ]]; then
  printf '\n==> set-active %s\n    + fastboot set-active %s\n' "$SET_ACTIVE" "$SET_ACTIVE"
  fb set-active "$SET_ACTIVE" || { warn "set-active failed"; fails=$((fails + 1)); }
fi

if ((REBOOT)); then
  printf '\n==> reboot\n    + fastboot reboot\n'
  fb reboot || warn "reboot failed"
fi

printf '\n================ DONE ================\n'
printf 'flashes failed: %s\n' "$fails"
printf 'log: %s\n' "$LOG"
if ((fails)); then
  printf '\nbail-out advice:\n'
  printf '  * re-check USB + that the device is still in fastboot (guide §6)\n'
  printf '  * re-run with --skip-size-check ONLY if the image/partition mismatch is understood\n'
  printf '  * EDL path for anything fastboot refuses: guide §9/§12\n'
  printf '======================================\n'
  exit 3
fi
printf 'device restored to the images in %s\n' "$IMAGES"
printf 'next: fastboot reboot (or re-run scripts/device/logs.sh after boot)\n'
printf '======================================\n'
