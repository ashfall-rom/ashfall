#
# AshFall RP5 touchscreen IDC.
#
# The RP5 panel is natively portrait (1080x1920) and the GSI declares it
# installed rotated 90 degrees via
# ro.surface_flinger.primary_display_orientation=ORIENTATION_90 (see
# ../../display.mk), so the logical display is landscape (1920x1080) while the
# focaltech panel still reports raw coordinates in its 1080x1920 portrait
# frame. This IDC rotates the raw coordinates to match.
#
# Same values as the community lineage-24.0 RP5 tree
# (android_device_retroidpocket_RP5/configs/idc/fts_ts.idc). The displayId is
# the primary panel's stable unique id (Built-in Screen, HWC display 0,
# port 129).
#
device.internal = 1

touch.deviceType = touchScreen
touch.orientation = ORIENTATION_270
touch.displayId = local:4630946482288158081
