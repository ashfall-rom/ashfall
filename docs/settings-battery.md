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
Tap to cycle] · [Averaging window, Sample interval, Battery capacity] ·
[Charging symbol, Charging cycle, Cycle interval, Meters to cycle] · Live
values.

| Row | Control | Property (written) | Values |
|---|---|---|---|
| Show indicator | switch | `persist.ashfall.batt.show` | `0` \| `1` |
| Placement | list, 5 | `persist.ashfall.batt.place` | `BATTERY_LEFT`, `BATTERY_RIGHT`, `CLOCK_LEFT`, `CLOCK_RIGHT`, `REPLACE_PERCENT` |
| Detail | list, 7 | `persist.ashfall.batt.detail` | `TIME`, `TIME_W`, `TIME_W_V`, `TIME_V`, `W`, `V`, `W_V` |
| Time format | list, 4 | `persist.ashfall.batt.fmt` | `COMPACT`, `SHORT`, `COLON`, `DETAILED` |
| Font size | slider 10–16 sp | `persist.ashfall.batt.size_sp` | `10`..`16` |
| Tap to cycle | switch | `persist.ashfall.batt.tap` | `0` \| `1` |
| Averaging window | list, 4 | `persist.ashfall.batt.window` | `1`, `3`, `5`, `10` (min) |
| Sample interval | slider 2–30 s | `persist.ashfall.batt.interval` | `2`..`30` (s) |
| Battery capacity | numeric dialog | `persist.ashfall.batt.capacity_mah` | `0` = auto, else `1`..`20000` mAh |
| Charging symbol | list, 4 | `persist.ashfall.batt.chg_symbol` | `BOLT` (`↯`), `PLUS` (`+`), `TEXT` (`CHG`), `EMOJI` (`⚡`) |
| Charging cycle | switch | `persist.ashfall.batt.chg_cycle` | `0` \| `1` |
| Cycle interval | slider 0.5–5 s | `persist.ashfall.batt.chg_cycle_ms` | `500`..`5000` ms, 100 ms steps |
| Meters to cycle | multi-select, 4 | `persist.ashfall.batt.chg_meters` | 2–3 of `TIME`, `PERCENT`, `WATTS`, `VOLTS`, written in that order |
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
