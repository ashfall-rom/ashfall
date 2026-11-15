#!/bin/bash
# Feed the Fan page graph with a synthetic status cycle.
#
# The graph reads ashfall.status.fan.{temp,pct,rpm}, which only the real
# ashfall-hwd publishes on the handheld.  On the stock emulator there is no
# daemon, so this mock writes the three properties itself every 500 ms - the
# exact cadence of the Fan page's live tick - with a 60 s cycle that exercises
# every part of the plot: a 35 -> 84 -> 36 °C ramp, a stepped fan output
# (20 / 55 / 85 %) and an rpm curve that follows the output.
#
#   tools/mock/fan-status.sh start       start the cycle (needs adb root)
#   tools/mock/fan-status.sh stop        stop it, leaving the last values
#   tools/mock/fan-status.sh status      print the feeder state and the values
#
# GAPS=1 makes the tach publish -1 (unavailable) for six ticks of every cycle,
# so the graph's gap handling can be checked; it is off by default.
#
# The feeder runs detached on the device (a host-side loop would stop with the
# adb session) and only writes status properties: the app still writes every
# persist.* value itself.  Both need a permissive policy on a stock emulator -
# `ashfall.*` has no property context there and a debug /data APK is not
# system_app - so run `adb shell setenforce 0` while exercising the page and
# restore `setenforce 1` afterwards.
set -euo pipefail
ADB=${ADB:-adb}
REMOTE=/data/local/tmp/ashfall-fan-status.sh

# The device-side helper: `run` drives the cycle, `stop` ends it, `status`
# prints the state.  Keeping stop/kill on the device lets it verify the pid
# against /proc before killing, and never uses `pkill -f` (that pattern also
# matches the invoking command line there).
helper() {
    cat <<'SH'
#!/system/bin/sh
PIDFILE=/data/local/tmp/ashfall-fan-status.pid

stop_feeder() {
    if [ -s "$PIDFILE" ]; then
        pid=$(cat "$PIDFILE")
        if tr -d '\000' < "/proc/$pid/cmdline" 2>/dev/null | grep -q ashfall-fan-status; then
            kill "$pid" 2>/dev/null && echo "stopped (pid $pid)"
        else
            echo "stale pid file ($pid is not the feeder)"
        fi
    else
        echo "not running"
    fi
    rm -f "$PIDFILE"
}

case "${1:-}" in
    stop)
        stop_feeder
        exit 0
        ;;
    status)
        if [ -s "$PIDFILE" ]; then
            pid=$(cat "$PIDFILE")
            if tr -d '\000' < "/proc/$pid/cmdline" 2>/dev/null | grep -q ashfall-fan-status; then
                echo "running (pid $pid)"
            else
                echo "stale pid file"
            fi
        else
            echo "stopped"
        fi
        echo "temp=$(getprop ashfall.status.fan.temp)"
        echo "pct=$(getprop ashfall.status.fan.pct)"
        echo "rpm=$(getprop ashfall.status.fan.rpm)"
        exit 0
        ;;
esac

# Synthetic ashfall.status.fan.* cycle: 120 ticks of 500 ms = the graph window.
GAPS=${GAPS:-0}
echo $$ > "$PIDFILE"
i=0
while true; do
    t=$((i % 120))
    if [ "$t" -lt 60 ]; then
        temp=$((350 + t * 500 / 60))          # 35.0 -> 84.1 °C (tenths)
    else
        temp=$((850 - (t - 60) * 500 / 60))   # 84.1 -> 35.9 °C
    fi
    if [ "$t" -lt 20 ]; then
        pct=20
    elif [ "$t" -lt 40 ]; then
        pct=55
    else
        pct=85
    fi
    rpm=$((pct * 150 + t % 10 * 40))          # 3 000 .. 13 110 rpm
    if [ "$GAPS" = 1 ] && [ "$t" -lt 6 ]; then
        rpm=-1                                 # tach unavailable: a plot gap
    fi
    setprop ashfall.status.fan.temp "$temp"
    setprop ashfall.status.fan.pct "$pct"
    setprop ashfall.status.fan.rpm "$rpm"
    i=$((i + 1))
    sleep 0.5
done
SH
}

push_helper() {
    local tmp
    tmp=$(mktemp)
    helper > "$tmp"
    "$ADB" push "$tmp" "$REMOTE" >/dev/null
    rm -f "$tmp"
    "$ADB" shell chmod 755 "$REMOTE"
}

root_check() {
    if [ "$("$ADB" shell id -u | tr -d '\r')" != 0 ]; then
        "$ADB" root >/dev/null 2>&1 || true
        "$ADB" wait-for-device
    fi
}

case "${1:-}" in
    start)
        root_check
        push_helper
        "$ADB" shell "sh $REMOTE stop" >/dev/null 2>&1 || true
        # setsid detaches the loop from the adb shell's session: a closing pty
        # must not take the cycle with it (the feeder records its own pid).
        "$ADB" shell "setsid env GAPS=${GAPS:-0} sh $REMOTE >/dev/null 2>&1 < /dev/null &"
        sleep 1
        if [ -z "$("$ADB" shell getprop ashfall.status.fan.temp | tr -d '\r')" ]; then
            echo "error: the feeder published nothing." >&2
            echo "       'ashfall.*' has no property context on a stock image, so" >&2
            echo "       both the feeder and the app need 'adb root' and a" >&2
            echo "       permissive policy: adb shell setenforce 0" >&2
            "$ADB" shell "sh $REMOTE stop" >/dev/null 2>&1 || true
            exit 1
        fi
        echo "fan status feeder started ($("$ADB" shell "sh $REMOTE status" | tr -d '\r' \
            | head -1))"
        ;;
    stop)
        root_check
        push_helper
        echo "fan status feeder $("$ADB" shell "sh $REMOTE stop" | tr -d '\r' | head -1)"
        ;;
    status)
        root_check
        push_helper
        "$ADB" shell "sh $REMOTE status" | tr -d '\r'
        ;;
    *)
        echo "usage: $0 start|stop|status" >&2
        exit 2
        ;;
esac
