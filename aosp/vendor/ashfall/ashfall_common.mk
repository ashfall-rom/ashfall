#
# Copyright (C) 2026 The AshFall Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# AshFall variables shared by the GSI product (phase 2) and the future RP5
# device build (phase 4). Anything that must be identical in both builds
# belongs here; per-product settings belong in products/<name>.mk.
#
# `scripts/build.sh` exports ASH_VERSION (from config/ashfall.conf) and
# ASH_BUILD_ID (the build's id / artifact directory) into the build
# environment, and make imports environment variables, so no generated
# makefile is needed. A plain `lunch` + `m` without those exports still works
# and falls back to the values below.

ASHFALL_VERSION ?= $(if $(strip $(ASH_VERSION)),$(strip $(ASH_VERSION)),0.1.0-dev)
ASHFALL_BUILD_ID ?= $(if $(strip $(ASH_BUILD_ID)),$(strip $(ASH_BUILD_ID)),local)

PRODUCT_PRODUCT_PROPERTIES += \
    ro.ashfall.version=$(ASHFALL_VERSION) \
    ro.ashfall.build.id=$(ASHFALL_BUILD_ID)

# ---------------------------------------------------------------------------
# Hook points for later phases. Each line is intentionally commented out until
# the directory it points at actually contains content, so that a plain GSI
# build never depends on phase-3/4 work.
# ---------------------------------------------------------------------------
# Shared packages (fan control, RGB, gamepad, ...): phase 5.
# PRODUCT_PACKAGES += \
#     AshFallSettings

# Static framework/package overlays (rotation default, strings, ...): phase 4.
# PRODUCT_PACKAGE_OVERLAYS += vendor/ashfall/overlay

# Device-specific sepolicy (SM8250 / stock 5.15 GKI quirks): phase 4. This is a
# board-level variable, which works from a product makefile for the same
# reason GSI_FILE_SYSTEM_TYPE does (product config is parsed first).
# BOARD_SEPOLICY_DIRS += vendor/ashfall/sepolicy

# Phase 5: SELinux for ashfall-hwd and the ashfall-rp5-input extensions.
#
# A GSI replaces /system and /system_ext but not the stock vendor partition, so
# BOARD_SEPOLICY_DIRS (vendor policy) would never be flashed.  system_ext
# policy is built by us and loaded together with the platform policy, so new
# domains, sysfs types (genfs_contexts), file labels and property types live
# there.  See aosp/vendor/ashfall/sepolicy/README.md.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/ashfall/sepolicy

# AshFall UI defaults and apps (see ui.mk, apps.mk).
include vendor/ashfall/ui.mk
include vendor/ashfall/apps.mk

# RP5 hardware support (phase 4): display, input, usb - see rp5/rp5.mk.
include vendor/ashfall/rp5/rp5.mk

# ---------------------------------------------------------------------------
# Bluetooth profile services (issue #106).
#
# A17 decides which profile services start at Bluetooth init from the
# bluetooth.profile.* system properties
# (system/libsysprop/srcs/android/sysprop/BluetoothProperties.sysprop). Only
# GattService and HearingAidService treat an unset property as enabled
# (`.orElse(true)`); every other service returns `.orElse(false)`, so a product
# that sets none of them starts GATT + HEARING_AID only. Discovery and bonding
# still work (GATT/SMP for LE, SSP/SDP for BR/EDR), but no profile service
# opens a channel, so L2CAP finds no channel to keep and tears the link down
# after a few seconds (logged as `HCI_ERR_PEER_USER` /
# `l2c_link_timeout All channels closed`); the accessory then cannot reconnect.
#
# AOSP's GSI inherits no profile properties; the emulator and Cuttlefish set
# their own in device product prop files
# (device/generic/goldfish/product/bluetooth.prop,
# device/google/cuttlefish/shared/phone/product.prop). A real phone product
# sets the classic phone roles: A2DP source, AVRCP target, HFP AG, HID host,
# plus the harmless OPP/MAP/PBAP/PAN/MCP servers. AVRCP controller is added
# here because the RP5 should also control playback on a speaker.
#
# System properties, not product properties: this GSI sets
# TARGET_COPY_OUT_PRODUCT := system/product, so both would end up inside
# system.img in this build, but /system/build.prop stays ours regardless of
# whether the flashing flow keeps or deletes the stock product partition, now
# and in the future device build.
#
# Deliberately NOT enabled: BAP/BASS/broadcast audio, VCP, HAP and CSIP, nor
# hfp.hf (phone as a headset) or hid.device (phone as a peripheral). LE Audio is
# unverified on the RP5 (docs/stock-analysis.md: the standard Bluetooth audio
# HAL is AIDL v2; the HIDL QTI extension is not the A17 audio path). ASHA is a
# separate LE audio path and is already on by platform default, so it is left
# unset instead of being turned on here. The explicit `=false` lines document
# intent and keep a later product overlay or upstream default change from
# silently turning LE Audio on.
#
# A2DP offload stays at the vendor default. If a speaker connects but stays
# silent, follow the offload diagnostic in docs/rp5/bluetooth.md instead of
# disabling offload unconditionally.
PRODUCT_SYSTEM_PROPERTIES += \
    bluetooth.profile.a2dp.source.enabled=true \
    bluetooth.profile.avrcp.target.enabled=true \
    bluetooth.profile.avrcp.controller.enabled=true \
    bluetooth.profile.hid.host.enabled=true \
    bluetooth.profile.hfp.ag.enabled=true \
    bluetooth.profile.gatt.enabled=true \
    bluetooth.profile.bas.client.enabled=true \
    bluetooth.profile.opp.enabled=true \
    bluetooth.profile.map.server.enabled=true \
    bluetooth.profile.pbap.server.enabled=true \
    bluetooth.profile.pan.nap.enabled=true \
    bluetooth.profile.pan.panu.enabled=true \
    bluetooth.profile.mcp.server.enabled=true \
    bluetooth.profile.bap.broadcast.assist.enabled=false \
    bluetooth.profile.bap.broadcast.source.enabled=false \
    bluetooth.profile.bap.unicast.client.enabled=false \
    bluetooth.profile.ccp.server.enabled=false \
    bluetooth.profile.hap.client.enabled=false \
    bluetooth.profile.csip.set_coordinator.enabled=false \
    bluetooth.profile.vcp.controller.enabled=false

# Shared (GSI + device) system properties go above this line.
