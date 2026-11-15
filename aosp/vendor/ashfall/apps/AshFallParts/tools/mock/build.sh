#!/bin/bash
# Build and install the emulator mock daemon (test tooling only, never shipped).
#
#   tools/mock/build.sh          build + push the binary
#   tools/mock/build.sh --run    ... and (re)start it on the device
#
# The binary is built for the emulator's x86_64 ABI.  For an arm64 device use
# ABI=aarch64 (the NDK target triple is adjusted below).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-/root/android-sdk}
ndk_root="$sdk/ndk"
ndk=$(ls -d "$ndk_root"/* 2>/dev/null | sort -V | tail -1)
[ -n "$ndk" ] || { echo "no NDK under $ndk_root" >&2; exit 1; }

abi=${ABI:-x86_64}
api=${API:-33}
case "$abi" in
    x86_64) triple="x86_64-linux-android$api" ;;
    aarch64) triple="aarch64-linux-android$api" ;;
    *) echo "unsupported ABI $abi" >&2; exit 1 ;;
esac
clang="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/$triple-clang"
[ -x "$clang" ] || { echo "no clang at $clang" >&2; exit 1; }

out="$here/build"
mkdir -p "$out"
"$clang" -O2 -Wall -Wextra -o "$out/ashfall-mock" "$here/AshfallMockDaemon.c" -lm
adb push "$out/ashfall-mock" /data/local/tmp/ashfall-mock >/dev/null
adb shell chmod 755 /data/local/tmp/ashfall-mock
echo "pushed /data/local/tmp/ashfall-mock"

if [ "${1:-}" = "--run" ]; then
    adb root >/dev/null 2>&1 || true
    sleep 1
    adb shell "pkill -x ashfall-mock || true"
    adb shell "setsid /data/local/tmp/ashfall-mock > /data/local/tmp/ashfall-mock.log 2>&1 < /dev/null &"
    sleep 1
    # A debug /data APK is not system_app, so it cannot connect to the 0755
    # socket the mock creates; the real init socket is 0660 system:system.
    adb shell chmod 666 /dev/socket/ashfall_input || true
    adb shell "ps -A | grep ashfall-mock || cat /data/local/tmp/ashfall-mock.log"
fi
