#!/bin/bash
# Prepare the emulator preview build inputs.
#
# The canonical sources live one level up (src/, res/, AndroidManifest.xml) and
# are compiled by Soong in the AOSP tree.  The preview build cannot consume two
# things from that tree:
#   * res/values-v36/*.xml inherits SettingsLib's Theme.SubSettingsBase.Expressive
#     and Theme.DeviceDefault.AlertDialog.SettingsLib.Expressive, which only
#     exist in the platform tree;
#   * the SettingsLib surface colours used by the layouts/dialogs do not exist
#     in a plain Gradle project, so this script adds preview-only stand-ins;
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

# The app uses SettingsLib's page/dialog surface colours on the platform
# (res/layout/*.xml, res/values-v36/*).  The preview has no SettingsLib
# resources and runs on API 33, so provide preview-only stand-ins for the two
# names (API 35+ framework surface colours are not available there).
mkdir -p "$out/res/values" "$out/res/values-night"
cat > "$out/res/values/settingslib_surfaces.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="settingslib_materialColorSurfaceContainer">@android:color/background_light</color>
    <color name="settingslib_materialColorSurfaceContainerHigh">@android:color/background_light</color>
</resources>
XML
cat > "$out/res/values-night/settingslib_surfaces.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="settingslib_materialColorSurfaceContainer">@android:color/background_dark</color>
    <color name="settingslib_materialColorSurfaceContainerHigh">@android:color/background_dark</color>
</resources>
XML

sed -e '/android:sharedUserId/d' \
    -e '/package="org.ashfall.parts"/d' \
    "$root/AndroidManifest.xml" > "$out/AndroidManifest.xml"

echo "prepared $out"
