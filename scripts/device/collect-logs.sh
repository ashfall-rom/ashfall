#!/usr/bin/env bash
# AshFall: collect logs from the RP5 (Android or recovery) for Phase 3/4
# debugging. Runs inside the ashfall-dev container on the build host.
#
# Read-only: logcat/dmesg/pstore reads, `adb pull` of crash artefacts, optional
# bugreport. No reboot, no flash, no erase.
#
# Usage:
#   collect-logs.sh --out DIR [--serial SERIAL] [--bugreport]
#
# What it collects
#   logcat (all buffers: main/system/crash/kernel), properties, dmesg,
#   /proc/last_kmsg, /sys/fs/pstore, tombstones, ANRs, dropbox crashes,
#   boot reason, USB/adb configuration. Files that need root are attempted and
#   their failure is recorded (user builds: adb root is refused by design).
#
# Early-boot adb (userdebug/eng GSIs)
#   adbd starts from init in the ramdisk/system image. On a userdebug build
#   `ro.adb.secure=0` means no authorization prompt, and
#   `persist.sys.usb.config=adb` (or `sys.usb.config`) keeps the adb function
#   on without unlocking the device, so a bootlooping GSI is reachable as soon
#   as adbd starts. On a user build the prompt must be accepted on-device.
set -euo pipefail

OUT=""
SERIAL=${ANDROID_SERIAL:-}
BUGREPORT=0

while (($#)); do
  case $1 in
    --out) OUT=${2:?--out needs a dir}; shift 2 ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    --bugreport) BUGREPORT=1; shift ;;
    -h|--help) awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

[[ -n $OUT ]] || { echo "--out is required" >&2; exit 2; }
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
[[ -n $SERIAL ]] && export ANDROID_SERIAL=$SERIAL

capf() {
  local rel=$1 f; shift
  f="$OUT/$rel"
  mkdir -p "$(dirname "$f")"
  {
    printf '# cmd: %s\n' "$*"
    "$@" 2>&1 || printf '\n# exit: %s\n' "$?"
    printf '\n'
  } >"$f"
}

state=$(timeout 25 adb devices 2>/dev/null | awk 'NR>1 && NF {print $2; exit}' || true)

{
  printf 'ashfall log collection\n'
  printf 'timestamp: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'host:      %s\n' "$(hostname)"
  printf 'adb:       %s\n' "$(adb version 2>/dev/null | head -1)"
  printf 'devices:\n'
  timeout 25 adb devices -l 2>&1 | sed 's/^/  /' || true
  printf 'state:     %s\n' "${state:-none}"
} >"$OUT/_meta.txt"

if [[ $state != device && $state != recovery && $state != sideload ]]; then
  {
    echo "No usable adb device (state: ${state:-none})."
    case $state in
      unauthorized) echo "Accept the USB debugging prompt on the RP5, or enable USB debugging." ;;
      "") echo "Check the cable and that Android/recovery is booted. In fastboot there is no adb." ;;
    esac
    echo "For a bootlooping GSI on a userdebug build: ro.adb.secure=0 means no prompt,"
    echo "and persist.sys.usb.config=adb keeps adb on during early boot."
  } >"$OUT/NO_ADB_DEVICE.txt"
  echo "no adb device (state: ${state:-none})" >&2
  exit 0
fi

export ANDROID_SERIAL=$SERIAL
capf adb-devices.txt           timeout 25 adb devices -l
capf getprop.txt               timeout 90 adb shell getprop
capf props-boot.txt            timeout 60 adb shell 'getprop | grep -E "\[(ro\.(build\.(fingerprint|type|version\.release|date)|boot\.(bootreason|slot_suffix|verifiedbootstate|flash\.locked|hardware)|debuggable|secure|adb\.secure)|persist\.sys\.(usb\.config|boot\.reason)|sys\.(usb\.config|boot\.reason))\]"'
capf proc-version.txt          timeout 60 adb shell 'cat /proc/version; uname -a'
capf proc-cmdline.txt          timeout 60 adb shell cat /proc/cmdline
capf mount.txt                 timeout 60 adb shell 'cat /proc/mounts'
capf uptime.txt                timeout 60 adb shell uptime

capf logcat-all.txt            timeout 300 adb logcat -b all -d -v threadtime
capf logcat-crash.txt          timeout 120 adb logcat -b crash -d -v threadtime

capf dmesg.txt                 timeout 120 adb shell dmesg
capf dmesg-root.txt            timeout 120 adb shell 'su -c dmesg' || true
capf last-kmsg.txt             timeout 60 adb shell 'cat /proc/last_kmsg 2>&1 | tail -2000'
capf pstore-list.txt           timeout 60 adb shell 'ls -l /sys/fs/pstore 2>&1'
capf pstore-dump.txt           timeout 120 adb shell 'for f in /sys/fs/pstore/*; do echo "== $f"; cat "$f" 2>&1; done | head -4000'
capf tombstones-list.txt       timeout 60 adb shell 'ls -l /data/tombstones 2>&1'
capf anr-list.txt              timeout 60 adb shell 'ls -l /data/anr 2>&1'
capf dropbox-anr.txt           timeout 120 adb shell 'dumpsys dropbox --print system_app_anr 2>&1 | head -400'
capf dropbox-crash.txt         timeout 120 adb shell 'dumpsys dropbox --print system_server_crash 2>&1 | head -400'
capf exit-info.txt             timeout 120 adb shell 'dumpsys activity exit-info 2>&1 | head -400'

# Pull whatever is readable without root; failures are recorded.
{
  echo '# adb pull /data/tombstones'
  timeout 120 adb pull /data/tombstones "$OUT/tombstones" 2>&1 || echo "# exit: $?"
} >"$OUT/pull-tombstones.txt"
{
  echo '# adb pull /data/anr'
  timeout 120 adb pull /data/anr "$OUT/anr" 2>&1 || echo "# exit: $?"
} >"$OUT/pull-anr.txt"
{
  echo '# adb pull /sys/fs/pstore'
  timeout 120 adb pull /sys/fs/pstore "$OUT/pstore" 2>&1 || echo "# exit: $?"
} >"$OUT/pull-pstore.txt"

if ((BUGREPORT)); then
  echo "collecting bugreport (this can take several minutes)" >&2
  capf bugreport.txt timeout 900 adb bugreport "$OUT/bugreport" || true
fi

echo "$OUT"
