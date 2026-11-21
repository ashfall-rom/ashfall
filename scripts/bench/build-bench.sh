#!/usr/bin/env bash
# Build ashfall-bench for the RP5 with the local Android NDK (issue #130).
#
# The binary is a plain aarch64 PIE pushed to /data/local/tmp; it is not part
# of the ROM image.  Usage:
#   scripts/bench/build-bench.sh [--ndk DIR] [--api 30] [--out PATH]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NDK="${ANDROID_NDK_HOME:-}"
API=30
OUT="$ROOT/out/bench/ashfall-bench"

while [ $# -gt 0 ]; do
    case "$1" in
        --ndk) NDK="$2"; shift 2 ;;
        --api) API="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$NDK" ]; then
    for d in "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/root/android-sdk}}"/ndk/*; do
        [ -d "$d" ] && NDK="$d"
    done
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "error: no Android NDK found; set ANDROID_NDK_HOME or pass --ndk" >&2
    exit 1
fi

CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || { echo "error: $CC missing" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
"$CC" -O2 -fno-fast-math -Wall -Wextra -o "$OUT" "$ROOT/scripts/bench/ashfall-bench.c" \
    -lEGL -lGLESv3 -landroid -llog
file "$OUT"
echo "built $OUT"
