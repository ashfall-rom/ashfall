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

# AshFall products. Mirrored into $REMOTE_SRC/vendor/ashfall by scripts/build.sh.
#
# LineageOS 24.0 (Android 17) release configs live in
# vendor/lineage/release/release_config_map.textproto ("cp2a" at the time of
# writing) and the default is in vendor/lineage/vars/aosp_target_release.
# scripts/build.sh auto-detects it and always lunches the explicit
# <product>-<release>-<variant> form; the two-part choices below are kept for
# `lunch` completion only (same style as vendor/lineage/build/target/product/).

PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/products/ashfall_gsi_arm64.mk

COMMON_LUNCH_CHOICES := \
    ashfall_gsi_arm64-userdebug \
    ashfall_gsi_arm64-eng
