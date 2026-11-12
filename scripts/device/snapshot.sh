#!/usr/bin/env bash
# AshFall device snapshot collector (READ-ONLY).
#
# Runs *inside* the ashfall-dev container on the build host, where adb/fastboot
# see the RP5 on USB. Normally invoked by scripts/device/info.sh, which copies
# the result back to this VM and redacts it.
#
# It never writes to the device: no reboot, no flash, no erase. It only reads
# properties, sysfs, dumpsys output and individual `fastboot getvar <var>`
# values. `fastboot getvar all` is intentionally NEVER used: interrupting it can
# wedge the bootloader (docs/reference/rp5-recovery-guide.md §8.1).
#
# Usage:
#   snapshot.sh [--out DIR] [--mode auto|android|fastboot] [--serial SERIAL]
#
# Output: DIR/<mode>/... plus DIR/_meta.txt and DIR/SUMMARY.txt
set -euo pipefail

OUT=""
MODE=auto
SERIAL=""

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

while (($#)); do
  case $1 in
    --out) OUT=${2:?--out needs a directory}; shift 2 ;;
    --mode) MODE=${2:?--mode needs auto|android|fastboot}; shift 2 ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z $OUT ]]; then
  OUT="device-snapshot-$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

STAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FASTBOOT_VARS=(
  product variant hw-revision current-slot slot-count slot-suffixes unlocked secure
  is-userspace max-download-size version-bootloader version-baseband version
  battery-voltage battery-soc-ok off-mode-charge charger-screen-enabled
  snapshot-update-status
)
# Keep this list short: every entry costs two fastboot sessions and this
# bootloader gets flaky when hammered with getvars. Sizes we need for
# flashing/restoring first, curiosities second.
PARTITIONS=(
  boot_a boot_b vendor_boot_a vendor_boot_b init_boot_a init_boot_b
  dtbo_a dtbo_b vbmeta_a vbmeta_b vbmeta_system_a recovery_a
  super userdata metadata splash
)
# partition-type is only interesting for the special ones
PARTITION_TYPES=(super userdata metadata)

# capf <relpath> <command...> : run a command, record the command line and exit
# status alongside its output. Never fails the script.
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

fastboot_serial() {
  timeout 25 fastboot devices 2>/dev/null | awk 'NF>=2 {print $1; exit}'
}
adb_serial() {
  timeout 25 adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}'
}

# The RP5 bootloader intermittently reports "GetVar Variable Not found" or
# "unknown command" for variables that exist a moment later. Retry a few times.
fgetvar() {
  local v=$1 out=''
  for _ in 1 2 3; do
    out=$(timeout 15 fastboot getvar "$v" 2>&1 || true)
    if grep -q '< waiting for any device >' <<<"$out"; then
      printf '%s\n' "$out"; return 0
    fi
    if ! grep -qE "FAILED \(remote: '(GetVar Variable Not found|unknown command)'\)" <<<"$out"; then
      printf '%s\n' "$out"; return 0
    fi
    sleep 0.4
  done
  printf '%s\n' "$out"
  return 0
}

# --- meta --------------------------------------------------------------------
{
  printf 'ashfall device snapshot\n'
  printf 'timestamp:   %s\n' "$STAMP"
  printf 'host:        %s\n' "$(hostname)"
  printf 'kernel:      %s\n' "$(uname -a)"
  printf 'container:   %s\n' "${CONTAINER_ID:-none}"
  printf 'adb:         %s\n' "$(adb version 2>/dev/null | head -1 || echo missing)"
  printf 'fastboot:    %s\n' "$(fastboot --version 2>/dev/null | head -1 || echo missing)"
  printf 'usb:\n'
  lsusb 2>/dev/null | grep -E '18d1:|05c6:|2dc8:' | sed 's/^/  /' || true
  printf 'adb devices:\n'; timeout 25 adb devices -l 2>&1 | sed 's/^/  /' || true
  printf 'fastboot devices:\n'; timeout 25 fastboot devices -l 2>&1 | sed 's/^/  /' || true
} >"$OUT/_meta.txt" 2>&1

FSER=$(fastboot_serial || true)
ASER=$(adb_serial || true)
if [[ -n $SERIAL ]]; then
  if [[ $FSER == "$SERIAL" ]]; then
    [[ $MODE == auto ]] && MODE=fastboot
  elif [[ $ASER == "$SERIAL" ]]; then
    [[ $MODE == auto ]] && MODE=android
  elif timeout 25 adb devices 2>/dev/null | awk 'NR>1 && $1=="'"$SERIAL"'" && $2!="device" {print $2}' | grep -q .; then
    echo "device $SERIAL is visible but not usable over adb (unauthorized/offline); re-authorize and retry" >&2
    MODE=none; FSER=''; ASER=''
  else
    echo "serial $SERIAL is not visible via adb or fastboot on this host" >&2
    MODE=none; FSER=''; ASER=''
  fi
fi

# --- fastboot ----------------------------------------------------------------
capture_fastboot() {
  local d=$OUT/fastboot v
  mkdir -p "$d"
  export ANDROID_SERIAL=$FSER
  capf fastboot/devices.txt timeout 25 fastboot devices -l
  local f
  for v in "${FASTBOOT_VARS[@]}"; do
    # Individual getvars only, each with a timeout (a wedged session must not
    # block the snapshot).
    printf '\n## %s\n' "$v" >>"$d/getvars.txt"
    f=$(fgetvar "$v" | head -5)
    if grep -q '< waiting for any device >' <<<"$f"; then
      printf 'device disappeared from USB during the snapshot\n' >>"$d/getvars.txt"
      break
    fi
    printf '%s\n' "$f" >>"$d/getvars.txt"
    sleep 0.2
  done
  for v in "${PARTITIONS[@]}"; do
    printf '\n## partition-size:%s\n' "$v" >>"$d/partition-sizes.txt"
    f=$(fgetvar "partition-size:$v" | head -5)
    grep -q '< waiting for any device >' <<<"$f" && { printf 'device disappeared from USB during the snapshot\n' >>"$d/partition-sizes.txt"; break; }
    printf '%s\n' "$f" >>"$d/partition-sizes.txt"
    sleep 0.2
  done
  for v in "${PARTITION_TYPES[@]}"; do
    printf '\n## partition-type:%s\n' "$v" >>"$d/partition-sizes.txt"
    fgetvar "partition-type:$v" | head -5 >>"$d/partition-sizes.txt" || true
    sleep 0.2
  done
  for v in system product; do
    printf '\n## is-logical:%s\n' "$v" >>"$d/logical.txt"
    fgetvar "is-logical:$v" | head -5 >>"$d/logical.txt" || true
    sleep 0.2
  done
  unset ANDROID_SERIAL
}

# --- android / recovery ------------------------------------------------------
capture_android() {
  local d=$OUT/android
  mkdir -p "$d"
  export ANDROID_SERIAL=$ASER
  local -a S=(timeout 90 adb shell)

  capf android/getprop.txt sh -c 'timeout 90 adb shell getprop' || true
  {
    printf '# identity/build (selected)\n'
    timeout 60 adb shell 'getprop | grep -E "\[(ro\.build\.(fingerprint|type|version\.(release|sdk|security_patch)|date|product|device|board|hardware)|ro\.product\.(device|model|name)|ro\.boot\.(hardware|slot_suffix|verifiedbootstate|flash\.locked)|ro\.secure|ro\.debuggable|ro\.adb\.secure|ro\.treble\.enabled|ro\.vndk\.version|persist\.sys\.usb\.config|sys\.usb\.config|ro\.boot\.revision|ro\.kernel\.qemu)"\]'
    printf '\n# getprop ro.product.*\n'
    timeout 60 adb shell 'getprop | grep -E "\[(ro\.product|ro\.system|ro\.vendor|ro\.odm)\."'
    printf '\n# radio/sim keys are deliberately NOT collected\n'
  } >"$d/props-key.txt" 2>&1

  capf android/proc-version.txt      "${S[@]}" 'cat /proc/version; echo; uname -a'
  capf android/proc-cmdline.txt      "${S[@]}" 'cat /proc/cmdline'
  capf android/proc-cpuinfo.txt      "${S[@]}" 'cat /proc/cpuinfo'
  capf android/proc-partitions.txt   "${S[@]}" 'cat /proc/partitions'
  capf android/proc-mounts.txt       "${S[@]}" 'cat /proc/mounts'
  capf android/df.txt                "${S[@]}" 'df -h'
  capf android/meminfo.txt           "${S[@]}" 'head -25 /proc/meminfo'
  capf android/uptime.txt            "${S[@]}" 'uptime'

  capf android/vintf-list.txt        "${S[@]}" 'ls -lR /vendor/etc/vintf 2>&1 | head -80'
  capf android/vintf-manifest.txt    "${S[@]}" 'for f in /vendor/etc/vintf/manifest.xml /vendor/etc/vintf/manifest_*.xml; do echo "== $f"; cat "$f" 2>&1; done | head -4000'
  capf android/vintf-matrix.txt      "${S[@]}" 'for f in /vendor/etc/vintf/compatibility_matrix*.xml; do echo "== $f"; cat "$f" 2>&1; done | head -4000'
  capf android/hals.txt sh -c 'if timeout 60 adb shell "command -v lshal >/dev/null 2>&1"; then timeout 60 adb shell lshal; else timeout 60 adb shell service list; fi | head -400'
  capf android/service-list.txt      "${S[@]}" 'service list | head -400'

  capf android/block-by-name.txt     "${S[@]}" 'ls -l /dev/block/by-name 2>&1 | head -300'
  capf android/block-mapper.txt      "${S[@]}" 'ls -l /dev/block/mapper 2>&1 | head -300'
  capf android/lpdump.txt            "${S[@]}" 'command -v lpdump >/dev/null 2>&1 && lpdump 2>&1 | head -400 || echo "lpdump not available/denied"'

  capf android/display.txt           "${S[@]}" 'dumpsys display 2>&1 | head -4000'
  capf android/wm.txt                "${S[@]}" 'wm size; wm density'
  capf android/surfaceflinger.txt    "${S[@]}" 'dumpsys SurfaceFlinger 2>&1 | head -400'
  capf android/drm-modes.txt         "${S[@]}" 'for f in /sys/class/drm/*/modes; do echo "== $f"; head -10 "$f"; done 2>&1'

  capf android/input-getevent.txt    "${S[@]}" 'getevent -lp 2>&1 | head -400'
  capf android/input-devices.txt     "${S[@]}" 'cat /proc/bus/input/devices 2>&1 | head -400'
  capf android/input-dumpsys.txt     "${S[@]}" 'dumpsys input 2>&1 | head -600'

  capf android/thermal.txt           "${S[@]}" 'for z in /sys/class/thermal/thermal_zone*; do printf "%s %s %s\n" "$z" "$(cat $z/type 2>/dev/null)" "$(cat $z/temp 2>/dev/null)"; done 2>&1 | head -200'
  capf android/thermal-service.txt   "${S[@]}" 'dumpsys thermalservice 2>&1 | head -300'
  capf android/hwmon.txt             "${S[@]}" 'for h in /sys/class/hwmon/hwmon*; do echo "== $h $(cat $h/name 2>/dev/null)"; for f in $h/pwm*_enable $h/pwm* $h/fan*_input $h/temp*_input; do [ -f "$f" ] && echo "$f $(cat $f 2>/dev/null)"; done; done 2>&1 | head -300'
  capf android/cpufreq.txt           "${S[@]}" 'for c in /sys/devices/system/cpu/cpu*/cpufreq; do echo "== $c"; cat $c/scaling_governor $c/scaling_cur_freq $c/cpuinfo_max_freq 2>/dev/null; done 2>&1 | head -200'
  capf android/kgsl.txt              "${S[@]}" 'for f in /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies; do [ -f "$f" ] && echo "$f $(cat $f 2>/dev/null)"; done 2>&1'

  capf android/leds.txt              "${S[@]}" 'for l in /sys/class/leds/*; do echo "== $l"; cat $l/brightness $l/max_brightness 2>/dev/null; done 2>&1 | head -200'
  capf android/power-supply.txt      "${S[@]}" 'for p in /sys/class/power_supply/*; do echo "== $p"; cat $p/uevent 2>/dev/null; done 2>&1 | head -300'
  capf android/battery.txt            "${S[@]}" 'dumpsys battery 2>&1 | head -120'

  capf android/audio.txt             "${S[@]}" 'dumpsys audio 2>&1 | head -150; echo; dumpsys media.audio_flinger 2>&1 | head -150'
  capf android/sensors.txt           "${S[@]}" 'dumpsys sensorservice 2>&1 | head -200'
  capf android/camera.txt            "${S[@]}" 'dumpsys media.camera 2>&1 | head -150'
  capf android/wifi.txt              "${S[@]}" 'dumpsys wifi 2>&1 | head -80'
  capf android/bluetooth.txt         "${S[@]}" 'dumpsys bluetooth_manager 2>&1 | head -80'
  capf android/usb.txt               "${S[@]}" 'getprop | grep -i usb; dumpsys usb 2>&1 | head -80; cat /sys/class/android_usb/android0/state 2>/dev/null'
  capf android/storage.txt           "${S[@]}" 'sm list-volumes all 2>&1; echo; sm list-disks 2>&1'

  # Recovery / early boot extras
  capf android/dmesg.txt             "${S[@]}" 'dmesg 2>&1 | tail -3000'
  capf android/last-kmsg.txt         "${S[@]}" 'cat /proc/last_kmsg 2>&1 | tail -1000'
  capf android/pstore.txt            "${S[@]}" 'ls -l /sys/fs/pstore 2>&1; for f in /sys/fs/pstore/*; do echo "== $f"; cat "$f" 2>/dev/null; done | head -2000'
  capf android/recovery-logs.txt     "${S[@]}" 'ls -l /tmp/recovery.log /cache/recovery 2>&1; tail -300 /tmp/recovery.log 2>&1'

  unset ANDROID_SERIAL
}

# --- decide mode -------------------------------------------------------------
mode=$MODE
if [[ $mode == auto ]]; then
  if [[ -n $FSER ]]; then mode=fastboot
  elif [[ -n $ASER ]]; then mode=android
  else mode=none; fi
fi

case $mode in
  fastboot) [[ -n $FSER ]] && capture_fastboot || echo "no fastboot device" >&2 ;;
  android)  [[ -n $ASER ]] && capture_android  || echo "no adb device" >&2 ;;
  none)     echo "no device visible via adb or fastboot" >&2 ;;
  *) echo "bad --mode: $mode" >&2; exit 2 ;;
esac

# --- summary -----------------------------------------------------------------
{
  echo "# Snapshot summary"
  echo
  echo "- timestamp: $STAMP"
  echo "- mode: $mode"
  [[ -n $FSER ]] && echo "- fastboot serial: $FSER"
  [[ -n $ASER ]] && echo "- adb serial: $ASER"
  if [[ -f $OUT/fastboot/getvars.txt ]]; then
    echo "- fastboot getvars:"
    grep -E '^(product|variant|hw-revision|current-slot|slot-count|slot-suffixes|unlocked|secure|is-userspace|max-download-size|battery-voltage):' \
      "$OUT/fastboot/getvars.txt" | sed 's/^/    - /'
  fi
  if [[ -f $OUT/android/props-key.txt ]]; then
    echo "- android props:"
    grep -E '\[(ro\.build\.fingerprint|ro\.build\.type|ro\.product\.device|ro\.boot\.hardware|ro\.boot\.slot_suffix|ro\.boot\.verifiedbootstate|ro\.secure|ro\.debuggable|ro\.adb\.secure|ro\.treble\.enabled|ro\.vndk\.version)\]' \
      "$OUT/android/props-key.txt" | sed 's/^/    - /'
  fi
  echo
  echo "## Files"
  (cd "$OUT" && find . -type f | sort | sed 's/^/  /')
} >"$OUT/SUMMARY.txt"

printf '%s\n' "$OUT"
