#!/usr/bin/env bash
# AshFall: diagnose and fix the 90-degree rotated UI after a no-wipe flash of the
# GSI over the stock ROM (issue #42).
#
# Why this exists
#   The RP5 panel is 1080x1920 native portrait; AshFall makes it natively
#   landscape in SurfaceFlinger (ro.surface_flinger.primary_display_orientation
#   =ORIENTATION_90), so ROTATION_0 is already the upright landscape frame.
#   The primary display's rotation in A17 comes ONLY from Settings.System
#   (DisplayRotation.updateSettings(): USER_ROTATION, default ROTATION_0, plus
#   ACCELEROMETER_ROTATION -> LOCKED/FREE; restoreSettings() returns early for
#   the default display, so /data/system/display_settings.xml cannot rotate it).
#   A no-wipe flash inherits the stock /data, so a stock-time
#   `user_rotation=1` with auto-rotate off survives and locks the logical
#   display to ROTATION_90 on top of the landscape panel -> portrait UI rotated
#   90 degrees. A wiped flash re-creates the settings database from the GSI
#   defaults (def_user_rotation=0, def_accelerometer_rotation=false) and boots
#   landscape. The fix is the persistent, minimal one: lock user rotation 0.
#   See docs/rp5/display.md ("No-wipe flash from stock").
#
# Safety
#   * Read-only by default: `--dry-run` is the default mode, it prints the live
#     rotation state and changes nothing. `--apply` is opt-in.
#   * `--apply` writes ONE setting, and only when all four preconditions hold:
#     SF physical orientation is ORIENTATION_90, user_rotation is 1,
#     accelerometer_rotation is 0 and the effective wm size is 1920x1080 (no
#     forced 1080x1920 portrait size). On any mismatch it changes nothing and
#     prints the exact reason plus the next diagnostic step.
#   * Idempotent: a second run is a no-op.
#   * The decision is a pure function (`rotation_decision`) fed by
#     `--self-test`, so the logic can be checked without a device and without
#     adb.
#
# Usage:
#   scripts/device/fix-rotation.sh [--dry-run] [--serial SERIAL]
#   scripts/device/fix-rotation.sh --apply [--serial SERIAL]
#   scripts/device/fix-rotation.sh --self-test
#
# Options:
#   --dry-run        print the rotation state and the verdict, change nothing
#                    (the default)
#   --apply          run `wm user-rotation lock 0` when all preconditions hold;
#                    on any mismatch, change nothing and exit 1
#   --self-test      run the decision-table cases on this host (no adb, no
#                    device, no lease)
#   --serial SERIAL  device serial (default: the only one connected)
#   -h, --help       this help
#
# Exit codes: 0 success / nothing to do, 1 refusal (precondition failed or the
# apply did not verify), 2 usage.
#
# The RP5 is single-tenant: run this under the device lease, never bare:
#   scripts/device/lock.sh run <owner> -- scripts/device/fix-rotation.sh --apply
# (dev_adb already refuses while another owner holds the lease.)
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
# shellcheck source=SCRIPTDIR/lib/device.sh
. "$HERE/lib/device.sh"

APPLY=0
SELF_TEST=0
SERIAL=""
RAW=""

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

while (($#)); do
  case $1 in
    --dry-run) APPLY=0; shift ;;
    --apply) APPLY=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) die_usage "unknown option: $1" ;;
    *) die_usage "unexpected argument: $1" ;;
  esac
done

# --- pure decision logic ------------------------------------------------------
# rotation_decision <sf_prop> <user_rotation> <accelerometer_rotation> <wm_size>
#   sf_prop                ro.surface_flinger.primary_display_orientation
#   user_rotation          Settings.System.user_rotation ("0"/"1"/...)
#   accelerometer_rotation Settings.System.accelerometer_rotation ("0"/"1")
#   wm_size                effective `wm size` as WxH ("1920x1080")
#
# Pure: no globals, no adb, no side effects. Prints exactly one line:
#   apply
#   noop
#   refuse<TAB><tag><TAB><reason and next step>
# The <tag> is stable (self-test matches on it); the text after it is for humans.
rotation_decision() {
  local sf=${1:-} ur=${2:-} ar=${3:-} size=${4:-}

  if [[ $sf != ORIENTATION_90 ]]; then
    printf 'refuse\tno-sf-prop\tro.surface_flinger.primary_display_orientation=%s (want ORIENTATION_90): the panel is not natively landscape, so locking user rotation cannot make the UI upright. Next: check that the flashed build carries the property from aosp/vendor/ashfall/rp5/display.mk (getprop after a reboot; ro.* properties are write-once).\n' \
      "${sf:-<empty>}"
    return 0
  fi

  case $size in
    1920x1080) ;;
    1080x1920)
      printf 'refuse\tforced-portrait-size\twm size is 1080x1920 (a forced portrait size): a locked user rotation cannot fix that. Next: adb shell wm size reset (and wm density reset if set), reboot, then re-run --dry-run. Settings.Global.display_size_forced is printed above.\n'
      return 0
      ;;
    *)
      printf 'refuse\tunexpected-size\twm size is %s (want 1920x1080): the logical display is not the expected landscape mode. Next: adb shell wm size reset, check dumpsys SurfaceFlinger for the active display mode, reboot if a stale override persists.\n' \
        "${size:-<empty>}"
      return 0
      ;;
  esac

  case $ar in
    0) ;;
    1)
      printf 'refuse\tauto-rotate\taccelerometer_rotation=1 (auto-rotate on): a locked user rotation has no effect while auto-rotate is on (DisplayRotation treats it as USER_ROTATION_FREE). If the UI is rotated, the cause is elsewhere (sensor/panel projection), not a stale user_rotation. Next: turn auto-rotate off and re-run --dry-run; if the UI is still rotated, capture dumpsys display and open an issue.\n'
      return 0
      ;;
    *)
      printf 'refuse\tunknown-auto-rotate\taccelerometer_rotation=%s is not 0/1 (unreadable?). Next: adb shell settings get system accelerometer_rotation and re-run.\n' \
        "${ar:-<empty>}"
      return 0
      ;;
  esac

  case $ur in
    0)
      printf 'noop\n'
      ;;
    1)
      printf 'apply\n'
      ;;
    *)
      printf 'refuse\tunexpected-user-rotation\tuser_rotation=%s (want 1 to explain the rotation, 0 for a no-op): not the inherited-stock pattern this fix targets. Next: adb shell wm user-rotation lock 0 explicitly, or capture dumpsys window displays and open an issue.\n' \
        "${ur:-<empty>}"
      ;;
  esac
}

# --- self-test ----------------------------------------------------------------
selftest_fail=0

# st_case <name> <expect> <sf> <ur> <ar> <size>
st_case() {
  local name=$1 expect=$2 out verdict
  shift 2
  out=$(rotation_decision "$@")
  verdict=${out%%$'\t'*}
  if [[ $verdict == refuse ]]; then
    local rest=${out#refuse$'\t'}
    verdict="${verdict}:${rest%%$'\t'*}"
  fi
  if [[ $verdict == "$expect" ]]; then
    printf '  PASS  %-28s -> %s\n' "$name" "$verdict"
  else
    printf '  FAIL  %-28s -> %s (expected %s)\n' "$name" "$verdict" "$expect"
    selftest_fail=1
  fi
  return 0
}

run_self_test() {
  printf 'rotation_decision self-test (no adb, no device, no lease):\n'
  # stale stock values: portrait-natural stock locked ROTATION_90 in /data
  st_case "stale stock values" apply ORIENTATION_90 1 0 1920x1080
  # already fixed / wiped flash: GSI defaults
  st_case "already 0" noop ORIENTATION_90 0 0 1920x1080
  st_case "wiped flash (GSI defaults)" noop ORIENTATION_90 0 0 1920x1080
  # refusals
  st_case "auto-rotate on" refuse:auto-rotate ORIENTATION_90 1 1 1920x1080
  st_case "forced portrait size" refuse:forced-portrait-size ORIENTATION_90 1 0 1080x1920
  st_case "SF prop missing" refuse:no-sf-prop "" 1 0 1920x1080
  st_case "SF prop ORIENTATION_0" refuse:no-sf-prop ORIENTATION_0 1 0 1920x1080
  st_case "odd size (docked)" refuse:unexpected-size ORIENTATION_90 1 0 1280x720
  st_case "odd user_rotation" refuse:unexpected-user-rotation ORIENTATION_90 3 0 1920x1080
  st_case "unreadable auto-rotate" refuse:unknown-auto-rotate ORIENTATION_90 0 unknown 1920x1080
  if ((selftest_fail)); then
    printf 'self-test: FAIL\n'
    return 1
  fi
  printf 'self-test: PASS (10/10)\n'
  return 0
}

if ((SELF_TEST)); then
  run_self_test
  exit $?
fi

# --- live state ---------------------------------------------------------------
# The remote shell prints "@@KEY=value" markers, so one adb round trip returns
# every scalar and the dumpsys excerpts in a single batch.
read_snapshot() {
  # The remote snippet must reach the device shell unexpanded: single quotes.
  # shellcheck disable=SC2016
  RAW=$(dev_adb shell '
    echo "@@SF=$(getprop ro.surface_flinger.primary_display_orientation)"
    echo "@@HWC=$(getprop debug.sf.ignore_hwc_physical_display_orientation)"
    echo "@@SIZE=$(wm size | tr "\n" "|")"
    echo "@@DENSITY=$(wm density | tr "\n" "|")"
    echo "@@UR=$(settings get system user_rotation)"
    echo "@@AR=$(settings get system accelerometer_rotation)"
    echo "@@DSF=$(settings get global display_size_forced)"
    echo "@@WINDOWS"
    dumpsys window displays 2>/dev/null | grep -iE "rotation|orientation|init=|cur=" | head -40
    echo "@@DISPLAY"
    dumpsys display 2>/dev/null | grep -iE "rotation|orientation" | head -40
  ' | tr -d '\r')
}

parse_snapshot() {
  SF_PROP=""; HWC_PROP=""; WM_SIZE_RAW=""; WM_DENSITY_RAW=""
  UR_RAW=""; AR_RAW=""; DSF_RAW=""
  WINDOW_LINES=(); DISPLAY_LINES=()
  local line section=''
  while IFS= read -r line; do
    case $line in
      @@SF=*) SF_PROP=${line#@@SF=} ;;
      @@HWC=*) HWC_PROP=${line#@@HWC=} ;;
      @@SIZE=*) WM_SIZE_RAW=${line#@@SIZE=} ;;
      @@DENSITY=*) WM_DENSITY_RAW=${line#@@DENSITY=} ;;
      @@UR=*) UR_RAW=${line#@@UR=} ;;
      @@AR=*) AR_RAW=${line#@@AR=} ;;
      @@DSF=*) DSF_RAW=${line#@@DSF=} ;;
      @@WINDOWS) section=window ;;
      @@DISPLAY) section=display ;;
      @@*) ;;
      *)
        case $section in
          window) WINDOW_LINES+=("$line") ;;
          display) DISPLAY_LINES+=("$line") ;;
        esac
        ;;
    esac
  done <<<"$RAW"

  # `settings get` prints "null" when the row does not exist. Both stock and the
  # GSI ship def_user_rotation=0 and def_accelerometer_rotation=false (verified
  # from the stock SettingsProvider.apk and the lineage tree), so an absent row
  # means exactly those defaults.
  USER_ROTATION=${UR_RAW:-null}; [[ $USER_ROTATION == null ]] && USER_ROTATION=0
  ACCEL_ROTATION=${AR_RAW:-null}; [[ $ACCEL_ROTATION == null ]] && ACCEL_ROTATION=0

  # The applied size is the override when one is set, else the physical size.
  WM_SIZE=$(printf '%s' "$WM_SIZE_RAW" | awk -F'|' '
    { for (i = 1; i <= NF; i++) {
        if ($i ~ /^Override size:/) { sub(/^Override size:[ ]*/, "", $i); ov = $i }
        if ($i ~ /^Physical size:/) { sub(/^Physical size:[ ]*/, "", $i); ph = $i } } }
    END { print (ov != "" ? ov : ph) }')
}

print_state() {
  local label=$1 l
  printf '\n--- rotation state (%s) ---\n' "$label"
  printf 'ro.surface_flinger.primary_display_orientation = %s\n' "${SF_PROP:-<empty>}"
  printf 'debug.sf.ignore_hwc_physical_display_orientation = %s\n' "${HWC_PROP:-<empty>}"
  printf 'wm size            : %s\n' "${WM_SIZE_RAW:-<empty>}"
  printf 'wm size (effective): %s\n' "${WM_SIZE:-<empty>}"
  printf 'wm density         : %s\n' "${WM_DENSITY_RAW:-<empty>}"
  printf 'settings get system user_rotation          = %s (effective %s)\n' "${UR_RAW:-<empty>}" "$USER_ROTATION"
  printf 'settings get system accelerometer_rotation = %s (effective %s)\n' "${AR_RAW:-<empty>}" "$ACCEL_ROTATION"
  printf 'settings get global display_size_forced    = %s\n' "${DSF_RAW:-<empty>}"
  printf 'dumpsys window displays (rotation/orientation lines):\n'
  if ((${#WINDOW_LINES[@]})); then
    for l in "${WINDOW_LINES[@]}"; do printf '  %s\n' "$l"; done
  else
    printf '  <none matched>\n'
  fi
  printf 'dumpsys display (rotation/orientation lines):\n'
  if ((${#DISPLAY_LINES[@]})); then
    for l in "${DISPLAY_LINES[@]}"; do printf '  %s\n' "$l"; done
  else
    printf '  <none matched>\n'
  fi
}

# --- main ---------------------------------------------------------------------
DEV_SERIAL=$(dev_resolve_serial "$SERIAL")
log "device $DEV_SERIAL"

read_snapshot
parse_snapshot
verdict=$(rotation_decision "$SF_PROP" "$USER_ROTATION" "$ACCEL_ROTATION" "$WM_SIZE")
decision=${verdict%%$'\t'*}

if ((APPLY)); then
  print_state before
else
  print_state current
fi

case $decision in
  apply)
    if ((!APPLY)); then
      printf '\nverdict: APPLY - the primary display is locked to ROTATION_90 by an inherited user_rotation=1 (stock /data) on a landscape-native panel, i.e. a 90-degree rotated UI.\n'
      printf 'next:    scripts/device/lock.sh run <owner> -- scripts/device/fix-rotation.sh --apply\n'
      printf '         (changes only Settings.System user_rotation: 1 -> 0, via wm user-rotation lock 0)\n'
      log "dry-run: nothing changed"
      exit 0
    fi
    before_ur=$USER_ROTATION before_ar=$ACCEL_ROTATION before_size=$WM_SIZE
    before_window=${WINDOW_LINES[0]:-}
    log "applying: adb shell wm user-rotation lock 0"
    dev_adb shell wm user-rotation lock 0
    read_snapshot
    parse_snapshot
    print_state after
    printf '\nmeasured: user_rotation %s -> %s, accelerometer_rotation %s -> %s, wm size %s -> %s\n' \
      "$before_ur" "$USER_ROTATION" "$before_ar" "$ACCEL_ROTATION" "$before_size" "$WM_SIZE"
    if [[ -n $before_window && ${WINDOW_LINES[0]:-} != "$before_window" ]]; then
      printf 'measured: dumpsys window displays %s -> %s\n' "$before_window" "${WINDOW_LINES[0]}"
    fi
    after=$(rotation_decision "$SF_PROP" "$USER_ROTATION" "$ACCEL_ROTATION" "$WM_SIZE")
    case ${after%%$'\t'*} in
      noop)
        ok "user rotation locked to 0; ROTATION_0 is upright landscape on this panel"
        ;;
      apply)
        die "wm user-rotation lock 0 did not take effect (user_rotation still 1); the device may be in a frozen state. Next: adb shell dumpsys window displays; scripts/device/logs.sh"
        ;;
      *)
        warn "applied, but the state no longer matches the expected pattern:"
        printf '  %s\n' "${after#*$'\t'}"
        exit 1
        ;;
    esac
    ;;
  noop)
    ok "nothing to do: user_rotation is already 0 (accelerometer_rotation=0, logical display 1920x1080 landscape)"
    ;;
  *)
    printf '\nverdict: REFUSE - nothing was changed.\n'
    # decision\t<tag>\t<text>
    printf 'reason:  %s\n' "${verdict#*$'\t'*$'\t'}"
    exit 1
    ;;
esac
