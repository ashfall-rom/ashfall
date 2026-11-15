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
# the build host's ashfall-dev container. Every image here is a PHYSICAL
# partition: `super` is the 12 GiB physical partition (guide §8.3 flashes it as
# `fastboot flash super lun0_super.bin` from the bootloader), so no step needs
# fastbootd. The dynamic/logical partitions inside super only matter to
# flash-gsi.sh. This script deliberately refuses fastbootd instead of rebooting
# the device on its own.
#
# ONE fastboot session for the whole restore (issue #54): this ABL wedges when a
# new fastboot process opens between transfers or after a getvar burst (two
# restores hung on `Sending ...` on 2026-11-13). All flashes, the optional
# erase and set-active are therefore a single `fastboot` command line, started
# detached on the build host with a log + exit file. Partition sizes come from
# the stock capture (docs/stock/device-live/README.md) instead of asking the
# bootloader `partition-size:*`; the only getvars are the three preflight reads
# (mode/product/unlocked), before the batch.
#
# Userdata erase is 351 s after a super flash and ~0.09 s when super was not
# rewritten (issue #54, observed 2026-11-13). The batch timeout covers both.
#
# Usage:
#   scripts/device/restore-stock.sh [options]
#
# Options:
#   --images DIR        stock image directory on the build host
#                       (default: $REMOTE_FIRMWARE/img when it exists, else
#                       $REMOTE_FIRMWARE)
#   --slot a|b|both     slots to flash (default: auto: both when both slot
#                       images exist, else the slot whose images are present)
#   --no-super          skip the 12 GiB super image (keeps current system)
#   --no-splash         skip splash
#   --wipe              erase userdata + metadata (needed if /data is unusable;
#                       ~351 s after the super flash, seconds otherwise)
#   --set-active a|b    mark this slot active (default: the restored slot when
#                       super is flashed, otherwise leave it)
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

# Physical partition sizes on the RP5, identical for slots a and b, from the
# stock bootloader capture (docs/stock/device-live/README.md, guide §8.2).
# Checking images against this table avoids `partition-size:*` getvars in the
# bootloader, which is exactly what wedged the two 2026-11-13 restore attempts
# (issue #54). Override/complete it only from a fresh snapshot, never blind.
declare -A PART_SIZE=(
  [boot]=0x6000000
  [init_boot]=0x800000
  [vendor_boot]=0x6000000
  [dtbo]=0x1800000
  [recovery]=0x6400000
  [vbmeta]=0x10000
  [vbmeta_system]=0x10000
  [vm-linux]=0x2000000
  [vm-system]=0x8000000
  [splash]=0x2000000
  [super]=0x300000000
)

IMAGES=""
SLOTS="auto"
SLOTS_REQ="auto"
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
    --slot) SLOTS=${2:?--slot needs a|b|both}; SLOTS_REQ=$SLOTS; shift 2 ;;
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

[[ $SLOTS == a || $SLOTS == b || $SLOTS == both || $SLOTS == auto ]] || die_usage "--slot must be a, b, both or auto"
[[ -z $SET_ACTIVE || $SET_ACTIVE == a || $SET_ACTIVE == b ]] || die_usage "--set-active must be a or b"
if ((NO_DEVICE)) && ((!DRY_RUN)); then
  die_usage "--no-device only makes sense with --dry-run"
fi
if [[ -z $IMAGES ]]; then
  if [[ $(host_sh "test -d $(_dq "$REMOTE_FIRMWARE/img") && echo yes || echo no") == yes ]]; then
    IMAGES="$REMOTE_FIRMWARE/img"
  else
    IMAGES="$REMOTE_FIRMWARE"
  fi
fi

TS=$(date -u +%Y%m%dT%H%M%SZ)
LOG_DIR="$LOCAL_ARTIFACTS/device/$TS"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/restore-stock.log"
exec > >(tee -a "$LOG") 2>&1

# --- helpers -----------------------------------------------------------------
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
# These are the ONLY getvars this script performs (mode/product/unlocked) and
# they all happen before the single write batch (issue #54).
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
# has_slot_images <slot>: true when the complete required set for that slot is
# present (vendor_boot/dtbo/vbmeta etc. are slot-suffixed; find_image still
# accepts unsuffixed images that can serve either slot).
has_slot_images() {
  local slot=$1 p
  for p in boot init_boot vendor_boot dtbo vbmeta; do
    [[ -n $(find_image "$p" "$slot") ]] || return 1
  done
  return 0
}

resolve_plan() {
  local slots=() slot part img size have_a have_b
  case $SLOTS in
    both) slots=(a b) ;;
    auto)
      have_a=no; have_b=no
      has_slot_images a && have_a=yes
      has_slot_images b && have_b=yes
      if [[ $have_a == yes && $have_b == yes ]]; then
        slots=(a b)
      elif [[ $have_a == yes ]]; then
        slots=(a)
        warn "only slot-A stock images are present in $IMAGES; planning slot A (use --slot both once B is extracted)"
      elif [[ $have_b == yes ]]; then
        slots=(b)
        warn "only slot-B stock images are present in $IMAGES; planning slot B (use --slot both once A is extracted)"
      else
        die "no complete slot image set (boot/init_boot/vendor_boot/dtbo/vbmeta) in $IMAGES"
      fi
      ;;
    *) slots=("$SLOTS") ;;
  esac
  SLOTS="${slots[*]}"
  log "image dir: $IMAGES; slots: $SLOTS"
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

# build_batch: turn PLAN + wipe/set-active/reboot into the ONE fastboot command
# line. No per-step fastboot process may be inserted here (issue #54).
build_batch() {
  local entry part img size
  BATCH_ARGS=()
  for entry in "${PLAN[@]}"; do
    IFS='|' read -r part img size <<<"$entry"
    BATCH_ARGS+=(flash "$part" "$img")
  done
  if ((WIPE)); then BATCH_ARGS+=(erase userdata erase metadata); fi
  [[ -n $SET_ACTIVE ]] && BATCH_ARGS+=(set_active "$SET_ACTIVE")
  ((REBOOT)) && BATCH_ARGS+=(reboot)
  return 0
}

print_plan() {
  local entry part img size partsize base
  printf '\n================ PLAN ================\n'
  printf 'images dir:  %s\n' "$IMAGES"
  printf 'device:      %s (fastboot/bootloader)\n' "${DEV_SERIAL:-unknown}"
  printf 'slots:       %s (requested: %s)\n' "$SLOTS" "$SLOTS_REQ"
  printf 'wipe:        %s   set-active: %s   reboot: %s\n' "$((WIPE))" "${SET_ACTIVE:-no}" "$((REBOOT))"
  printf '\nflashes (sizes checked against the documented stock table, no getvars):\n'
  for entry in "${PLAN[@]}"; do
    IFS='|' read -r part img size <<<"$entry"
    base=${part%_a}
    base=${base%_b}
    partsize=${PART_SIZE[$base]:-}
    printf '  %-18s <- %s (%s)' "$part" "$img" "$(human "$size")"
    if [[ -n $partsize ]]; then
      partsize=$((partsize))
      printf '  [partition %s]' "$(human "$partsize")"
    fi
    printf '\n'
    if [[ $SIZE_CHECK == 1 && -n $partsize ]]; then
      if ((size > partsize)); then
        die "image for $part is larger than the partition ($(human "$size") > $(human "$partsize")); refusing (--skip-size-check to override)"
      fi
    elif [[ $SIZE_CHECK == 1 ]]; then
      warn "no documented partition size for '$part'; skipping the size check"
    fi
  done
  printf '\none fastboot process for the whole restore (detached, log + exit file):\n'
  printf '  + fastboot -s %s' "${DEV_SERIAL:-<serial>}"
  printf ' %q' "${BATCH_ARGS[@]}"
  printf '\n'
  if ((WIPE)); then
    printf '\n  erase userdata: ~351 s after the super flash, ~0.09 s otherwise\n'
  fi
  printf '======================================\n'
}

# --- main --------------------------------------------------------------------
preflight
resolve_plan

# A factory `super` image only populates slot A's dynamic partitions.  If the
# bootloader stays on slot B the device boots a slot with no /system or
# /vendor: init starts from the ramdisk, finds nothing, exits 127 and the
# kernel panics ("Attempted to kill init! exitcode=0x00007f00", 2026-11-15).
# So restoring `super` pins the active slot to the restored one unless the
# caller asked for something else.
if ((DO_SUPER)) && [[ -z $SET_ACTIVE ]]; then
  SET_ACTIVE=${SLOTS%% *}
  log "super restore: set_active $SET_ACTIVE (a factory super only populates slot A)"
fi
build_batch
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

# Every partition name is checked before the single batch starts; a refusal
# must happen here, never between fastboot transfers.
for entry in "${PLAN[@]}"; do
  IFS='|' read -r part img size <<<"$entry"
  assert_flashable "${part%_a}"
  assert_flashable "${part%_b}"
done

printf '\n==> one fastboot process: %s operations (flashes, wipe, active slot)\n' "${#BATCH_ARGS[@]}"
printf '    logs stay on %s; do not start another fastboot while this runs\n' "$BUILD_HOST"
if dev_fastboot_batch "restore-stock-$TS" "${BATCH_ARGS[@]}"; then
  rc=0
  ok "restore batch finished"
else
  rc=$?
  warn "restore batch failed (exit $rc)"
fi

printf '\n================ DONE ================\n'
printf 'batch exit: %s\n' "$rc"
printf 'log: %s\n' "$LOG"
if ((rc != 0)); then
  printf '\nbail-out advice:\n'
  printf '  * do NOT open another fastboot process between transfers (issue #54)\n'
  printf '  * if the ABL is wedged: hold Power 15 s, then Vol Down + Power, re-enter fastboot (guide §6)\n'
  printf '  * check the batch log on %s for the last successful/FAILED step\n' "$BUILD_HOST"
  printf '  * re-run with --skip-size-check ONLY if the image/partition mismatch is understood\n'
  printf '  * EDL path for anything fastboot refuses: guide §9/§12\n'
  printf '======================================\n'
  exit 3
fi
printf 'device restored to the images in %s\n' "$IMAGES"
printf 'next: fastboot reboot (or re-run scripts/device/logs.sh after boot)\n'
printf '======================================\n'
