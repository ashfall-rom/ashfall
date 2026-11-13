# AshFall Parts (`org.ashfall.parts`)

The AshFall hardware tools as a built-in Settings module: one root entry
**Handheld → Fan / Lighting / Controller**, plus the **Fan** and **Lighting**
Quick Settings tiles.  No launcher entry, no separate-looking control app.

- Source: `aosp/vendor/ashfall/apps/AshFallParts/`
- Module: `AshFallParts` (`android_app`), added to `PRODUCT_PACKAGES` in
  `vendor/ashfall/apps.mk`
- Package: `org.ashfall.parts`, platform-signed, privileged, `system_app`
- Install path: `/system_ext/priv-app/AshFallParts`
- Contract: `docs/phase5.md` §2 (properties, socket) and §3 (this app)
- Clean-room: all code, drawables and strings in this directory were written
  for AshFall.  No Retroid code, assets or strings are used.

This document is both the implementation description and the verification
record (emulator screenshots, gamepad checks, A17 API check).

## 1. Settings integration

The app does not patch Settings.  `HandheldActivity` is discovered by
SettingsLib's `TileUtils` like any vendor Settings entry:

```xml
<intent-filter>
    <action android:name="com.android.settings.action.EXTRA_SETTINGS" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
<meta-data android:name="com.android.settings.category"
           android:value="com.android.settings.category.ia.homepage" />
<meta-data android:name="com.android.settings.title"   android:resource="@string/handheld_title" />
<meta-data android:name="com.android.settings.summary" android:resource="@string/handheld_summary" />
<meta-data android:name="com.android.settings.icon"    android:resource="@drawable/ic_handheld" />
<meta-data android:name="com.android.settings.icon_tintable" android:value="true" />
<meta-data android:name="com.android.settings.keyhint" android:value="ashfall_handheld" />
<meta-data android:name="com.android.settings.order"   android:value="1050" />
```

Details that matter:

* `TileUtils.loadActivityTiles()` only accepts **system apps**
  (`ResolveInfo.system`), so the module must be in the system image
  (`system_ext_specific: true` here).  A `/data` install of the same package
  shadows it and silently removes the entry.
* The category must be `com.android.settings.category.ia.homepage`; the tile is
  then hosted by `TopLevelSettings` (`DashboardFragmentRegistry` maps that
  category to it).
* `keyhint` is required for homepage tiles (Settings logs “Key hint missing”
  otherwise and skips the search index entry).
* The icon is tinted by Settings.  `ic_handheld` is a monochrome vector; the
  expressive homepage (Android 16+) wraps it in the same layer container it
  uses for its own entries.

`HandheldActivity` and the three page activities extend
`com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity`, the
same base class as a Settings sub-page.  The app theme is
`Theme.SubSettingsBase.Expressive` on Android 16+ (`res/values-v36/themes.xml`),
which also installs SettingsLib's preference theme overlay, so the
androidx.preference rows (title/summary/switch/slider, light and dark) render
exactly like platform rows.  The fallback theme (pre-36 SDKs and the emulator
preview) is an AppCompat Settings-like theme.

## 2. Pages

All pages are one `PreferenceFragmentCompat` per activity.  Changes are applied
immediately to the properties from §2 of the contract; there is no Apply button.

| Page | Activity | Content |
|---|---|---|
| Handheld | `HandheldActivity` | Fan / Lighting / Controller rows with live summaries, daemon note |
| Fan | `FanActivity` | main switch, live status, preset list, curve, manual %, safety |
| Fan presets | `PresetActivity` | create / rename / duplicate / delete / change mode |
| Curve | `CurveActivity` | drag-and-D-pad curve editor with live temperature marker |
| Lighting | `LightingActivity` | switch, target, colour picker, brightness, effect, speed, screen-off |
| Controller | `ControllerActivity` | tester, calibration wizard, factory reset, deadzones |
| Tester | `TesterActivity` | drawn handheld, live buttons/sticks/triggers, raw stream |
| Calibration | `CalibrationActivity` | four-step wizard, review, `SET CAL` |

### 2.1 Fan

* **Main switch** (`MainSwitchPreference`) turns the fan on to the last active
  preset (or Smart) and off by writing `persist.ashfall.fan.mode=off` and
  `persist.ashfall.fan.preset=builtin.off`.  The safety override is a daemon
  feature and stays active even in Off.
* **Status** is read every 2 s while the page is visible:
  `ashfall.status.fan.{temp,pct,rpm,safety}`.  Temperature is `tenths °C`; the
  page shows `°C`, output %, RPM (or `—`), and the safety warning.
* **Presets** are single-choice rows.  Selecting one calls `Fan.apply()`:
  `mode`, then `manual_pct` for Manual or `curve` for Smart, then the preset id
  in `persist.ashfall.fan.preset`.  The daemon only ever sees the active values.
* **Curve** row opens the curve editor for the active preset (falling back to
  the built-in Smart preset when the active one has no curve).
* **Manual output** slider is enabled only in Manual mode.
* **Safety threshold** slider is 70–95 °C with the explanation that the fan runs
  at 100 % at or above it.

### 2.2 Presets and the curve editor

Presets live only in this app (the property interface has a 92-byte value
limit).  They are stored as JSON in **device-protected storage**
(`/data/user_de/0/org.ashfall.parts/shared_prefs/fan_presets.xml`, file
`fan_presets`), so the Fan page, the tile and the daemon-facing defaults work
before the first unlock:

```json
{"version":1,"presets":[
  {"id":"builtin.off",     "name":"Off",            "mode":"off",      "manual_pct":50, "curve":"45:0,...","builtin":true},
  {"id":"builtin.quiet",   "name":"Quiet",          "mode":"quiet",    ...},
  {"id":"builtin.balanced","name":"Balanced",       "mode":"balanced", ...},
  {"id":"builtin.max",     "name":"Maximum",        "mode":"max",      ...},
  {"id":"builtin.smart",   "name":"Smart (default)","mode":"smart",    "curve":"45:0,55:30,65:55,75:80,82:100","builtin":true},
  {"id":"user.129648e1",   "name":"Preset 1",       "mode":"smart",    "curve":"48:2,55:30,...","builtin":false}
]}
```

* Built-ins are re-seeded on load and cannot be renamed or deleted; the
  built-in **Smart** curve is editable (it is the “Smart default” curve).
* A user preset is mode + `manual_pct` *or* `curve`; ids are
  `user.` + 8 hex characters (≤ 32 characters, matching the property limit).
* The editor offers Activate / Change mode / Curve (Smart only) / Rename /
  Duplicate / Delete; built-ins only get Activate / Change mode / Curve /
  Duplicate.
* Every curve change is saved immediately (and written to
  `persist.ashfall.fan.curve` when the edited preset is the active one), so
  leaving the page can never lose an edit.

Curve editor (`ui/CurveEditorView`): 2–8 points, ascending 25–95 °C, 0–100 %.
Touch drags points; with the D-pad, left/right moves the selected point along
the temperature axis, up/down changes its output, L1/R1 select the previous/next
point, A adds a point, X deletes one.  The current temperature is a dashed live
marker at the interpolated output.  The graph draws a focus ring when it owns
the focus.

### 2.3 Lighting

The UI target selector maps onto the daemon's `led.sync`:

| Target | `led.sync` | Colours written |
|---|---|---|
| Both | `1` | `color_left` and `color_right` |
| Left | `0` | `color_left` |
| Right | `0` | `color_right` |

* Main switch: on restores the last non-off effect (`ui` prefs), off writes
  `led.mode=off`.
* Colour row opens a colour picker with eight preset swatches and an HSV
  picker: preset swatches, a saturation/value square (touch or D-pad), a hue
  slider, Cancel/Set.  The row shows the left/right colours.
* Brightness 0–100, effects static/breathing/rainbow/duo, speed 1–10 (enabled
  for breathing/rainbow), and “turn off while the screen is off” writing
  `led.screen_off=off|keep`.
* Everything applies live.

### 2.4 Controller

* **Deadzones**: inner 0–30 %, outer 0–20 %, trigger 0–30 %, written to
  `persist.ashfall.pad.*` as they move.
* **Calibration** row shows the active source read with `GET CAL`
  (`user`, `factory`, `default`, or “No calibration available”).
* **Reset to factory calibration** confirms, then sends `RESET CAL`; the
  factory file is never touched.
* **Tester** (`TesterActivity`) draws a landscape RP5 outline: sticks with the
  configured inner/outer deadzone rings, D-pad, A/B/X/Y, L1/R1, analog
  L2/R2 bars, Start/Select and L3/R3.  It consumes the activity's
  `dispatchKeyEvent`/`dispatchGenericMotionEvent`, so gamepad input works
  without focus wandering.  The second data path is the `RAW ON` stream:
  raw values and the button mask are drawn next to the calibrated values.
  Select toggles the raw overlay, B/Back exits.
* **Calibration wizard** (`CalibrationActivity`) opens a socket and requests
  `RAW ON`, then runs four steps:
  1. release the sticks (2 s, centre = average of the raw samples),
  2. roll both sticks around the edge (8 s, min/max per axis),
  3. pull both triggers fully (4 s, min/max of `l2`/`r2`),
  4. review (min/centre/max per axis, trigger ranges).
  Only **Save** sends `SET CAL` with all 16 keys (`left/right_x/y_min`,
  `_center`, `_max`, `l2_min/max`, `r2_min/max`, values in raw units); Cancel
  leaves the existing calibration untouched.  A dead stream or impossible
  ranges show an inline error with “Try again”.  Closing the socket on pause or
  on the review step is not treated as an error.

## 3. Quick Settings tiles

| Tile | Label | Subtitle | State |
|---|---|---|---|
| Fan (`qs.FanTileService`) | active preset name | temperature | inactive only for Off |
| Lighting (`qs.LightingTileService`) | “Lighting” | effect name / “Off” | inactive when `led.mode=off` |

* Tap opens the dialog with `TileService.showDialog(dialog)` (window type
  `TYPE_QS_DIALOG`, so it behaves like the Internet/Bluetooth dialogs).  If the
  device is locked the action runs through `unlockAndRun`.
* **Fan dialog**: header with the active preset and temperature, a
  single-choice preset list (applies immediately), “Edit presets” (opens the
  preset editor) and Done.  The header temperature refreshes every 2 s.
* **Lighting dialog**: on/off switch, target (Both/Left/Right), preset colour
  swatches plus an **inline** custom colour picker (saturation/value square +
  hue slider; no second window, which would be hidden behind the QS dialog),
  brightness, effect, speed and Done.  All controls apply immediately.
* Long-press: `TileService` has no `onLongClick`; SystemUI resolves
  `android.service.quicksettings.action.QS_TILE_PREFERENCES` inside the tile's
  package and passes the tile component in `EXTRA_COMPONENT_NAME`.
  `qs/TilePreferencesActivity` (a no-UI trampoline) opens the Fan page for the
  Fan tile and the Lighting page for the Lighting tile.
* Dice note: dialogs are built from the tile **service** context.  The service
  declares `android:theme="@style/Theme.AshFall.QsDialog"`; the dialog theme
  uses a literal accent (a self-referential `?android:attr/colorAccent` makes
  framework TextAppearances unresolvable in that context).

## 4. Gamepad navigation

Everything is reachable with the RP5 pad and the D-pad/left stick:

| Input | Action |
|---|---|
| D-pad / left stick | move focus; rows highlight with the platform focus/ripple |
| A / D-pad center / Enter | activate the focused row / toggle / open |
| B / Back | dismisses dialogs and leaves the page (toolbar back button for touch) |
| D-pad left/right on a slider | adjust the value (the SeekBar is the focus stop) |
| Curve editor | L1/R1 select a point, left/right temperature, up/down output, A add, X delete |
| Tester | all input is displayed; Select toggles raw, B exits |
| Wizard | D-pad moves between Cancel/Save, A activates; B cancels |

The QS dialogs are ordinary focusable views in a `TYPE_QS_DIALOG` window, so the
same D-pad/A/B rules apply.  In the QS panel, D-pad focus is managed by
SystemUI.

## 5. Properties and socket used

Read/written exactly as specified in `docs/phase5.md`:

* `persist.ashfall.fan.{mode,manual_pct,curve,safety_c,preset}`
* `ashfall.status.fan.{temp,pct,rpm,safety}`
* `persist.ashfall.led.{mode,color_left,color_right,sync,brightness,speed,screen_off}`
* `persist.ashfall.pad.{deadzone_inner,deadzone_outer,trigger_deadzone}`
* `/dev/socket/ashfall_input`: `GET CAL`, `SET CAL`, `RESET CAL`, `RAW ON/OFF`

Property access uses `android.os.SystemProperties` through reflection (with a
`getprop`/`setprop` fallback); the app is platform-signed `system_app`, so the
SELinux rules owned by the native worker apply.

## 6. Verification

### 6.1 A17 API / theme check (build host, read-only)

The sources compile against the LineageOS 24 (Android 17) API surface, not only
against the SDK:

```
javac 21, -source/-target 17
  -cp prebuilts/sdk/current/public/android.jar
      out/soong/.intermediates/.../SettingsLibCollapsingToolbarBaseActivity.jar
      androidx (appcompat/preference/recyclerview/fragment/core/...) jars
  src/org/ashfall/parts/**/*.java
-> 70 classes, no errors
```

`com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity` and
`Theme.SubSettingsBase.Expressive` were also confirmed in
`frameworks/base/packages/SettingsLib` (the latter in `values-v36`, exactly the
config used by Android 17) and `CategoryKey.CATEGORY_HOMEPAGE` /
`TileUtils.loadActivityTiles()` in `packages/apps/Settings` +
`frameworks/base/packages/SettingsLib/Tile`.

### 6.2 Emulator preview

The emulator image is Android 13, not 17, so the preview uses the same
`src/` through a small Gradle build:

* `preview/` – AGP project; sources are `../../src`; the only shim is
  `preview/shims/.../CollapsingToolbarBaseActivity.java`, a Material
  collapsing-toolbar stand-in for SettingsLib's class (production always links
  the real one).  `preview/prepare.sh` copies the resources without
  `values-v36` (which references SettingsLib resources that only exist in the
  AOSP tree) and strips `sharedUserId` from a copy of the manifest.
* Build: `preview/prepare.sh && cd preview && gradle :app:assembleDebug`.
* AVD: `Retroid_Pocket_5`, 1920×1080 landscape, `wm density 360`.
* Mock daemon (`tools/mock/`, test-only C binary, never installed in the
  image): serves `/dev/socket/ashfall_input` with `GET/SET/RESET CAL` and an
  animated `RAW` stream, and is started with
  `tools/mock/build.sh --run`.  Status properties and the persistent settings
  are mocked with `adb shell setprop` (root); the preview runs with SELinux
  permissive because a debug-signed `/data` APK cannot write `persist.*`.
* To exercise the **Settings homepage entry** the preview APK is pushed to
  `/system/priv-app/AshFallParts/` on a `-writable-system` emulator (Settings
  only accepts system-app tiles), then Settings is force-stopped so
  `CategoryManager` re-reads its tile cache.

Screenshots (all 1920×1080; light and dark):

| | |
|---|---|
| ![Settings homepage](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/settings-homepage.png) | ![Handheld](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/handheld.png) |
| ![Fan](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/fan.png) | ![Fan presets](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/fan-presets.png) |
| ![Curve editor](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/curve.png) | ![Preset editor](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/presets.png) |
| ![Lighting](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/lighting.png) | ![Colour picker](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/colour-picker.png) |
| ![Controller](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/controller.png) | ![Tester](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/tester.png) |
| ![Calibration review](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/calibration-review.png) | ![Calibration saved](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/calibration-saved.png) |
| ![QS fan dialog](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/qs-fan.png) | ![QS lighting dialog](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/qs-lighting.png) |
| ![Fan, dark](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/fan-dark.png) | ![Lighting, dark](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/lighting-dark.png) |

![QS fan dialog, dark](../aosp/vendor/ashfall/apps/AshFallParts/docs/screenshots/qs-fan-dark.png)

### 6.3 Checks performed

* Settings homepage shows **Handheld — Fan, lighting, controller** and opens
  the page from the Settings task (`topResumedActivity` = `HandheldActivity`,
  Back returns to Settings).
* Handheld page summaries update from the properties
  (`Smart (default) · 56 °C`, `Static · #FF2020`, `Stick deadzone 5% · 3%`).
* Fan page: D-pad focuses rows (row LinearLayout 1920×164), A activates;
  activating Quiet wrote `mode=quiet, preset=builtin.quiet`; the saved preset
  list renders Off/Quiet/Balanced/Maximum/Smart and the user “Preset 1”.
* Preset editor: D-pad scrolled to “New preset”, A created
  `user.129648e1` and opened the action dialog (Activate/Change mode/Curve/
  Rename/Duplicate/Delete); Activate wrote `preset=user.129648e1`.
* Curve editor: D-pad right/up changed the selected point from 45 °C/0 % to
  48 °C/2 %; the change was persisted to the Smart preset JSON and written to
  `persist.ashfall.fan.curve`.
* Lighting: colour picker swatch `#2979FF` + Set wrote
  `led.color_left=2979FF` (Both/sync) and the row summary updated; effect
  selection wrote `led.mode=breathing` with speed unlocked.
* Controller: `GET CAL` source shown as “factory” from the mock daemon;
  tester shows calibrated stick values plus animated `RAW` values in its
  accessibility description; A/L1/D-pad key events are handled.
* Calibration wizard: release → rotate → trigger → review reached with sane
  ranges; Save returned “Calibration saved” from `SET CAL`; cancel path leaves
  the daemon untouched.
* QS: both tiles appear in the panel as **Smart (default) — 59 °C** and
  **Lighting — Static**; tapping opens the dialogs; the fan dialog preset list
  is D-pad navigable and applies on A; the lighting dialog exposes target,
  colour swatches, custom picker, brightness, effect and speed live.
* Dark mode: pages and the QS dialog render with the dark theme.

## 7. Build integration

`aosp/vendor/ashfall/apps.mk` gets a separate block (kept apart from the
launcher's block so the two merges do not conflict):

```make
# ---------------------------------------------------------------------------
# AshFallParts: "Handheld" Settings pages and the Fan/Lighting QS tiles.
# ---------------------------------------------------------------------------
PRODUCT_PACKAGES += \
    AshFallParts
```

`Android.bp` mirrors LineageParts: `defaults: ["SettingsLibDefaults"]`,
`platform_apis`, `certificate: "platform"`, `privileged`, `system_ext_specific`,
plus `androidx.preference`/`appcompat`/`recyclerview`.  The manifest declares
`android:sharedUserId="android.uid.system"`, the homepage activity meta-data,
the three page activities, the tester/wizard activities, the QS preferences
trampoline and the two `TileService`s.

## 8. Device test checklist

1. Settings → bottom of the homepage: **Handheld** with the fan/lighting/
   controller subtitle and a tinted icon in the expressive style.
2. Open Handheld; check the three rows show live values and open the pages;
   Back returns to Settings.
3. Fan: switch off/on; activate each built-in; check the daemon follows within
   a second (temperature/output/RPM/safety update every 2 s).  Select Manual
   and move the manual slider; edit the Smart curve and confirm the fan follows.
4. Presets: create, rename, duplicate, delete a user preset; verify it survives
   a reboot (device-protected storage) and that a preset id appears in
   `persist.ashfall.fan.preset`.
5. Safety: set the threshold to 70 °C and confirm the fan goes to 100 % above
   it, including in Off mode, then drops 10 °C below.
6. Lighting: switch on/off, change target (Both/Left/Right), pick a preset and
   a custom colour, brightness, each effect and speed; check the rings live and
   that Both uses the same colour on both sticks.
7. Screen-off: enable “turn off while the screen is off” and check the rings go
   dark with the panel and return on wake.
8. Controller: open the tester with the pad; move sticks (dots inside the
   deadzone rings), press every button/shoulder/trigger; verify the raw overlay
   via `RAW ON` shows data.
9. Calibration: run the wizard, save, verify `GET CAL` reports `user`; reset to
   factory and verify `factory`; confirm the factory file is unchanged.
10. Deadzones: set inner 15 %, outer 10 %, trigger 10 %; verify in the tester
    and in another app/emulator that the deadzone is applied by the daemon.
11. Quick Settings: add both tiles; check the Fan label is the active preset
    name and the subtitle the temperature; tap each tile, change something,
    check it applies live; long-press each tile opens the matching page.
12. Lock the device and tap a tile: it must ask for unlock before opening the
    dialog.
13. Light/dark: switch the system theme and check the pages, dialogs, the
    tester and the QS dialogs.
14. Gamepad only: complete every flow with D-pad/A/B (no touch).

## 9. Deviations and notes

* The emulator is Android 13.  Production links the real SettingsLib
  collapsing-toolbar activity and the Android 16+ expressive Settings theme;
  the preview uses an equivalent Material stand-in and the fallback theme, which
  is why the emulator screenshots show slightly different app-bar typography
  than a real A17 Settings page.  The A17 API/theme check in §6.1 covers the
  real classes.
* The preview APK is debug-signed and installed as a system app, but cannot
  hold `android.uid.system` (no platform key on the emulator).  Property writes
  are therefore demonstrated with SELinux permissive; on the device the app is
  platform-signed and `system_app`.
* QS tile dialogs cannot open a second app dialog above `TYPE_QS_DIALOG`; the
  custom colour picker is inline in the lighting dialog for that reason.  The
  in-app Lighting page uses the standalone `ColorPickerDialog`.
* The loop `tools/mock/` and `preview/` are development tooling; they are not
  installed into the image and are not referenced by `Android.bp`.
