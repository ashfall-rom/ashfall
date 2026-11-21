#!/usr/bin/env bash
# Measure RP5 idle drain, suspend ratio and wake reasons (issue #233).
#
# The device must be **discharging and unplugged**: with the charger attached
# the USB PHY never reaches L2 and the AP cannot suspend at all (#255), so an
# idle measurement would read 0 % suspend by construction.  Leave the panel off
# and the cable out for the whole run; the only traffic is one small sample
# every --interval minutes plus a final history pull.
#
# Output: per-sample markers and the full batterystats history in --out, then a
# readable summary from drain-summary.py plus summary.json for comparisons.
#
# Usage:
#   scripts/bench/idle-drain.sh [--minutes N] [--interval N] [--out DIR]
#                               [--label NAME] [--connect HOST:PORT]
#
# The caller holds the RP5 lease for the whole run, with a TTL above --minutes:
#   scripts/device/lock.sh run drain-233 --ttl 3000 -- \
#       scripts/bench/idle-drain.sh --minutes 30 --out /tmp/opencode/drain-base
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN="$ROOT/scripts/device/run.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MINUTES=60
INTERVAL=5
OUT=""
LABEL="idle"
CONNECT=""

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --minutes) MINUTES="$2"; shift 2 ;;
        --minutes=*) MINUTES="${1#*=}"; shift ;;
        --interval) INTERVAL="$2"; shift 2 ;;
        --interval=*) INTERVAL="${1#*=}"; shift ;;
        --out) OUT="$2"; shift 2 ;;
        --out=*) OUT="${1#*=}"; shift ;;
        --label) LABEL="$2"; shift 2 ;;
        --label=*) LABEL="${1#*=}"; shift ;;
        --connect) CONNECT="$2"; shift 2 ;;
        --connect=*) CONNECT="${1#*=}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ $MINUTES =~ ^[0-9]+$ && $MINUTES -ge 1 ]] || { echo "error: --minutes must be >= 1" >&2; exit 2; }
[[ $INTERVAL =~ ^[0-9]+$ && $INTERVAL -ge 1 ]] || { echo "error: --interval must be >= 1" >&2; exit 2; }
[[ -n $OUT ]] || OUT="$ROOT/out/bench/idle-drain-$(date -u +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

dev_shell() { # dev_shell <command>: one adb shell round trip
    "$RUN" adb shell "$1"
}

sample() { # sample <index>
    local index=$1 file
    file=$(printf '%s/sample-%03d.txt' "$OUT" "$index")
    {
        dev_shell 'printf "@@wall=%s\n" "$(date +%s)";
                    printf "@@local=%s\n" "$(date "+%Y-%m-%d %H:%M:%S")";
                    printf "@@level=%s\n" "$(dumpsys battery | sed -n "s/^  level: //p")";
                    printf "@@status=%s\n" "$(dumpsys battery | sed -n "s/^  status: //p")";
                    printf "@@counter=%s\n" \
                        "$(cat /sys/class/power_supply/battery/charge_counter 2>/dev/null)";
                    printf "@@uptime=%s\n" "$(cut -d" " -f1 /proc/uptime)"'
    } | tr -d '\r' >"$file"
    # A dropped link must not silently turn an hour into one sample.
    grep -q '^@@wall=' "$file" || {
        echo "error: sample $index produced no data ($file); check the adb link" >&2
        exit 1
    }
    local level counter
    level=$(sed -n 's/^@@level=//p' "$file")
    counter=$(sed -n 's/^@@counter=//p' "$file")
    printf 'sample %-3s level=%s%% counter=%s uAh\n' "$index" "${level:-?}" "${counter:-?}" >&2
}

if [[ -n $CONNECT ]]; then
    "$RUN" adb connect "$CONNECT" >&2
fi

status=$(dev_shell 'dumpsys battery | sed -n "s/^  status: //p"' | tr -d '\r')
if [[ $status == 2 ]]; then
    echo "error: the device is on the charger (status=2); unplug it first (#255)" >&2
    exit 1
fi
[[ $status == 3 ]] || echo "warning: battery status=$status (3 = discharging)" >&2

echo "$LABEL" >"$OUT/label.txt"
echo "measuring $MINUTES min, sampling every $INTERVAL min -> $OUT" >&2

index=1
sample "$index"
deadline=$(( $(date +%s) + MINUTES * 60 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    remaining=$(( deadline - $(date +%s) ))
    (( remaining <= 0 )) && break
    gap=$(( INTERVAL * 60 ))
    (( gap > remaining )) && gap=$remaining
    sleep "$gap"
    index=$(( index + 1 ))
    sample "$index"
done

# The full history once: every runtime state change and wake reason is in it.
dev_shell 'dumpsys batterystats --history' | tr -d '\r' >"$OUT/history.txt"
if ! grep -qE '^ *[0-9]{2}-[0-9]{2} ' "$OUT/history.txt"; then
    echo "error: no history lines in $OUT/history.txt" >&2
    exit 1
fi

python3 "$HERE/drain-summary.py" "$OUT"
