#!/usr/bin/env bash
# AshFall: flash a vendor_boot image (phase 6 GPU OC) or restore the stock one.
#
# vendor_boot and vbmeta are *non-slotted* physical partitions on the RP5, so
# this runs in the bootloader's fastboot (not fastbootd) and flashes the plain
# `vendor_boot` / `vbmeta` partitions (never `--slot`).  It never wipes and
# never touches the other slot.
#
# Issue #54: the RP5 ABL wedges when a new fastboot process opens between
# transfers or after a burst of getvars.  The whole write is ONE detached
# `fastboot` process via `dev_fastboot_batch` (AGENTS.md): a log + exit file on
# the build host, no partition-size getvars, and only the three read-only
# preflight values before the batch.  The 96 MiB zero-padded vendor_boot image
# also wedges the ABL on `Sending 'vendor_boot'`, so the **content-only** image
# (12,931,072 B for the phase-6 image) is what gets flashed.
#
# Debug OC iterations: a failed variant is normally left in place and the next
# variant is flashed over it.  `--stock` is for last-resort recovery only
# (device unreachable and the bootloader cannot be entered), not a required
# step between variants.
#
# Usage:
#   scripts/device/flash-vendor-boot.sh <image|host:/path> [options]
#   scripts/device/flash-vendor-boot.sh --release [<tag>] [options]
#   scripts/device/flash-vendor-boot.sh --stock [options]
#
# Options:
#   --release [TAG]    download a published artifact from $VENDOR_BOOT_REPO
#                      (default: the pinned $VENDOR_BOOT_TAG), verify its
#                      sha256 and flash that image.  The pinned tag must match
#                      $VENDOR_BOOT_SHA256; an explicit older/newer tag is
#                      verified against its own .sha256 asset.  Content-only
#                      images only (issue #54).
#   --stock            restore the stock image for the active slot
#                      ($REMOTE_FIRMWARE/img/lun4_vendor_boot_<slot>.bin);
#                      last-resort recovery only - do not use it between debug
#                      variants, flash the next variant over the failed one
#   --slot a|b         active slot for the guard/vbmeta; vendor_boot and
#                      vbmeta are non-slotted on the RP5 and are flashed
#                      without --slot (default: the active slot)
#   --serial SERIAL    device serial (default: the only one connected)
#   --vbmeta PATH      stock vbmeta img (default:
#                      $REMOTE_FIRMWARE/img/lun4_vbmeta_<slot>.bin)
#   --no-vbmeta        do not re-flash vbmeta (only if verification is already
#                      disabled for this device)
#   --partition-size N vendor_boot partition size in bytes for the fit check
#                      (default: 100663296 = the stock partition; no getvar)
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
RELEASE=0
RELEASE_TAG=""
STOCK_VENDOR_BOOT_SIZE=100663296

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }
die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

while (($#)); do
  case $1 in
    --stock) STOCK=1; shift ;;
    --release)
      RELEASE=1
      if [[ -n ${2:-} && ${2:-} != -* ]]; then RELEASE_TAG=$2; shift 2; else shift; fi
      ;;
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
((STOCK)) && ((RELEASE)) && die_usage "--stock and --release are mutually exclusive"
((RELEASE)) && [[ -n $IMG ]] && die_usage "--release and an image argument are mutually exclusive"
if ((STOCK || RELEASE)); then :; else
  [[ -n $IMG ]] || die_usage "missing <vendor_boot.img> (or --release [tag], or --stock)"
fi
[[ -z $SLOT || $SLOT == a || $SLOT == b ]] || die_usage "--slot must be a or b"
if ((NO_DEVICE)) && ((!DRY_RUN)); then die_usage "--no-device only makes sense with --dry-run"; fi
[[ -z $PARTITION_SIZE || $PARTITION_SIZE =~ ^[0-9]+$ ]] || die_usage "--partition-size must be a number"

TS=$(date -u +%Y%m%dT%H%M%SZ)
LOG_DIR="$LOCAL_ARTIFACTS/device/$TS"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/flash-vendor-boot.log"
exec > >(tee -a "$LOG") 2>&1
log "log: $LOG"

# --- published artifact (--release) -----------------------------------------
# One-way from $VENDOR_BOOT_REPO: download the content-only image, verify its
# sha256, then hand it to the normal flash path.  The pinned tag must match the
# pinned hash; any other tag is checked against its own .sha256 asset.
if ((RELEASE)); then
  need gh
  [[ -n ${VENDOR_BOOT_REPO:-} ]] || die "VENDOR_BOOT_REPO is not set in config/ashfall.conf"
  [[ -n $RELEASE_TAG ]] || RELEASE_TAG=${VENDOR_BOOT_TAG:-}
  [[ -n $RELEASE_TAG ]] || die "no release tag: pass --release <tag> or set VENDOR_BOOT_TAG"
  REL_DIR="$LOCAL_ARTIFACTS/vendor_boot/$RELEASE_TAG"
  REL_IMG="$REL_DIR/vendor_boot-$RELEASE_TAG.img"
  REL_SUM="$REL_DIR/vendor_boot-$RELEASE_TAG.img.sha256"
  mkdir -p "$REL_DIR"
  if [[ ! -f $REL_IMG || ! -f $REL_SUM ]]; then
    log "downloading $VENDOR_BOOT_REPO@$RELEASE_TAG"
    gh release download "$RELEASE_TAG" --repo "$VENDOR_BOOT_REPO" \
      --pattern "vendor_boot-$RELEASE_TAG.img" \
      --pattern "vendor_boot-$RELEASE_TAG.img.sha256" --dir "$REL_DIR" \
      || die "cannot download release $RELEASE_TAG from $VENDOR_BOOT_REPO"
  fi
  WANT_SHA=$(awk '{print $1}' "$REL_SUM")
  GOT_SHA=$(sha256sum "$REL_IMG" | awk '{print $1}')
  [[ -n $WANT_SHA ]] || die "empty checksum asset: $REL_SUM"
  [[ $GOT_SHA == "$WANT_SHA" ]] || die "release $RELEASE_TAG: image sha256 $GOT_SHA does not match its .sha256 asset ($WANT_SHA)"
  if [[ $RELEASE_TAG == "${VENDOR_BOOT_TAG:-}" && -n ${VENDOR_BOOT_SHA256:-} ]]; then
    [[ $GOT_SHA == "$VENDOR_BOOT_SHA256" ]] || die "release $RELEASE_TAG sha256 $GOT_SHA does not match the pinned VENDOR_BOOT_SHA256 ($VENDOR_BOOT_SHA256)"
    log "release: $VENDOR_BOOT_REPO@$RELEASE_TAG (pinned, sha256 $GOT_SHA)"
  else
    warn "release: $VENDOR_BOOT_REPO@$RELEASE_TAG is not the pinned tag (sha256 $GOT_SHA)"
  fi
  IMG=$REL_IMG
fi

# --- helpers -----------------------------------------------------------------
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

# validate_vendor_boot <host path>: magic and header version.
validate_vendor_boot() {
  local img=$1 magic version
  magic=$(host_magic "$img")
  [[ $magic == VNDRBOOT ]] || die "not a vendor_boot image (magic '$magic'): $img"
  version=$(host_sh "python3 -c 'import struct,sys;d=open(sys.argv[1],\"rb\").read(12);print(struct.unpack_from(\"<I\",d,8)[0])' $(_dq "$img")")
  [[ $version == 4 ]] || die "vendor_boot header version $version, expected 4: $img"
}

# vb_content_size <host path>: content_end from the v4 header (the flashable
# size; the rest is zero padding / an AVB footer).  No remote Python needed:
# pull the 2128-byte header as base64 and parse it here.
vb_content_size() {
  local img=$1 b64 size
  b64=$(host_sh "head -c 2128 $(_dq "$img") | base64 -w0") || die "cannot read $img header"
  size=$(python3 - "$b64" <<'PY'
import base64, struct, sys
raw = base64.b64decode(sys.argv[1])
if len(raw) < 2128:
    raise SystemExit("short vendor_boot header")
magic, version, page, _kaddr, _raddr, vramdisk = struct.unpack_from("<8sIIIII", raw, 0)
if magic != b"VNDRBOOT":
    raise SystemExit("bad vendor_boot magic")
if version != 4:
    raise SystemExit(f"vendor_boot version {version}, expected 4")
if page not in (2048, 4096, 8192, 16384):
    raise SystemExit(f"implausible page size {page}")
name_off = 28 + 2048 + 4
header_size, dtb_size = struct.unpack_from("<II", raw, name_off + 16)
table_size, _num, _ent, bootconfig_size = struct.unpack_from("<IIII", raw, name_off + 32)
def align(n):
    return (n + page - 1) // page * page
ramdisk = align(header_size)
dtb = align(ramdisk + vramdisk)
table = align(dtb + dtb_size)
bootconfig = align(table + table_size)
print(align(bootconfig + bootconfig_size))
PY
) || die "cannot parse the vendor_boot header: $img"
  [[ $size =~ ^[0-9]+$ ]] || die "cannot parse the vendor_boot header: $img"
  printf '%s' "$size"
}

# make_content_only <host path> -> host path of the content-only image.
# A padded image is trimmed to content_end in the upload dir; an image that is
# already content-only is returned unchanged (issue #54).
make_content_only() {
  local img=$1 size end dest
  size=$(host_size "$img")
  end=$(vb_content_size "$img")
  ((end <= size)) || die "$img: content_end $end exceeds the file size $size"
  if ((end == size)); then
    printf '%s' "$img"
    return 0
  fi
  dest="$REMOTE_ARTIFACTS/device/upload/$(basename "${img%.*}")-content-$TS.img"
  host_sh "mkdir -p $(printf %q "$(dirname "$dest")")" >/dev/null
  host_sh "head -c $end $(_dq "$img") > $(_dq "$dest")" ||
    die "cannot create the content-only image $dest (issue #54)"
  log "content-only image: $img ($(human "$size")) -> $dest ($(human "$end"))"
  printf '%s' "$dest"
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
      # The only bootloader getvars of this script, once, before the batch.
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
  die "refusing to touch the inactive slot: current is '$CUR_SLOT', --slot is '$SLOT'"
fi
log "active slot: $SLOT (guard/vbmeta only; vendor_boot and vbmeta are non-slotted)"

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
IMG_SIZE=$(host_size "$IMG_HOST")
IMG_SHA=$(host_sha "$IMG_HOST")
IMG_CONTENT=$(make_content_only "$IMG_HOST")
IMG_CONTENT_SIZE=$(host_size "$IMG_CONTENT")
IMG_CONTENT_SHA=$(host_sha "$IMG_CONTENT")
log "image: $IMG_HOST ($(human "$IMG_SIZE"), sha256 $IMG_SHA)"
log "flash:  $IMG_CONTENT ($(human "$IMG_CONTENT_SIZE"), sha256 $IMG_CONTENT_SHA)"

LIMIT=${PARTITION_SIZE:-$STOCK_VENDOR_BOOT_SIZE}
((IMG_CONTENT_SIZE <= LIMIT)) ||
  die "content-only image is $(human "$IMG_CONTENT_SIZE") but the vendor_boot partition is $(human "$LIMIT")"
ok "content-only image fits vendor_boot: $(human "$IMG_CONTENT_SIZE") <= $(human "$LIMIT")"

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
  vb_magic=$(host_magic "$VB" 4)
  [[ $vb_magic == AVB0 ]] || die "not an AVB vbmeta image (magic '$vb_magic' != AVB0): $VB"
fi

# One fastboot process for the whole bootloader write (AGENTS.md / issue #54).
# vendor_boot and vbmeta are non-slotted: no --slot anywhere in the batch.
BATCH_ARGS=()
if ((!NO_VBMETA)); then
  BATCH_ARGS+=(--disable-verity --disable-verification flash vbmeta "$VB")
fi
BATCH_ARGS+=(flash vendor_boot "$IMG_CONTENT" reboot)

print_plan() {
  printf '\n================ PLAN ================\n'
  printf 'device:        %s (%s)\n' "${DEV_SERIAL:-unknown}" "${MODE:-unknown}"
  printf 'product:       %s (must be %s)\n' "${PRODUCT:-unknown}" "$DEVICE_FASTBOOT_PRODUCT"
  printf 'unlocked:      %s\n' "${UNLOCKED:-unknown}"
  printf 'active slot:   %s (guard only; vendor_boot/vbmeta are non-slotted)\n' "${CUR_SLOT:-unknown}"
  printf 'source image:  %s (%s)\n' "$IMG_HOST" "$(human "$IMG_SIZE")"
  printf 'flash image:   %s (%s, content-only, issue #54)\n' "$IMG_CONTENT" "$(human "$IMG_CONTENT_SIZE")"
  printf 'sha256:        %s\n' "$IMG_CONTENT_SHA"
  printf 'stock recovery: %s%s\n' "$STOCK_IMG" "${STOCK_SHA:+ (sha256 $STOCK_SHA)}"
  if ((NO_VBMETA)); then
    printf 'vbmeta:        untouched (--no-vbmeta%s)\n' "$( ((STOCK)) && printf ' / --stock')"
  else
    printf 'vbmeta:        %s with --disable-verity --disable-verification (non-slotted)\n' "$VB"
  fi
  printf '\nbatch (one detached fastboot process, no getvars between writes):\n'
  printf '  fastboot -s %s' "${DEV_SERIAL:-<serial>}"
  printf ' %q' "${BATCH_ARGS[@]}"
  printf '\n'
  printf 'log/exit:      %s:device/jobs/flash-vendor-boot-%s/\n' "$BUILD_HOST" "$TS"
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

# No second round of bootloader getvars: the preflight values above are the
# guard, and the batch is a single fastboot process (issue #54).
printf '\n==> one detached fastboot batch (%s args)\n' "${#BATCH_ARGS[@]}"
printf '    do not start another fastboot while this runs\n'
if dev_fastboot_batch "flash-vendor-boot-$TS" "${BATCH_ARGS[@]}"; then
  ok "vendor_boot flash batch finished"
else
  rc=$?
  warn "vendor_boot flash batch failed (exit $rc)"
  printf '\nbail-out advice (issue #54):\n'
  printf '  * do NOT open another fastboot process between transfers\n'
  printf '  * if the ABL is wedged: hold Power 15 s, then Vol Down + Power; re-enter fastboot\n'
  printf '  * check the batch log under device/jobs/flash-vendor-boot-%s/ on %s\n' "$TS" "$BUILD_HOST"
  printf '  * restore stock is last-resort only; otherwise flash the next variant over the failed one\n'
  exit 3
fi

printf '\n================ DONE ================\n'
printf 'slot guard:  %s (vendor_boot/vbmeta non-slotted)\n' "$SLOT"
printf 'flashed:     %s\n' "$IMG_CONTENT"
printf 'sha256:      %s\n' "$IMG_CONTENT_SHA"
printf 'vbmeta:      %s\n' "$( ((NO_VBMETA)) && printf 'untouched' || printf '%s' "$VB")"
printf 'log:         %s\n' "$LOG"
printf '\nnext (after boot):\n'
printf '  adb shell cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies\n'
printf '  adb shell getprop ashfall.status.perf.gpu_oc\n'
printf '  # failed variant: flash the next variant over it; do NOT --stock between attempts\n'
printf '  # scripts/device/flash-vendor-boot.sh --stock   # last-resort recovery only\n'
printf '======================================\n'
