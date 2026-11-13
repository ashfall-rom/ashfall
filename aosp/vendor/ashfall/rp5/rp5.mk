# AshFall RP5 hardware support for the GSI (phase 4). CONTRACT: orchestrator-owned.
# AshFall targets one device (Retroid Pocket 5) on its stock Android 13 vendor,
# so RP5-specific system-side fixes live here. Each topic has its own file so
# parallel work does not conflict.
include vendor/ashfall/rp5/display.mk
include vendor/ashfall/rp5/input.mk
include vendor/ashfall/rp5/usb.mk
