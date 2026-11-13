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

# Shared (GSI + device) system properties go above this line.
