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

# AshFall GSI for the Retroid Pocket 5 (SM8250 "kona", kernel 4.19).
#
# Inherits LineageOS' vanilla arm64 GSI:
#   vendor/lineage/build/target/product/lineage_gsi_arm64.mk   (lineage-24.0)
# which in turn inherits device/generic/common/gsi_arm64.mk,
# full_base_telephony and vendor/lineage/config/common_full_phone.mk.
#
# Keep PRODUCT_DEVICE := generic_arm64 (inherited): Android resolves the board
# config through TARGET_DEVICE, and the GSI board config lives at
# build/make/target/board/generic_arm64/BoardConfig.mk.

$(call inherit-product, vendor/lineage/build/target/product/lineage_gsi_arm64.mk)
include vendor/ashfall/ashfall_common.mk

PRODUCT_NAME := ashfall_gsi_arm64
PRODUCT_BRAND := AshFall
PRODUCT_MODEL := AshFall GSI arm64
PRODUCT_MANUFACTURER := AshFall

# --- filesystem -------------------------------------------------------------
# The RP5 ships a 4.19 kernel, which has no EROFS support, so the system image
# MUST be ext4. GSI builds read this knob in
# build/make/target/board/BoardConfigGsiCommon.mk:
#     GSI_FILE_SYSTEM_TYPE ?= ext4
#     BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := $(GSI_FILE_SYSTEM_TYPE)
# Product makefiles are parsed before board configs (core/envsetup.mk:
# product_config.mk before board_config.mk), so pinning it here is effective.
# scripts/build.sh asserts `get_build_var BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE`
# == ext4 after lunch and aborts the build otherwise.
GSI_FILE_SYSTEM_TYPE := ext4
