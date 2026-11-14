# AshFall Parts (`org.ashfall.parts`)

The AshFall hardware tools as a built-in Settings module: one root entry
**AshFall settings → Fan / Lighting / Controller**, plus the **Fan** and
**Lighting** Quick Settings tiles.  No launcher entry, no separate-looking
control app.

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
same base class as a Settings sub-page.  The visible root entry is **AshFall
settings** (`handheld_title`); the class name is only an implementation detail.
The app theme is
`Theme.SubSettingsBase.Expressive` on Android 16+ (`res/values-v36/themes.xml`),
which also installs SettingsLib's preference theme overlay, so the
androidx.preference rows (title/summary/switch/slider, light and dark) render
exactly like platform rows.  The fallback theme (pre-36 SDKs and the emulator
preview) is an AppCompat Settings-like theme.

### 1.1 Icons

The user-selected clean-room icons from `docs/icons/drafts/vectors/` (issue #85)
are copied into `res/drawable` under their final names:

* `ic_ashfall_fan.xml` — the framed four-blade rotor
  (`fan-c-four-blade-framed`).  Used by the Fan row on the root page, the Fan
  page's Edit/Curve rows, the preset editor's add row, the Fan QS dialog header
  and the Fan Quick Settings tile.
* `ic_ashfall_lighting.xml` — the stick ring with a sparkle
  (`lighting-b-ring`).  Used by the Lighting row/page, the colour row, the
  Lighting QS dialog header and the Lighting Quick Settings tile.

Both are 24 dp, single-colour vectors with no baked tint (`@android:color/white`
artwork): Settings tints the preference rows, and SystemUI tints the tile icon
for its active/inactive states, which is why the QS icons must stay
monochrome.  The root homepage icon (`ic_handheld`) and the app icon
(`ic_ashfall`) are unchanged; the old `ic_fan`/`ic_led`/`ic_tile_*` drawables are
removed.

## 2. Pages

All pages are one `PreferenceFragmentCompat` per activity.  Changes are applied
immediately to the properties from §2 of the contract; there is no Apply button.

| Page | Activity | Content |
|---|---|---|
| AshFall settings | `HandheldActivity` | Fan / Lighting / Controller rows with live summaries, daemon note |
| Fan | `FanActivity` | main switch, live status, presets, curve, manual %, kick, screen-off, throttling override |
| Fan presets | `PresetActivity` | create / rename / duplicate / delete / change mode |
| Curve | `CurveActivity` | drag-and-D-pad curve editor, live marker, live preview, stress test |
| Lighting | `LightingActivity` | switch, target, live colour picker, brightness, effect, speed |
| Controller | `ControllerActivity` | tester, calibration wizard, factory reset, deadzones |
| Tester | `TesterActivity` | drawn handheld, live buttons/sticks/triggers, raw stream |
| Calibration | `CalibrationActivity` | four-step wizard, review, `SET CAL` |

### 2.1 Fan

* **Main switch** (`MainSwitchPreference`) turns the fan on to the last active
  preset (or Smart) and off by writing `persist.ashfall.fan.mode=off` and
  `persist.ashfall.fan.preset=builtin.off`.  The last-resort rule (100 % at
  90 °C) is a daemon feature and stays active even in Off.  The row uses the
  framework `android.widget.Switch`, not `androidx…SwitchCompat`: the page is
  inflated with `Theme.SubSettingsBase.Expressive`, which is not an AppCompat
  theme (see §9).
* **Status** is read every 500 ms while the page is visible (the page sets
  `ashfall.ui.fan_live=1` on resume and clears it on pause, so the daemon
  publishes at its fast cadence): `ashfall.status.fan.{temp,pct,rpm,safety}`.
  Temperature is `tenths °C`; the page shows `°C`, output %, RPM (or `—`), and
  the last-resort warning.
* **Presets** are single-choice rows.  Selecting one calls `Fan.apply()`:
  `mode`, then `manual_pct` for Manual or `curve` for Smart, then the preset id
  in `persist.ashfall.fan.preset`.  The daemon only ever sees the active values.
  The built-ins are Off / Quiet / Balanced / Max / **Manual** / Smart, so the
  daemon's `manual` mode is reachable from the UI and the output slider always
  has a preset to write back to.
* **Curve** row opens the curve editor for the active preset (falling back to
  the built-in Smart preset when the active one has no curve).
* **Manual output** slider is enabled only in Manual mode; moving it writes
  `persist.ashfall.fan.manual_pct` and keeps the active Manual preset's stored
  percentage in step.
* **Behaviour**: **Spin-up kick** (`persist.ashfall.fan.kick`) and **Fan off
  while the screen is off** (`persist.ashfall.fan.screen_off` = `off`/`run`),
  both ordinary switch rows (`ui/TogglePreference`).
* **Performance**: **Override throttling** writes the non-persistent
  `ashfall.perf.throttle_override`; enabling always goes through a danger
  confirmation.  The row state is the daemon's own
  `ashfall.status.perf.throttle` (1 active, 0 restored, -1 unsupported), so it
  already shows ON after a reboot when the permanent override is applied; the
  just-written runtime value wins while the daemon catches up, and
  `Perf.permanent()` is the fallback before the first publish.  A hidden
  **Advanced** category, revealed by pressing the version row seven times
  (remembered in the app's UI prefs), holds the **Keep after reboot** switch
  (`persist.ashfall.perf.throttle_override_permanent`).  Enabling the hidden
  switch also applies the override immediately; turning the visible switch off
  releases the runtime override without touching the permanent flag.
* **Safety threshold**: removed.  The daemon keeps a fixed, undocumented
  last-resort rule (100 % at 90 °C, released at 80 °C) and the status block
  still shows when it is active; there is no slider and no property.

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
  {"id":"builtin.max",     "name":"Max",            "mode":"max",      ...},
  {"id":"builtin.manual",  "name":"Manual",         "mode":"manual",   "manual_pct":50, "builtin":true},
  {"id":"builtin.smart",   "name":"Smart (default)","mode":"smart",    "curve":"45:0,55:30,65:55,75:80,82:100","builtin":true},
  {"id":"user.129648e1",   "name":"Preset 1",       "mode":"smart",    "curve":"48:2,55:30,...","builtin":false}
]}
```

* Built-ins are re-seeded on load and cannot be renamed or deleted.  Their
  **mode is fixed** by the contract (Off/Quiet/Balanced/Max/Manual/Smart), so
  the UI does not offer “Change mode” for them and `PresetStore.save()` keeps
  the re-seeded mode/name even if a future caller tries to change it.  The
  built-in **Smart** curve is editable (it is the “Smart default” curve); the
  built-in **Manual** keeps the last output set with the Fan page slider; every
  other built-in has no curve to edit.
* A user preset is mode + `manual_pct` *or* `curve`; ids are
  `user.` + 8 hex characters (≤ 32 characters, matching the property limit).
* The editor offers Activate / Change mode / Curve (Smart only) / Rename /
  Duplicate / Delete; built-ins get Activate / Curve (Smart only) /
  Duplicate.  Duplicating a built-in creates a normal user preset whose mode is
  editable, which is the supported way to customise a fixed mode.
* The **active preset** is resolved in one place, `Fan.activePreset(store)`:
  use `persist.ashfall.fan.preset` when it still names a preset, otherwise map
  the daemon-visible `mode` to the matching built-in (Off/Quiet/Balanced/Max/
  Manual/Smart).  The Fan page, the AshFall settings summary, the QS tile, the
  QS dialog and the curve editor all use this helper, so an empty id on a fresh
  install (mode `smart`) shows Smart and an ACTIVE tile instead of Off.
* Deleting the active user preset, or “Reset presets”, clears/remaps
  `persist.ashfall.fan.preset` to the built-in for the current mode
  (`builtin.manual` for Manual), so the recorded id can never go stale.
* Every curve change is saved immediately and, when the edited preset is the
  resolved active preset **and the current mode is Smart**, written to
  `persist.ashfall.fan.curve` **and** to `ashfall.ui.fan_preview_curve`, which
  the daemon follows while the editor is open (live preview).  The preview is
  cleared in `onPause`, so the daemon falls back to the saved curve as soon as
  the editor closes.  That covers the fresh-install case where
  `persist.ashfall.fan.preset` is empty and the edited preset is the built-in
  Smart; leaving the page can never lose an edit.

Curve editor (`ui/CurveEditorView`): 2–8 points, ascending 25–95 °C, 0–100 %.
Touch drags points; with the D-pad, left/right moves the selected point along
the temperature axis, up/down changes its output, L1/R1 select the previous/next
point, A adds a point, X deletes one.  When the selected point is already at
0 %/100 %, up/down is not consumed and the framework moves the focus to the
controls below the graph, so the page stays fully gamepad-navigable.  The
current temperature is a dashed live marker at the interpolated output.  The
graph draws a focus ring when it owns the focus.

**Stress test** (bottom of the curve page): CPU and GPU load sliders, 0–100 %.
CPU load is one worker per (up to eight) core running a busy loop with the
requested duty cycle.  GPU load is an offscreen 512×512 EGL/GLES2 pbuffer that
renders a deliberately expensive fragment shader with `glFinish()` and the same
duty cycle.  A zero slider means no load; the engine starts on the first
non-zero value, auto-stops after **ten minutes**, and the page calls
`Stress.stop()` in `onPause`, so leaving the editor always ends the load.  A
fresh visit starts at 0/0 and never silently resumes.  The load is ordinary app
work: it touches neither the daemons nor any property.

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
  picker: preset swatches, a saturation/value square (touch or D-pad) and a hue
  slider.  The picker is **live**: every swatch/hue/square change writes
  `led.color_*` immediately, there is no Set step, only Done.  The row shows the
  left/right colours.
* Brightness 0–100, effects static/breathing/rainbow/duo, speed 1–10 (enabled
  for breathing, rainbow **and duo** — the daemon animates duo with
  `led.speed` too).  The screen-off option is **removed** (LEDs are always off
  while the panel is off, re-applied on screen-on by the daemon); there is no
  `led.screen_off` property any more.
* Everything applies live.

### 2.4 Controller

* **Deadzones**: inner 0–30 %, outer 0–20 %, trigger 0–30 %, written to
  `persist.ashfall.pad.*` as they move.
* **Calibration** row shows the active source read with `GET CAL`
  (`user`, `factory`, `default`, or “No calibration available”).
* **Reset to factory calibration** confirms, then sends `RESET CAL`; the
  factory file is never touched.
* **Tester** (`TesterActivity`) draws only the controls, large, with no
  handheld outline (issue #101): sticks with the configured inner/outer deadzone
  rings, D-pad, A/B/X/Y, L1/R1, exact analog L2/R2 bars, Start/Select, Home/Back
  and L3/R3, using the full landscape space.  The face buttons follow the
  physical RP5 layout: X top, Y left, A right, B bottom.  Since the 2026-11-14
  follow-up (issue #84) it sends `GRAB ON` on the raw-stream socket, so nothing
  is injected into uinput and every button is drawn from the `RAW` mask -
  including Home, Back and Select, which the system would otherwise consume.
  The live RAW counts, calibration source and GRAB state are shown between the
  two control clusters.  Leave by holding Start + Select for 2 s or tapping
  Close; the key-event path remains only as a fallback when the daemon is
  unreachable (B/Back then exits).  Details and the button-layout note:
  [parts-controller.md](parts-controller.md).
* **Calibration wizard** (`CalibrationActivity`) opens a socket and requests
  `RAW ON`, then runs four steps; each capture step now waits for the user to
  press **Start** instead of running on a timer (issue #84):
  1. release the sticks (centre = average of the raw samples),
  2. roll both sticks around the edge (min/max per axis, a quarter of the
     factory travel is required),
  3. pull both triggers fully (min/max of `l2`/`r2`, same travel check),
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
* Both dialogs use SettingsLib's **platform** dialog theme
  (`Theme.DeviceDefault.AlertDialog.SettingsLib.Expressive` via
  `res/values-v36/styles.xml`), the same theme Settings uses: it follows the
  system light/dark choice and, unlike AppCompat, the LineageOS black-theme
  RRO reaches it, so its surface is `SurfaceContainerHigh` (#1D1F26 normally,
  #171717 in pure black).  Both are **modal**: the window is focusable, dims
  the panel and takes input focus, so neither touch nor the gamepad can
  navigate the Quick Settings panel behind them.  The rounded card is the
  window background itself (one outline/shadow); the theme adds no second
  background or elevation, so there are no stray shadows.
* Their height is fitted by `ui.Ui.fitDialog()`: after the runtime rows exist,
  the card is measured and clamped to 85 % of the display height (the Fan
  dialog has a 60 % minimum so it looks like a real dialog), and only the
  `ScrollView` gets the space the fixed header and footer leave over.  The
  header, "Edit presets" and Done never scroll and are always fully visible;
  only the preset/control list scrolls when it genuinely overflows.  A plain
  `ScrollView` ignores `android:maxHeight`, which used to let the Lighting
  content push the Done button out of the window.  The colour picker dialog
  uses the same helper and a 160 dp saturation/value square.
* **Fan dialog**: header with the active preset and temperature, a
  single-choice preset list (applies immediately), “Edit presets” (opens the
  preset editor) and Done.  The header temperature refreshes every 500 ms and
  the dialog sets `ashfall.ui.fan_live` while it is open.
* **Lighting dialog**: on/off switch, target (Both/Left/Right), preset colour
  swatches plus an **inline** custom colour picker (saturation/value square +
  hue slider; no second window, which would be hidden behind the QS dialog),
  brightness, effect, speed and Done.  All controls apply immediately.  It uses
  the same hue-based picker and swatches as the Lighting page (no RGB-only
  path).
* Long-press: `TileService` has no `onLongClick`; SystemUI resolves
  `android.service.quicksettings.action.QS_TILE_PREFERENCES` inside the tile's
  package and passes the tile component in `EXTRA_COMPONENT_NAME`.
  `qs/TilePreferencesActivity` (a no-UI trampoline) opens the Fan page for the
  Fan tile and the Lighting page for the Lighting tile.
* Dice note: dialogs are built from the tile **service** context.  The service
  declares `android:theme="@style/Theme.AshFall.QsDialog"`; the dialog theme
  uses a literal accent (a self-referential `?android:attr/colorAccent` makes
  framework TextAppearances unresolvable in that context).
* Tile icons are the shared `ic_ashfall_fan`/`ic_ashfall_lighting` vectors
  (24 dp, single colour, no baked tint).  SystemUI applies its own tint for
  the active and inactive tile states, so no state-specific drawable is
  needed.

## 4. Gamepad navigation

Everything is reachable with the RP5 pad and the D-pad/left stick:

| Input | Action |
|---|---|
| D-pad / left stick | move focus; rows highlight with the platform focus/ripple |
| A / D-pad center / Enter | activate the focused row / toggle / open |
| B / Back | dismisses dialogs and leaves the page (toolbar back button for touch) |
| D-pad left/right on a slider | adjust the value (the SeekBar is the focus stop) |
| Curve editor | L1/R1 select a point, left/right temperature, up/down output, A add, X delete; up/down at 0 %/100 % moves focus to the controls below the graph |
| Tester | all input is displayed (grabbed, so no key leaves the tester); hold Start + Select 2 s or tap Close |
| Wizard | D-pad moves between Cancel/Save, A activates; B cancels |

### 4.1 Why the app does not translate A/B itself

The daemon (`aosp/vendor/ashfall/rp5/input/`) emits the standard Android
gamepad keys, `KEYCODE_BUTTON_A`/`B`/`X`/`Y`, not `DPAD_CENTER`/`BACK`.  The
uinput device is `vendor 0x2020 / product 0x3001` with no vendor key layout, so
Android applies `Generic.kl`/`Generic.kcm`.  In the A17 tree
(`frameworks/base/data/keyboards/Generic.kcm`) the gamepad section is:

```
key BUTTON_A {  base: fallback DPAD_CENTER }
key BUTTON_B {  base: fallback BACK }
```

`InputDispatcher` looks the fallback action up with
`KeyCharacterMap.getFallbackAction()` and dispatches a second key event when the
focused window returns `false` for the original key
(`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`,
“Find the fallback associated with the incoming key event”).  That happens in
the input pipeline, below every app, so it covers:

* the androidx.preference rows and the toolbar on all pages,
* every `AlertDialog` (button, list item, single-choice item),
* the colour picker swatches and buttons,
* both QS dialogs in their `TYPE_QS_DIALOG` window,
* the Settings homepage “Handheld” row (owned by `com.android.settings`).

Adding an app-level `BUTTON_A -> DPAD_CENTER` / `BUTTON_B -> BACK` rewrite
would therefore be redundant and would double-handle the keys in the views
that deliberately consume them.  The only deliberate consumers are:

* `ui/CurveEditorView`: A (and D-pad center) adds a point, X deletes one.
* `TesterActivity`: while grabbed it consumes no keys at all (there are none);
  the Start + Select chord and the Close button leave, and B/Back still exits in
  the no-daemon fallback.
* `CalibrationActivity`: B cancels the wizard.

Everywhere else A/B are not consumed, so the platform fallback performs the
contract's “A = select, B = back”.  This was confirmed against the A17 sources
above; the real RP5 button path is exercised in the device checklist with
`sendevent` on the gamepad evdev node (§8.15), not with `input keyevent`.

The QS dialogs are ordinary focusable views in a `TYPE_QS_DIALOG` window, so the
same D-pad/A/B rules apply.  In the QS panel, D-pad focus is managed by
SystemUI.

## 5. Properties and socket used

Read/written exactly as specified in `docs/phase5.md`:

* `persist.ashfall.fan.{mode,manual_pct,curve,preset,kick,kick_pct,screen_off}`
* `ashfall.ui.{fan_live,fan_preview_curve}`
* `ashfall.status.fan.{temp,pct,rpm,safety}`
* `persist.ashfall.led.{mode,color_left,color_right,sync,brightness,speed}`
* `ashfall.perf.throttle_override`, `persist.ashfall.perf.throttle_override_permanent`
* `persist.ashfall.pad.{deadzone_inner,deadzone_outer,trigger_deadzone}`
* `/dev/socket/ashfall_input`: `GET CAL`, `SET CAL`, `RESET CAL`, `RAW ON/OFF`

`persist.ashfall.fan.safety_c` and `persist.ashfall.led.screen_off` are **no
longer read or written** by this app (both were removed by §6.1 of the
contract).  Property access uses `android.os.SystemProperties` through
reflection (with a `getprop`/`setprop` fallback); the app is platform-signed
`system_app`, so the SELinux rules owned by the native worker apply.

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
-> 94 classes, no errors
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
| ![Settings homepage](screenshots/settings-homepage.png) | ![Handheld](screenshots/handheld.png) |
| ![Fan](screenshots/fan.png) | ![Fan presets](screenshots/fan-presets.png) |
| ![Curve editor](screenshots/curve.png) | ![Preset editor](screenshots/presets.png) |
| ![Lighting](screenshots/lighting.png) | ![Colour picker](screenshots/colour-picker.png) |
| ![Controller](screenshots/controller.png) | ![Tester](screenshots/tester.png) |
| ![Calibration review](screenshots/calibration-review.png) | ![Calibration saved](screenshots/calibration-saved.png) |
| ![QS fan dialog](screenshots/qs-fan.png) | ![QS lighting dialog](screenshots/qs-lighting.png) |
| ![Fan, dark](screenshots/fan-dark.png) | ![Lighting, dark](screenshots/lighting-dark.png) |

![QS fan dialog, dark](screenshots/qs-fan-dark.png)

### 6.3 Checks performed

* Settings homepage shows **Handheld — Fan, lighting, controller** and opens
  the page from the Settings task (`topResumedActivity` = `HandheldActivity`,
  Back returns to Settings).
* Handheld page summaries update from the properties
  (`Smart (default) · 56 °C`, `Static · #FF2020`, `Stick deadzone 5% · 3%`).
* Fan page: D-pad focuses rows (row LinearLayout 1920×164), D-pad center
  activates; activating Quiet wrote `mode=quiet, preset=builtin.quiet`; the
  saved preset list renders Off/Quiet/Balanced/Max/Smart and the user
  “Preset 1”.  (The emulator has no RP5 pad; it was driven with
  `KEYCODE_DPAD_CENTER`/`BACK`.  The hardware A/B path is the platform
  `Generic.kcm` fallback described in §4.1 and is verified on the RP5 in
  §8.15.)
* Fresh state: with `persist.ashfall.fan.preset` empty and mode `smart`, the
  Fan page, the Handheld summary and the QS tile all resolve to
  **Smart (default)**; editing the Smart curve with the empty id wrote
  `persist.ashfall.fan.curve` immediately (issue #76), and deleting the active
  user preset remapped `persist.ashfall.fan.preset` to the built-in for the
  current mode (issue #79).
* Preset editor: D-pad scrolled to “New preset”, D-pad center created
  `user.129648e1` and opened the action dialog (Activate/Change mode/Curve/
  Rename/Duplicate/Delete); Activate wrote `preset=user.129648e1`.  Built-ins
  show Activate/Curve/Duplicate only (no Change mode, issue #78).
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
* A/B premise check (#77, build host, read-only): A17
  `frameworks/base/data/keyboards/Generic.kcm` maps `BUTTON_A` to
  `fallback DPAD_CENTER` and `BUTTON_B` to `fallback BACK`, and
  `InputDispatcher.cpp` dispatches that fallback when the focused window does
  not handle the key.  No app-level A/B translation is therefore needed or
  added; only `CurveEditorView`, `TesterActivity` and `CalibrationActivity`
  consume A/B deliberately (§4.1).  The homepage row and all dialogs are
  covered by the same input-pipeline fallback.

### 6.4 Review fixes (post-build review)

* **#76** – `CurveActivity` now resolves the active preset with
  `Fan.activePreset()` and writes `persist.ashfall.fan.curve` whenever the
  edited preset is that preset and the mode is Smart.  Verified with an empty
  `persist.ashfall.fan.preset` (fresh-install state) and mode `smart`: a
  D-pad edit changed the property from the default curve to
  `49:3,55:30,65:55,75:80,82:100` with the preset id still empty.
* **#77** – premise checked before changing code (§4.1).  No app-level A/B
  translation was added; the platform `Generic.kcm` fallback does the work.
  Verified on the emulator by injecting the actual gamepad key codes:
  `KEYCODE_BUTTON_A` (96) activated the focused **Quiet** row
  (`mode=quiet, preset=builtin.quiet`) and `KEYCODE_BUTTON_B` (97) left the
  page, i.e. the fallback path, not `DPAD_CENTER`/`BACK`.
* **#78** – built-ins no longer offer **Change mode** (verified: the built-in
  Smart action list is Activate/Curve/Duplicate), `PresetStore.save()` keeps
  the fixed built-in mode/name, and the built-in label is **Max**.  The list
  renders Off/Quiet/Balanced/Max/Smart (default).
* **#79** – the tile, QS dialog, Fan page, Handheld summary and curve editor
  all use `Fan.activePreset()`.  With an empty preset id and mode `smart` the
  QS tile label is **Smart (default)** (not Off) and the tile is ACTIVE.
  Deleting the active user preset remapped
  `persist.ashfall.fan.preset` from `user.129648e1` to `builtin.smart`;
  “Reset presets” did the same from `user.cf4e9b04`.
* **A17 page crash** – `preference_main_switch.xml` used
  `androidx.appcompat.widget.SwitchCompat`, which cannot inflate under
  `Theme.SubSettingsBase.Expressive` (not an AppCompat theme).  It and the QS
  lighting dialog switch are now the framework `android.widget.Switch`; every
  page and dialog was re-checked for AppCompat/Material widgets and only
  framework widgets remain in Settings-themed layouts.

### 6.5 Real-device verification (RP5, A17 build 20261114-002243)

Tested on the RP5 (serial `2f184581`, `ro.build.version.sdk=37`, no root) with
`integ/phase5` = `4826f21` + `a3aef7c` + `3d9ee62` and the native daemons
(#62).  Gamepad input was injected on the real evdev node
(`/dev/input/event4`, “AshFall RP5 Gamepad”) with `sendevent`, never
`input keyevent`; `am start` used `--display 0`.  All checks below passed
except where noted.

| Area | Result |
|---|---|
| Settings homepage | **Handheld — Fan, lighting, controller** row appears at the bottom of the homepage (after Accessibility) with the tinted icon; tapping opens `HandheldActivity` in the Settings task. |
| All pages | Handheld, Fan, Lighting and Controller open on A17 without `InflateException` (the `SwitchCompat` crash is gone; the framework `Switch` renders). |
| Fan live status | Temperature/output/RPM/safety update every 2 s (`43 °C`, `0 %`, `0 rpm` in Off). |
| Fan presets | Built-ins render Off/Quiet/Balanced/Max/Smart. Activating Quiet wrote `mode=quiet, preset=builtin.quiet` and the daemon wrote `state=1, duty=4920` (spin-up kick). User preset `user.33d52efd` was created, renamed, duplicated, activated, and deleted; deleting the active preset remapped `persist.ashfall.fan.preset` to `builtin.smart`. |
| Curve editor | Opened for the active user preset and for `builtin.smart`; real D-pad HAT events changed points, and the changes reached `persist.ashfall.fan.curve` (`46:1…` then `47:2…`). With an empty/derived active id this covers #76 on hardware. Reset restored the default curve. |
| Lighting | Main switch off/on wrote `led.mode=off/static`; target Left wrote `sync=0` and `color_left=2979FF`; Both wrote `sync=1` with both colours; brightness 60/79 and breathing speed 8 applied. Restored to `static`, `FF2020` both, `sync=1`, `brightness=60`, `speed=5`, `screen_off=off`. |
| Controller tester | Real stick and trigger input displayed (`Left 0.61 -0.31`, `R2 0.78`, pressed A); the daemon `RAW ON` values were shown (`raw lx 140 ly 25 …`) and Select toggled the raw overlay. |
| Calibration | Wizard opens, captures the release/rotate/trigger phases and validates them against the **raw MCU** stream; injected uinput events do not move the raw sticks, so the review step was not reached. Cancelled at the retry step, which leaves the calibration untouched: the page still reports `Source: default` and no `SET CAL` was sent. |
| Deadzones | Inner 15 % then 5 %, outer 10 % then 3 % reached `persist.ashfall.pad.*`; left at 5/3/3. |
| QS tiles | Both tiles are in the panel (`Lighting`, active preset name). Dialogs open; the Fan header showed **Smart (default) · 59 °C** in the default state and **Off · 44 °C** after the final reset; long-press on the Fan tile opens the Fan page. |
| Gamepad A/B/D-pad | A (304) selected the focused Quiet row on the Fan page and in the QS Fan dialog, and selected Right in the QS Lighting target group; B (305) left the Curve page, the tester and the QS dialogs; HAT events moved focus and edited curve points. This is the `Generic.kcm` fallback path, not `DPAD_CENTER`/`BACK`. |
| Light/dark | Dark-mode Fan page opens and renders (device screenshot captured); night mode restored to off. |

Found on hardware and fixed in `feat/parts-61` (not in the tested build):

* **Preference order after a rebuild.** `FanFragment` and `PresetActivity`
  reuse the Edit/Curve and New/Reset `Preference` objects.  After an
  activation/create, `PreferenceCategory.addPreference()` kept their old order
  values, so they sorted **above** the freshly added preset rows (observed:
  “Reset presets” above “Off”; “Curve” above the preset list).  Both fragments
  now reset those rows to `Preference.DEFAULT_ORDER` before re-adding them, so
  the rows stay after the preset list.
* **Curve editor initial focus.** On A17 the window/toolbar could take focus
  during the first layout pass, so the first D-pad press moved focus instead of
  a curve point until the graph was tapped.  `CurveActivity` now re-requests
  focus with `mEditor.post()`.

Final device state: `persist.ashfall.fan.mode=off`,
`persist.ashfall.fan.preset=builtin.off`, default curve; LED `static`
`FF2020`/`FF2020`, `sync=1`, `brightness=60`, `speed=5`, `screen_off=off`;
deadzones 5/3/3; calibration source `default` (unchanged); both daemons
running; fan hardware `state=0 duty=0`.

### 6.6 Phase-5 follow-ups v2 (#83)

The user-test changes of `docs/phase5.md` §6.3 were implemented on
`feat/settings-v2-83`.  Verification performed for this change:

* **A17 API/theme check (build host, read-only).**  All sources, including the
  new `hw/Perf`, `hw/Stress`, `ui/TogglePreference` and the `Ui.fitDialog()`
  sizing helper, compile against
  `prebuilts/sdk/current/public/android.jar`, SettingsLib
  (`CollapsingToolbarBaseActivity`, `SettingsTheme`) and the androidx
  appcompat/preference/recyclerview/fragment/lifecycle jars from
  `out/soong/.intermediates` with javac 21 (`-source/-target 17`):
  94 classes, no errors.  (An `R` class generated from the real `res/` tree was
  used, so every resource name referenced from Java had to exist.)
* **Resource compile (build host, read-only).**  `aapt2 compile --dir res`
  succeeds for all 26 resource files; the only unresolved references are the
  external SettingsLib/AppCompat themes (`Theme.SubSettingsBase.Expressive`,
  `Theme.AppCompat.DayNight.*`), as expected outside the platform build.
* **Icons (issue #85 selection).**  `docs/icons/drafts/vectors/fan-c-four-blade-framed.xml`
  and `lighting-b-ring.xml` are copied verbatim (minus the preview-only
  `android:tint`) to `res/drawable/ic_ashfall_fan.xml` and
  `res/drawable/ic_ashfall_lighting.xml`; both compile with `aapt2` and are
  single-colour 24 dp vectors so Settings and SystemUI can tint them.  They
  replace the Fan/Lighting rows, page/dialog glyphs and both QS tile icons.
* **Theme parity (#97).**  The v36 theme now sets `android:colorBackground` and
  `android:windowBackground` to
  `@color/settingslib_materialColorSurfaceContainer`, and both content layouts
  draw the same colour.  `?android:attr/colorBackground` resolved to black in
  this app (measured #000000 against Settings' #171717), so it was replaced by
  the explicit SettingsLib colour (`system_surface_container_dark` =
  `#17191F`), which the LineageOS black RRO turns into `#000000`.  The earlier
  claim that the attribute alone followed pure black was wrong and is fixed
  above.
* **Dialogs (#97/#98).**  On v36, `Theme.AshFall.Dialog` is re-parented in
  `res/values-v36/styles.xml` to
  `Theme.DeviceDefault.AlertDialog.SettingsLib.Expressive` with
  `android:colorBackground = settingslib_materialColorSurfaceContainerHigh`
  (#1D1F26, #171717 in pure black); `alertDialogTheme` points at the same
  platform theme for the preset editor and throttle confirmation.  AppCompat
  is only the pre-36 preview fallback.  `Ui.fitDialog()` now runs after the
  runtime rows are built, clamps the card to 85 % of the screen (60 % minimum
  for the Fan dialog) and gives the remainder to the scroll view, so the
  header and Done footer never scroll; the saturation/value square is 160 dp.
* **Live fan status.**  `ashfall.ui.fan_live=1` is written on resume/onStart of
  the Fan page, the curve editor and the QS Fan dialog and cleared when they
  leave; their polling period is 500 ms.  A counter in `Fan.setLive()` keeps
  the flag set while more than one fan UI is visible.
* **Live curve preview.**  Every editor change writes the curve to
  `ashfall.ui.fan_preview_curve` (and the saved property) when the edited preset
  is the active Smart preset; `onPause` clears the preview.
* **Manual mode.**  A `builtin.manual` preset is seeded, `Fan.builtinPresetId`
  maps `manual` to it, and the Fan page slider keeps its stored percentage in
  step, so the daemon's Manual mode is reachable without creating a preset.
* **Stress test.**  `hw/Stress` implements the CPU duty-cycle workers and the
  offscreen GLES2 shader load, a ten-minute cap and a `stop()` used from
  `CurveActivity.onPause`; the UI is two 0–100 sliders on the curve page and a
  live countdown.  On this machine the page and load paths were only statically
  checked (device/emulator run pending; see below).
* **Not yet run on hardware for this change:** the RP5 verification of the
  stress test, the throttling override and the pure-black theme is pending a
  serialized device slot; the items are in the §8 checklist.  No device state
  was modified by this change.

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
plus `androidx.preference`/`appcompat`/`recyclerview`.  R8 is enabled
(`optimize: true`) and `shrink_resources: true` trims the SettingsLib/Material
resource table that linking pulls in; `preview/`, `tools/` and this
documentation are not resource dirs and are never packaged.  The manifest declares
`android:sharedUserId="android.uid.system"`, the homepage activity meta-data,
the three page activities, the tester/wizard activities, the QS preferences
trampoline and the two `TileService`s.

## 8. Device test checklist

1. Settings → bottom of the homepage: **AshFall settings** with the
   fan/lighting/controller subtitle and a tinted icon in the expressive style.
2. Open AshFall settings; check the three rows show live values and open the
   pages; Back returns to Settings.
3. Fan: switch off/on; activate each built-in (including **Manual**, then move
   the output slider); check the daemon follows within a second
   (temperature/output/RPM/safety update every 0.5 s while the page is open).
   Edit the Smart curve and confirm the fan follows the live preview.
4. Presets: create, rename, duplicate, delete a user preset; verify it survives
   a reboot (device-protected storage) and that a preset id appears in
   `persist.ashfall.fan.preset`.
5. Behaviour: toggle **Spin-up kick** and **Fan off while the screen is off**;
   confirm `persist.ashfall.fan.kick`/`screen_off` change and that the fan
   stops/resumes with the panel.
6. Throttling: **Override throttling** shows the danger confirmation, enabling
   writes `ashfall.perf.throttle_override=1`, and a reboot clears it.  Press the
   version row seven times, enable **Keep after reboot** and confirm the
   permanent property; turn the visible switch off afterwards.
7. Stress test: on the curve page set CPU 50 % / GPU 50 % and watch the
   temperature rise; set both to 0, leave the page and confirm the load stops
   (`top`/GPU idle) and the countdown/stop after 10 minutes works.
8. Lighting: switch on/off, change target (Both/Left/Right), pick a preset and
   a custom colour (it applies as you drag the hue/square, no Set), brightness,
   each effect and speed; check the rings live and that Both uses the same
   colour on both sticks.  Confirm there is no screen-off option.
9. Controller: open the tester with the pad; move sticks (dots inside the
   deadzone rings), press every button/shoulder/trigger; verify the raw overlay
   via `RAW ON` shows data.
10. Calibration: run the wizard, save, verify `GET CAL` reports `user`; reset to
    factory and verify `factory`; confirm the factory file is unchanged.
11. Deadzones: set inner 15 %, outer 10 %, trigger 10 %; verify in the tester
    and in another app/emulator that the deadzone is applied by the daemon.
12. Quick Settings: add both tiles; check the Fan label is the active preset
    name and the subtitle the temperature; tap each tile, change something,
    check it applies live, that the Done button is fully visible and that there
    is no stray double shadow; long-press each tile opens the matching page.
13. Modal dialogs: with a QS dialog open, push D-pad and A/B and confirm focus
    never moves into the Quick Settings panel behind it; do the same for the
    in-app colour picker.
14. Lock the device and tap a tile: it must ask for unlock before opening the
    dialog.
15. Light/dark/pure black: switch the system theme (including LineageOS pure
    black) and check the pages match the rest of Settings, and that the dialogs
    and the QS dialogs follow it.
16. Gamepad only: complete every flow with D-pad/A/B (no touch), including
    leaving the curve editor graph with D-pad down and operating the stress
    sliders.
17. Real A/B path: on the RP5 send the face buttons with
    `sendevent` on the “AshFall RP5 Gamepad” evdev node (not
    `input keyevent`) and confirm A selects on every page, both QS dialogs and
    the Settings homepage row, and B dismisses dialogs / leaves pages.  This
    exercises the `Generic.kcm` `BUTTON_A -> DPAD_CENTER`,
    `BUTTON_B -> BACK` fallback from §4.1.

## 9. Deviations and notes

* **Framework widgets only in Settings-themed layouts.**
  `Theme.SubSettingsBase.Expressive` is a platform/SettingsLib theme, not an
  AppCompat or MaterialComponents theme, so `SwitchCompat` throws
  `InflateException` in its constructor on the real A17 build.  Layouts
  inflated with that theme (`preference_*.xml`) use `android.widget.*` only.
  The QS lighting dialog uses the framework switch too.  The QS dialog theme
  remains an AppCompat dialog theme (it works on A17), but no AppCompat widget
  is inflated there either.  This is why the built-in main switch is a
  framework `Switch` rather than SettingsLib's expressive `MaterialSwitch`
  (which that library inflates with an explicit
  `@style/Theme.Material3.DynamicColors.DayNight`).
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
* **Theme parity uses SettingsLib's colours explicitly.**  The app does not
  invent a grey and does not rely on `?android:attr/colorBackground` (in this
  app that resolved to black, not the Settings surface — issue #97).
  `android:colorBackground`, `android:windowBackground` and both content
  layouts use `@color/settingslib_materialColorSurfaceContainer`
  (`@android:color/system_surface_container_dark` = `#17191F` on A17 night).
  LineageOS' pure-black mode is `LineageBlackTheme`, an RRO that sets
  `system_surface_container_dark` to `#000000`
  (`packages/overlays/Lineage/customizations/BlackTheme/res/values-night/colors.xml`),
  so the pages turn pure black at the same moment the rest of Settings does.
* **Dialogs use a platform SettingsLib theme, not AppCompat.**  The AppCompat
  dialog theme is only the pre-36 preview fallback.  On Android 16+,
  `res/values-v36/styles.xml` re-parents `Theme.AshFall.Dialog` (and therefore
  `Theme.AshFall.QsDialog`) to
  `Theme.DeviceDefault.AlertDialog.SettingsLib.Expressive`, sets the card
  colour to `settingslib_materialColorSurfaceContainerHigh`, and points
  `android:alertDialogTheme` at the same platform theme for the preset editor
  and the throttling confirmation.  AppCompat's own resources are not touched
  by the LineageOS black RRO, which is why the old dialogs stayed grey; the
  SettingsLib/DeviceDefault surface is #1D1F26 normally and #171717 in pure
  black.  Modal behaviour comes from a focusable, dimming window, not from
  consuming the gamepad keys in the app.
* **Stress test scope.**  It is intentional load, not a benchmark: the CPU
  side uses a duty cycle per core and the GPU side renders an offscreen shader
  with `glFinish()`, so the reported percentage is approximate.  It runs at
  normal app priority (no `Process.setThreadPriority`), so it can be preempted
  like any other app; that keeps it safe and is enough to heat the SoC for
  curve tuning.  A device without a working EGL pbuffer simply produces no GPU
  load and logs a warning.
* **Throttling override is two switches.**  The visible switch writes the
  non-persistent `ashfall.perf.throttle_override`; the hidden one writes
  `persist.ashfall.perf.throttle_override_permanent` and also enables the
  runtime override so its effect is immediate.  Turning the visible switch off
  does not clear the permanent flag (it only releases the runtime override);
  the hidden switch is the only way to change the boot behaviour.  This follows
  §6.1 of the contract.
* **Unused legacy strings** (`fan_safety_*`, `lighting_screen_off_*`, `set`
  usage in the colour picker) were removed with the features, so the resource
  table does not advertise controls that no longer exist.
* **Cross-worker dependency (outside this app).**  `persist.ashfall.fan.{kick,
  kick_pct,screen_off}` already fall under the `persist.ashfall.` property
  context and `set_prop(system_app, ashfall_prop)`, but `ashfall.ui.*` and
  `ashfall.perf.*` need new property types (`ashfall_ui_prop`,
  `ashfall_perf_prop`), contexts and `set_prop(system_app, …)` rules in
  `aosp/vendor/ashfall/sepolicy/`, plus readers in
  `aosp/vendor/ashfall/rp5/hw/ashfall_hwd.c` (per `docs/phase5.md` §6.1).
  Neither file is owned by this change, so until they land the app's writes are
  best-effort and the daemon does not yet observe kick/screen-off, the 500 ms
  cadence, the live preview or the throttling override.  `ashfall-hwd` also
  still reads the removed `persist.ashfall.fan.safety_c` and must switch to its
  fixed 90 °C/80 °C last-resort rule; the app no longer writes that property.
  These are merge/sequencing items, not app defects.
