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
# com.android.virt is an exception: it is only the AVF/Terminal APEX, it has no
# system_server reference in this tree, and it is filtered together with its
# conditional ART entries at the bottom of this file.

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
    LocalContactsBackup \
    com.android.cellbroadcast \
    ExactCalculator \
    Etar \
    Twelve \
    Recorder \
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
    Jelly \
    com.android.virt

PRODUCT_PACKAGES := $(filter-out $(ASHFALL_DEBLOAT_PACKAGES),$(PRODUCT_PACKAGES))
PRODUCT_PACKAGES_DEBUG := $(filter-out UsbDisableDebugger,$(PRODUCT_PACKAGES_DEBUG))
PRODUCT_PACKAGES_ENG := $(filter-out UsbDisableDebugger,$(PRODUCT_PACKAGES_ENG))

# Updater's init rc is added by vendor/lineage/config/common.mk independently of
# the app. Drop it with the app instead of leaving an init service that can
# never exec.
PRODUCT_COPY_FILES := $(filter-out %init.lineage-updater.rc:%,$(PRODUCT_COPY_FILES))

# com.android.virt: the AVF/Terminal APEX. Its framework and (conditional)
# system-server jars are listed by default_art_config.mk; filter them too or the
# build keeps trying to link a missing APEX.
PRODUCT_APEX_BOOT_JARS := $(filter-out com.android.virt:%,$(PRODUCT_APEX_BOOT_JARS))
PRODUCT_APEX_SYSTEM_SERVER_JARS := $(filter-out com.android.virt:%,$(PRODUCT_APEX_SYSTEM_SERVER_JARS))
