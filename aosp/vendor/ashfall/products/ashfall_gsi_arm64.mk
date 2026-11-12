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

# AshFall GSI for the Retroid Pocket 5 (SM8250 "kona", stock kernel 5.15 GKI).
# See docs/stock-analysis.md: the STOCK kernel is 5.15.148 with EROFS support.
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

# LineageOS' build system derives LINEAGE_BUILD from a "lineage_" product-name
# prefix (vendor/lineage/build/envsetup.sh:check_product) and gates
# vendor/lineage/config/BoardConfigLineage.mk on it (build/make/core/config.mk).
# Our product is called ashfall_gsi_arm64, so declare it here - product config is
# parsed before that gate. Without it:
#   * the Lineage soong config namespace is never registered and
#     vendor/lineage/build/soong/Android.bp fails the analysis with
#     "unknown variable '$(KERNEL_BUILD_OUT_PREFIX)'";
#   * the AOSP product makefiles take their non-Lineage branches.
LINEAGE_BUILD := ashfall_gsi_arm64

PRODUCT_NAME := ashfall_gsi_arm64
PRODUCT_BRAND := AshFall
PRODUCT_MODEL := AshFall GSI arm64
PRODUCT_MANUFACTURER := AshFall

# --- filesystem -------------------------------------------------------------
# EROFS by default (compressed, and the stock RP5 kernel is 5.15 GKI with EROFS
# support). The stock super group leaves ~4.2 GiB for system.img, so the
# compressed image matters. ext4 stays available as a fallback for kernels
# without EROFS (`scripts/build.sh --fs ext4`).
#
# GSI builds read this knob in
# build/make/target/board/BoardConfigGsiCommon.mk:
#     GSI_FILE_SYSTEM_TYPE ?= ext4          # AOSP/LineageOS default: ext4
#     BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := $(GSI_FILE_SYSTEM_TYPE)
# Product makefiles are parsed before board configs (core/envsetup.mk:
# product_config.mk before board_config.mk), so setting it here is effective.
# scripts/build.sh exports ASH_SYSTEM_FS (default erofs) before lunch and
# asserts that BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE matches it, then verifies the
# finished image by its superblock magic.
ASHFALL_SYSTEM_FS ?= $(if $(filter ext4 erofs,$(strip $(ASH_SYSTEM_FS))),$(strip $(ASH_SYSTEM_FS)),erofs)
GSI_FILE_SYSTEM_TYPE := $(ASHFALL_SYSTEM_FS)
