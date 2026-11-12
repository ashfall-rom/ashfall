#!/usr/bin/env bash
# AshFall device snapshot: capture everything we know about the RP5 into
# out/device/<UTC timestamp>/ and return a redacted copy here.
#
# The device is USB-connected to the build host, so the collector runs there in
# the `ashfall-dev` container (adb/fastboot). This script pushes the repo to the
# host, runs scripts/device/snapshot.sh, rsyncs the result back, redacts
# identity values (serials, IMEI-like, MAC) and writes SUMMARY.md.
#
# READ-ONLY with respect to the device: no reboot, no flash, no erase.
# Never uses `fastboot getvar all` (it can wedge the bootloader, guide §8.1).
#
# Usage:
#   scripts/device/info.sh [--out DIR] [--mode auto|android|fastboot]
#                          [--serial SERIAL] [--no-ensure] [--help]
#
# Options:
#   --out DIR     local destination (default: out/device/<timestamp>)
#   --mode MODE   force the collector mode (default: auto)
#   --serial S    use this device serial (default: the only one connected)
#   --no-ensure   do not create/install the ashfall-dev container
#   -h, --help    this help
#
# Exit codes: 0 snapshot taken (even if the device is absent: an empty report
#             with a note is still written), 1 setup/transfer failure.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
# shellcheck source=SCRIPTDIR/lib/device.sh
. "$HERE/lib/device.sh"

TS=$(date -u +%Y%m%dT%H%M%SZ)
OUT=""
MODE=auto
SERIAL=""
ENSURE=1

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

while (($#)); do
  case $1 in
    --out) OUT=${2:?--out needs a dir}; shift 2 ;;
    --mode) MODE=${2:?--mode needs auto|android|fastboot}; shift 2 ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    --no-ensure) ENSURE=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

[[ -n $OUT ]] || OUT="$LOCAL_ARTIFACTS/device/$TS"

((ENSURE)) && dev_ensure

mode=$(dev_mode)
log "device mode on $BUILD_HOST: $mode"
if [[ $mode == unauthorized ]]; then
  warn "adb reports 'unauthorized': accept the USB debugging prompt on the RP5 \
(or enable Developer options -> USB debugging) and re-run; continuing with other modes"
fi
if [[ $mode == none || $mode == ambiguous ]]; then
  warn "no single usable device visible via adb/fastboot; the snapshot will record USB state only"
fi

# --- push collector to the host ----------------------------------------------
WS=$(ws_push)
log "pushed scripts to $BUILD_HOST:$WS"
REMOTE_OUT="$REMOTE_ARTIFACTS/device/$TS"

snap_args=(--out "$REMOTE_OUT" --mode "$MODE")
[[ -n $SERIAL ]] && snap_args+=(--serial "$SERIAL")
dev_sh "bash $(_dq "$WS/scripts/device/snapshot.sh") $(_cmd "${snap_args[@]}")" >&2

# --- pull back + redact -------------------------------------------------------
mkdir -p "$LOCAL_ARTIFACTS/device"
log "pulling snapshot to $OUT"
pull_artifacts "$REMOTE_OUT" "$OUT" >/dev/null

if [[ -f $HERE/redact.sed ]]; then
  while IFS= read -r -d '' f; do
    sed -i -E -f "$HERE/redact.sed" "$f"
  done < <(find "$OUT" -type f \( -name '*.txt' -o -name '*.log' -o -name '*.md' \) -print0)
fi

# --- summary -----------------------------------------------------------------
mk_summary() {
  local f="$OUT/SUMMARY.md"
  local fb="$OUT/fastboot/getvars.txt" ps="$OUT/fastboot/partition-sizes.txt"
  local props="$OUT/android/props-key.txt"
  {
    echo "# RP5 device snapshot"
    echo
    echo "- taken: \`$TS\` (UTC) on \`$BUILD_HOST\`"
    echo "- mode: \`$mode\`"
    echo "- source dir: \`out/device/$TS/\`"
    echo
    if [[ -f $fb ]]; then
      echo "## fastboot"
      echo
      echo '| var | value |'
      echo '|---|---|'
      for v in product variant hw-revision current-slot slot-count slot-suffixes unlocked \
               secure is-userspace max-download-size version-bootloader version-baseband \
               battery-voltage snapshot-update-status; do
        local val
        val=$(fb_parse "$v" <"$fb" || true)
        printf '| `%s` | `%s` |\n' "$v" "${val:-n/a}"
      done
      echo
      echo "Partition sizes/types: [\`fastboot/partition-sizes.txt\`](fastboot/partition-sizes.txt)."
      echo
    fi
    if [[ -f $props ]]; then
      echo "## Android properties"
      echo
      echo '| prop | value |'
      echo '|---|---|'
      for p in ro.build.fingerprint ro.build.type ro.build.version.release ro.product.device \
               ro.boot.hardware ro.boot.slot_suffix ro.boot.verifiedbootstate ro.secure \
               ro.debuggable ro.adb.secure ro.treble.enabled ro.vndk.version \
               persist.sys.usb.config; do
        local val
        val=$(awk -v k="[$p]:" 'index($0, k)==1 { sub(/^\[[^]]*\]:[ \t]*\[/, ""); sub(/\][ \t]*$/, ""); print; exit }' "$props" || true)
        printf '| `%s` | `%s` |\n' "$p" "${val:-n/a}"
      done
      echo
    fi
    if [[ -f $ps ]]; then
      echo "## Physical partition sizes (selected)"
      echo
      echo '| partition | size |'
      echo '|---|---|'
      for p in boot_a vendor_boot_a init_boot_a dtbo_a vbmeta_a vbmeta_system_a super userdata metadata; do
        local val
        val=$(fb_parse "partition-size:$p" <"$ps" || true)
        [[ -n $val ]] && printf '| `%s` | `%s` |\n' "$p" "$val"
      done
      echo
    fi
    echo "## Files"
    echo
    (cd "$OUT" && find . -type f | sort | sed 's/^/  - `/; s/$/`/')
  } >"$f"
}
mk_summary

printf '%s\n' "$OUT"

if [[ $mode == none || $mode == ambiguous ]]; then
  exit 0
fi
ok "device snapshot: $OUT/SUMMARY.md"
