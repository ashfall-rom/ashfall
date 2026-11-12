#!/usr/bin/env bash
# AshFall: flash a GSI system image onto the RP5 (A/B, dynamic partitions).
#
# Safe by construction:
#   * refuses anything that is not a `kona` (SM8250) with an unlocked bootloader;
#   * works through fastbootd (userspace fastboot), the only place logical
#     partitions like `system` can be written;
#   * touches only `system`, `vbmeta` and (with --wipe) `userdata`+`metadata`;
#   * hard-blocks the partitions from the guide's NEVER-flash list (§13);
#   * refuses to delete `product_<slot>` unless the image really does not fit,
#     and explains the trade-off first;
#   * `--dry-run` performs only read-only getvars and prints the plan.
#
# The device must be USB-connected to the build host ($BUILD_HOST); all
# adb/fastboot calls run there in the `ashfall-dev` container.
#
# Usage:
#   scripts/device/flash-gsi.sh <system.img|host:/path/system.img> [options]
#
# Options:
#   --slot a|b         target slot (default: the device's current slot)
#   --wipe             erase userdata + metadata after flashing (clean boot;
#                      userdata erase takes ~6 minutes on this device)
#   --vbmeta PATH      use this vbmeta image instead of the stock one / an
#                      auto-generated empty vbmeta (host path or local file)
#   --keep-product     never delete product_<slot>, even if the image needs room
#   --serial SERIAL    device serial (default: the only one connected)
#   --yes              skip the interactive confirmation
#   --dry-run          print the plan, write nothing (still does read-only checks)
#   --no-device        plan without a device (dry-run only; for offline docs)
#   -h, --help         this help
#
# Exit codes: 0 success, 1 preflight/refusal, 2 usage, 3 flash failure.
#
# Notes
#   * Never uses `fastboot getvar all` (can wedge the bootloader, guide §8.1);
#     individual getvars are retried because this ABL is flaky under load.
#   * The RP5's stock vbmeta lives in $REMOTE_FIRMWARE (lun4_vbmeta_<slot>.bin).
#     If it is missing, an empty vbmeta with flags=3 (verity+verification
#     disabled) is generated with python3 and flashed instead.
#   * Phase 1 only ever runs this with --dry-run. See docs/flashing.md.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
# shellcheck source=SCRIPTDIR/lib/device.sh
. "$HERE/lib/device.sh"

# Partitions that must never be written from a foreign dump (guide §13).
# Erasing userdata/metadata/misc is allowed; flashing them is not.
NEVER_FLASH_RE='^(persist|modemst[12]|fsg|fsc|mdm1m9kefs[0-9a-z]*|devinfo|frp|keystore|secdata|ssd|storsec|cdt|ddr|mdmddr|nvdata[12]|nvram|qpdata[0-9a-z]*|rawdump|reserve[0-9a-z]*|ssign|apdp|msadp|spunvm|uefivarstore|vm-data|vm-keystore|.*_gpt_header|.*_last_parti|metadata|userdata|misc)$'

IMG=""
SLOT=""
WIPE=0
DRY_RUN=0
ASSUME_YES=0
KEEP_PRODUCT=0
SERIAL=""
VBMETA=""
NO_DEVICE=0

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

while (($#)); do
  case $1 in
    --slot) SLOT=${2:?--slot needs a|b}; shift 2 ;;
    --wipe|-w) WIPE=1; shift ;;
    --vbmeta) VBMETA=${2:?--vbmeta needs a path}; shift 2 ;;
    --keep-product) KEEP_PRODUCT=1; shift ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-device) NO_DEVICE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) die_usage "unknown option: $1" ;;
    *) [[ -z $IMG ]] || die_usage "only one image argument is supported"; IMG=$1; shift ;;
  esac
done

[[ -n $IMG ]] || die_usage "missing <system.img>"
[[ -z $SLOT || $SLOT == a || $SLOT == b ]] || die_usage "--slot must be a or b"
((NO_DEVICE)) && ((DRY_RUN)) || { ((NO_DEVICE)) && die_usage "--no-device only makes sense with --dry-run"; }

TS=$(date -u +%Y%m%dT%H%M%SZ)
LOG_DIR="$LOCAL_ARTIFACTS/device/$TS"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/flash-gsi.log"
exec > >(tee -a "$LOG") 2>&1
log "log: $LOG"

# --- helpers -----------------------------------------------------------------
fb() { dev_sh "$(_cmd timeout 1800 fastboot -s "$DEV_SERIAL" "$@")"; }

# step <description> <fastboot args...>: run unless dry-run
step() {
  local what=$1; shift
  printf '\n==> %s\n' "$what"
  printf '    + fastboot %s\n' "$*"
  ((DRY_RUN)) && { printf '    [dry-run] not executed\n'; return 0; }
  fb "$@"
}

assert_flashable() {
  local name=$1
  [[ $name =~ $NEVER_FLASH_RE ]] && die "refusing to flash '$name': NEVER-flash list (guide §13)"
  return 0
}

host_file_exists() { [[ $(host_sh "test -f $(_dq "$1") && echo yes || echo no") == yes ]]; }
host_size() { host_sh "stat -c %s $(_dq "$1")"; }
host_head() { host_sh "head -c ${2:-4} $(_dq "$1") | od -An -tx1 | tr -d ' \\n'"; }

human() { numfmt --to=iec-i --suffix=B "$1" 2>/dev/null || printf '%s bytes' "$1"; }

# Resolve the image: a path on the build host wins, otherwise upload it.
resolve_image() {
  local img=$1 dest
  if host_file_exists "$img"; then
    printf '%s' "$img"; return 0
  fi
  [[ -f $img ]] || die "image not found locally or on $BUILD_HOST: $img"
  dest="$REMOTE_ARTIFACTS/device/upload"
  log "uploading $img to $BUILD_HOST:$dest/"
  host_sh "mkdir -p $(_dq "$dest")" >/dev/null
  rsync -a --info=progress2 -e "ssh ${ASH_SSH_OPTS[*]}" "$img" "$BUILD_HOST:$dest/" >&2
  printf '%s/%s' "$dest" "$(basename "$img")"
}

# Stock vbmeta from $REMOTE_FIRMWARE if present; empty string means "generate".
resolve_vbmeta() {
  local slot=$1 cand
  if [[ -n $VBMETA ]]; then
    printf '%s' "$VBMETA"; return 0
  fi
  for cand in "$REMOTE_FIRMWARE/vbmeta.img" \
              "$REMOTE_FIRMWARE/lun4_vbmeta_${slot}.bin" \
              "$REMOTE_FIRMWARE/lun4_vbmeta_a.bin" \
              "$REMOTE_FIRMWARE/vbmeta_${slot}.img"; do
    if host_file_exists "$cand"; then printf '%s' "$cand"; return 0; fi
  done
  printf ''
}

make_empty_vbmeta() {
  local out=$1
  mkdir -p "$(dirname "$out")"
  python3 - "$out" <<'PY'
import struct, sys
out = sys.argv[1]
h = bytearray(256)
h[0:4] = b"AVB0"
struct.pack_into(">I", h, 4, 1)    # required libavb major
struct.pack_into(">I", h, 8, 0)    # required libavb minor
struct.pack_into(">Q", h, 12, 0)   # auth block size
struct.pack_into(">Q", h, 20, 0)   # aux block size
struct.pack_into(">I", h, 28, 0)   # algorithm NONE
struct.pack_into(">Q", h, 112, 0)  # rollback index
struct.pack_into(">I", h, 120, 3)  # flags: HASHTREE_DISABLED | VERIFICATION_DISABLED
rel = b"avbtool 1.2.0"
h[128:128+len(rel)] = rel
open(out, "wb").write(bytes(h))
PY
}

# upload_to_host <local-file>: copy to $REMOTE_ARTIFACTS/device/<ts>/ and
# print the host path.
upload_to_host() {
  local src=$1 dest="$REMOTE_ARTIFACTS/device/$TS"
  host_sh "mkdir -p $(_dq "$dest")" >/dev/null
  rsync -a -e "ssh ${ASH_SSH_OPTS[*]}" "$src" "$BUILD_HOST:$dest/" >&2
  printf '%s/%s' "$dest" "$(basename "$src")"
}

# Read-only preflight on the connected device; fills MODE, CUR_SLOT, PRODUCT,
# UNLOCKED. In dry-run without a device it degrades to "unknown" instead of
# dying (so the plan can be read offline).
preflight_device() {
  MODE=none CUR_SLOT='' PRODUCT='' UNLOCKED='' DEV_SERIAL=''
  if ((NO_DEVICE)); then
    warn "--no-device: skipping device preflight (plan only)"
    return 0
  fi
  MODE=$(dev_mode)
  if [[ $MODE == none || $MODE == ambiguous ]]; then
    if ((DRY_RUN)); then
      warn "no usable device on $BUILD_HOST right now; showing an offline plan"
      return 0
    fi
    die "no usable device via adb/fastboot on $BUILD_HOST (check the cable and the mode)"
  fi
  if ! DEV_SERIAL=$(dev_resolve_serial "$SERIAL"); then
    die "cannot determine the device serial (several devices? use --serial)"
  fi
  log "device $DEV_SERIAL, mode $MODE"
  case $MODE in
    fastboot|fastbootd)
      PRODUCT=$(dev_fb_value product)
      UNLOCKED=$(dev_fb_value unlocked)
      CUR_SLOT=$(dev_fb_value current-slot)
      ;;
    android|recovery)
      PRODUCT=$(dev_adb shell getprop ro.boot.hardware | tr -d '\r')
      if [[ $PRODUCT != *kona* && $PRODUCT != *sm8250* ]]; then
        PRODUCT=$(dev_adb shell getprop ro.board.platform | tr -d '\r')
      fi
      UNLOCKED=$(dev_adb shell getprop ro.boot.flash.locked | tr -d '\r')
      [[ -z $UNLOCKED ]] && UNLOCKED=$(dev_adb shell getprop ro.secureboot.lockstate | tr -d '\r')
      CUR_SLOT=$(dev_adb shell getprop ro.boot.slot_suffix | tr -d '\r_')
      ;;
    unauthorized)
      die "adb is unauthorized: accept the USB debugging prompt on the RP5, then re-run"
      ;;
    *)
      die "unexpected device mode: $MODE"
      ;;
  esac
}

print_plan() {
  local img=$1 img_size=$2 vb=$3 vb_desc=$4
  printf '\n================ PLAN ================\n'
  printf 'device:        %s (%s)\n' "${DEV_SERIAL:-unknown}" "${MODE:-unknown}"
  printf 'product:       %s (must be %s)\n' "${PRODUCT:-unknown}" "$DEVICE_FASTBOOT_PRODUCT"
  printf 'unlocked:      %s\n' "${UNLOCKED:-unknown}"
  printf 'current slot:  %s -> target slot: %s\n' "${CUR_SLOT:-unknown}" "$SLOT"
  printf 'image:         %s (%s)\n' "$img" "$(human "$img_size")"
  printf 'vbmeta:        %s\n' "$vb_desc"
  printf '\nsteps:\n'
  printf '  1. switch to fastbootd (userspace fastboot) if needed\n'
  printf '  2. re-check free space in super; delete product_%s only if the image\n' "$SLOT"
  printf '     does not fit (frees vendor apps; restored by restore-stock.sh)\n'
  printf '  3. fastboot flash system %s\n' "$img"
  if [[ $VB_FLAGS == 1 ]]; then
    printf '  4. fastboot --disable-verity --disable-verification flash vbmeta %s\n' "$vb"
  else
    printf '  4. fastboot flash vbmeta %s (already flags=3)\n' "$vb"
  fi
  if ((WIPE)); then
    printf '  5. fastboot erase userdata  (slow, ~6 min)\n'
    printf '  6. fastboot erase metadata\n'
  fi
  printf '  7. fastboot set-active %s (only if it is not active already)\n' "$SLOT"
  printf '======================================\n'
}

# --- main --------------------------------------------------------------------
IMG_HOST=$(resolve_image "$IMG")
IMG_SIZE=$(host_size "$IMG_HOST")
[[ $IMG_SIZE =~ ^[0-9]+$ ]] || die "cannot stat image on $BUILD_HOST: $IMG_HOST"
IMG_MAGIC=$(host_head "$IMG_HOST" 4)
IMG_FS=$(host_sh "dd if=$(_dq "$IMG_HOST") bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1 | tr -d ' \\n'")

log "image $IMG_HOST ($(human "$IMG_SIZE"), magic $IMG_MAGIC)"
case $IMG_MAGIC in
  3aff26ed) log "android sparse image" ;;
  *)
    if [[ $IMG_FS == 53ef ]]; then
      log "ext4 image (magic at 0x438)"
    elif [[ $IMG_MAGIC == e2e1f5e0 || $IMG_MAGIC == e0f5e1e2 ]]; then
      die "image looks like EROFS; the 4.19 kernel cannot mount it (PLAN: GSI must be ext4)"
    else
      warn "unrecognised image type (magic $IMG_MAGIC); expected Android sparse or ext4 system.img"
    fi
    ;;
esac

preflight_device

# Slot selection
if [[ -z $SLOT ]]; then
  if [[ -n $CUR_SLOT ]]; then SLOT=$CUR_SLOT; else SLOT=a; fi
fi
log "target slot: $SLOT"

# Product / unlock checks (skip when unknown: --no-device or offline dry-run)
if [[ -n $PRODUCT ]]; then
  if [[ $PRODUCT == "$DEVICE_FASTBOOT_PRODUCT" ]]; then
    ok "product is $PRODUCT"
  elif [[ $MODE == android || $MODE == recovery ]] && [[ $PRODUCT == *sm8250* || $PRODUCT == *kona* ]]; then
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

# vbmeta resolution
VB=$(resolve_vbmeta "$SLOT")
if [[ -n $VB ]]; then
  host_file_exists "$VB" || die "vbmeta not found on $BUILD_HOST: $VB"
  [[ $(host_head "$VB" 4) == 41564230 ]] || die "not an AVB vbmeta image (magic != AVB0): $VB"
  VB_DESC="$VB (stock, flags patched by fastboot)"
  VB_FLAGS=1
else
  VB="$LOG_DIR/vbmeta-empty.img"
  make_empty_vbmeta "$VB"
  VB=$(upload_to_host "$VB")
  VB_DESC="$VB (generated empty vbmeta, flags=3: verity+verification disabled)"
  VB_FLAGS=0
  warn "no stock vbmeta in $REMOTE_FIRMWARE; generating an empty one"
fi

print_plan "$IMG_HOST" "$IMG_SIZE" "$VB" "$VB_DESC"

if ((DRY_RUN)); then
  log "dry-run: no device writes performed"
  exit 0
fi

if ((!ASSUME_YES)); then
  printf '\nType "flash" to continue: '
  read -r answer
  [[ $answer == flash ]] || { log "aborted"; exit 1; }
fi

# --- 1. reach fastbootd ------------------------------------------------------
ensure_fastbootd() {
  local i m
  m=$(dev_mode)
  case $m in
    fastbootd) ok "already in fastbootd"; return 0 ;;
    fastboot) ;;
    android|recovery|sideload)
      printf '\n==> reboot to bootloader (adb)\n    + adb reboot bootloader\n'
      dev_adb reboot bootloader
      for ((i = 0; i < 60; i++)); do [[ $(dev_mode) == fastboot ]] && break; sleep 2; done
      ;;
    *) die "cannot reach fastbootd from mode '$m'" ;;
  esac
  printf '\n==> reboot bootloader -> fastbootd\n    + fastboot reboot fastboot\n'
  fb reboot fastboot
  for ((i = 0; i < 90; i++)); do
    if [[ $(dev_mode) == fastbootd ]]; then ok "fastbootd reachable"; return 0; fi
    sleep 2
  done
  die "device did not enter fastbootd (check the screen; hard power-off and retry)"
}

ensure_fastbootd
DEV_SERIAL=$(dev_resolve_serial "$SERIAL")
log "fastbootd device: $DEV_SERIAL"

# --- 2. space check + optional product deletion ------------------------------
sys_size=$(dev_fb_value "partition-size:system_$SLOT")
prod_size=$(dev_fb_value "partition-size:product_$SLOT")
super_size=$(dev_fb_value "partition-size:super")
log "size system_$SLOT=${sys_size:-unknown} product_$SLOT=${prod_size:-unknown} super=${super_size:-unknown}"

need=0
if [[ $sys_size =~ ^0x[0-9a-fA-F]+$ ]]; then
  sys_bytes=$((sys_size))
  ((IMG_SIZE > sys_bytes)) && need=$((IMG_SIZE - sys_bytes))
fi
if [[ $super_size =~ ^0x[0-9a-fA-F]+$ ]] && ((IMG_SIZE > super_size)); then
  die "image ($(human "$IMG_SIZE")) is larger than super ($(human "$super_size")); not a GSI system image?"
fi

if ((need > 0)); then
  prod_bytes=0
  [[ $prod_size =~ ^0x[0-9a-fA-F]+$ ]] && prod_bytes=$((prod_size))
  warn "image is $(human "$IMG_SIZE"), system_$SLOT is $(human "$sys_bytes") -> need $(human "$need") more in super"
  if ((KEEP_PRODUCT)); then
    die "--keep-product set and the image does not fit; free space manually or drop the flag"
  fi
  if ((prod_bytes >= need)); then
    log "deleting logical partition product_$SLOT (frees $(human "$prod_bytes")) to make room"
    log "  this only removes vendor/product apps from the flashed build;"
    log "  scripts/device/restore-stock.sh re-flashes the stock super"
    step "delete-logical-partition product_$SLOT" delete-logical-partition "product_$SLOT"
    PRODUCT_DELETED=1
  else
    die "not enough space: need $(human "$need"), product_$SLOT only holds $(human "$prod_bytes"). Free another logical partition manually."
  fi
else
  ok "image fits in the current system_$SLOT (no partition surgery needed)"
  PRODUCT_DELETED=0
fi

# --- 3. flash system ---------------------------------------------------------
assert_flashable system

flash_system() {
  local out rc
  printf '\n==> flash system -> system_%s\n' "$SLOT"
  printf '    + fastboot --slot %s flash system %s\n' "$SLOT" "$IMG_HOST"
  out=$(fb --slot "$SLOT" flash system "$IMG_HOST" 2>&1) && rc=0 || rc=$?
  printf '%s\n' "$out"
  return $rc
}

if ! flash_system; then
  if ((PRODUCT_DELETED == 0)) && ((KEEP_PRODUCT == 0)) && [[ $prod_size =~ ^0x[0-9a-fA-F]+$ ]]; then
    warn "flash failed and product_$SLOT still exists; freeing it and retrying once"
    step "delete-logical-partition product_$SLOT" delete-logical-partition "product_$SLOT"
    PRODUCT_DELETED=1
    flash_system || die "flashing system failed twice; device left in fastbootd (see log above)"
  else
    die "flashing system failed; device left in fastbootd (see log above)"
  fi
fi
ok "system_$SLOT flashed"

# --- 4. vbmeta ---------------------------------------------------------------
assert_flashable vbmeta
if ((VB_FLAGS)); then
  step "flash vbmeta with verity+verification disabled" \
    --disable-verity --disable-verification flash vbmeta "$VB"
else
  step "flash generated empty vbmeta (flags=3)" flash vbmeta "$VB"
fi
ok "vbmeta flashed (verification disabled: required for a GSI)"

# --- 5./6. wipe + active slot (in the bootloader, like guide §8.3) -----------
# fastbootd only knows logical partitions; erasing userdata/metadata and
# changing the active slot belong to the bootloader's fastboot, so go back.
if ((WIPE)) || [[ $SLOT != "${CUR_SLOT:-}" ]]; then
  step "reboot fastbootd -> bootloader" reboot bootloader
  for ((i = 0; i < 60; i++)); do [[ $(dev_mode) == fastboot ]] && break; sleep 2; done
  [[ $(dev_mode) == fastboot ]] || die "device did not return to the bootloader"
  ok "back in the bootloader"
fi

if ((WIPE)); then
  # Erasing userdata/metadata is explicitly allowed (never *flashing* them).
  step "erase userdata (slow, ~6 min on this device)" erase userdata
  step "erase metadata (vold regenerates encryption keys)" erase metadata
  ok "userdata + metadata erased"
else
  log "keeping userdata (use --wipe for a clean first boot; /data from the stock ROM is usually not readable by the GSI)"
fi

cur=$(dev_fb_value current-slot)
if [[ $cur == "$SLOT" ]]; then
  ok "slot $SLOT already active"
else
  if step "set active slot $SLOT" set-active "$SLOT"; then
    ok "slot $SLOT marked active (takes effect on the next boot)"
  else
    warn "could not set the active slot; from the bootloader run: fastboot set-active $SLOT"
  fi
fi

# --- 7. report ---------------------------------------------------------------
printf '\n================ DONE ================\n'
printf 'image:      %s\n' "$IMG_HOST"
printf 'slot:       %s\n' "$SLOT"
printf 'wipe:       %s\n' "$((WIPE))"
printf 'product_%s deleted: %s\n' "$SLOT" "${PRODUCT_DELETED:-0}"
printf 'log:        %s\n' "$LOG"
printf '\nnext:\n'
printf '  fastboot reboot           # boot Android; first GSI boot can take 2-5 min\n'
printf '  scripts/device/logs.sh     # if it boots but misbehaves\n'
printf '  scripts/device/restore-stock.sh   # bail-out to stock\n'
printf '======================================\n'

