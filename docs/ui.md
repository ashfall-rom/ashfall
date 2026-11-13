# AshFall UI: keyguard swipe and system bars on the RP5

Phase-4 `ui` worker. Covers issues **#50** (keyguard unlock swipe only registers
when perfectly vertical) and **#51** (no visible gesture pill / navigation bar or
taskbar, gestures kept). Written against v0.1.0-gsi (build `20261113-163514`) on
the RP5: 1920x1080 landscape, 360 dpi, stock Android 13 vendor, LineageOS 24.0
(Android 17) GSI.

Both fixes follow the `docs/PLAN.md` preference order. #50 cannot be done with an
overlay or property (see 1.5), so it is a small `frameworks/base` patch; #51 is a
settings-provider default, also carried as a `frameworks/base` patch because
SettingsProvider only seeds a hardcoded list of keys (see 2.4).

| File | Purpose |
|---|---|
| `patches/frameworks/base/0002-systemui-tune-falsing-for-landscape-handheld.patch` | #50: SystemUI falsing thresholds |
| `patches/frameworks/base/0003-settingsprovider-ashfall-ui-defaults.patch` | #51: `navigation_bar_hint=0`, `enable_taskbar=0` defaults |
| `aosp/vendor/ashfall/ui.mk` | documents both hooks (no generated content) |
| `docs/ui.md` | this document |

## 1. #50 - unlock swipe is direction/length sensitive

### 1.1 Symptom

On the lock screen the unlock swipe has to be almost perfectly vertical. Slightly
diagonal swipes are ignored, and short swipes do not unlock at all even when they
are vertical.

### 1.2 Root cause

A keyguard swipe is a drag of the notification panel
(`NotificationPanelViewController.endMotionEvent()`), and on release
`flingExpands()` asks `FalsingManager.isFalseTouch(UNLOCK)` before it lets the
panel collapse (which is what dismisses the keyguard). `BrightLineFalsingManager`
then runs all of `PointerCount`, `Type`, `Diagonal`, `Distance`, `Proximity` and
`ZigZag`.

Two of those thresholds are sized for a **portrait phone**, not for this panel:

* `DistanceClassifier.VERTICAL_FLING_THRESHOLD_DISTANCE_IN = 1.5f` inches. It is
  converted with the display's y-dpi and capped by
  `SCREEN_FRACTION_MAX_DISTANCE = 0.8` of the screen height:

  ```
  min(0.8 * 1080, 1.5 * 318.976) = min(864, 478.46) = 478.46 px
  ```

  478 px is **44% of the 1080 px tall landscape screen**. On a normal portrait
  phone (~2400 px tall, ~400 dpi) the same 1.5 in is 600 px = 25% of the height.
  The check uses the *Y component only*, so any horizontal component in the
  gesture shrinks the qualifying distance while the threshold stays the same -
  exactly "must go straight up".

* `ZigZagClassifier.MAX_Y_PRIMARY_DEVIANCE = .15f`: along the primary axis a
  gesture may deviate 15% of its own length. A short thumb swipe arcs far more
  than that, so the classifier rejects real swipes.

Live evidence (`dumpsys activity service com.android.systemui`, section
`FalsingManager`; the global dumpsys `falsing` service does not exist on A17):

```
BRIGHTLINE FALSING MANAGER
classifierEnabled=1
width=1920
height=1080
...
{context=DistanceClassifier reason={distanceVectors={dx=13.993164, vx=0.719473,
  dy=-258.759766, vy=-6.534017}, isHorizontal=false,
  verticalFlingThreshold=478.464020, verticalSwipeThreshold=864.0}}
{context=DistanceClassifier reason={distanceVectors={dx=-8.995117, vx=0.143515,
  dy=-189.824219, vy=-6.997551}, ... verticalFlingThreshold=478.464020 ...}}
{context=ZigZagClassifier reason={devianceX=28.000000, maxDevianceX=98.97564,
  devianceY=68.0, maxDevianceY=36.9}}
```

The first two are 190-259 px vertical swipes rejected against a 478 px
threshold; the third is a real swipe rejected because its primary-axis deviance
(68 px) exceeded the 15% allowance (36.9 px). `Recent swipes` in the same dump
shows the same gestures recorded with `falsed=1`, and the belief value climbing
towards the 0.9 "false event" threshold, which then starts rejecting taps as
well ("bad history").

Live sweep on the device (synthetic `input swipe`, 600 px long, from the bottom
centre, after a SystemUI restart to clear the falsing history):

| angle from vertical | unlock |
|---|---|
| 0 deg | yes |
| 10 deg | yes |
| 20 deg | yes |
| 30 deg | yes |
| 40 deg | no |
| 45 deg | no |
| 0 deg, 250 px short | no |
| 15 deg, 250 px short | no |

40-45 deg is the `DiagonalClassifier` band (it rejects gestures within 5 deg of
45 deg, intentionally). The short-swipe rows are the `DistanceClassifier`
distance gate.

Note on synthetic input: `input swipe` only gets a *tail* of its motion events
into the falsing collector (the reasons above show `dy=-26..-64` for a 250 px
swipe), so it under-reports the distance a finger would produce on the same
path. The user-visible failures therefore show up as "short" in the classifier
even when the injected path is longer. Real finger swipes deliver every MOVE
event; the recorded real swipes in `Recent swipes` are the better sample.

### 1.3 xdpi / ydpi

`dumpsys display` reports the panel as 1920x1080 with **320.842 x 318.976 dpi**
(density 360, `installOrientation ROTATION_90`). The 5.5" 1080p panel is
~400 ppi, so the vendor's reported physical size is off by ~25%. It is *not* the
cause of the unlock problem:

* the 0.8 screen-fraction cap dominates the 1.5 in term for the fling threshold,
  so a corrected y-dpi would make the px threshold *larger*, not smaller;
* the ZigZag allowance is a fraction of the gesture length, so dpi cancels out.

(The panel is natively portrait, so `yDpi` describes the panel's *short* axis -
another reason not to build ergonomics on these numbers.) The thresholds are
therefore tuned in proportion to the screen, not by "fixing" the dpi.

### 1.4 Fix

`patches/frameworks/base/0002-systemui-tune-falsing-for-landscape-handheld.patch`:

```java
// DistanceClassifier.java
private static final float VERTICAL_FLING_THRESHOLD_DISTANCE_IN = 0.75f;  // was 1.5f
// ZigZagClassifier.java
private static final float MAX_Y_PRIMARY_DEVIANCE = .3f;                 // was .15f
```

0.75 in = 239 px = 22% of the 1080 px height, the same proportion a portrait
phone gets from 1.5 in. 30% primary-axis deviance still rejects scribbles but
accepts a normal thumb arc.

Pocket-touch protection is kept, not disabled:

* `ProximityClassifier` still applies its long-swipe requirement when the
  proximity sensor reports the screen covered: `VERTICAL_SWIPE_THRESHOLD_DISTANCE_IN`
  stays at 3 in, capped to 864 px = **80% of the screen height**;
* `DiagonalClassifier` (±5 deg around 45 deg), `PointerCountClassifier`,
  `TypeClassifier` and the velocity/fling combination are unchanged;
* falsing is still fully enabled (`classifierEnabled=1`, history tracker intact).

The values are the defaults of the existing `device_config` flags
(`brightline_falsing_distance_vertical_fling_threshold_in`,
`brightline_falsing_zigzag_y_primary_deviance`), so they remain tunable per build
or at runtime with `device_config put systemui <flag> <value>` (SystemUI must be
restarted to re-read them, the classifiers read them in their constructors).

### 1.5 Why a patch and not an overlay/property

The classifier defaults are Java constants read through `DeviceConfigProxy`;
there is no resource for them. `DeviceConfig` values live in the SettingsProvider
data store (`/data`), not in `build.prop`: `DeviceConfig.getProperty()` goes
through `DeviceConfigServiceManager`, and the only property bridge in the tree
(`SettingsToPropertiesMapper`) covers `persist.device_config.<namespace>_native_boot.*`
for native namespaces, not `systemui`. A product makefile cannot seed a
DeviceConfig default, and a boot-time seeding service would need a new SELinux
domain to call the settings provider. A two-constant patch is the smallest
correct mechanism and keeps the runtime knobs working.

## 2. #51 - no visible navigation bar / taskbar / gesture pill

### 2.1 What draws what on this device

There is no SystemUI `NavigationBar` window on the panel. On `displayId=0` the
navigation bar **is the Launcher3 taskbar window**:

```
Window #3 Window{... Taskbar}:
  mAttrs={(0,0)(fillx54) ... ty=NAVIGATION_BAR ...}      <- 54px = 24dp
  InsetsFrameProvider: {type=navigationBars, insetsSize bottom=0, flags=[SUPPRESS_SCRIM]}
  InsetsFrameProvider: {type=mandatorySystemGestures, insetsSize bottom=72}
  InsetsFrameProvider: {type=systemGestures, insetsSize left/right=67}
```

Launcher3 treats the display as a large screen because it is **853 dp wide**
(1920 px / 2.25), even though the device is a 5.5" handheld. It therefore builds
the transient taskbar - pinned-app strip, bubble bar and the stashed handle (the
"navigation hint" pill) - and the handle window is the 24 dp strip at the bottom
that the user sees drawn over app content. The `Taskbar` window for `displayId=2`
(the separate 1280x720 virtual/second display, port 130) is a different, full
48 dp navigation bar; it is intentionally left alone.

`TaskbarStashController` reads `LineageSettings.System.NAVIGATION_BAR_HINT`:
with the hint enabled the stashed height is `taskbar_stashed_size`; with it
disabled both the stashed height and the handle view height are **0**, so nothing
is drawn *and* the window reports no `navigationBars` inset. `TaskbarManagerImpl`
gates the taskbar itself on `LineageSettings.System.ENABLE_TASKBAR` (the
LineageOS "Enable taskbar" switch). Both default to *enabled* in Launcher3's
`SettingsCache` (`SETTINGS_ENABLED_BY_DEFAULT`), because they are LineageOS
settings whose consumers pass no default of their own.

### 2.2 Live experiment (non-persistent, reverted)

Applied with `settings put system ...`, Launcher3 restarted
(`am force-stop com.android.launcher3` + HOME) so it re-read the settings, then
reverted with `settings delete system ...`:

| | default (`null`) | `navigation_bar_hint=0` + `enable_taskbar=0` |
|---|---|---|
| display 0 taskbar window | `(0,0)(fillx54)` when stashed | `(0,0)(fillx0)` |
| display 0 `navigationBars` inset | `[0,0][0,0]` (0 px) | `[0,0][0,0]` (0 px) |
| display 0 `mandatorySystemGestures` bottom | 72 px (`[0,1008][1920,1080]`) | 72 px (unchanged) |
| display 0 `systemGestures` sides (back) | 67 px left/right | 67 px left/right |
| display 2 taskbar (port 130) | `fillx108` | `fillx108` (untouched) |

Gesture checks with the settings applied (all passed):

* back: Settings root -> tap "Network & internet" (`com.android.settings/.SubSettings`)
  -> swipe in from the left edge at x=3 -> back on `com.android.settings/.Settings`;
* home: DeskClock -> swipe up from the bottom edge (y=1070 -> 400) ->
  `com.android.launcher3/.uioverrides.QuickstepLauncher`.

`navigationBars` being 0 px in both columns is expected on this device: the
taskbar already suppresses the navigation-bar inset and only contributes the
32 dp `mandatorySystemGestures` area. The visible difference is the handle/pill
drawn in the 24 dp taskbar window, which goes away with the hint disabled; apps
keep the full 1080 px height in both cases, so nothing is clipped by insets -
the clipping the user reported is the pill/taskbar drawing over app content.

Device-state caveat: until ~18:05 the RP5 was shared with the #52 launcher
worker, which switched the default HOME app, installed test APKs and repeatedly
killed/restarted Trebuchet. The `fillx54` observation was taken at session start
and the `fillx0` observation after restarting the launcher with the settings
applied; later A/B attempts were confounded by a Launcher3 process that kept
creating a 0-height nav window after the churn. The final confirmation is the
post-flash checklist below.

### 2.3 Fix

`patches/frameworks/base/0003-settingsprovider-ashfall-ui-defaults.patch` seeds
both settings from the SettingsProvider:

* `SETTINGS_VERSION` 236 -> 237, with a migration block that inserts
  `navigation_bar_hint=0` and `enable_taskbar=0` **only when the key is absent**,
  so an explicit user choice is never overwritten;
* the values come from new `R.integer.def_navigation_bar_hint` /
  `R.integer.def_enable_taskbar` resources in SettingsProvider's
  `res/values/defaults.xml` (both `0`), so a product can overlay them later;
* the migration runs on every user's settings database and therefore also fixes
  an existing `/data` (the keys do not exist on the current image - `settings get
  system navigation_bar_hint` returns `null`), not just a freshly wiped install.

Every consumer of these keys (Launcher3's `SettingsCache`, the Settings toggle
`gesture_navigation_settings.xml`, SetupWizard) then sees the same value, so the
switch in Settings shows "off" instead of disagreeing with the effective state.

### 2.4 Why SettingsProvider and not an overlay

These are not framework resources; Launcher3 resolves them via
`LineageSettings.System.getInt()` with a default supplied by its Dagger
`SETTINGS_ENABLED_BY_DEFAULT` set. SettingsProvider only seeds defaults for a
hardcoded key list in `DatabaseHelper` (and that class is frozen/deprecated), so
a LineageOS key cannot get a database default from an overlay or a `PRODUCT_*`
variable. Options considered:

* patch Launcher3's `SettingsModule` to drop the two URIs from the
  default-true set - works, but leaves the Settings app toggles showing "on" for
  an "off" state, and does not help the SetupWizard, which writes the hint value
  at the end of setup;
* `PRODUCT_PACKAGE_OVERLAYS` on SettingsProvider - cannot add a default for an
  unknown key;
* an init service running `settings put` at boot - would need a new SELinux
  domain/permissions for calling the settings provider, and races Launcher3.

A versioned SettingsProvider migration is the supported mechanism and is the
same pattern AOSP uses for its own new settings (for example
`Settings.Secure.TEXT_SHOW_PASSWORD_TOUCH`).

### 2.5 Not in scope

The Trebuchet *workspace* itself also lays out a vertical hotseat ("side space")
in this 853x480 dp landscape configuration. That is launcher code, not the
taskbar, and is being replaced by the AshFall launcher (#52); this change only
stops Trebuchet from drawing the taskbar, the bubble bar and the gesture handle.
Launcher3QuickStep stays the recents provider.

## 3. Runtime knobs (no rebuild)

```sh
# falsing (SystemUI restart required: the classifiers read them in their constructors)
device_config put systemui brightline_falsing_distance_vertical_fling_threshold_in 0.75
device_config put systemui brightline_falsing_zigzag_y_primary_deviance 0.3
device_config delete systemui brightline_falsing_distance_vertical_fling_threshold_in

# system bars (Launcher3 restart or reboot to re-read)
settings put system navigation_bar_hint 0     # hide the gesture handle
settings put system enable_taskbar 0          # disable the large-screen taskbar
settings delete system navigation_bar_hint     # back to the build default
```

## 4. Build verification request

`scripts/build.sh` (orchestrator, serial). Expected in the build output:

* both patches apply with `git am --3way` (`patches.sh apply`) - they were
  generated from the pristine tree blobs and apply cleanly to a copy of
  `frameworks/base`;
* `SettingsProvider` compiles with the new `R.integer.def_navigation_bar_hint` /
  `def_enable_taskbar` resources and `SETTINGS_VERSION = 237`;
* SystemUI compiles (only two `static final float` defaults changed);
* nothing installs into `system/usr/idc` etc. - this change adds no files to the
  image, only code changes.

## 5. Test checklist (after flashing, no wipe needed)

1. Unlock: on the lock screen, swipe up **slightly diagonally** (10-30 deg off
   vertical) from the lower third with a normal thumb motion - it unlocks.
   A short (~2 cm) vertical swipe also unlocks. A 45 deg swipe may still be
   rejected (intentional diagonal protection).
2. Pocket protection sanity: cover the proximity sensor area / put the device in
   a pocket and brush the screen - a short accidental swipe must not unlock.
3. `adb shell settings get system navigation_bar_hint` -> `0` and
   `adb shell settings get system enable_taskbar` -> `0` (also after an OTA
   without wiping /data). Settings -> System -> Buttons -> Gesture navigation
   shows "Navigation hint" **off**.
4. No pill/handle at the bottom of the screen, on the launcher and inside apps.
5. No large-screen taskbar/bubble bar/pinned-app strip appears when swiping up
   from the bottom.
6. Gestures still work: swipe from the left or right edge = back; swipe up from
   the bottom = home; swipe up and hold = recents.
7. Apps use the full 1080 px height: open a full-screen app, its bottom content
   is not hidden behind a bar (`adb shell dumpsys window | grep -A3 "type=navigationBars"`
   shows a zero-size `navigationBars` source on display 0).
8. Toggling Settings -> "Navigation hint" on brings the pill back (proves the
   default is a value, not a disabled feature).
9. The second display (port 130, 1280x720) is unchanged.
