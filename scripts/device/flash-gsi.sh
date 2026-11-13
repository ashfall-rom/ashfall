#!/usr/bin/env bash
# AshFall: flash a GSI system image onto the RP5 (A/B, dynamic partitions).
#
# Safe by construction:
#   * refuses anything that is not a `kona` (SM8250) with an unlocked bootloader;
#   * works through fastbootd (userspace fastboot), the only place logical
#     partitions like `system` can be written;
#   * writes only `system`/`vbmeta`, deletes only the logical product_<slot>/
#     system_ext_<slot> (for space) and erases userdata/metadata with --wipe;
#   * hard-blocks the partitions from the guide's NEVER-flash list (§13);
#   * computes the space budget of group `qti_dynamic_partitions_<slot>`
#     (6.0 GiB; stock used ~4.6 GiB) and frees the stock product_<slot> first,
#     then system_ext_<slot>, only when needed. vendor_/odm_ are never touched;
#   * accepts Android-sparse, EROFS (A17 GSI default, stock 5.15 kernel) and
#     ext4 images;
#   * the target slot is re-validated to be exactly a|b, including when it came
#     from the device's current-slot;
#   * `--dry-run` performs only read-only getvars and prints the plan including
#     the space arithmetic (exact numbers once fastbootd is reachable). With
#     --keep-product and a deficit it marks the plan as refused and exits 1.
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
#                      userdata erase is seconds on this device, not minutes)
#   --vbmeta PATH      use this vbmeta image instead of the stock one / an
#                      auto-generated empty vbmeta (host path or local file)
#   --keep-product     never delete product_<slot>/system_ext_<slot>, even if
#                      the image needs room (fails instead)
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
#   * The RP5 ABL rejects `fastboot reboot fastboot` from the bootloader
#     (`FAILED (remote: 'unknown command')`, 2026-11-13). fastbootd is reached
#     via stock recovery instead: `fastboot reboot recovery` -> wait for adb
#     state `recovery` -> `adb reboot fastboot` -> wait for `is-userspace: yes`.
#     From Android/recovery `adb reboot fastboot` is used directly. See
#     ensure_fastbootd in lib/device.sh.
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
SLOT_GIVEN=0
SERIAL=""
VBMETA=""
NO_DEVICE=0

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

while (($#)); do
  case $1 in
    --slot) SLOT=${2:?--slot needs a|b}; SLOT_GIVEN=1; shift 2 ;;
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
if ((NO_DEVICE)) && ((!DRY_RUN)); then die_usage "--no-device only makes sense with --dry-run"; fi

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
host_bytes() { host_sh "dd if=$(_dq "$1") bs=1 skip=$2 count=$3 2>/dev/null | od -An -tx1 | tr -d ' \\n'"; }

# Android sparse header (little-endian): magic 0xed26ff3a, blk_sz @12,
# total_blks @16 -> expanded size = total_blks * blk_sz (fastboot writes that).
sparse_expanded_size() {
  local out blk total
  out=$(host_sh "od -An -tu4 -j 12 -N 8 $(_dq "$1")" 2>/dev/null) || return 1
  read -r blk total <<<"$out" || return 1
  [[ $blk =~ ^[0-9]+$ && $total =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$((blk * total))"
}

human() {
  local n=$1 sign='' out
  ((n < 0)) && { sign='-'; n=$((-n)); }
  if out=$(numfmt --to=iec-i --suffix=B "$n" 2>/dev/null); then
    printf '%s%s' "$sign" "$out"
  else
    printf '%s%s bytes' "$sign" "$n"
  fi
}

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
    if host_file_exists "$VBMETA"; then
      printf '%s' "$VBMETA"
    elif [[ -f $VBMETA ]]; then
      printf '%s' "$(upload_to_host "$VBMETA")"
    else
      die "--vbmeta: not found locally or on $BUILD_HOST: $VBMETA"
    fi
    return 0
  fi
  for cand in "$REMOTE_FIRMWARE/vbmeta.img" \
              "$REMOTE_FIRMWARE/lun4_vbmeta_${slot}.bin" \
              "$REMOTE_FIRMWARE/lun4_vbmeta_a.bin" \
              "$REMOTE_FIRMWARE/vbmeta_${slot}.img" \
              "$REMOTE_FIRMWARE/img/vbmeta.img" \
              "$REMOTE_FIRMWARE/img/lun4_vbmeta_${slot}.bin" \
              "$REMOTE_FIRMWARE/img/lun4_vbmeta_a.bin" \
              "$REMOTE_FIRMWARE/img/vbmeta_${slot}.img"; do
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

# --- dynamic-partition group budget ------------------------------------------
# Stock layout (docs/stock-analysis.md, PR #10): group
# qti_dynamic_partitions_<slot> max 6.0 GiB, ~4.6 GiB used:
# system 1.39 GiB, vendor 1.78 GiB, product 0.875 GiB, system_ext 0.49 GiB.
# A GSI replaces system and bundles its own product/system_ext, so the stock
# product_<slot> and system_ext_<slot> may be deleted, in that order.
# vendor_<slot> and odm_<slot> are NEVER touched.
GROUP_MAX=${ASH_GROUP_MAX_BYTES:-6442450944}   # 6.0 GiB per slot group

hexok() { [[ $1 =~ ^0x[0-9a-fA-F]+$ ]]; }

compute_space_budget() {
  BUDGET_LINES=(); DELETIONS=(); SPACE_KNOWN=0; GROUP_NEED=0; PLAN_REFUSED=0
  local sys prod sx vendor odm sys_dlkm vend_dlkm sum free_after pair part v
  local b_vendor b_odm b_product b_system_ext b_system_dlkm b_vendor_dlkm b_other b_free b_need b_left

  # Sizes are only readable in fastbootd. Everywhere else (bootloader, Android,
  # no device) show the arithmetic on the documented stock baseline (PR #10);
  # it is re-measured in fastbootd before any deletion.
  if [[ ${MODE:-none} != fastbootd ]]; then
    # Exact stock sizes from docs/stock/super-lpdump.txt (issue #32).
    b_vendor=1910083584      # 1.78 GiB
    b_odm=1220608            # 1.2 MiB
    b_product=939675648      # 896 MiB
    b_system_ext=522039296   # 498 MiB
    b_system_dlkm=438272     # 428 KiB
    b_vendor_dlkm=67862528   # 64.7 MiB
    b_other=$((b_vendor + b_odm + b_product + b_system_ext + b_system_dlkm + b_vendor_dlkm))
    b_free=$((GROUP_MAX - b_other - IMG_SIZE))
    BUDGET_LINES+=("group qti_dynamic_partitions_$SLOT max $(human "$GROUP_MAX")")
    BUDGET_LINES+=("stock baseline (lpdump, PR #10): vendor $(human "$b_vendor") + odm $(human "$b_odm") + product $(human "$b_product") + system_ext $(human "$b_system_ext") + system_dlkm $(human "$b_system_dlkm") + vendor_dlkm $(human "$b_vendor_dlkm") = $(human "$b_other")")
    b_need=0
    if ((b_free >= 0)); then
      BUDGET_LINES+=("baseline: $(human "$GROUP_MAX") - $(human "$b_other") - $(human "$IMG_SIZE") (image) = $(human "$b_free") free (${b_free} B)")
    else
      b_need=$((-b_free))
      BUDGET_LINES+=("baseline: $(human "$GROUP_MAX") - $(human "$b_other") - $(human "$IMG_SIZE") (image) = deficit $(human "$b_need") (${b_need} B)")
    fi
    GROUP_NEED=$b_need
    if ((b_need == 0)); then
      BUDGET_LINES+=("=> fits the group on the stock baseline")
    elif ((KEEP_PRODUCT)); then
      BUDGET_LINES+=("=> does NOT fit and --keep-product forbids deleting product_$SLOT/system_ext_$SLOT: the run would refuse")
      PLAN_REFUSED=1
    else
      BUDGET_LINES+=("=> deficit $(human "$b_need"); deletion order: product_$SLOT, then system_ext_$SLOT")
      b_left=$((b_need - b_product))
      if ((b_left > 0)); then
        b_left=$((b_left - b_system_ext))
        if ((b_left > 0)); then
          BUDGET_LINES+=("=> still $(human "$b_left") short after both: image too large for the group")
        else
          BUDGET_LINES+=("=> fits after deleting product_$SLOT and system_ext_$SLOT")
        fi
      else
        BUDGET_LINES+=("=> fits after deleting product_$SLOT")
      fi
    fi
    BUDGET_LINES+=("(mode=${MODE:-none}: sizes are re-measured in fastbootd before anything is deleted)")
    return 0
  fi

  sys=$(dev_fb_value "partition-size:system_$SLOT")
  prod=$(dev_fb_value "partition-size:product_$SLOT")
  sx=$(dev_fb_value "partition-size:system_ext_$SLOT")
  vendor=$(dev_fb_value "partition-size:vendor_$SLOT")
  odm=$(dev_fb_value "partition-size:odm_$SLOT")
  sys_dlkm=$(dev_fb_value "partition-size:system_dlkm_$SLOT")
  vend_dlkm=$(dev_fb_value "partition-size:vendor_dlkm_$SLOT")
  BUDGET_LINES+=("group qti_dynamic_partitions_$SLOT max $(human "$GROUP_MAX")")
  BUDGET_LINES+=("sizes: system=${sys:-?} vendor=${vendor:-?} product=${prod:-?} system_ext=${sx:-?} odm=${odm:-?} system_dlkm=${sys_dlkm:-?} vendor_dlkm=${vend_dlkm:-?}")
  if ! hexok "$sys" || ! hexok "$vendor"; then
    BUDGET_LINES+=("sizes unreadable even in fastbootd (flaky getvar); will free partitions only if the flash reports no space")
    return 0
  fi
  sum=0
  for v in "$vendor" "$odm" "$prod" "$sx" "$sys_dlkm" "$vend_dlkm"; do
    hexok "$v" && sum=$((sum + v))
  done
  free_after=$((GROUP_MAX - sum - IMG_SIZE))
  if ((free_after >= 0)); then
    BUDGET_LINES+=("budget: $(human "$GROUP_MAX") - $(human "$sum") (vendor+odm+product+system_ext+dlkm) - $(human "$IMG_SIZE") (image) = $(human "$free_after") free (${free_after} B)")
    SPACE_KNOWN=1
    return 0
  fi
  GROUP_NEED=$((-free_after))
  BUDGET_LINES+=("budget: $(human "$GROUP_MAX") - $(human "$sum") - $(human "$IMG_SIZE") = deficit $(human "$GROUP_NEED")")
  if ((KEEP_PRODUCT)); then
    BUDGET_LINES+=("--keep-product forbids deleting product_$SLOT/system_ext_$SLOT: the run would refuse")
    SPACE_KNOWN=1
    PLAN_REFUSED=1
    return 0
  fi
  for pair in "product:$prod" "system_ext:$sx"; do
    part=${pair%%:*}; v=${pair#*:}
    hexok "$v" || continue
    ((GROUP_NEED > 0)) || break
    DELETIONS+=("$part")
    BUDGET_LINES+=("  -> delete ${part}_$SLOT (frees $(human "$v"))")
    GROUP_NEED=$((GROUP_NEED - v))
  done
  SPACE_KNOWN=1
  if ((GROUP_NEED > 0)); then
    BUDGET_LINES+=("deficit $(human "$GROUP_NEED") remains after product+system_ext: image is too large for the group")
  else
    BUDGET_LINES+=("free after deletions: $(human "$((-GROUP_NEED))")")
  fi
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
  printf '  - reach fastbootd if needed (bootloader: reboot recovery -> adb reboot fastboot;\n'
  printf '    Android/recovery: adb reboot fastboot)\n'
  if ((KEEP_PRODUCT)) && ((GROUP_NEED > 0)); then
    printf '  - space budget: image does not fit and --keep-product forbids deleting\n'
    printf '    product_%s/system_ext_%s -> this run would REFUSE\n' "$SLOT" "$SLOT"
  else
    printf '  - apply the space budget: free stock product_%s first, then\n' "$SLOT"
    printf '    system_ext_%s only if still short (never vendor_/odm_)\n' "$SLOT"
  fi
  printf '  - fastboot --slot %s flash system %s\n' "$SLOT" "$img"
  if [[ $VB_FLAGS == 1 ]]; then
    printf '  - fastboot --disable-verity --disable-verification --slot %s flash vbmeta %s\n' "$SLOT" "$vb"
  else
    printf '  - fastboot --slot %s flash vbmeta %s (already flags=3)\n' "$SLOT" "$vb"
  fi
  if ((WIPE)); then
    printf '  - fastboot erase userdata  (observed 0.09 s on 2026-11-13)\n'
    printf '  - fastboot erase metadata\n'
  fi
  printf '  - fastboot set-active %s (only if it is not active already)\n' "$SLOT"
  if ((${#BUDGET_LINES[@]})); then
    printf '\nspace budget:\n'
    printf '  %s\n' "${BUDGET_LINES[@]}"
  fi
  printf '======================================\n'
}

# --- main --------------------------------------------------------------------
IMG_HOST=$(resolve_image "$IMG")
IMG_SIZE=$(host_size "$IMG_HOST")
[[ $IMG_SIZE =~ ^[0-9]+$ ]] || die "cannot stat image on $BUILD_HOST: $IMG_HOST"
IMG_MAGIC=$(host_head "$IMG_HOST" 4)
IMG_FS=$(host_bytes "$IMG_HOST" 1080 2)
IMG_EROFS=$(host_bytes "$IMG_HOST" 1024 4)

log "image $IMG_HOST (file $(human "$IMG_SIZE"), magic $IMG_MAGIC)"
case $IMG_MAGIC in
  3aff26ed)
    if expanded=$(sparse_expanded_size "$IMG_HOST") && [[ $expanded =~ ^[0-9]+$ ]]; then
      IMG_SIZE=$expanded
      log "android sparse image: expands to $(human "$IMG_SIZE") (budgeted)"
    else
      warn "android sparse image: could not parse the header; budgeting the on-disk size"
    fi
    ;;
  *)
    if [[ $IMG_EROFS == e2e1f5e0 ]]; then
      log "EROFS image (expected for the GSI: stock 5.15 GKI mounts EROFS)"
    elif [[ $IMG_FS == 53ef ]]; then
      log "ext4 image (magic at 0x438) -- fallback filesystem, also supported"
    else
      warn "unrecognised image type (magic $IMG_MAGIC); expected Android sparse, EROFS or ext4 system.img"
    fi
    ;;
esac

preflight_device

# Slot selection
if [[ -z $SLOT ]]; then
  if [[ -n $CUR_SLOT ]]; then SLOT=$CUR_SLOT; else SLOT=a; fi
fi
# Defense in depth: the slot is interpolated into partition names, --slot args
# and set-active. Never trust a device-reported value without re-validating it.
[[ $SLOT == a || $SLOT == b ]] || die "unexpected current-slot '$SLOT'; pass --slot a|b explicitly"
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

# A flaky getvar must never let a write through unverified: these values are
# required for a real flash, even though dry-run/offline plans tolerate them.
if ((!DRY_RUN)) && ((NO_DEVICE == 0)); then
  [[ -n $PRODUCT ]] || die "cannot read 'product' from the device (flaky fastboot?); refusing to flash"
  [[ -n $UNLOCKED ]] || die "cannot read 'unlocked' from the device (flaky fastboot?); refusing to flash"
  if ((SLOT_GIVEN == 0)); then
    [[ -n $CUR_SLOT ]] || die "cannot read 'current-slot'; pass --slot a|b explicitly"
  fi
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

compute_space_budget
print_plan "$IMG_HOST" "$IMG_SIZE" "$VB" "$VB_DESC"

if ((DRY_RUN)); then
  if ((PLAN_REFUSED)); then
    warn "dry-run: this plan would be refused (--keep-product and the image does not fit)"
    log "dry-run: no device writes performed"
    exit 1
  fi
  log "dry-run: no device writes performed"
  exit 0
fi

if ((!ASSUME_YES)); then
  printf '\nType "flash" to continue: '
  read -r answer
  [[ $answer == flash ]] || { log "aborted"; exit 1; }
fi

# --- 1. reach fastbootd ------------------------------------------------------
# The route is device-specific and lives in lib/device.sh: this ABL rejects
# `fastboot reboot fastboot`, so the bootloader path goes through the stock
# recovery (reboot recovery -> wait for adb `recovery` -> adb reboot fastboot).
ensure_fastbootd
MODE=fastbootd
DEV_SERIAL=$(dev_resolve_serial "$SERIAL")
log "fastbootd device: $DEV_SERIAL"

# --- 2. space budget in the dynamic partition group --------------------------
compute_space_budget
log "space budget for qti_dynamic_partitions_$SLOT:"
printf '  %s\n' "${BUDGET_LINES[@]}" >&2
if ((GROUP_NEED > 0)) && ((KEEP_PRODUCT)); then
  die "--keep-product: image does not fit the group and deleting product_$SLOT/system_ext_$SLOT is forbidden (deficit $(human "$GROUP_NEED"))"
fi
if ((GROUP_NEED > 0)); then
  die "image does not fit the group even after deleting product_$SLOT and system_ext_$SLOT (deficit $(human "$GROUP_NEED"))"
fi
if ((!SPACE_KNOWN)); then
  warn "could not read the logical partition sizes (flaky fastboot); will free partitions only if the flash reports no space"
fi

DELETED_PARTS=" "
if ((${#DELETIONS[@]})); then
  if ((KEEP_PRODUCT)); then
    die "--keep-product is set but the image needs room: ${DELETIONS[*]} would have to be deleted"
  fi
  for part in "${DELETIONS[@]}"; do
    log "deleting ${part}_$SLOT (GSIs bundle product/system_ext; vendor/odm are never touched)"
    step "delete-logical-partition ${part}_$SLOT" delete-logical-partition "${part}_$SLOT"
    DELETED_PARTS+="$part "
  done
fi

# --- 3. flash system ---------------------------------------------------------
assert_flashable system

flash_system() {
  local out rc
  printf '\n==> flash system -> system_%s\n' "$SLOT"
  printf '    + fastboot --slot %s flash system %s\n' "$SLOT" "$IMG_HOST"
  out=$(fb --slot "$SLOT" flash system "$IMG_HOST" 2>&1) && rc=0 || rc=$?
  printf '%s\n' "$out"
  LAST_FLASH_OUT=$out
  return $rc
}

flashed=0
if flash_system; then
  flashed=1
fi
if ((!flashed)) && grep -qiE 'not enough space|no space|failed to resize|cannot resize|too large' <<<"$LAST_FLASH_OUT"; then
  if ((KEEP_PRODUCT)); then
    die "super is full and --keep-product is set; free space manually or drop the flag"
  fi
  for part in product system_ext; do
    [[ $DELETED_PARTS == *" $part "* ]] && continue
    size=$(dev_fb_value "partition-size:${part}_$SLOT")
    hexok "$size" || continue
    warn "super is full; deleting ${part}_$SLOT ($(human "$size")) and retrying"
    step "delete-logical-partition ${part}_$SLOT" delete-logical-partition "${part}_$SLOT"
    DELETED_PARTS+="$part "
    if flash_system; then flashed=1; break; fi
  done
fi
((flashed)) || die "flashing system failed; device left in fastbootd (see log above)"
ok "system_$SLOT flashed"

# --- 4. vbmeta ---------------------------------------------------------------
assert_flashable vbmeta
if ((VB_FLAGS)); then
  step "flash vbmeta with verity+verification disabled" \
    --disable-verity --disable-verification --slot "$SLOT" flash vbmeta "$VB"
else
  step "flash generated empty vbmeta (flags=3)" --slot "$SLOT" flash vbmeta "$VB"
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
  step "erase userdata (seconds on this device)" erase userdata
  step "erase metadata (vold regenerates encryption keys)" erase metadata
  ok "userdata + metadata erased"
else
  log "keeping userdata (use --wipe for a clean first boot; /data from the stock ROM is usually not readable by the GSI)"
fi

cur=$(dev_fb_value current-slot)
if [[ $cur != a && $cur != b ]]; then
  warn "unexpected current-slot '$cur' from fastbootd; leaving the active slot untouched"
  cur=''
fi
if [[ $cur == "$SLOT" ]]; then
  ok "slot $SLOT already active"
elif [[ -z $cur ]]; then
  log "active slot not changeable now; flashing slot $SLOT only"
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
printf 'deleted logical partitions: %s\n' "${DELETED_PARTS:- none}"
printf 'log:        %s\n' "$LOG"
printf '\nnext:\n'
printf '  fastboot reboot           # boot Android; first GSI boot can take 2-5 min\n'
printf '  scripts/device/logs.sh     # if it boots but misbehaves\n'
printf '  scripts/device/restore-stock.sh   # bail-out to stock\n'
printf '======================================\n'

