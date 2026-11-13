# AshFall RP5 support: usb (phase 4). Owned by the phase-4 'usb' worker.
# Included from rp5/rp5.mk. Keep RP5 usb changes in this file and in
# vendor/ashfall/rp5/usb/ (overlays, configs, sepolicy, sources).

# GSI-on-stock-vendor USB fix (issue #44).
#
# The stock Android 13 vendor seeds persist.vendor.usb.config with the factory
# diag composition when ro.build.type != user, and its QTI gadget HAL then uses
# that composition for ADB-only requests. The diag FunctionFS is not usable
# under the GSI, so the gadget never binds. ashfall_usb.rc restores the
# standard value before the vendor USB stack starts; see docs/rp5/usb.md.
PRODUCT_COPY_FILES += \
    vendor/ashfall/rp5/usb/ashfall_usb.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/ashfall_usb.rc
