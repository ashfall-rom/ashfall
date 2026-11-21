# Phase 7 contract: debloat, branding and device identity

Status: contract, 2026-11-21. The six items in the phase brief were confirmed
with the user, plus the recommended debloat set. The decisions below are fixed
for this phase; anything outside them is a follow-up issue.

## 1. Scope (what changes, and what does not)

| Area | Decision |
|---|---|
| Trust onboarding | The "Discover Trust" notification is suppressed **by default**, not the Trust UI. The setting is seeded in `LineageSettingsProvider`. |
| Setup wizard | Rebranded (mark, wordmark, Soot/Ember palette, "Welcome to AshFall", AshFall wizard icon) and pruned of dead pages (SIM, empty device-specific, recovery update, biometric). |
| Settings identity | "AshFall version" row + build id, "LineageOS base" row, "AshFall Settings" label, RP5 AshFall device name, no LineageOS updater entry. |
| Device name | `PRODUCT_MODEL := RP5 AshFall`; Settings/Bluetooth use it. Existing `/data` is migrated once from the old defaults. |
| Camera | System-side camera apps and services removed (`cameraserver` included). The stock `/vendor` camera HAL and the vendor camera feature flags are **never touched**. |
| Phone/cell | Full Wi-Fi-only cleanup: user-facing apps, providers and the Telecom shim are removed. The `/vendor` RIL and the `com.android.telephonycore` platform APEX stay (framework dependency). The `com.android.cellbroadcast` APEX is removed. |
| Extra debloat | Updater/OTA, printing, screensavers/egg/diagnostics/test tools, NFC/SE/UWB/wallet app APKs, cell broadcast, terminal/AVF, and the two-browser duplicate. Boot-jar platform APEXes stay; see 5. |
| Kept on purpose | ContactsProvider, CalendarProvider, DeskClock, Glimpse, AudioFX, Seedvault, Profiles, Canvas, ThemePicker/Backgrounds/AvatarPicker/WallpaperBackup, Camelot (one browser), webview, DocumentsUI, MtpService. |
| Stock partitions | `flash-gsi.sh` drops the stock `product_<slot>`/`system_ext_<slot>` by default (the GSI bundles its own). `--keep-stock` restores the old space-only behaviour. |

Non-goals: re-theming the whole system (Ember accent/theme overlays), a full
"LineageOS" string purge, framework feature suppression for camera, vendor
partition changes, and a custom recovery.

## 2. Trust onboarding notification

Source: `lineage-sdk/lineage/lib/main/java/org/lineageos/platform/internal/TrustInterfaceService.java`
posts the onboarding notification at `PHASE_BOOT_COMPLETED` while
`LineageSettings.System.TRUST_INTERFACE_HINTED != 1`.

Fix: `patches/lineage-sdk/0002-lineagesettings-provider-ashfall-trust-default.patch`

- `res/values/defaults.xml`: `def_trust_interface_hinted = 1`.
- `LineageDatabaseHelper`: write it on a fresh database and migrate an existing
  database (`DATABASE_VERSION` 25 -> 26) with `INSERT OR REPLACE` once. A user
  who already tapped the notification keeps `1`; a user who never did gets `1`
  instead of the notification.
- The Trust page and its security warnings remain reachable in Settings; only
  the recurring onboarding card is gone.

## 3. Branding

### 3.1 Setup wizard (`packages/apps/SetupWizard`)

Resource overlays in `aosp/vendor/ashfall/overlay/no-rro/packages/apps/SetupWizard/app/src/main/res/`:

- `values/strings.xml`: `os_name` = `AshFall`, `app_name` = `AshFall Setup`,
  plus `ashfall_setup_wordmark` and `ashfall_setup_subtitle` ("LineageOS 24.0
  for the Retroid Pocket 5").
- `values/colors.xml` / `values/styles.xml`: the wizard's Start button and
  controls use the Ember accent (`#FF8A3D`) on Soot, not the LineageOS teal;
  the system-wide theme is unchanged.
- `drawable/ic_launcher_background.xml` / `drawable/ic_launcher_foreground.xml`:
  the adaptive AshFall setup icon.
- `drawable-nodpi/theme_preview_wallpaper.jpg`, `drawable/theme_preview_qs.xml`
  and `drawable/ic_palette.xml`: the Theme page preview carries the AshFall
  tile/wordmark and the Ember QS preview; the page glyph is the AshFall A.
- `drawable/logo.xml`: the AshFall tile (Soot disc, Ember bloom, Ash mark),
  replacing the Lineage wordmark on the finish page and in the welcome hero.
- `layout/welcome_activity.xml` and `layout-w600dp/welcome_activity.xml`:
  the Lottie Lineage logo becomes the AshFall mark + wordmark + "Welcome to
  AshFall" + subtitle. The RP5 is 853dp wide, so the `w600dp` variant is the one
  the wizard actually inflates on this device. The `start` and
  `launch_accessibility` buttons keep their ids and behaviour.
- `layout/welcome_locale_activity.xml`: same mark substitution for the
  TV/leanback locale welcome.
- `raw/lineage_wizard_script.xml`: skip the empty `device_specific` page, the
  `recovery_update` page (Updater is gone) and the SIM page (no telephony
  feature); no functional page is removed.

The welcome Lottie is not required; a static vector + the existing title
plumbing is deliberate, because the mark must be crisp at 1080p and the boot
animation already carries the motion.

### 3.2 Settings (`packages/apps/Settings`, `LineageParts`, `lineage-sdk`)

- `overlay/no-rro/packages/apps/LineageParts/res/values/strings.xml`:
  `lineageparts_title` = "AshFall Settings".
- `overlay/no-rro/lineage-sdk/lineage/res/res/values/strings.xml`:
  `lineage_version` = "LineageOS base" (upstream attribution stays).
- `overlay/no-rro/packages/apps/Settings/res/values/config.xml`:
  `config_show_system_update_settings=false`,
  `config_additional_system_update_setting_enable=false` (Updater is gone) and
  `config_show_sim_info=false` (no telephony; hides Phone number, SIM status,
  IMEI/MEID and EID in About phone).
- `patches/packages/apps/Settings/0002-ashfall-about-branding.patch`:
  new "AshFall version" preference (summary from `ro.ashfall.version` and
  `ro.ashfall.build.id`) and an "AshFall project" preference. The existing
  Lineage version row stays as the base-version row.
- The device name, model, manufacturer and brand are already `AshFall`; the
  model becomes `RP5 AshFall` (section 4).

The legal/contributors pages are kept. Statistics remain opt-in; no data is
sent by default.

### 3.3 Device name

- `aosp/vendor/ashfall/products/ashfall_gsi_arm64.mk`:
  `PRODUCT_MODEL := RP5 AshFall`. `PRODUCT_BRAND`/`PRODUCT_MANUFACTURER`
  stay `AshFall`.
- `patches/frameworks/base/0007-settingsprovider-ashfall-device-name.patch`:
  bump `SETTINGS_VERSION` 237 -> 238 and replace `Settings.Global.DEVICE_NAME`
  only when it still equals the old default (`AshFall GSI arm64`, `Generic
  System`, `Android`). A name the user typed is never overwritten.
- Fresh `/data` gets `RP5 AshFall` from `Build.MODEL`; the Bluetooth adapter
  name follows `Settings.Global.DEVICE_NAME` (verified on the current build).

## 4. Removals

The single source of truth is `aosp/vendor/ashfall/debloat/blocklist.txt`.
`aosp/vendor/ashfall/debloat.mk` filters the module names after all product
inherits; the blocklist drives the host and device audits.

### 4.1 The six requested items

| Request | Module / package | Where |
|---|---|---|
| Calculator | `ExactCalculator` (`com.android.calculator2`) | product |
| Calendar | `Etar` (`org.lineageos.etar`); `CalendarProvider` stays | product |
| LED test | **not in the ROM**; `org.ashfall.ledtest` is a sideloaded `/data` debug APK | uninstall on device |
| Music | `Twelve` (`org.lineageos.twelve`) | product |
| Recorder | `Recorder` (`org.lineageos.recorder`) | product |
| USB data signal test | `UsbDisableDebugger` (`com.android.usb.testing.debuggertool.datasignal`) | `PRODUCT_PACKAGES_DEBUG` |

### 4.2 Camera

`Aperture` (with `PRODUCT_NO_CAMERA := true`, set **before** the product
inherit), `ApertureLensLauncher`, `Camera2` (the AOSP fallback that reappears
when Aperture is not added), `CameraExtensionsProxy`, `DeviceAsWebcam`,
`cameraserver` and the `virtual_camera` service. The vendor camera HAL and the
`android.hardware.camera*` feature flags are out of reach and stay.

### 4.3 Phone/cell (full Wi-Fi-only)

`Dialer`, `Contacts`, `ContactsPicker`, `messaging`, `MmsService`,
`TeleService`, `TelephonyProvider`, `TelecomShim`, `EmergencyInfo`,
`CarrierConfig`, `CarrierDefaultApp`, `ImsServiceEntitlement`, `ONS`,
`SimAppDialog`, `Stk`, `BlockedNumberProvider`, `CallLogBackup`,
`E2eeContactKeysProvider`, `CellBroadcastLegacyApp`,
`com.android.cellbroadcast` (APEX). Seedvault keeps its `LocalContactsBackup`
companion; it uses the retained ContactsProvider.

Kept: `ContactsProvider`, `CalendarProvider`, `com.android.telephonycore`
(APEX; framework dependency), vendor RIL. The device already declares no
`android.hardware.telephony`, so Settings hides the mobile pages.

### 4.4 Extra debloat

`Updater` + `init.lineage-updater.rc`; `PrintSpooler`,
`PrintRecommendationService`, `BuiltInPrintService`; `BasicDreams`,
`PhotoTable`, `EasterEgg`; `DeviceDiagnostics`; `HTMLViewer`; `Tag`,
`SecureElement`, `QuickAccessWallet`; `Jelly` (Camelot stays). The AVF Terminal
app is removed by `patches/packages/modules/Virtualization/0001-*.patch`; the
`com.android.virt` APEX itself stays.

The following are **kept** because they are platform boot/system-server APEXes,
not removable without a framework change: `com.android.nfcservices`,
`com.android.uwb`, `com.android.healthfitness`,
`com.android.ondevicepersonalization`, `com.android.webapp` and
`com.android.virt`. Their contained packages are not launcher entries. The
Terminal app was the one launcher entry and is removed from the virt APEX's
`apps` list. This is recorded as a known limit; a follow-up issue can take the
APEX removal if a boot test proves it safe.

## 5. Implementation notes

- `PRODUCT_NO_CAMERA := true` must be set in `products/ashfall_gsi_arm64.mk`
  **before** `$(call inherit-product, vendor/lineage/build/target/product/lineage_gsi_arm64.mk)`;
  the inherited makefile reads it at include time.
- `debloat.mk` is included last in `ashfall_common.mk`; it sets
  `PRODUCT_PACKAGES_BLACKLIST`. The blacklist is applied by
  `patches/build/make/0001-product-blacklists.patch` in
  `build/make/core/product_config.mk`, **after** the product node merge. A
  plain `PRODUCT_PACKAGES := $(filter-out ...)` in the product makefile is a
  no-op for inherited packages: the node merge appends the inherited values
  after the product file's body (the first phase-7 build proved this: it still
  had Dialer/Etar/Updater even though the product makefile filtered them).
- The Updater's `init.lineage-updater.rc` copy rule is removed at its source by
  `patches/vendor/lineage/0001-drop-updater-rc.patch`, not by a copy-file
  blacklist.
- The `com.android.virt` APEX is **not** removed: its `service-virtualization`
  fragment must be declared in `PRODUCT_APEX_SYSTEM_SERVER_JARS` (Soong rejects
  the APEX otherwise). Only `VmTerminalApp` is removed from its `apps` list.
- The stock product/system_ext deletion is a fastbootd operation (logical
  partitions); the bootloader batch rule is unchanged. Never use
  `fastboot getvar all` (AGENTS.md, issue #54).
- The LED test APK is removed from the device, not the build; it lives in
  `/data/app` and is not part of the image.

## 6. Verification

Static:

- [ ] The product config applies `PRODUCT_NO_CAMERA` before the GSI inherit.
- [ ] A clean build succeeds; `installed-files.txt` contains none of the
      blocklist entries (`scripts/verify/debloat.sh <artifact-dir>`).
- [ ] The new patches apply with `git am --3way` on a pristine tree.
- [ ] `docs/` and the overlay paths are covered by `PRODUCT_PACKAGE_OVERLAYS`.

Runtime (orchestrator, after a lease):

- [ ] Fresh `/data`: welcome says "Welcome to AshFall", finish shows the
      AshFall mark; no SIM/empty device-specific/recovery-update page.
- [ ] `Settings > About phone`: "RP5 AshFall", "AshFall version" + build id,
      "LineageOS base", "AshFall Settings" entry, no "System updates".
- [ ] Bluetooth name is `RP5 AshFall`.
- [ ] No "Discover Trust" notification after a reboot; Trust is still in
      Settings.
- [ ] No Calculator, Etar, Twelve, Recorder, Aperture, print, NFC/wallet or
      updater package; `pm list packages` audit passes.
- [ ] Camera apps are gone and `cameraserver` is not running; no camera app in
      the drawer.
- [ ] Phone/cell apps are gone; no dialer/SMS/contacts launcher entries; the
      boot completes without a telephony-related crash.
- [ ] `org.ashfall.ledtest` is uninstalled from `/data`.
- [ ] `flash-gsi.sh --dry-run` shows the stock product/system_ext deletion on a
      normal (no `--keep-stock`) run.

## 7. Ownership

| Workstream | Paths |
|---|---|
| Product/debloat + guard | `aosp/vendor/ashfall/debloat.mk`, `aosp/vendor/ashfall/debloat/blocklist.txt`, `aosp/vendor/ashfall/products/ashfall_gsi_arm64.mk`, `aosp/vendor/ashfall/ashfall_common.mk`, `patches/build/make/0001-product-blacklists.patch`, `patches/vendor/lineage/0001-drop-updater-rc.patch`, `patches/packages/modules/Virtualization/0001-drop-avf-terminal-app.patch`, `scripts/verify/debloat.sh` |
| Branding/defaults | `aosp/vendor/ashfall/overlay/no-rro/**`, `patches/lineage-sdk/0002-*`, `patches/frameworks/base/0007-*`, `patches/packages/apps/Settings/0002-*`, `docs/branding.md` |
| Flash policy | `scripts/device/flash-gsi.sh`, `docs/flashing.md` |
| Integration/build/flash | orchestrator |

No worker touches the RP5 for a write; the orchestrator takes the lease and
collects the runtime evidence.
