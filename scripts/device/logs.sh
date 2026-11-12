#!/usr/bin/env bash
# AshFall: pull logs/off the RP5 into out/device/<timestamp>-logs/.
#
# The device is USB-connected to the build host, so adb runs there in the
# `ashfall-dev` container; this script pushes the collector, runs it, pulls the
# result back and prints a summary. Read-only w.r.t. the device.
#
# Works for a booting-but-broken GSI as well as a healthy stock Android or
# recovery: logcat (all buffers), dmesg, /proc/last_kmsg, pstore, tombstones,
# ANRs, dropbox crashes, properties, boot reason. Optional full bugreport.
#
# Usage:
#   scripts/device/logs.sh [--out DIR] [--serial SERIAL] [--bugreport]
#                          [--redact] [--no-ensure] [--help]
#
# Options:
#   --out DIR     local destination (default: out/device/<timestamp>-logs)
#   --serial S    device serial (default: the only one connected)
#   --bugreport   also run `adb bugreport` (slow, hundreds of MB on stock)
#   --redact      scrub serials/IMEI/MAC from the collected text files
#                 (off by default: out/ is gitignored, raw logs debug better)
#   --no-ensure   do not create/install the ashfall-dev container
#   -h, --help    this help
#
# Early boot (userdebug GSI): adbd is up as soon as init starts it. With
# ro.adb.secure=0 there is no authorization prompt, and
# persist.sys.usb.config=adb keeps the adb USB function enabled, so a
# bootlooping GSI can still be logged. On a user build accept the prompt on the
# device, or the device shows up as `unauthorized`.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
# shellcheck source=SCRIPTDIR/lib/device.sh
. "$HERE/lib/device.sh"

TS=$(date -u +%Y%m%dT%H%M%SZ)
OUT=""
SERIAL=""
BUGREPORT=0
REDACT=0
ENSURE=1

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

while (($#)); do
  case $1 in
    --out) OUT=${2:?--out needs a dir}; shift 2 ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    --bugreport) BUGREPORT=1; shift ;;
    --redact) REDACT=1; shift ;;
    --no-ensure) ENSURE=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

[[ -n $OUT ]] || OUT="$LOCAL_ARTIFACTS/device/$TS-logs"

if ((REDACT)) && ((BUGREPORT)); then
  die "--redact cannot scrub the bugreport zip (it stays unredacted); drop --bugreport or share the zip knowingly"
fi

((ENSURE)) && dev_ensure

mode=$(dev_mode)
log "device mode on $BUILD_HOST: $mode"
case $mode in
  android|recovery|sideload) ;;
  unauthorized) die "adb is unauthorized: accept the USB debugging prompt on the RP5 (Developer options -> USB debugging) and re-run" ;;
  fastboot|fastbootd) die "device is in $mode: there is no adb there. Boot Android or recovery first (guide §6)" ;;
  none) die "no device visible via adb/fastboot on $BUILD_HOST (check the cable and that Android/recovery is booted)" ;;
  ambiguous) die "several devices visible; pass --serial" ;;
  *) die "unexpected device mode: $mode" ;;
esac

WS=$(ws_push)
REMOTE_OUT="$REMOTE_ARTIFACTS/device/$TS-logs"
args=(--out "$REMOTE_OUT")
[[ -n $SERIAL ]] && args+=(--serial "$SERIAL")
((BUGREPORT)) && args+=(--bugreport)

log "collecting logs into $BUILD_HOST:$REMOTE_OUT"
dev_sh "bash $(_dq "$WS/scripts/device/collect-logs.sh") $(_cmd "${args[@]}")" >/dev/null

mkdir -p "$(dirname "$OUT")"
pull_artifacts "$REMOTE_OUT" "$OUT" >/dev/null

if ((REDACT)) && [[ -f $HERE/redact.sed ]]; then
  while IFS= read -r -d '' f; do
    sed -i -E -f "$HERE/redact.sed" "$f"
  done < <(find "$OUT" -type f \( -name '*.txt' -o -name '*.log' \) -print0)
  log "redacted identity values in $OUT"
fi

# summary
{
  echo "# RP5 log collection"
  echo
  echo "- taken: \`$TS\` (UTC)"
  echo "- mode: \`$mode\`"
  echo "- dir: \`$OUT\`"
  echo
  echo "## Highlights"
  echo
  if [[ -f $OUT/logcat-crash.txt ]]; then
    local_crashes=$(grep -cE 'FATAL EXCEPTION|Fatal signal' "$OUT/logcat-all.txt" 2>/dev/null || true)
    echo "- crash lines in logcat: ${local_crashes:-0}"
  fi
  for f in dmesg.txt last-kmsg.txt pstore-dump.txt tombstones-list.txt anr-list.txt; do
    [[ -f $OUT/$f ]] && printf -- '- `%s` (%s bytes)\n' "$f" "$(stat -c %s "$OUT/$f")"
  done
  if [[ -f $OUT/NO_ADB_DEVICE.txt ]]; then
    echo
    echo "**No adb device.** See \`NO_ADB_DEVICE.txt\`."
  fi
  echo
  echo "## Files"
  echo
  (cd "$OUT" && find . -maxdepth 1 -type f | sort | sed 's|^\./|  - `|; s|$|`|')
} >"$OUT/SUMMARY.md"

ok "logs: $OUT/"
printf '%s\n' "$OUT"
