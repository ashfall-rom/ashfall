#!/bin/bash
# Turn the AshFall Parts GPU/Performance preview mock on or off.
#
# The app normally writes persist.ashfall.perf.gpu_max_mhz and reads the two
# ashfall.status.perf.* values published by ashfall-hwd.  Neither the daemon
# nor the ashfall.* property contexts exist on the stock emulator image, so the
# Performance section has an in-process fake daemon (test-only) that implements
# the phase-6 contract: the request is clamped to the highest available table
# step and the phase-5 throttle override clamps it to stock 670 MHz.
#
#   tools/mock/gpu-perf.sh on       enable the mock (stops the app)
#   tools/mock/gpu-perf.sh off      back to the real properties (stops the app)
#   tools/mock/gpu-perf.sh status   print the flag
#
# The flag is a non-persistent debug.* property, so a reboot also disables the
# mock.  It only takes effect on debuggable images (ro.debuggable=1).  The mock
# state lives in the app process and starts at stock / off; reopen the app
# after toggling.
set -euo pipefail
ADB=${ADB:-adb}
PKG=org.ashfall.parts
PROP=debug.ashfall.parts.gpu_mock

case "${1:-}" in
    on)
        "$ADB" shell setprop "$PROP" 1
        "$ADB" shell am force-stop "$PKG" || true
        echo "GPU/Performance mock enabled (in-process fake ashfall-hwd)"
        ;;
    off)
        "$ADB" shell setprop "$PROP" 0
        "$ADB" shell am force-stop "$PKG" || true
        echo "GPU/Performance mock disabled (real ashfall.* properties)"
        ;;
    status)
        "$ADB" shell getprop "$PROP"
        ;;
    *)
        echo "usage: $0 on|off|status" >&2
        exit 2
        ;;
esac
