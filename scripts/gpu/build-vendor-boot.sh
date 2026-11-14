#!/usr/bin/env bash
# Phase 6 GPU overclock: build a vendor_boot image with the Adreno 650 OC DTB.
#
# Contract: docs/phase6.md section 4, issue #112.
#
# The stock 5.15.148 GKI kernel is untouched: the GPU frequency/voltage table
# lives in the vendor_boot DTB (`qcom,kgsl-3d0@3d00000` ->
# `qcom,gpu-pwrlevel-bins`).  This script unpacks the stock vendor_boot, splits
# its multi-FDT dtb payload, applies the OC table to every 670 MHz speed-bin
# table, and repacks the image with the original vendor ramdisk, bootconfig and
# header fields.  Nothing here is an Android build: it runs host-side with the
# AOSP `unpack_bootimg.py`/`mkbootimg.py` and the prebuilt `dtc`.
#
# Usage:
#   scripts/gpu/build-vendor-boot.sh [options]
#
# Options:
#   --stock PATH        stock vendor_boot image (default:
#                       $REMOTE_FIRMWARE/img/lun4_vendor_boot_a.bin)
#   --id ID             artifact id (default: vendor-boot-oc-<stock sha8>)
#   --partition-size N  vendor_boot partition size in bytes (default: the
#                       stock image size; the stock file is padded to it)
#   --active-dtb N      FDT index used for the review DTS (default: auto-match
#                       the RP5 board: msm-id 0x164/0x20001, pmic-id 1e 20 1f 21)
#   --extra-step MHZ:LEVEL
#                       DEBUG ONLY: add exactly these steps instead of the full
#                       OC table (repeatable), e.g. --extra-step 940:384
#   --drop-step MHZ     DEBUG ONLY: drop these stock steps to stay within the
#                       RPMh/KGSL level limit (repeatable), e.g. --drop-step 400
#   --dtc PATH          dtc binary (default: AOSP prebuilt)
#   --mkbootimg DIR     AOSP system/tools/mkbootimg directory
#   --keep-work         keep the remote work directory (decompiled DTS, logs)
#   --no-reproducible   skip the second build pass that proves determinism
#   -h, --help          this help
#
# Debug variants use their own artifact id (vendor-boot-dbg-<stock sha8>-...)
# so they never overwrite the full OC image.  Document them as debug-only in
# the PR; the default build (no --extra-step/--drop-step) is unchanged.
#
# The real work happens on the build host ($BUILD_HOST); the outer invocation
# rsyncs this checkout to $REMOTE_WS/<branch>, re-execs there with
# ASH_GPU_REMOTE=1 and pulls the artifacts back to out/gpu/<id>/.
#
# Output:
#   out/gpu/<id>/vendor_boot.img   the image to flash (padded to the partition)
#   out/gpu/<id>/manifest.json     stock/output/DTB SHA-256, header comparison,
#                                  per-FDT board/bin summary, tool versions
#   out/gpu/<id>/SHA256SUMS
#   out/gpu/<id>/header-*.txt|json, dtb-verify.json, active-{stock,oc}.dts,
#   out/gpu/<id>/dts/*.dts         every board DTB, before and after
#
# The image is not AVB-signed: the bootloader verification state must be
# disabled, exactly as for the GSI system flash (scripts/device/flash-gsi.sh
# flashes vbmeta with --disable-verity --disable-verification).
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config

STOCK=${GPU_STOCK:-$REMOTE_FIRMWARE/img/lun4_vendor_boot_a.bin}
ID=${GPU_ID:-}
PARTITION_SIZE=${GPU_PARTITION_SIZE:-}
ACTIVE_DTB=${GPU_ACTIVE_DTB:-}
DTC=${GPU_DTC:-$REMOTE_SRC/prebuilts/kernel-build-tools/linux-x86/bin/dtc}
MKBOOTIMG_DIR=${GPU_MKBOOTIMG_DIR:-$REMOTE_SRC/system/tools/mkbootimg}
KEEP_WORK=0
REPRODUCIBLE=1
EXTRA_STEPS=()
DROP_STEPS=()
REMOTE_ARGS=("$@")

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

tag() { printf '%s' "$1" | sed 's/[^A-Za-z0-9.-]/-/g'; }

while (($#)); do
  case $1 in
    --stock) STOCK=${2:?--stock needs a path}; shift 2 ;;
    --id) ID=${2:?--id needs a value}; shift 2 ;;
    --partition-size) PARTITION_SIZE=${2:?--partition-size needs a value}; shift 2 ;;
    --active-dtb) ACTIVE_DTB=${2:?--active-dtb needs an index}; shift 2 ;;
    --extra-step) EXTRA_STEPS+=("${2:?--extra-step needs MHz:LEVEL}"); shift 2 ;;
    --drop-step) DROP_STEPS+=("${2:?--drop-step needs MHz}"); shift 2 ;;
    --dtc) DTC=${2:?--dtc needs a path}; shift 2 ;;
    --mkbootimg) MKBOOTIMG_DIR=${2:?--mkbootimg needs a directory}; shift 2 ;;
    --keep-work) KEEP_WORK=1; shift ;;
    --no-reproducible) REPRODUCIBLE=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die_usage "unknown option: $1" ;;
  esac
done

remote_phase() {
  local stock=$STOCK id=$ID partition=$PARTITION_SIZE active=$ACTIVE_DTB
  local dtc=$DTC mkbootimg_dir=$MKBOOTIMG_DIR keep_work=$KEEP_WORK reproducible=$REPRODUCIBLE
  local debug_args=() spec debug_tags=""

  for spec in "${EXTRA_STEPS[@]}"; do debug_args+=(--extra-step "$spec"); done
  for spec in "${DROP_STEPS[@]}"; do debug_args+=(--drop-step "$spec"); done
  for spec in "${EXTRA_STEPS[@]}"; do debug_tags+="-$(tag "$spec")"; done
  for spec in "${DROP_STEPS[@]}"; do debug_tags+="-drop$(tag "$spec")"; done

  need python3 sha256sum
  [[ -f $stock ]] || die "stock vendor_boot not found: $stock"
  [[ -x $dtc ]] || die "dtc not found or not executable: $dtc"
  [[ -f $mkbootimg_dir/unpack_bootimg.py ]] || die "missing $mkbootimg_dir/unpack_bootimg.py"
  [[ -f $mkbootimg_dir/mkbootimg.py ]] || die "missing $mkbootimg_dir/mkbootimg.py"

  local stock_sha stock_size
  stock_sha=$(sha256sum "$stock" | awk '{print $1}')
  stock_size=$(stat -c %s "$stock")
  [[ $partition =~ ^[0-9]+$ ]] || partition=$stock_size
  ((partition >= stock_size)) || \
    warn "--partition-size $partition is smaller than the stock image ($stock_size)"

  [[ -n $id ]] || id="vendor-boot-oc-${stock_sha:0:8}"
  if ((${#EXTRA_STEPS[@]} || ${#DROP_STEPS[@]})); then
    [[ -n $ID ]] || id="vendor-boot-dbg-${stock_sha:0:8}${debug_tags}"
  fi
  local art=$REMOTE_ARTIFACTS/gpu/$id
  local work=$REMOTE_ROOT/work/gpu/$id
  rm -rf "$work" "$art"
  mkdir -p "$work" "$art"

  log "stock:   $stock ($stock_size bytes, sha256 $stock_sha)"
  log "work:    $work"
  log "output:  $art"
  python3 --version >"$work/versions.txt" 2>&1
  "$dtc" --version >>"$work/versions.txt" 2>&1

  python3 "$mkbootimg_dir/unpack_bootimg.py" --boot_img "$stock" --out "$work/unpack" \
    --format=info >"$work/header-stock.txt"
  python3 "$mkbootimg_dir/unpack_bootimg.py" --boot_img "$stock" --out "$work/unpack" \
    --format=mkbootimg >"$work/mkbootcmd.txt"

  # --- DTB: split the multi-FDT payload, transform every board DTB ----------
  python3 "$HERE/dtb_oc.py" split-blob --blob "$work/unpack/dtb" \
    --outdir "$work/fdt" --json "$work/fdt-parts.json" >/dev/null
  local fdt_count
  fdt_count=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["count"])' \
    "$work/fdt-parts.json")
  log "dtb payload: $fdt_count FDT(s)"

  build_dtb_payload() {
    local root=$1 fdt_dir=$2 f
    mkdir -p "$root/final" "$root/dts"
    for f in "$fdt_dir"/fdt-*.dtb; do
      local name
      name=$(basename "$f" .dtb)
      "$dtc" -I dtb -O dts -o "$root/dts/$name.stock.dts" "$f" 2>"$root/dts/$name.decompile.log"
      if python3 "$HERE/dtb_oc.py" apply --in "$root/dts/$name.stock.dts" \
          --out "$root/dts/$name.oc.dts" --summary "$root/dts/$name.json" \
          ${debug_args[@]+"${debug_args[@]}"} \
          | grep -q '"changed": true'; then
        "$dtc" -I dts -O dtb -o "$root/$name.oc.dtb" "$root/dts/$name.oc.dts" \
          2>"$root/dts/$name.recompile.log"
        # Splice only the transformed GPU bins into the stock FDT: the dtc DTS
        # round-trip is not byte-faithful for unrelated nodes (it re-reads the
        # stock pinctrl `\013` NUL escape as octal 013), so every non-GPU
        # property must come from the stock blob.
        python3 "$HERE/dtb_oc.py" splice --stock-fdt "$f" --oc-fdt "$root/$name.oc.dtb" \
          --out "$root/final/$name.dtb" >/dev/null
      else
        python3 "$HERE/dtb_oc.py" splice --stock-fdt "$f" --oc-fdt "$f" \
          --out "$root/final/$name.dtb" >/dev/null
      fi
    done
    python3 "$HERE/dtb_oc.py" join-blob --indir "$root/final" \
      --out "$root/dtb-oc.bin" --count "$fdt_count" >/dev/null
    python3 "$HERE/dtb_oc.py" verify --stock "$work/unpack/dtb" \
      --oc "$root/dtb-oc.bin" --json "$root/dtb-verify.json" \
      ${debug_args[@]+"${debug_args[@]}"} >/dev/null
  }

  build_dtb_payload "$work/pass1" "$work/fdt"
  ok "DTB transformation verified ($(stat -c %s "$work/unpack/dtb") -> \
$(stat -c %s "$work/pass1/dtb-oc.bin") bytes)"

  # --- mkbootimg ------------------------------------------------------------
  build_image() {
    local dtb=$1 out=$2
    python3 - "$work/mkbootcmd.txt" "$dtb" "$out" "$mkbootimg_dir/mkbootimg.py" <<'PY'
import shlex, subprocess, sys
cmd_file, dtb, out, mkbootimg = sys.argv[1:5]
args = shlex.split(open(cmd_file).read().strip())

# The stock image has one vendor ramdisk fragment (type platform, empty name,
# board_id 0).  unpack_bootimg prints that as --ramdisk_type/--ramdisk_name/
# --vendor_ramdisk_fragment, but this mkbootimg.py build only accepts the
# fragment group when it is re-parsed from the *unknown* argument list, which
# is brittle.  --vendor_ramdisk creates exactly the same single table entry
# (VENDOR_RAMDISK_TYPE_PLATFORM, empty name, zero board id); the repack
# validation compares the ramdisk table byte-for-byte.
rebuilt, fragment, index = [], None, 0
while index < len(args):
    arg = args[index]
    if arg in ("--ramdisk_type", "--ramdisk_name"):
        index += 2
        continue
    if arg == "--vendor_ramdisk_fragment":
        fragment = args[index + 1]
        index += 2
        continue
    rebuilt.append(arg)
    index += 1
if fragment:
    rebuilt += ["--vendor_ramdisk", fragment]

if "--dtb" in rebuilt:
    rebuilt[rebuilt.index("--dtb") + 1] = dtb
else:
    rebuilt += ["--dtb", dtb]
# --vendor_boot (not -o) selects the vendor_boot v4 writer.
subprocess.run([sys.executable, mkbootimg, *rebuilt, "--vendor_boot", out], check=True)
PY
  }

  build_image "$work/pass1/dtb-oc.bin" "$work/vendor_boot.raw.img"
  cp "$work/vendor_boot.raw.img" "$work/vendor_boot.img"
  truncate -s "$partition" "$work/vendor_boot.img"

  python3 "$HERE/vb_image.py" compare --stock "$stock" --out "$work/vendor_boot.img" \
    --partition-size "$partition" --json "$work/image-compare.json" \
    >"$work/image-compare.txt"
  ok "vendor_boot header/ramdisk/bootconfig preserved; image fits $partition bytes"

  # --- reproducible second pass --------------------------------------------
  local repro=skipped
  if ((reproducible)); then
    build_dtb_payload "$work/pass2" "$work/fdt"
    cmp -s "$work/pass1/dtb-oc.bin" "$work/pass2/dtb-oc.bin" || \
      die "DTB build is not deterministic (pass1 != pass2)"
    build_image "$work/pass2/dtb-oc.bin" "$work/vendor_boot.raw2.img"
    cmp -s "$work/vendor_boot.raw.img" "$work/vendor_boot.raw2.img" || \
      die "vendor_boot repack is not deterministic (pass1 != pass2)"
    repro=yes
    ok "reproducible: two independent passes produced the same image"
  fi

  # --- active board DTS for review -----------------------------------------
  if [[ -z $active ]]; then
    active=$(python3 - "$work/fdt-parts.json" "$HERE/dtb_oc.py" "$work/unpack/dtb" <<'PY'
import json, subprocess, sys
parts, tool, blob = sys.argv[1:4]
count = json.load(open(parts))["count"]
info = json.loads(subprocess.run([sys.executable, tool, "info", "--blob", blob],
                                 capture_output=True, text=True, check=True).stdout)
want_msm = {0x164, 0x20001}
want_pmic = [0x1e, 0x20, 0x1f, 0x21, 0x0, 0x0]
for index in range(count):
    item = info[index]
    msm = {int(value, 16) for value in item["msm_id"]}
    pmic = [int(value, 16) for value in item["pmic_id"]]
    if want_msm <= msm and pmic == want_pmic:
        print(index)
        break
PY
)
    [[ -n $active ]] || die "could not identify the RP5 board FDT (msm-id 0x164/0x20001, pmic-id 1e 20 1f 21); pass --active-dtb"
  fi
  [[ $active =~ ^[0-9]+$ ]] || die "--active-dtb must be an index"
  local active_name
  active_name=$(printf 'fdt-%02d' "$active")
  [[ -f $work/pass1/dts/$active_name.oc.dts ]] || \
    die "active FDT index $active ($active_name) not found"
  cp "$work/pass1/dts/$active_name.stock.dts" "$art/active-stock.dts"
  cp "$work/pass1/dts/$active_name.oc.dts" "$art/active-oc.dts"

  # --- manifest -------------------------------------------------------------
  local out_sha out_size dtb_sha dtb_stock_sha dtb_stock_size dtb_size
  local debug_extra debug_drop
  out_sha=$(sha256sum "$work/vendor_boot.img" | awk '{print $1}')
  out_size=$(stat -c %s "$work/vendor_boot.img")
  dtb_stock_sha=$(sha256sum "$work/unpack/dtb" | awk '{print $1}')
  dtb_stock_size=$(stat -c %s "$work/unpack/dtb")
  dtb_sha=$(sha256sum "$work/pass1/dtb-oc.bin" | awk '{print $1}')
  dtb_size=$(stat -c %s "$work/pass1/dtb-oc.bin")
  debug_extra=$(IFS=,; printf '%s' "${EXTRA_STEPS[*]}")
  debug_drop=$(IFS=,; printf '%s' "${DROP_STEPS[*]}")

  python3 - "$work" "$art" "$stock" "$stock_sha" "$stock_size" \
      "$id" "$partition" "$out_sha" "$out_size" \
      "$dtb_stock_sha" "$dtb_stock_size" "$dtb_sha" "$dtb_size" \
      "$repro" "$active" "$dtc" "$mkbootimg_dir" "$fdt_count" \
      "$debug_extra" "$debug_drop" <<'PY'
import json, sys
(work, art, stock, stock_sha, stock_size, artifact_id, partition, out_sha,
 out_size, dtb_stock_sha, dtb_stock_size, dtb_sha, dtb_size, repro, active,
 dtc, mkbootimg_dir, fdt_count, debug_extra, debug_drop) = sys.argv[1:21]


def load(name, default=None):
    try:
        return json.load(open(f"{work}/{name}"))
    except FileNotFoundError:
        return default


debug = {
    "debug_only": bool(debug_extra or debug_drop),
    "extra_steps": [item for item in debug_extra.split(",") if item],
    "drop_steps": [item for item in debug_drop.split(",") if item],
    "max_pwrlevels_hint": 10,
}

manifest = {
    "id": artifact_id,
    "contract": "docs/phase6.md",
    "debug": debug,
    "stock": {"path": stock, "size": int(stock_size), "sha256": stock_sha},
    "output": {"file": "vendor_boot.img", "size": int(out_size),
               "sha256": out_sha, "partition_size": int(partition)},
    "dtb": {"stock_sha256": dtb_stock_sha, "stock_size": int(dtb_stock_size),
            "out_sha256": dtb_sha, "out_size": int(dtb_size),
            "fdts": int(fdt_count),
            "verify": load("pass1/dtb-verify.json")},
    "image": load("image-compare.json"),
    "active_dtb": int(active),
    "reproducible": repro,
    "tools": {"dtc": dtc, "mkbootimg_dir": mkbootimg_dir},
}
with open(f"{art}/manifest.json", "w") as fh:
    json.dump(manifest, fh, indent=2)
    fh.write("\n")
PY
  # --- pull the review material into the artifact dir ----------------------
  cp "$work/header-stock.txt" "$work/image-compare.txt" "$work/image-compare.json" \
     "$work/pass1/dtb-verify.json" "$work/versions.txt" "$art/"
  cp -r "$work/pass1/dts" "$art/dts"
  python3 "$HERE/dtb_oc.py" info --blob "$work/unpack/dtb" >"$art/dtb-stock-info.json"
  python3 "$HERE/dtb_oc.py" info --blob "$work/pass1/dtb-oc.bin" >"$art/dtb-oc-info.json"

  cp "$work/vendor_boot.img" "$art/vendor_boot.img"
  (cd "$art" && sha256sum vendor_boot.img > SHA256SUMS)
  ((keep_work)) || rm -rf "$work"

  {
    printf 'image:         %s/vendor_boot.img\n' "$art"
    printf 'sha256:        %s\n' "$out_sha"
    printf 'size:          %s bytes (partition %s)\n' "$out_size" "$partition"
    printf 'stock sha256:  %s\n' "$stock_sha"
    printf 'dtb sha256:    %s (%s -> %s bytes)\n' "$dtb_sha" "$dtb_stock_size" "$dtb_size"
    if [[ -n $debug_extra || -n $debug_drop ]]; then
      printf 'debug only:    extra=[%s] drop=[%s] (max KGSL pwrlevels hint 10)\n' \
        "$debug_extra" "$debug_drop"
    fi
    printf 'active FDT:    %s (%s)\n' "$active" "$active_name"
    printf 'reproducible:  %s\n' "$repro"
    printf 'ARTIFACT_DIR=%s\n' "$art"
  } | tee "$art/summary.txt"
}

# --- outer phase: push the checkout, re-exec on the build host, pull back ---
if [[ ${ASH_GPU_REMOTE:-0} != 1 ]]; then
  need rsync
  ws=$(ws_push)
  log "running on $BUILD_HOST ($ws)"
  cmd="ASH_ROOT=$(printf %q "$ws") ASH_GPU_REMOTE=1 bash $(printf %q "$ws/scripts/gpu/build-vendor-boot.sh")"
  for arg in "${REMOTE_ARGS[@]}"; do cmd+=" $(printf %q "$arg")"; done
  output=$(host_sh "$cmd")
  printf '%s\n' "$output"
  artifact=$(printf '%s\n' "$output" | sed -n 's/^ARTIFACT_DIR=//p' | tail -1)
  [[ -n $artifact ]] || die "remote build did not report ARTIFACT_DIR"
  dest="$ASH_ROOT/out/gpu/$(basename "$artifact")"
  mkdir -p "$dest"
  pull_artifacts "$artifact" "$dest" >/dev/null
  ok "artifacts: $dest"
  printf 'local image:   %s/vendor_boot.img\n' "$dest"
  printf 'local sha256:  %s\n' "$(sha256sum "$dest/vendor_boot.img" | awk '{print $1}')"
  exit 0
fi

remote_phase
