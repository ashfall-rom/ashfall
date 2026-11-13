# AshFall RP5 support: display (phase 4). Owned by the phase-4 'display' worker.
# Included from rp5/rp5.mk. Keep RP5 display changes in this file and in
# vendor/ashfall/rp5/display/ (overlays, configs, sepolicy, sources).
#
# The RP5 panel is 1080x1920 native portrait, but the device is a landscape
# handheld. Stock Android 13 starts WindowManager at ROTATION_90 from a patched
# DisplayRotation that reads persist.panel.orientation=90 (stock
# /system/build.prop). The GSI replaces that system image.
#
# A first attempt set ro.bootanim.set_orientation_logical_0=ORIENTATION_90,
# which only changes WindowManager's *default rotation*. The panel's natural
# orientation stayed portrait, and Launcher3's RotationHelper requests
# SCREEN_ORIENTATION_NOSENSOR on phone-class (smallest width < 600 dp)
# displays, so the home screen still locked to ROTATION_0 (portrait). The panel
# must be natively landscape instead.
#
# Do that at SurfaceFlinger level, exactly like the community lineage-24.0 RP5
# tree (android_device_retroidpocket_RP5, commit fe4f466 "Setup default display
# orientation"): SF applies the physical display orientation to composition and
# reports the logical mode as 1920x1080, so ROTATION_0 *is* landscape. The stock
# vendor composer is HIDL 2.4, which does not advertise
# PhysicalDisplayOrientation, so the sysprop path in
# SurfaceFlinger::getPhysicalDisplayOrientation() is taken; the debug prop also
# covers a future AIDL composer.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.surface_flinger.primary_display_orientation=ORIENTATION_90 \
    debug.sf.ignore_hwc_physical_display_orientation=true

# The focaltech touch controller (input device "fts_ts") still reports the
# panel's 1080x1920 portrait frame. With the display now natively landscape it
# needs the matching IDC, otherwise InputReader scales the portrait raw axes
# into a landscape viewport. Values are the community RP5 tree's
# (touch.orientation=ORIENTATION_270); /system/usr/idc is one of InputReader's
# config paths (frameworks/native/libs/input/InputDevice.cpp) and its files are
# already labelled system_file, readable by system_server/inputflinger.
PRODUCT_COPY_FILES += \
    vendor/ashfall/rp5/display/idc/fts_ts.idc:system/usr/idc/fts_ts.idc
