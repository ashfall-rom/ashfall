#!/bin/bash
# Turn the AshFall Parts Power preview mock on or off.
#
# The Power page writes persist.ashfall.perf.{cpu_max_*,cpu_gov,gpu_max_mhz}
# and reads ashfall.status.perf.{power_mw,cpu_mhz_*,gpu_mhz}, which only
# ashfall-hwd publishes on the handheld.  Neither the daemon nor the ashfall.*
# property contexts exist on the stock emulator image, so the page has an
# in-process fake daemon (test-only) that implements the phase-6 contract:
# the caps and the governor are applied immediately, the GPU request is clamped
# to the highest available table step, the throttle override clamps it to stock
# 670 MHz, and the status values follow a 60 s synthetic cycle with a charging
# window in which power is -1 (a gap in the graph).
#
#   tools/mock/power-perf.sh on       enable the mock (stops the app)
#   tools/mock/power-perf.sh off      back to the real properties (stops the app)
#   tools/mock/power-perf.sh status   print the flag
#
# The flag is a non-persistent debug.* property, so a reboot also disables the
# mock.  It only takes effect on debuggable images (ro.debuggable=1) and a user
# build ignores it.  The mock state lives in the app process and starts at
# stock, so reopen the app after toggling.  Unlike tools/mock/fan-status.sh this
# feeder needs no root and no permissive SELinux: it never writes a property.
set -euo pipefail
ADB=${ADB:-adb}
PKG=org.ashfall.parts
PROP=debug.ashfall.parts.power_mock

case "${1:-}" in
    on)
        "$ADB" shell setprop "$PROP" 1
        "$ADB" shell am force-stop "$PKG" || true
        echo "Power mock enabled (in-process fake ashfall-hwd)"
        ;;
    off)
        "$ADB" shell setprop "$PROP" 0
        "$ADB" shell am force-stop "$PKG" || true
        echo "Power mock disabled (real ashfall.* properties)"
        ;;
    status)
        "$ADB" shell getprop "$PROP"
        ;;
    *)
        echo "usage: $0 on|off|status" >&2
        exit 2
        ;;
esac
