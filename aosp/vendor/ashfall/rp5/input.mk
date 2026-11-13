# AshFall RP5 support: input (phase 4). Owned by the phase-4 'input' worker.
# Included from rp5/rp5.mk. Keep RP5 input changes in this file and in
# vendor/ashfall/rp5/input/ (overlays, configs, sepolicy, sources).
#
# The RP5 gamepad is not USB/HID: an MCU on /dev/ttyHS1 reports buttons, two
# sticks and two triggers in 0xA5D35A3D frames.  Stock Android runs the bridge
# from the stock system image; this daemon (source in rp5/input/) injects the
# same data through /dev/uinput so it survives a GSI system replacement.
PRODUCT_PACKAGES += ashfall-rp5-input
