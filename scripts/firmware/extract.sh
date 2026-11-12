#!/usr/bin/env bash
# extract.sh - unpack the stock RP5 firmware into inspectable pieces.
#
#   * pulls super / boot / init_boot / vendor_boot / dtbo / vbmeta out of the 7z
#   * unpacks the super partition with scripts/firmware/lpunpack.py
#   * unpacks boot/init_boot/vendor_boot headers and the vendor_boot ramdisk
#   * lists the dtbo table and parses vbmeta flags
#   * dumps the text bits of the stock images (props, VINTF, init rc, idc,
#     keylayouts, app listings) with debugfs - no root, no loop mounts
#
# Usage: scripts/firmware/extract.sh [--force] [--workdir DIR]
#
# All work happens on the build host inside the "$FW_BOX" distrobox container.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../lib/common.sh
. "$SCRIPT_DIR/../lib/common.sh"
load_config
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Unpack the stock firmware archive into $REMOTE_FIRMWARE.

Options:
  --force          redo work even if outputs exist
  --workdir DIR    firmware root (default: $REMOTE_FIRMWARE)
  --no-super       skip the (slow) 12 GiB super-partition unpack
  -h, --help       show this help
EOF
}

FORCE=0
NO_SUPER=0
PASSTHRU=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --force) FORCE=1; PASSTHRU+=(--force); shift ;;
    --no-super) NO_SUPER=1; PASSTHRU+=(--no-super); shift ;;
    --workdir)
      REMOTE_FIRMWARE=${2:?--workdir needs a value}
      PASSTHRU+=(--workdir "$2")
      shift 2
      ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done
FW_DL=$REMOTE_FIRMWARE/dl
FW_IMG=$REMOTE_FIRMWARE/img
FW_SUPER=$FW_IMG/super
FW_RAMDISK=$FW_IMG/vendor_ramdisk
FW_VENDOR_DUMP=$FW_IMG/vendordump
FW_LOG=$REMOTE_FIRMWARE/logs
FW_SLOT=${FW_SLOT:-_a}   # active slot suffix inside the super metadata

if ((${#PASSTHRU[@]})); then
  fw_remote_reentry "$(basename "$0")" "${PASSTHRU[@]}"
else
  fw_remote_reentry "$(basename "$0")"
fi

# --------------------------------------------------------------------------
# Remote phase (runs on the build host).
# --------------------------------------------------------------------------
need stat
ensure_fw_box
mkdir -p "$FW_IMG" "$FW_SUPER" "$FW_LOG" "$FW_IMG/vendor_boot"

IMAGE_FILES=(
  flash/lun0_super.bin
  flash/lun0_vbmeta_system_a.bin
  flash/lun4_boot_a.bin
  flash/lun4_init_boot_a.bin
  flash/lun4_vendor_boot_a.bin
  flash/lun4_dtbo_a.bin
  flash/lun4_vbmeta_a.bin
  flash/lun0_gpt_header.bin
  flash/lun4_gpt_header.bin
)

extract_images() {
  if [[ $FORCE != 1 && -f $FW_IMG/lun0_super.bin && -f $FW_IMG/lun4_vendor_boot_a.bin && -f $FW_IMG/lun4_boot_a.bin ]]; then
    ok "firmware images already extracted in $FW_IMG"
    return 0
  fi
  local first
  first=$(fw_archive_volumes | head -1)
  [[ -n $first ]] || die "firmware archive not found; run scripts/firmware/fetch.sh first"
  log "extracting ${#IMAGE_FILES[@]} files from $(basename "$first")"
  fw_sh "cd $(printf %q "$FW_DL") && 7z e -y -o$(printf %q "$FW_IMG") $(printf %q "$first") $(printf '%q ' "${IMAGE_FILES[@]}")" >&2
}

unpack_super() {
  if [[ $NO_SUPER == 1 ]]; then
    warn "skipping super unpack (--no-super)"
    return 0
  fi
  if [[ $FORCE != 1 && -f $FW_SUPER/lpdump.txt && -f $FW_SUPER/system${FW_SLOT}.img && -f $FW_SUPER/vendor${FW_SLOT}.img ]]; then
    ok "super already unpacked in $FW_SUPER"
    return 0
  fi
  log "reading super metadata -> $FW_SUPER/lpdump.txt"
  fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/lpunpack.py") $(printf %q "$FW_IMG/lun0_super.bin") --list > $(printf %q "$FW_SUPER/lpdump.txt")"
  log "extracting super partitions (this copies ~10 GiB)"
  fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/lpunpack.py") $(printf %q "$FW_IMG/lun0_super.bin") --extract $(printf %q "$FW_SUPER")"
}

unpack_boot() {
  local img
  for img in boot init_boot vendor_boot; do
    [[ -f $FW_IMG/lun4_${img}_a.bin ]] || die "missing $FW_IMG/lun4_${img}_a.bin"
    fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/bootimg.py") $(printf %q "$FW_IMG/lun4_${img}_a.bin") --extract $(printf %q "$FW_IMG/$img") > $(printf %q "$FW_LOG/${img}-header.txt")"
    ok "$img header -> $FW_LOG/${img}-header.txt"
  done
  fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/bootimg.py") $(printf %q "$FW_IMG/lun4_dtbo_a.bin") --dtbo > $(printf %q "$FW_LOG/dtbo-table.txt")"
  ok "dtbo table -> $FW_LOG/dtbo-table.txt"
}

unpack_ramdisk() {
  if [[ $FORCE != 1 && -f $FW_RAMDISK/.unpacked ]]; then
    ok "vendor_boot ramdisk already unpacked in $FW_RAMDISK"
    return 0
  fi
  local comp
  comp=$(fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/bootimg.py") $(printf %q "$FW_IMG/lun4_vendor_boot_a.bin") | awk -F': ' '/^ramdisk_type:/ {print \$2}'")
  log "decompressing vendor_boot ramdisk ($comp)"
  mkdir -p "$FW_RAMDISK"
  fw_sh "cd $(printf %q "$FW_RAMDISK") && case $(printf %q "$comp") in \
      lz4-legacy|lz4) lz4 -dc $(printf %q "$FW_IMG/vendor_boot/vendor_ramdisk") > ramdisk.cpio ;; \
      gzip) gzip -dc $(printf %q "$FW_IMG/vendor_boot/vendor_ramdisk") > ramdisk.cpio ;; \
      cpio) cp $(printf %q "$FW_IMG/vendor_boot/vendor_ramdisk") ramdisk.cpio ;; \
      *) echo 'unknown ramdisk compression' >&2; exit 1 ;; esac && \
    cpio -idm --quiet < ramdisk.cpio && rm -f ramdisk.cpio && find . -type f | sort > ../vendor_ramdisk.list && touch .unpacked"
  ok "vendor_boot ramdisk -> $FW_RAMDISK ($(wc -l <"$FW_RAMDISK.list" 2>/dev/null || echo '?') files)"
}

fs_type() { # <image> -> ext4|erofs|unknown
  local magic
  magic=$(od -An -tx1 -j1080 -N2 "$1" 2>/dev/null | tr -d ' \n')
  if [[ $magic == 53ef ]]; then
    echo ext4
    return
  fi
  magic=$(od -An -tx1 -j1024 -N4 "$1" 2>/dev/null | tr -d ' \n')
  if [[ $magic == e2e1f5e0 ]]; then
    echo erofs
    return
  fi
  echo unknown
}

dump_fs_paths() { # <image> <outdir> <paths...>
  local img=$1 out=$2
  shift 2
  local type
  type=$(fs_type "$img")
  mkdir -p "$out"
  case $type in
    ext4)
      local p
      for p in "$@"; do
        fw_sh "debugfs -R $(printf %q "rdump $p $out") $(printf %q "$img")" >/dev/null 2>&1 || warn "debugfs: $p not in $(basename "$img")"
      done
      ;;
    erofs)
      warn "$(basename "$img") is erofs; extracting whole image to read selected paths"
      fw_sh "rm -rf $(printf %q "$out/.full") && fsck.erofs --extract=$(printf %q "$out/.full") $(printf %q "$img") >/dev/null && \
        for p in $(printf '%q ' "$@"); do [ -e $(printf %q "$out/.full")/\$p ] && cp -a $(printf %q "$out/.full")/\$p $(printf %q "$out")/ || true; done; rm -rf $(printf %q "$out/.full")"
      ;;
    *)
      warn "$(basename "$img"): unknown filesystem, skipping text extraction"
      ;;
  esac
}

dump_dir_listing() { # <image> <outfile> <dirs...>
  local img=$1 out=$2
  shift 2
  {
    local p
    for p in "$@"; do
      echo "## $p"
      fw_sh "debugfs -R $(printf %q "ls -l $p") $(printf %q "$img")" 2>/dev/null | sed '/^debugfs /d'
    done
  } >"$out"
}

dump_images() {
  local vendor=$FW_SUPER/vendor${FW_SLOT}.img
  [[ -f $vendor ]] || die "missing $vendor (run without --no-super)"
  if [[ $FORCE != 1 && -f $FW_VENDOR_DUMP/vendor/build.prop ]]; then
    ok "vendor text dump already present in $FW_VENDOR_DUMP"
    return 0
  fi
  log "dumping text files from vendor/system/system_ext/product/odm"
  rm -rf "$FW_VENDOR_DUMP"
  mkdir -p "$FW_VENDOR_DUMP"

  dump_fs_paths "$vendor" "$FW_VENDOR_DUMP/vendor" \
    /build.prop /default.prop /etc/vintf /etc/init /etc/permissions /usr/idc /usr/keylayout
  dump_dir_listing "$vendor" "$FW_LOG/vendor-apps.txt" /app /priv-app

  local part prefix img
  for part in system system_ext product odm; do
    img=$FW_SUPER/${part}${FW_SLOT}.img
    [[ -f $img ]] || continue
    # The system image is system-as-root: its payload lives under /system.
    prefix=""
    [[ $part == system ]] && prefix=/system
    dump_fs_paths "$img" "$FW_VENDOR_DUMP/$part" "$prefix/build.prop" "$prefix/etc/init"
    dump_dir_listing "$img" "$FW_LOG/${part}-apps.txt" "$prefix/app" "$prefix/priv-app"
  done
  ok "image text dump -> $FW_VENDOR_DUMP"
}

vbmeta_report() {
  {
    local img
    for img in "$FW_IMG/lun4_vbmeta_a.bin" "$FW_IMG/lun0_vbmeta_system_a.bin"; do
      echo "## $(basename "$img")"
      fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/imgmeta.py") vbmeta $(printf %q "$img")" || true
    done
  } >"$FW_LOG/vbmeta.txt"
  ok "vbmeta flags -> $FW_LOG/vbmeta.txt"
}

gpt_report() {
  {
    local img
    for img in "$FW_IMG/lun0_gpt_header.bin" "$FW_IMG/lun4_gpt_header.bin"; do
      echo "## $(basename "$img")"
      fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/imgmeta.py") gpt $(printf %q "$img")" || true
    done
  } >"$FW_LOG/gpt-headers.txt"
  ok "GPT headers -> $FW_LOG/gpt-headers.txt"
}

extract_images
unpack_super
unpack_boot
unpack_ramdisk
vbmeta_report
gpt_report
dump_images
ok "extract complete: $FW_IMG"
