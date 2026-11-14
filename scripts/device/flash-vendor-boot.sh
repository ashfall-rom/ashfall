#!/usr/bin/env bash
# AshFall: flash a vendor_boot image (phase 6 GPU OC) or restore the stock one.
#
# vendor_boot is a *physical, non-slotted* partition on the RP5: this script
# runs in the bootloader's fastboot (not fastbootd), flashes the plain
# `vendor_boot` partition (never `--slot`, which fastboot rejects/warns about
# and ignores) and optionally the stock `vbmeta` with verification disabled.
# It never wipes and never touches the other slot.
#
# Debug OC iterations: a failed variant is normally left in place and the next
# variant is flashed over it.  `--stock` is for last-resort recovery only
# (device unreachable and the bootloader cannot be entered), not a required
# step between variants.
#
# The phase-6 OC image is not AVB-signed.  The stock vendor_boot is padded to
# its partition and carries an AVB hash footer; repacking the DTB invalidates
# it, so -- exactly as for the GSI system flash -- the stock vbmeta is flashed
# with --disable-verity --disable-verification.  The current build already
# boots with verification disabled; re-applying it keeps that invariant.
#
# Usage:
#   scripts/device/flash-vendor-boot.sh <image|host:/path> [options]
#   scripts/device/flash-vendor-boot.sh --stock [options]
#
# Options:
#   --stock            restore the stock image for the active slot
#                      ($REMOTE_FIRMWARE/img/lun4_vendor_boot_<slot>.bin);
#                      last-resort recovery only - do not use it between debug
#                      variants, flash the next variant over the failed one
#   --slot a|b         target active slot for the guard/vbmeta; vendor_boot
#                      itself is non-slotted and is flashed without --slot
#                      (default: the active slot reported by the device)
#   --serial SERIAL    device serial (default: the only one connected)
#   --vbmeta PATH      stock vbmeta img (default:
#                      $REMOTE_FIRMWARE/img/lun4_vbmeta_<slot>.bin)
#   --no-vbmeta        do not re-flash vbmeta (only if verification is already
#                      disabled for this device)
#   --partition-size N vendor_boot partition size in bytes (default: read with
#                      `fastboot getvar partition-size:vendor_boot`, fall back
#                      to 100663296 = the stock image size)
#   --yes              skip the interactive confirmation
#   --dry-run          print the plan; no device writes
#   --no-device        offline plan with --dry-run
#   -h, --help         this help
#
# Exit codes: 0 success, 1 preflight/refusal, 2 usage, 3 flash failure.
#
# The RP5 is USB-connected to $BUILD_HOST; adb/fastboot run in the `ashfall-dev`
# container (scripts/device/lib/device.sh).
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
# shellcheck source=SCRIPTDIR/lib/device.sh
. "$HERE/lib/device.sh"

STOCK=0
IMG=""
SLOT=""
SLOT_GIVEN=0
SERIAL=""
VBMETA=""
NO_VBMETA=0
PARTITION_SIZE=""
DRY_RUN=0
NO_DEVICE=0
ASSUME_YES=0
STOCK_VENDOR_BOOT_SIZE=100663296
# The RP5 vendor_boot partition is not A/B-slotted (fastboot prints "vendor_boot
# does not support slots" and ignores --slot).  Keep this 0 for this device; the
# active-slot guard still applies to vbmeta and the system slot.
VENDOR_BOOT_SLOTTED=0

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }
die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

while (($#)); do
  case $1 in
    --stock) STOCK=1; shift ;;
    --slot) SLOT=${2:?--slot needs a|b}; SLOT_GIVEN=1; shift 2 ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    --vbmeta) VBMETA=${2:?--vbmeta needs a path}; shift 2 ;;
    --no-vbmeta) NO_VBMETA=1; shift ;;
    --partition-size) PARTITION_SIZE=${2:?--partition-size needs a value}; shift 2 ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-device) NO_DEVICE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) die_usage "unknown option: $1" ;;
    *) [[ -z $IMG ]] || die_usage "only one image argument is supported"; IMG=$1; shift ;;
  esac
done

((STOCK)) && [[ -n $IMG ]] && die_usage "--stock and an image argument are mutually exclusive"
((STOCK)) || [[ -n $IMG ]] || die_usage "missing <vendor_boot.img> (or --stock)"
[[ -z $SLOT || $SLOT == a || $SLOT == b ]] || die_usage "--slot must be a or b"
if ((NO_DEVICE)) && ((!DRY_RUN)); then die_usage "--no-device only makes sense with --dry-run"; fi
[[ -z $PARTITION_SIZE || $PARTITION_SIZE =~ ^[0-9]+$ ]] || die_usage "--partition-size must be a number"

TS=$(date -u +%Y%m%dT%H%M%SZ)
LOG_DIR="$LOCAL_ARTIFACTS/device/$TS"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/flash-vendor-boot.log"
exec > >(tee -a "$LOG") 2>&1
log "log: $LOG"

# --- helpers -----------------------------------------------------------------
fb() { dev_sh "$(_cmd timeout 1800 fastboot -s "$DEV_SERIAL" "$@")"; }

step() {
  local what=$1; shift
  printf '\n==> %s\n' "$what"
  printf '    + fastboot %s\n' "$*"
  ((DRY_RUN)) && { printf '    [dry-run] not executed\n'; return 0; }
  fb "$@"
}

host_file_exists() { [[ $(host_sh "test -f $(_dq "$1") && echo yes || echo no") == yes ]]; }
host_size() { host_sh "stat -c %s $(_dq "$1")"; }
host_sha() { host_sh "sha256sum $(_dq "$1") | cut -d' ' -f1"; }
host_magic() { host_sh "head -c ${2:-8} $(_dq "$1")"; }

human() {
  local n=$1
  if out=$(numfmt --to=iec-i --suffix=B "$n" 2>/dev/null); then printf '%s' "$out"; else printf '%s bytes' "$n"; fi
}

# resolve_image <local-or-host path> -> host path (upload when it is local)
resolve_image() {
  local img=$1 dest
  if host_file_exists "$img"; then printf '%s' "$img"; return 0; fi
  [[ -f $img ]] || die "image not found locally or on $BUILD_HOST: $img"
  dest="$REMOTE_ARTIFACTS/device/upload"
  log "uploading $img to $BUILD_HOST:$dest/"
  host_sh "mkdir -p $(_dq "$dest")" >/dev/null
  rsync -a --info=progress2 -e "ssh ${ASH_SSH_OPTS[*]}" "$img" "$BUILD_HOST:$dest/" >&2
  printf '%s/%s' "$dest" "$(basename "$img")"
}

# validate_vendor_boot <host path>: magic and header version, size later.
validate_vendor_boot() {
  local img=$1 magic version
  magic=$(host_magic "$img")
  [[ $magic == VNDRBOOT ]] || die "not a vendor_boot image (magic '$magic'): $img"
  version=$(host_sh "python3 -c 'import struct,sys;d=open(sys.argv[1],\"rb\").read(12);print(struct.unpack_from(\"<I\",d,8)[0])' $(_dq "$img")")
  [[ $version == 4 ]] || die "vendor_boot header version $version, expected 4: $img"
  IMG_SIZE=$(host_size "$img")
  [[ $IMG_SIZE =~ ^[0-9]+$ ]] || die "cannot stat image on $BUILD_HOST: $img"
}

# --- device preflight --------------------------------------------------------
MODE=none CUR_SLOT='' PRODUCT='' UNLOCKED='' DEV_SERIAL=''

preflight_device() {
  if ((NO_DEVICE)); then
    warn "--no-device: skipping device preflight (plan only)"
    return 0
  fi
  MODE=$(dev_mode)
  if [[ $MODE == none || $MODE == ambiguous ]]; then
    if ((DRY_RUN)); then
      warn "no usable device on $BUILD_HOST right now; showing an offline plan"
      MODE=none
      return 0
    fi
    die "no usable device via adb/fastboot on $BUILD_HOST (check the cable and the mode)"
  fi
  DEV_SERIAL=$(dev_resolve_serial "$SERIAL")
  log "device $DEV_SERIAL, mode $MODE"
  case $MODE in
    android|recovery)
      PRODUCT=$(dev_adb shell getprop ro.boot.hardware | tr -d '\r')
      if [[ $PRODUCT != *kona* && $PRODUCT != *sm8250* ]]; then
        PRODUCT=$(dev_adb shell getprop ro.board.platform | tr -d '\r')
      fi
      UNLOCKED=$(dev_adb shell getprop ro.boot.flash.locked | tr -d '\r')
      [[ -z $UNLOCKED ]] && UNLOCKED=$(dev_adb shell getprop ro.secureboot.lockstate | tr -d '\r')
      CUR_SLOT=$(dev_adb shell getprop ro.boot.slot_suffix | tr -d '\r_')
      ;;
    fastboot)
      PRODUCT=$(dev_fb_value product)
      UNLOCKED=$(dev_fb_value unlocked)
      CUR_SLOT=$(dev_fb_value current-slot)
      ;;
    fastbootd)
      PRODUCT=$(dev_fb_value product)
      UNLOCKED=$(dev_fb_value unlocked)
      CUR_SLOT=$(dev_fb_value current-slot)
      ;;
    unauthorized) die "adb is unauthorized: accept the USB debugging prompt on the RP5, then re-run" ;;
    *) die "unexpected device mode: $MODE" ;;
  esac
}

assert_device() {
  if [[ -n $PRODUCT ]]; then
    if [[ $PRODUCT == "$DEVICE_FASTBOOT_PRODUCT" ]]; then
      ok "product is $PRODUCT"
    elif [[ $PRODUCT == *sm8250* || $PRODUCT == *kona* ]]; then
      ok "platform is $PRODUCT (kona/SM8250)"
    else
      die "refusing to flash: product '$PRODUCT' != '$DEVICE_FASTBOOT_PRODUCT' (wrong device?)"
    fi
  fi
  if [[ -n $UNLOCKED ]]; then
    case $UNLOCKED in
      yes|0|unlocked) ok "bootloader unlocked: $UNLOCKED" ;;
      *) die "refusing to flash: bootloader reports locked ($UNLOCKED)" ;;
    esac
  fi
}

ensure_bootloader() {
  local m
  m=$(dev_mode)
  case $m in
    fastboot) ok "already in the bootloader"; return 0 ;;
    android|recovery|sideload)
      printf '\n==> %s -> bootloader (adb reboot bootloader)\n' "$m"
      dev_adb reboot bootloader || die "adb reboot bootloader failed in mode '$m'"
      ;;
    fastbootd)
      printf '\n==> fastbootd -> bootloader\n'
      dev_fastboot reboot bootloader || die "fastbootd could not reboot to the bootloader"
      ;;
    unauthorized) die "adb is unauthorized: accept the USB debugging prompt on the RP5, then re-run" ;;
    offline) die "adb device is offline: re-plug the USB cable (or hard power-off 15 s), then re-run" ;;
    *) die "cannot reach the bootloader from mode '$m' (connect the RP5 in Android, recovery or fastboot)" ;;
  esac
  if ! dev_wait_mode fastboot "$FB_TRANSITION_TIMEOUT"; then
    die "device did not reach the bootloader after ${FB_TRANSITION_TIMEOUT}s (last mode: ${DEV_MODE_LAST}); hard power-off 15 s and retry"
  fi
  ok "bootloader reachable"
}

# --- plan --------------------------------------------------------------------
preflight_device
assert_device

if [[ -z $SLOT ]]; then
  SLOT=${CUR_SLOT:-a}
fi
[[ $SLOT == a || $SLOT == b ]] || die "unexpected current-slot '$CUR_SLOT'; pass --slot a|b explicitly"
if ((SLOT_GIVEN)) && [[ -n $CUR_SLOT && $SLOT != "$CUR_SLOT" ]]; then
  die "refusing to touch the inactive slot: current is '$CUR_SLOT', --slot is '$SLOT' (this script only flashes the active slot)"
fi
log "active slot: $SLOT"

STOCK_IMG="$REMOTE_FIRMWARE/img/lun4_vendor_boot_${SLOT}.bin"
STOCK_VBMETA_DEFAULT="$REMOTE_FIRMWARE/img/lun4_vbmeta_${SLOT}.bin"

# Keep a working restore path before flashing anything.
if ((!STOCK)); then
  host_file_exists "$STOCK_IMG" || die "stock recovery image missing: $STOCK_IMG (refusing to flash without a stock restore path)"
  validate_vendor_boot "$STOCK_IMG"
  STOCK_SHA=$(host_sha "$STOCK_IMG")
else
  STOCK_SHA=""
fi

if ((STOCK)); then
  host_file_exists "$STOCK_IMG" || die "stock vendor_boot not found: $STOCK_IMG"
  IMG_HOST=$STOCK_IMG
  NO_VBMETA=1
else
  IMG_HOST=$(resolve_image "$IMG")
fi
validate_vendor_boot "$IMG_HOST"
IMG_SHA=$(host_sha "$IMG_HOST")
log "image: $IMG_HOST ($(human "$IMG_SIZE"), sha256 $IMG_SHA)"

VB=""
if ((!NO_VBMETA)); then
  if [[ -n $VBMETA ]]; then
    if host_file_exists "$VBMETA"; then VB=$VBMETA
    elif [[ -f $VBMETA ]]; then VB=$(resolve_image "$VBMETA")
    else die "--vbmeta: not found locally or on $BUILD_HOST: $VBMETA"; fi
  else
    VB=$STOCK_VBMETA_DEFAULT
    host_file_exists "$VB" || die "stock vbmeta missing: $VB (pass --vbmeta, or --no-vbmeta if verification is already disabled)"
  fi
fi

print_plan() {
  printf '\n================ PLAN ================\n'
  printf 'device:        %s (%s)\n' "${DEV_SERIAL:-unknown}" "${MODE:-unknown}"
  printf 'product:       %s (must be %s)\n' "${PRODUCT:-unknown}" "$DEVICE_FASTBOOT_PRODUCT"
  printf 'unlocked:      %s\n' "${UNLOCKED:-unknown}"
  printf 'active slot:   %s (only this slot is touched)\n' "${CUR_SLOT:-unknown}"
  printf 'image:         %s (%s)\n' "$IMG_HOST" "$(human "$IMG_SIZE")"
  printf 'image sha256:  %s\n' "$IMG_SHA"
  printf 'stock recovery: %s%s\n' "$STOCK_IMG" "${STOCK_SHA:+ (sha256 $STOCK_SHA)}"
  printf 'vendor_boot:    non-slotted (flashed as plain vendor_boot, no --slot)\n'
  if ((NO_VBMETA)); then
    printf 'vbmeta:        untouched (--no-vbmeta%s)\n' "$( ((STOCK)) && printf ' / --stock')"
  else
    printf 'vbmeta:        %s with --disable-verity --disable-verification\n' "$VB"
  fi
  printf '\nsteps:\n'
  printf '  - reach the bootloader (adb reboot bootloader; fastbootd: fastboot reboot bootloader)\n'
  printf '  - re-read product/unlocked/current-slot and refuse on any change\n'
  ((NO_VBMETA)) || printf '  - fastboot --slot %s --disable-verity --disable-verification flash vbmeta %s\n' "$SLOT" "$VB"
  printf '  - fastboot flash vendor_boot %s\n' "$IMG_HOST"
  printf '  - fastboot reboot\n'
  printf '======================================\n'
}

print_plan

if ((DRY_RUN)); then
  log "dry-run: no device writes performed"
  exit 0
fi

if ((!ASSUME_YES)); then
  printf '\nType "flash" to continue: '
  read -r answer
  [[ $answer == flash ]] || { log "aborted"; exit 1; }
fi

# --- flash -------------------------------------------------------------------
ensure_bootloader
DEV_SERIAL=$(dev_resolve_serial "$SERIAL")

# Re-read everything in the bootloader: never trust the pre-reboot values.
PRODUCT=$(dev_fb_value product)
UNLOCKED=$(dev_fb_value unlocked)
FB_SLOT=$(dev_fb_value current-slot)
assert_device
[[ -n $PRODUCT ]] || die "cannot read 'product' in the bootloader (flaky fastboot?); refusing to flash"
[[ -n $UNLOCKED ]] || die "cannot read 'unlocked' in the bootloader (flaky fastboot?); refusing to flash"
[[ $FB_SLOT == "$SLOT" ]] || die "active slot changed to '$FB_SLOT' (expected '$SLOT'); refusing to flash"

size_hex=$(dev_fb_value "partition-size:vendor_boot")
if [[ -n $PARTITION_SIZE ]]; then
  LIMIT=$PARTITION_SIZE
elif [[ $size_hex =~ ^0x[0-9a-fA-F]+$ ]]; then
  LIMIT=$((size_hex))
else
  LIMIT=$STOCK_VENDOR_BOOT_SIZE
  warn "could not read partition-size:vendor_boot; assuming $LIMIT bytes (the stock image size)"
fi
((IMG_SIZE <= LIMIT)) || die "image is $(human "$IMG_SIZE") but the vendor_boot partition is $(human "$LIMIT")"
ok "image fits vendor_boot: $(human "$IMG_SIZE") <= $(human "$LIMIT")"

if ((!NO_VBMETA)); then
  vb_magic=$(host_magic "$VB" 4)
  [[ $vb_magic == AVB0 ]] || die "not an AVB vbmeta image (magic '$vb_magic' != AVB0): $VB"
  step "flash vbmeta (verity + verification disabled)" \
    --slot "$SLOT" --disable-verity --disable-verification flash vbmeta "$VB"
  ok "vbmeta flashed (AVB verification disabled, as for the GSI flash)"
fi

Flash() {
  printf '\n==> flash vendor_boot -> vendor_boot_%s (non-slotted)\n' "$SLOT"
  local out rc
  if ((VENDOR_BOOT_SLOTTED)); then
    printf '    + fastboot --slot %s flash vendor_boot %s\n' "$SLOT" "$IMG_HOST"
    out=$(fb --slot "$SLOT" flash vendor_boot "$IMG_HOST" 2>&1) && rc=0 || rc=$?
  else
    printf '    + fastboot flash vendor_boot %s\n' "$IMG_HOST"
    out=$(fb flash vendor_boot "$IMG_HOST" 2>&1) && rc=0 || rc=$?
  fi
  printf '%s\n' "$out"
  return $rc
}

Flash || die "flashing vendor_boot failed; the device is still in the bootloader (see the log above)"
ok "vendor_boot_$SLOT flashed"

step "reboot" reboot || warn "reboot command failed; power the device on manually"

printf '\n================ DONE ================\n'
printf 'slot:        %s\n' "$SLOT"
printf 'image:       %s\n' "$IMG_HOST"
printf 'sha256:      %s\n' "$IMG_SHA"
printf 'log:         %s\n' "$LOG"
printf '\nnext (after boot):\n'
printf '  adb shell cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies\n'
printf '  adb shell getprop ashfall.status.perf.gpu_oc\n'
printf '  # failed variant: flash the next variant over it; do NOT --stock between attempts\n'
printf '  # scripts/device/flash-vendor-boot.sh --stock   # last-resort recovery only\n'
printf '======================================\n'
