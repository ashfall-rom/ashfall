# AshFall: PlayTime indicator on `Settings > Battery`

Settings UI for the PlayTime indicator (**#137** §4, wave-2 contract
`docs/phase6.md` §9.4). This is the project's first `packages/apps/Settings`
patch, so the scope is deliberately small: one host row, one sub-screen, one
new `res/xml` file, strings, and nothing else. The status bar element and the
estimator are SystemUI's (`patches/frameworks/base/`, `docs/playtime.md`).
Charging control (#140) lands on the same page as a second, sequential patch.

## 1. Patch

| | |
|---|---|
| Patch | `patches/packages/apps/Settings/0001-playtime-battery-page.patch` |
| Baseline | `LineageOS/android_packages_apps_Settings` @ `31b1a9f1cab9638bc6af9f4e6412fbf3b3a7947a` (`lineage-24.0`, no AshFall patch) |
| Apply | `git am --3way 0001-playtime-battery-page.patch` on the pristine project |

Files in the patch:

| File | Change |
|---|---|
| `res/xml/power_usage_summary.xml` | one `UntitledPreferenceCategory` with the `playtime_indicator` row, between the battery-percentage category and `battery_info` |
| `res/xml/playtime_settings.xml` | new sub-screen (`SettingsPreferenceFragment`, non-persistent rows) |
| `res/values/strings.xml` | `playtime_*` strings, keyword string and the list arrays (single values file on purpose) |
| `src/com/android/settings/fuelgauge/playtime/PlayTimeSettings.java` | sub-screen fragment: reads the properties once, writes on change |
| `src/com/android/settings/fuelgauge/playtime/PlayTimeProperties.java` | property contract, validation, summary/read-out formatting |
| `src/com/android/settings/fuelgauge/playtime/PlayTimeSummaryController.java` | live summary of the host row |
| `src/com/android/settings/fuelgauge/playtime/PlayTimeCapacityPreference.java` | numeric mAh dialog (`ValidatedEditTextPreference`) |

No new activity or manifest entry (`SubSettings` hosts the fragment), no APK,
no launcher entry, no QS tile, no service, no reflection.

Settings is platform-signed with `android.uid.system` and runs as `system_app`:
the writes use the existing `set_prop(system_app, ashfall_prop)`. This page no
longer reads any `ashfall.status.*` property (see §4), so the battery side needs
no new SELinux rule at all.

## 2. Which battery page actually renders (measured, not assumed)

The A17/lineage-24.0 tree carries both a View page and a metadata page, so the
render path was established before writing the row:

| Path | Where | Flag | Live? |
|---|---|---|---|
| View | `Settings$PowerUsageSummaryActivity` → `FRAGMENT_CLASS com.android.settings.fuelgauge.batteryusage.PowerUsageSummary` (`DashboardFragment`) → `res/xml/power_usage_summary.xml` | none | **yes** |
| Metadata | `PowerUsageSummaryScreen.kt` (`PreferenceScreenMixin`) | `Flags.catalystPowerUsageSummaryScreen()` (`aconfig/catalyst/battery.aconfig`) | no |
| Metadata API | `PowerUsageSummaryApiScreen.kt` (`PreferencesApiScreen`) | `Flags.catalystMigration26q3()` (`aconfig/catalyst/miscs.aconfig`) | no |

Both aconfig flags are declared without a release config in this tree, so they
keep the aconfig default and are **disabled**: the View path is the one that
renders today. `res/xml/power_usage_summary.xml` is therefore where the row
lives.

**When the flags flip** the metadata hierarchy in `PowerUsageSummaryScreen`
replaces the XML page and this row becomes dead code: the PlayTime row then
has to be declared in `PowerUsageSummaryScreen.getPreferenceHierarchy` (with a
`PreferenceMetadata` class carrying the launch intent to `PlayTimeSettings`).
That is a deliberate follow-up, not part of this patch; the flag flip is
visible in `aconfig/catalyst/`, not in `res/xml`.

The page is a `DashboardFragment`, so the row carries a controller
(`settings:controller`) for its live summary; XML-declared controllers are
instantiated by `PreferenceControllerListHelper` from `(Context, String)`.

## 3. Rows and properties

Host row: **PlayTime indicator**, summary `2h 45m · 5.4W` (or `Off`), opens the
sub-screen. It is a plain entry, like Battery saver / Battery information: the
switch lives on the sub-screen (see §6).

The summary always previews time · watts, whatever Detail is set to, and is
refreshed on the 2 s telemetry tick while the page is started (the controller is
a `LifecycleObserver`; the timer is removed on stop).

Sub-screen order: Show indicator · [Placement, Detail, Time format, Font size,
Tap to cycle] · [Average over, Update every, Battery capacity] ·
[Charging symbol, Charging cycle, Cycle interval, Values to cycle] · Live
values.

| Row | Control | Property (written) | Values |
|---|---|---|---|
| Show indicator | switch | `persist.ashfall.batt.show` | `0` \| `1` |
| Placement | list, 5 | `persist.ashfall.batt.place` | `BATTERY_LEFT`, `BATTERY_RIGHT`, `CLOCK_LEFT`, `CLOCK_RIGHT`, `REPLACE_PERCENT` |
| Detail | list, 7 | `persist.ashfall.batt.detail` | `TIME`, `TIME_W`, `TIME_W_V`, `TIME_V`, `W`, `V`, `W_V` |
| Time format | list, 4 | `persist.ashfall.batt.fmt` | `COMPACT`, `SHORT`, `COLON`, `DETAILED` |
| Font size | slider 10–16 sp | `persist.ashfall.batt.size_sp` | `10`..`16` |
| Tap to cycle | switch | `persist.ashfall.batt.tap` | `0` \| `1` |
| Average over | list, 4 | `persist.ashfall.batt.window` | `1`, `3`, `5`, `10` (min) |
| Update every | slider 2–30 s | `persist.ashfall.batt.interval` | `2`..`30` (s) |
| Battery capacity | numeric dialog | `persist.ashfall.batt.capacity_mah` | `0` = auto, else `1`..`20000` mAh |
| Charging symbol | list, 4 | `persist.ashfall.batt.chg_symbol` | `BOLT` (`↯`), `PLUS` (`+`), `TEXT` (`CHG`), `EMOJI` (`⚡`) |
| Charging cycle | switch | `persist.ashfall.batt.chg_cycle` | `0` \| `1` |
| Cycle interval | slider 0.5–5 s | `persist.ashfall.batt.chg_cycle_ms` | `500`..`5000` ms, 100 ms steps |
| Values to cycle | multi-select, 4 | `persist.ashfall.batt.chg_meters` | 2–3 of `TIME`, `PERCENT`, `WATTS`, `VOLTS`, written in that order |
| Live values | non-selectable | read only (no property) | `BatteryManager` (§4) |

The cycle-interval slider stores steps `0`..`45` and writes
`500 + step × 100`; a stored value that is not a multiple of 100 is displayed
rounded to the nearest step and is only rewritten when the user moves the
slider. The meter list is serialised in the canonical `TIME,PERCENT[,WATTS,VOLTS]`
order (not the `Set` iteration order) so SystemUI can parse it positionally, and
a selection outside 2–3 entries is rejected with a toast.

## 4. Live telemetry and the read-out

**Source: the framework `BatteryManager`, not `ashfall.status.*`.** There is no
battery sampler in `ashfall-hwd`: a coredomain cannot read `sysfs_batteryinfo`
(platform neverallow) and the RP5's power-supply nodes are vendor-labeled, so
the daemon must not open them. The page reads the same values the platform
already exposes to Settings.

| Value | Source | Mapping | Unavailable |
|---|---|---|---|
| `current_ma` | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` | µA, signed → `abs(value) / 1000` | `Integer.MIN_VALUE` (unsupported), or outside `1..10000 mA` |
| `voltage_mv` | sticky `ACTION_BATTERY_CHANGED`, `EXTRA_VOLTAGE` | mV as is | outside `2500..5500 mV` |
| `level` | sticky broadcast, `EXTRA_LEVEL / EXTRA_SCALE` | percent, rounded, capped at 100 | `EXTRA_LEVEL < 0` or `EXTRA_SCALE <= 0` |
| `charging` | sticky broadcast, `EXTRA_STATUS` | `CHARGING` or `FULL` | anything else counts as discharging |
| `charge_mah` | `getIntProperty(BATTERY_PROPERTY_CHARGE_COUNTER)` | µAh → `/ 1000` | negative (no fuel-gauge counter), outside `0..100000 mAh` |
| `capacity_mah` | `PowerProfile(context).getBatteryCapacity()` | design capacity, mAh | `<= 0` (profile does not declare it) |

The sticky broadcast is read with `registerReceiver(null, filter)`, the same way
`BatteryInfo` does; the two battery properties are read once per refresh. The
bounds are the ones SystemUI applies, so the page and the status bar agree on
what "unknown" is, and every field is independent: no value is fabricated from
another.

Non-selectable read-out row, refreshed every 2 s while the page is resumed and
immediately on resume:

```
4.8 W · 3.85 V · 5120 mAh · 72 %
          -      -       -        each field independently when unavailable
```

Watts are `current_ma × voltage_mv / 10^6`; volts are `voltage_mv / 1000` at
`%.2f`; mAh is the design capacity above (the value the estimate uses while
Battery capacity = auto); percent is `level`. A device that does not report
`CURRENT_NOW` (or `CHARGE_COUNTER`) shows `-` for the affected fields and `-`
for the summary time, and the page keeps working: there is nothing to poll and
nothing to wait for.

## 5. Defaults, validation and failure modes

Defaults live in SystemUI (`docs/playtime.md`, SystemUI PR for #137), **not**
here: this page only mirrors them for display, and never writes a default. The
table below is a copy of SystemUI's property table — if the two ever disagree,
SystemUI wins at runtime and this table is the only thing to update. When a
property is unset, unparsable or outside its range, the row shows the fallback
below and the value is not written back.

| Property | Fallback shown |
|---|---|
| `show` | `0` |
| `place` | `BATTERY_LEFT` |
| `detail` | `TIME` |
| `fmt` | `COMPACT` |
| `window` | `5` |
| `interval` | `5` |
| `chg_symbol` | `BOLT` |
| `chg_cycle` | `0` |
| `chg_cycle_ms` | `1000` |
| `chg_meters` | `TIME,PERCENT` |
| `tap` | `0` |
| `size_sp` | `12` |
| `capacity_mah` | `0` |

If SystemUI's default ever differs, SystemUI wins at runtime and this table
(and the row display) is the only thing to update.

Failure modes:

- A rejected property write (SELinux, invalid name) is caught and logged
  (`PlayTimeProperties: Failed to set …`); the page keeps working and the row
  keeps the user's chosen value for the session.
- A malformed status value (`-1`, empty, garbage) becomes `-`/`--`; nothing is
  fabricated.
- The capacity dialog cannot be confirmed with text outside `0..20000`; the
  dialog validator disables OK, and a programmatic bad value becomes `0` (auto).
- No fuel gauge / no `CURRENT_NOW`: the summary is `Off` or `--`, the affected
  read-out fields are `-`, and the rows still read and write.

## 6. Native behaviour

- `SettingsPreferenceFragment` + `res/xml`, hosted by `SubSettings` with
  `Theme.SubSettings`, so day/night, pure black, font scale and landscape come
  from Settings itself; no custom layout, colour or drawable.
- Standard `SwitchPreferenceCompat`, `ListPreference`,
  `com.android.settings.widget.SeekBarPreference`, `MultiSelectListPreference`
  and `ValidatedEditTextPreference` rows: D-pad focus and the `SeekBar` key
  handling are the ones every other Settings slider uses.
- Every preference is `android:persistent="false"`: the property is the only
  storage, so there is no second copy in Settings' `SharedPreferences`.
- Rows are grouped in titled `PreferenceCategory`s on the sub-screen
  (`Indicator`, `Estimate`, `Charging`, `Live values`), the host row in an
  untitled category on `Settings > Battery`, between the battery-percentage
  group and Battery information.
- Issue #137's table says "the summary row carries the switch and the live
  value"; this patch keeps the switch on the sub-screen and makes the host row a
  summary-only entry that opens the page. A `SwitchPreference` host row cannot
  also open a sub-screen on click, and the row list in the wave-2 brief puts
  "Show indicator (switch)" on the sub-screen. If the user wants an extra switch
  on the host row, it belongs in #140's patch (same page, same strings file).

## 7. Verification

**Patch applies** (run in this repo, not on the RP5):

```bash
cd /tmp/opencode && rm -rf verify-am
git clone --filter=blob:none --sparse \
    https://github.com/LineageOS/android_packages_apps_Settings verify-am
cd verify-am && git sparse-checkout set res src
git checkout 31b1a9f1cab9638bc6af9f4e6412fbf3b3a7947a
git am --3way /path/to/0001-playtime-battery-page.patch
git show --stat HEAD
git diff HEAD^ --stat           # 7 files: 4 new Java classes, 2 new XML res, 1 strings block
xmllint --noout res/xml/playtime_settings.xml res/values/strings.xml
```

**Build** (orchestrator, build host): `m Settings` in the
`ashfall_gsi_arm64-userdebug` tree; the patch is part of the integration build,
no Settings-local build was run by the worker.

**Emulator** (`Retroid_Pocket_5` AVD, no device):

```bash
adb root && adb remount
adb push $OUT/system_ext/priv-app/Settings/Settings.apk \
    /system_ext/priv-app/Settings/Settings.apk   # path from `adb shell pm path com.android.settings`
adb reboot
adb shell am start -a android.intent.action.POWER_USAGE_SUMMARY
```

- The row reads `Off`; open it: every row is visible, D-pad reaches every row,
  the sliders move with left/right, the capacity dialog opens with the numeric
  keypad, and the read-out shows the emulator's virtual battery (the AVD reports
  `CURRENT_NOW` as `0`, so the watts field is `-` and volts/percent are real).
- Rotate to landscape at 1920x1080: no clipped row, category headers intact,
  the read-out row wraps without ellipsis.
- Day/night and pure black: only Settings theme colours, no hard-coded colour.
- With `adb root; adb shell setprop persist.ashfall.batt.interval 5` (and the
  other mock values) the rows open on the stored values; with
  `setprop persist.ashfall.batt.window 7` the window row shows `5 min`
  (fallback) instead of a blank summary. No daemon is needed for any of this.

**Device** (orchestrator, after the integration flash): the row shows a live
estimate from the real fuel gauge (`dumpsys battery` is the reference: the
read-out's volts and percent must match it, and watts must be within rounding of
`abs(current now) × voltage`), opening the sub-screen and changing
Placement/Detail updates the status bar without a SystemUI restart; values
survive reboot; `logcat | grep 'Failed to set persist.ashfall'` is empty (the
`system_app` `set_prop` rule works); `dumpsys package com.android.settings |
grep userId` reports `userId=1000`. `logcat` must also be free of
`avc: denied` for `Settings` on the `ashfall_prop` type.

**Acceptance** (issue #137 DoD, Settings part): the row with a live summary
opens the sub-screen; every row applies live; values survive reboot; invalid
values fall back to the documented defaults; the read-out shows `-` per field
when the framework does not report it; the page follows day/night/pure black,
D-pad and landscape; no native battery behaviour changes.

## 8. Charging control (#140)

> **Device finding (2026-11-19):** the retail factory HAL's `chargerEnable` is a
> **no-op** on this kernel.  The HAL writes a command to `/dev/socket/mmi`; the
> `mmid` daemon then opens `/sys/class/power_supply/battery/input_suspend`, which
> does not exist on the RP5's PM8150B/smb5 kernel (the driver logs
> `pl_input_suspend channel unavailable`).  The binder transaction still returns
> OK, so the page says `Supported` while the battery keeps charging.
>
> The operative backend is `ashfall-hwd`, using the QTI charger class pair under
> `/sys/class/qcom-battery`: `restrict_chg=1` switches the charger to the
> restricted charge current and `restrict_cur` sets that current, so
> `restrict_cur=0` stops battery charging.  The charger input is suspended at
> the same time (USB current ~0), so the device runs on battery while plugged
> in - the same end state the vendor's own `mmid` would produce, because its
> `input_suspend` node is what this kernel lacks.  This kernel exposes no
> bypass/power-path node, so the level cannot be held by the adapter alone.
>
> Measured on the RP5 with the adapter attached: `constant_charge_current`
> 1 A -> 0, `charge_counter` -0.3..-2.2 mAh/min with separation on versus
> +3.7..+5.6 mAh/min with it off.  hwd saves the vendor current (1 A) and
> restores it, with `restrict_chg=0`, when separation is switched off.  The
> vendor charging stack recomputes the current on adapter plug and on thermal
> events, so a held request is re-asserted every 30 s.  Both nodes carry the
> genfscon type `ashfall_charger_sysfs`.  The Settings HAL call remains as the
> firmware-support probe only.
>
Second patch on the same page, stacked on §1 and independent of SystemUI:
**Charging separation** stops battery charging while the adapter stays
connected; the charger input is suspended, so the device runs on battery and
the level drifts down slowly (the same behaviour the vendor's own `mmid` would
produce with `input_suspend`). This kernel exposes no bypass/power-path node,
so a hold-at-level needs the automatic top-up loop below. v1 is the manual
toggle only.
The automatic 80 %/10 % hysteresis needs an always-on sampler and stays a
follow-up: `ashfall-hwd` cannot call a vendor HIDL service from `system_ext`,
so the boot re-apply lives in Settings while the ongoing policy would live in
hwd.

### 8.1 Patch

| | |
|---|---|
| Patch | `patches/packages/apps/Settings/0002-charging-control.patch` |
| Baseline | same as §1: `LineageOS/android_packages_apps_Settings` @ `31b1a9f1cab9638bc6af9f4e6412fbf3b3a7947a` |
| Order | applies **after** `0001-playtime-battery-page.patch`; both patches touch `res/xml/power_usage_summary.xml` and `res/values/strings.xml` |
| Apply | `git am --3way 0001-playtime-battery-page.patch 0002-charging-control.patch` |

Files in the patch:

| File | Change |
|---|---|
| `res/xml/power_usage_summary.xml` | `ashfall_charging_control` row in the PlayTime category, immediately after the PlayTime row |
| `res/xml/charging_control_settings.xml` | new sub-screen: toggle, support row, policy row, warning footer |
| `res/values/strings.xml` | `charging_control_*` and `keywords_charging_control` strings |
| `AndroidManifest.xml` | `ChargingControlReceiver`: `LOCKED_BOOT_COMPLETED` + `BOOT_COMPLETED`, direct-boot aware, not exported (no disconnect receiver). No `uses-library`: the vendor Java stubs do not exist on the RP5 (§8.4) |
| `src/com/android/settings/fuelgauge/chargecontrol/ChargingControlSettings.java` | sub-screen fragment: the guards (including the low-battery guard on resume), applies the HAL off the main thread |
| `src/com/android/settings/fuelgauge/chargecontrol/ChargingControlReceiver.java` | locked-boot/boot re-apply (`goAsync` + background HAL call) and the clear-and-restore fail-open helper |
| `src/com/android/settings/fuelgauge/chargecontrol/ChargingSeparation.java` | property contract and the battery-level/plugged guards |
| `src/com/android/settings/fuelgauge/chargecontrol/ChargingSeparationHal.java` | raw HwBinder client: probe `@1.1`, fall back to `@1.0`, transact 4 |

`RECEIVE_BOOT_COMPLETED` is already declared in the baseline manifest (line 95),
so the patch adds only the receiver entry. No new activity, service, APK or QS
tile; `SubSettings` hosts the fragment exactly as it hosts `PlayTimeSettings`,
and the category key stays `playtime_category` so `0001` is untouched.

### 8.2 Row and sub-screen

`Settings > Battery` shows **Charging control** directly under **PlayTime
indicator**. The new row uses the key `ashfall_charging_control`: the page
already carries a Lineage row keyed `charging_control` (a `LineagePartsPreference`
gated on `lineagehealth`), and androidx.preference rejects duplicate keys, so a
second `charging_control` would log an error, collide in `findPreference` and,
on a device with `lineagehealth`, show two rows with the same title. The Lineage
row is left untouched.

Opening the row shows:

| Row | Text | Behaviour |
|---|---|---|
| Charging separation | switch, default off | manual separation; applies immediately, only while the charger is connected |
| Firmware support | `Checking…`, then `Supported` / `Not supported on this device` / `Not available right now` | non-selectable, filled by the HAL transaction when the page opens |
| Battery care range | `Stay between 80% and 10%` | the 80 %/10 % policy in one short line; the automatic hysteresis is not in v1 |
| footer | fuel-gauge drift warning | the one genuine safety note, two lines |

The sub-screen is a plain `SettingsPreferenceFragment` with
`res/xml/charging_control_settings.xml`; no controller and no `res/layout`, so
day/night, pure black, font scale, landscape and D-pad come from Settings
itself. No QS tile, no AshFall Parts page, nothing outside this patch.

### 8.3 Property contract

| Property | Values | Default | Written by |
|---|---|---|---|
| `persist.ashfall.batt.charge_sep` | `1` = separation on, `0` = off | `0` (absent) | `ChargingControlSettings` / `ChargingControlReceiver` |

Anything other than the exact string `1` reads as off. Settings is platform
signed with `android.uid.system` and runs as `system_app`, so the write uses the
existing `set_prop(system_app, ashfall_prop)` rule that the PlayTime properties
already use. There is no second copy in `SharedPreferences`: every preference in
`charging_control_settings.xml` is `android:persistent="false"`.

The automatic policy (stop at 80 %, resume at 10 %) is **not** in this patch:
with the toggle on, the device stays separated until the user turns it off or
unplugs. That is why the policy row reads as guidance ("Stay between 80% and
10%") instead of claiming automation. A follow-up can add the automatic
hysteresis in `ashfall-hwd` (the natural owner of an always-on loop) once it has
a path to the vendor HAL; this patch only persists the property for it to read.

### 8.4 Backend and gating

The only writable control is the retail vendor factory HAL; the PM8150B/smb5
power-supply nodes have no writable charge-enable node and `charge_control_limit`
is read-only. `ChargingSeparationHal` is a **raw HwBinder client** for it, built
on `android.os.HwBinder`, `android.os.HwParcel` and `android.os.IHwBinder` —
platform APIs Settings compiles against with `platform_apis` and finds in
`BOOTCLASSPATH` at runtime. No generated HIDL proxy and no reflection.

**Why not reflection.** The vendor Java stubs are not installed on the RP5:
`pm list libraries` reports only `android.hidl.base-V1.0-java` and
`android.hidl.manager-V1.0-java`; there is no `*factory*` jar under
`/system/framework`, `/system_ext/framework` or `/vendor/framework`; and no
permissions XML declares one as a shared library. The `uses-library` entries and
`optional_uses_libs` from PR #188 therefore changed nothing — `Class.forName`
still threw and the page stayed at "Not supported" — so this patch removes them
instead of keeping a dead path. `Android.bp` is no longer touched at all.

**Which version answers.** The RP5 registers the factory HAL at **1.1 only**:
`docs/stock/vintf/manifest.xml` (`<fqname>@1.1::IFactory/default</fqname>`, and
the same in `docs/stock/vendor-vintf-merged.txt`), `docs/stock/fcm-report.md`
(`hidl | vendor.qti.hardware.factory | 1.1`) and the `.189` OTA
(`docs/stock-189/vintf-summary-189.txt`). The init entry
`vendor.qti.hardware.factory@1.0-service` is only the binary name; that binary
registers 1.1. HIDL lookup is an exact fqname match, so the client probes in this
order and uses the first one that answers:

```java
HwBinder.getService("vendor.qti.hardware.factory@1.1::IFactory", "default"); // RP5
HwBinder.getService("vendor.qti.hardware.factory@1.0::IFactory", "default"); // 1.0 firmware
```

**Transaction.** `IFactory.chargerEnable(boolean)` is transaction code **4** on
both versions (1.1 extends 1.0 without renumbering it). The payload is the
interface token plus one `bool`, exactly what the disassembled
`BpHwFactory::_hidl_chargerEnable` writes:

```java
HwParcel req = new HwParcel();
HwParcel rep = new HwParcel();
req.writeInterfaceToken("vendor.qti.hardware.factory@1.1::IFactory");
req.writeBool(enable);                  // false = stop charging, true = resume
binder.transact(4, req, rep, 0);        // 0 = synchronous
// void method: the reply carries only the HIDL status word
```

The method returns nothing, so the client reads no return value. It calls
`rep.verifySuccess()` to consume the status word when there is one; an empty
reply (`IllegalArgumentException`) is not treated as a failure, because a void
method has no data to lose. An error status surfaces as `RemoteException` and
counts as a failed transaction. Both parcels are `release()`d in a `finally`.

**Lazy start.** The vendor service is `disabled` + `oneshot` in
`/vendor/etc/init/vendor.qti.hardware.factory@1.0-service.rc` (`user system`,
`class hal`). System `hwservicemanager` issues `ctl.interface_start` when a
client asks for a service that is not registered
(`system/hwservicemanager/ServiceManager.cpp`), so the **first** lookup is what
starts the HAL and can miss while the process comes up. The client therefore
retries each lookup **5 times, 300 ms apart** (up to ~1.2 s per interface) before
an interface counts as absent, and logs every miss with its attempt number.

**Threading.** The lookups and the transaction run on one dedicated daemon
thread inside `ChargingSeparationHal`; the result is posted back to the main
thread. The page shows `Checking…` and enables the switch only after a
transaction succeeds, and the boot receiver holds `goAsync()` until the answer
arrives (worst case ~2.4 s of lookups, well inside the 10 s broadcast budget).
Nothing here blocks Settings' main thread or its shared background pool.

**Support state.** `Supported` is claimed only after a successful transaction,
never from a lookup alone. The page's probe is a real transaction: it re-applies
separation when the property says `1`, otherwise it asks for charging (a no-op
while charging is already allowed). The support row is honest about the three
outcomes:

| Condition | Result |
|---|---|
| a transaction succeeds | support row `Supported`, switch enabled and bound to the property |
| neither `@1.1` nor `@1.0` answers after 5 x 300 ms each | support row `Not supported on this device`, switch disabled; the log names each interface that missed |
| a service answered but the transaction failed | support row `Not available right now`, switch disabled, probe repeated on the next resume (transient, not "unsupported") |
| `RemoteException` from `transact()` (vendor process died) | cached proxy dropped so the next call re-resolves; reported as a failed transaction |
| enable transaction fails | `Could not stop charging`, charging restored, nothing stored |
| disable transaction cannot restore charging | `Restart your device if charging does not resume.` |

**SELinux is already in place.** The vendor policy puts `system_app_33_0` in
`vendor_hal_factory_qti_client` and allows `find` on
`vendor_hal_factory_qti_hwservice` plus `binder call` to the server, which is
exactly the access a `system_app` HwBinder client needs. This patch adds **no
policy rule**; the device pass must confirm that no denial appears rather than
guessing a domain/type pair:

```bash
adb root
adb shell 'dmesg | grep avc' | grep -iE 'factory|charger|ashfall_prop'   # must be empty
adb shell 'logcat -d | grep "avc: denied"' | grep -iE 'factory|charge'   # must be empty
```

If an `avc: denied` does appear for the factory service or the property write,
that is a new finding for a separate issue: do not ship a rule with this patch
without the domain/type names from the log.

### 8.5 Boot, unplug and fail-open

`ChargingControlReceiver` is the only piece that runs outside the page:

- **Boot** (`LOCKED_BOOT_COMPLETED`, then `BOOT_COMPLETED` as a fallback): the
  receiver is `directBootAware`, so separation is applied **before the first
  unlock** — the phone does not charge at full current while it sits at the lock
  screen. If the property is not `1`, do nothing and make no
  HAL call. If it is `1` and the level is at or above the floor, the receiver
  keeps the broadcast alive with `goAsync()` and asks for `chargerEnable(false)`
  on the HAL thread, retrying the lazy-start lookup; it finishes when the
  transaction answers. Only a successful transaction marks the boot apply done,
  so a `LOCKED_BOOT_COMPLETED` that found no service keeps the stored choice and
  `BOOT_COMPLETED` tries once more (typically after the vendor service has
  finished its lazy start); after a success the second delivery is a no-op. Each
  delivery has its own 10 s budget and the worst case is ~2.4 s of lookups, so
  the retry cannot stall boot. If the level is below the floor, drop the state
  and restore charging. On a firmware that still has no HAL at `BOOT_COMPLETED`
  the stored choice is kept and no clear is sent (the feature is simply not
  applicable there). The receiver sends no broadcast and is not re-triggered by
  its own work, so there is no boot loop, even with the HAL missing.
- **Unplug** is not a receiver path. `ACTION_POWER_DISCONNECTED` is **not** on
  the documented implicit-broadcast exemption list, so a manifest receiver is
  not guaranteed it while the app is in the background and this patch does not
  claim auto-off on unplug. The real behaviour: the stored setting **survives**
  an unplug; separation only does anything while VBUS is present, so the device
  charges normally on battery, and because the switch stays on the UI is honest.
  On replug the vendor HAL keeps (or resumes) the request — the device pass in
  §8.6 records whether charging stays stopped; the setting is not cleared by the
  unplug itself. Only the low-battery guards and a failed apply clear it.
- **Low-battery guard on page open**: opening the sub-screen below 20 % with the
  property at `1` clears the state and restores charging immediately, so an old
  request cannot strand a low battery after an unplug the app never saw.
- **Fail-open**: every error path clears `persist.ashfall.batt.charge_sep`,
  then asks for charging to be restored. Below the floor (boot or page open), a
  failed `chargerEnable(false)`, a rejected property write and a boot that cannot
  re-apply all end with charging enabled and, where the property is ours to
  clear, at `0`. A rejected write is detected by reading the property back, so
  the switch does not claim "off" while the next boot would still re-apply; that
  case reports `Could not turn off charging separation`, while a failed resume
  reports `Restart your device if charging does not resume.` A firmware without
  the factory HAL is the one case that keeps the stored choice: the call cannot
  be attempted, nothing is applied, and the next boot tries again. The one case
  software cannot fix is the HAL itself failing to resume charging.
- **Guards**: the toggle refuses to enable while the adapter is disconnected
  (`Connect the charger to use this`) and below 20 % (`Keep the battery above 20%
  to use this`). An unknown battery level counts as below the floor, so a device
  that does not report a level cannot enable separation.

### 8.6 Device validation (RP5, after the integration flash)

The lease guard only accepts adb from a child of `lock.sh run`, which exports
`ASH_DEVICE_OWNER`; a bare `lock.sh acquire` does **not** export it, so every
`run.sh` call below is wrapped in `lock.sh run`. Paste the block as is.

```bash
R=scripts/device/lock.sh
run() { "$R" run worker-140 --ttl 600 -- scripts/device/run.sh "$@"; }

run adb devices -l
run adb shell getprop persist.ashfall.batt.charge_sep    # empty or 0: default off
run adb shell dumpsys package com.android.settings | grep userId   # userId=1000
# Evidence that the reflective path is dead on this build (no vendor Java stub).
run adb shell pm list libraries | grep -i factory                    # expect: no factory line
run adb shell 'ls -l /system/framework/*factory* /system_ext/framework/*factory* /vendor/framework/*factory* 2>/dev/null'   # expect: nothing
run adb shell logcat -c
run adb shell am start -a android.intent.action.POWER_USAGE_SUMMARY
```

In the UI: **Charging control** sits under **PlayTime indicator**; open it and
the support row must read **Supported** on the `.31`/`.189` vendor (factory HAL
present). The probe must name the interface it resolved, and the transaction must
report success:

```bash
run adb shell logcat -d -s ChargingSeparationHal
# expect: Resolved vendor.qti.hardware.factory@1.1::IFactory/default on lookup 1
# expect: chargerEnable(true) on vendor.qti.hardware.factory@1.1::IFactory: ok
# fail:  "Lookup 5/5 ... not registered yet" x2 + "not applied: UNSUPPORTED" -> HAL absent/masked
# fail:  "chargerEnable(...) ... failed" -> binder reached the service but transact failed
```

The support row reads `Checking…` while the lookups run (at most ~2.4 s on a
cold, never-started vendor service) and must never freeze the page. With the
battery above 20 % and the adapter connected, enable **Charging separation** and
check the effect:

```bash
run adb shell dumpsys battery | grep -E 'level:|status:|present:'   # adapter still present
run adb shell getprop persist.ashfall.batt.charge_sep              # 1
run adb shell 'find /sys/class/power_supply -name current_now -exec cat {} +'
```

Expected: the battery current magnitude collapses to ~0 A (compare with the
value captured just before enabling) while `dumpsys battery` still shows
`present: true` and an AC/USB powered status, and `level` stops climbing. Turn
the switch off:

```bash
run adb shell getprop persist.ashfall.batt.charge_sep              # 0
run adb shell 'find /sys/class/power_supply -name current_now -exec cat {} +'
```

Expected: the charge current returns (non-zero, sign as the node reports) and
`level` climbs again. Do not expect a "Could not stop charging" toast on the
normal path; it only appears when the HAL rejects the stop.

Boot re-apply, before the first unlock. Enable **Charging separation** in the UI
first (so the property is set through the supported path), then reboot and stay
at the lock screen:

```bash
run adb shell getprop persist.ashfall.batt.charge_sep              # 1 before rebooting
run adb shell reboot
run adb wait-for-device
# do NOT unlock; at the lock screen:
run adb shell getprop persist.ashfall.batt.charge_sep              # 1 on a supported firmware
run adb shell 'find /sys/class/power_supply -name current_now -exec cat {} +'   # ~0 before unlock
run adb shell logcat -d -s ChargingSeparationHal ChargingControlReceiver
# expect: "Resolved ... on lookup N" once, "chargerEnable(false) ... ok", "Re-applied charging separation at boot"
# expect: exactly one successful re-apply, not one per boot broadcast
# retry path: if LOCKED_BOOT_COMPLETED logged "no factory HAL yet", BOOT_COMPLETED
#             makes a second attempt and logs "Re-applied ... at boot"; a second
#             UNSUPPORTED is the accepted end state (stored choice kept, no loop)
```

`current_now` ~0 before unlock is the direct-boot evidence (#202): with the old
`BOOT_COMPLETED`-only receiver it stayed at the charge current until the first
unlock. If it is only ~0 after unlocking, the locked broadcast did not reach the
receiver — record it.

Unplug is deliberately **not** an auto-off path (#213). With separation on, pull
the adapter and expect:

```bash
run adb shell getprop persist.ashfall.batt.charge_sep              # still 1: the setting persists
# device now runs on battery and charges normally when replugged or not; the switch still reads on
# replug the adapter, then:
run adb shell 'find /sys/class/power_supply -name current_now -exec cat {} +'
```

Record whether charging stays stopped after the replug (the HAL may keep the
request) or resumes; the property and the switch stay `1` either way. Open the
sub-screen while unplugged: the setting is not cleared by the page.

Low-battery guard (both paths). With the level below 20 %:

```bash
# page-open guard: with the property at 1, open Settings > Battery > Charging control
run adb shell getprop persist.ashfall.batt.charge_sep              # becomes 0, switch off
run adb shell 'find /sys/class/power_supply -name current_now -exec cat {} +'   # charging resumed
# boot guard (root; if the shell write is refused, skip and record it):
run adb root
run adb shell setprop persist.ashfall.batt.charge_sep 1
run adb shell reboot
run adb wait-for-device
run adb shell getprop persist.ashfall.batt.charge_sep              # 0 after boot below 20 %
```

Also try to enable below 20 %: the toast must read `Keep the battery above 20%
to use this`, the switch stays off and the property stays `0`. Unplugged enable:
the toast must read `Connect the charger to use this` and the property must stay
`0`.

Unsupported firmware or a missing HAL:

```bash
# On a build/firmware without vendor.qti.hardware.factory (or after masking it):
run adb shell am start -a android.intent.action.POWER_USAGE_SUMMARY
# UI: "Not supported on this device" after the lookups, switch disabled, no crash/ANR.
run adb shell logcat -d -s ChargingSeparationHal | tail
# expect: "Lookup 5/5 ... not registered yet" for @1.1 and @1.0, then
#         "chargerEnable(...) not applied: UNSUPPORTED", one per user action.
# Boot-loop check with the HAL missing (root as above):
run adb root
run adb shell setprop persist.ashfall.batt.charge_sep 1
run adb shell reboot
run adb wait-for-device
# the device boots normally; the boot path tries at locked boot and again at
# BOOT_COMPLETED (both UNSUPPORTED), and the stored choice is kept (getprop
# reads 1) because this firmware cannot apply it.
```

Documentation / logs:

```bash
run adb shell logcat -d -s ChargingControlSettings ChargingControlReceiver ChargingSeparationHal ChargingSeparation
run adb shell 'dmesg | grep avc' | grep -iE 'factory|charger|ashfall_prop'  # empty
run adb shell 'logcat -d | grep "avc: denied"' | grep -iE 'factory|charge' # empty
```

The device pass is the acceptance evidence for #140: the HwBinder client resolves
`@1.1` for the `system_app` domain, the transaction actually stops charging
(`current_now` ~0 with the adapter status, including before the first unlock), it
reverts cleanly, a stale low-battery request is dropped, the unsupported firmware
shows the message instead of a dead toggle, a missing HAL does not boot-loop, and
no `avc: denied` appears for the hwservice or the property. If the RP5's
`pm list libraries` and `/system*/framework` confirm the absence of the vendor
stub (as the #201 evidence says), the direct HwBinder path is the only remaining
way to reach the HAL; nothing in Settings' manifest or `Android.bp` should be
changed to chase it.
