#!/bin/bash
# Prepare the emulator preview build inputs.
#
# The canonical sources live one level up (src/, res/, AndroidManifest.xml) and
# are compiled by Soong in the AOSP tree.  The preview build cannot consume two
# things from that tree:
#   * res/values-v36/themes.xml inherits SettingsLib's
#     Theme.SubSettingsBase.Expressive, which only exists in the platform tree;
#   * android:sharedUserId="android.uid.system" requires the platform key.
# This script copies the resources without the v36 overlay and writes the
# manifest without sharedUserId / package (the Gradle namespace supplies it).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
out="$here/build/prepared"

rm -rf "$out"
mkdir -p "$out"

if command -v rsync >/dev/null 2>&1; then
    rsync -a --exclude 'values-v36' "$root/res/" "$out/res/"
else
    mkdir -p "$out/res"
    (cd "$root/res" && tar --exclude='values-v36' -cf - .) | (cd "$out/res" && tar -xf -)
fi

sed -e '/android:sharedUserId/d' \
    -e '/package="org.ashfall.parts"/d' \
    "$root/AndroidManifest.xml" > "$out/AndroidManifest.xml"

echo "prepared $out"
