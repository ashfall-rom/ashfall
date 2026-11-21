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

# Phase 7: the AshFall debloat list (docs/phase7.md).
#
# The RP5 is a Wi-Fi-only handheld. Lineage's phone product still builds the
# telephony apps/providers, the camera app, the printer/NFC stacks and the
# LineageOS OTA updater; none of them belong in this ROM. This file is the one
# place that filters them out, after every product inherit has run.
#
# Keep this list in sync with debloat/blocklist.txt, which the host and device
# audits use. The audits are the safety net: a package that creeps back in
# fails the build/verification even if this file is missed.
#
# DO NOT add the platform boot-jar APEXes here:
#   com.android.nfcservices, com.android.uwb, com.android.healthfitness,
#   com.android.ondevicepersonalization, com.android.webapp
# They are in PRODUCT_APEX_BOOT_JARS / PRODUCT_APEX_SYSTEM_SERVER_JARS and are
# loaded by system_server; removing them is a framework change, not debloat.
# Their apps are either launcher-less or are covered by an app module below.
# The com.android.virt APEX also stays; the AVF Terminal app is removed from its
# apps list by patches/packages/modules/Virtualization/0001-*.patch.

ASHFALL_DEBLOAT_PACKAGES := \
    Aperture \
    ApertureLensLauncher \
    CameraExtensionsProxy \
    DeviceAsWebcam \
    cameraserver \
    Dialer \
    Contacts \
    ContactsPicker \
    messaging \
    MmsService \
    TeleService \
    TelephonyProvider \
    TelecomShim \
    EmergencyInfo \
    CarrierConfig \
    CarrierDefaultApp \
    ImsServiceEntitlement \
    ONS \
    SimAppDialog \
    Stk \
    BlockedNumberProvider \
    CallLogBackup \
    E2eeContactKeysProvider \
    CellBroadcastLegacyApp \
    com.android.cellbroadcast \
    ExactCalculator \
    Etar \
    Twelve \
    Recorder \
    UsbDisableDebugger \
    Updater \
    PrintSpooler \
    PrintRecommendationService \
    BuiltInPrintService \
    BasicDreams \
    PhotoTable \
    EasterEgg \
    DeviceDiagnostics \
    HTMLViewer \
    Tag \
    SecureElement \
    QuickAccessWallet \
    Jelly

PRODUCT_PACKAGES_BLACKLIST := $(ASHFALL_DEBLOAT_PACKAGES)

# The blacklist is applied by patches/build/make/0001-product-blacklists.patch
# in product_config.mk, after the inherited products have been merged. Filtering
# PRODUCT_PACKAGES here would be a no-op: the product node merge appends the
# inherited values after this file has been included.
#
# PRODUCT_PACKAGES_DEBUG/ENG: UsbDisableDebugger is in the shared blacklist, so
# the same product_config.mk step removes it from the debug list too.
#
# The Updater's init rc is removed at its source by
# patches/vendor/lineage/0001-drop-updater-rc.patch.
