# AshFall RP5 support: boot branding.
#
# The boot animation ships as a prebuilt bootanimation.zip (stored entries,
# 1920x1080 @ 30 fps, 141 intro + 360 loop frames, ~37 MB). It is authored
# landscape because SurfaceFlinger reports the RP5 panel as 1920x1080 logical
# even though the panel is 1080x1920 native (docs/rp5/display.md). If a build
# comes up sideways, regenerate the zip with `--size 1080x1920`
# (assets/brand/README.md) rather than editing this file.
#
# Source of truth is assets/brand/bootanimation/generate.py. The zip is checked
# in because the build host has no numpy/Pillow/rsvg-convert, and a boot
# animation has to be present in system/media at image build time.
PRODUCT_COPY_FILES += \
    vendor/ashfall/rp5/brand/bootanimation.zip:system/media/bootanimation.zip
