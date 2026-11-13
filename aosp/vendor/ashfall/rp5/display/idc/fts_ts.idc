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
# The community lineage-24.0 RP5 tree uses ORIENTATION_270 with its 4.19
# kernel/vendor; on the stock vendor + GSI that left touch rotated 180 degrees
# (hardware test 2026-11-13), so this uses ORIENTATION_90. The displayId is
# the primary panel's stable unique id (Built-in Screen, HWC display 0,
# port 129).
#
device.internal = 1

touch.deviceType = touchScreen
touch.orientation = ORIENTATION_90
touch.displayId = local:4630946482288158081
