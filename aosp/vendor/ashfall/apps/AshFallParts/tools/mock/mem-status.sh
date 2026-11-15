#!/bin/bash
# Feed the Memory page with a synthetic ashfall.status.mem.* cycle.
#
# The page reads ashfall.status.mem.{swap_mb,swap_total_kb,swap_used_kb}, which
# only the real ashfall-hwd publishes on the handheld.  The stock emulator has
# neither the daemon nor the ashfall.* property contexts, so this feeder writes
# the three values itself once a second - the page's own tick - the way the
# daemon would report them.
#
# The feeder also reads the setting the page writes,
# persist.ashfall.mem.swap_mb, and reports it as applied, so the row does not
# show the requested/applied mismatch that only the missing daemon causes.  The
# app still writes every persist.* value itself; this script never writes one.
#
#   tools/mock/mem-status.sh start [normal|unavailable]
#   tools/mock/mem-status.sh stop      stop it, leaving the last values
#   tools/mock/mem-status.sh status    print the feeder state and the values
#
# "unavailable" publishes -1 for the applied size and the two live values: the
# documented swapfile-unavailable state, which has to be checked on the emulator
# too.
#
# The feeder runs detached on the device (a host-side loop would stop with the
# adb session).  Both it and the app need a permissive policy on a stock
# emulator - `ashfall.*` has no property context there and a debug /data APK is
# not system_app - so run `adb shell setenforce 0` while exercising the page and
# `setenforce 1` afterwards.
set -euo pipefail
ADB=${ADB:-adb}
REMOTE=/data/local/tmp/ashfall-mem-status.sh

# The device-side helper: `run` drives the cycle, `stop` ends it, `status`
# prints the state.  stop stays on the device so it can check the pid against
# /proc before killing, and never uses `pkill -f` (that pattern also matches
# the invoking command line there).
helper() {
    cat <<'SH'
#!/system/bin/sh
PIDFILE=/data/local/tmp/ashfall-mem-status.pid
MODEFILE=/data/local/tmp/ashfall-mem-status.mode
MODE=${MODE:-normal}

stop_feeder() {
    if [ -s "$PIDFILE" ]; then
        pid=$(cat "$PIDFILE")
        if tr -d '\000' < "/proc/$pid/cmdline" 2>/dev/null | grep -q ashfall-mem-status; then
            kill "$pid" 2>/dev/null && echo "stopped (pid $pid)"
        else
            echo "stale pid file ($pid is not the feeder)"
        fi
    else
        echo "not running"
    fi
    rm -f "$PIDFILE"
}

publish() {
    setprop "ashfall.status.mem.$1" "$2" 2>/dev/null || true
}

case "${1:-}" in
    stop)
        stop_feeder
        exit 0
        ;;
    status)
        if [ -s "$PIDFILE" ]; then
            pid=$(cat "$PIDFILE")
            if tr -d '\000' < "/proc/$pid/cmdline" 2>/dev/null | grep -q ashfall-mem-status; then
                echo "running (pid $pid, mode $(cat "$MODEFILE" 2>/dev/null || echo normal))"
            else
                echo "stale pid file"
            fi
        else
            echo "stopped"
        fi
        for key in swap_mb swap_total_kb swap_used_kb; do
            echo "$key=$(getprop "ashfall.status.mem.$key")"
        done
        exit 0
        ;;
esac

echo $$ > "$PIDFILE"
echo "$MODE" > "$MODEFILE"

if [ "$MODE" = unavailable ]; then
    while true; do
        for key in swap_mb swap_total_kb swap_used_kb; do
            publish "$key" -1
        done
        sleep 1
    done
fi

# A 60 s cycle: the swapfile fills slowly, so the status row and the overflow
# warning have plausible numbers to show.
i=0
while true; do
    t=$((i % 60))
    swap=$(getprop persist.ashfall.mem.swap_mb)
    case "$swap" in ''|*[!0-9]*) swap=0 ;; esac
    if [ "$swap" -gt 0 ]; then
        publish swap_total_kb "$((swap * 1024))"
        publish swap_used_kb "$((swap * (10 + t / 2) / 100 * 1024))"
    else
        publish swap_total_kb 0
        publish swap_used_kb 0
    fi
    publish swap_mb "$swap"
    i=$((i + 1))
    sleep 1
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
        MODE=${2:-normal}
        case "$MODE" in
            normal|unavailable) ;;
            *)
                echo "usage: $0 start [normal|unavailable]" >&2
                exit 2
                ;;
        esac
        root_check
        push_helper
        "$ADB" shell "sh $REMOTE stop" >/dev/null 2>&1 || true
        # setsid detaches the loop from the adb shell's session: a closing pty
        # must not take the cycle with it (the feeder records its own pid).
        "$ADB" shell "setsid env MODE=$MODE sh $REMOTE >/dev/null 2>&1 < /dev/null &"
        sleep 1
        if [ -z "$("$ADB" shell getprop ashfall.status.mem.swap_mb | tr -d '\r')" ]; then
            echo "error: the feeder published nothing." >&2
            echo "       'ashfall.*' has no property context on a stock image, so" >&2
            echo "       both the feeder and the app need 'adb root' and a" >&2
            echo "       permissive policy: adb shell setenforce 0" >&2
            "$ADB" shell "sh $REMOTE stop" >/dev/null 2>&1 || true
            exit 1
        fi
        echo "memory status feeder started ($MODE)"
        ;;
    stop)
        root_check
        push_helper
        echo "memory status feeder $("$ADB" shell "sh $REMOTE stop" | tr -d '\r' | head -1)"
        ;;
    status)
        root_check
        push_helper
        "$ADB" shell "sh $REMOTE status" | tr -d '\r'
        ;;
    *)
        echo "usage: $0 start [normal|unavailable]|stop|status" >&2
        exit 2
        ;;
esac
