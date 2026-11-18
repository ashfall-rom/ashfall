# AshFall UI: keyguard, system bars and home transitions on the RP5

Phase-4 `ui` worker. Covers issues **#50** (keyguard unlock swipe only registers
when perfectly vertical), **#51/#63** (no visible gesture pill / navigation bar
or taskbar, gestures kept) and **#64** (seamless HOME with the third-party
AshFall launcher). Written against v0.1.0-gsi (build `20261113-163514`) on the
RP5: 1920x1080 landscape, 360 dpi, stock Android 13 vendor, LineageOS 24.0
(Android 17) GSI.

Both original fixes follow the `docs/PLAN.md` preference order. #50 cannot be
done with an overlay or property (see 1.5), so it is a small `frameworks/base`
patch. #51/#63 is a `LineageSettingsProvider` default (the two keys are
LineageSettings, not AOSP Settings - see 2.4), plus one build-time SetupWizard
overlay because setup writes the hint itself. This document also covers #64,
the seamless HOME transition with the third-party AshFall launcher (section 3).

| File | Purpose |
|---|---|
| `patches/frameworks/base/0002-systemui-tune-falsing-for-landscape-handheld.patch` | #50: SystemUI falsing thresholds |
| `patches/lineage-sdk/0001-lineagesettings-provider-ashfall-navigation-defaults.patch` | #51/#63: `navigation_bar_hint=0`, `enable_taskbar=0` defaults and the 24 -> 25 DB migration |
| `aosp/vendor/ashfall/overlay/no-rro/packages/apps/SetupWizard/app/src/main/res/layout/setup_navigation.xml` | #63: setup's "hide gesture hint" switch defaults to on |
| `patches/packages/apps/Launcher3/0001-launcher3-keep-external-display-taskbar.patch` | #63: only the primary display follows `enable_taskbar=0`; the external display keeps its taskbar |
| `patches/frameworks/base/0003-settingsprovider-keep-settings-version.patch` | keeps the AOSP settings DB version at 237 after removing the old seed |
| `patches/frameworks/base/0004-wm-shell-instant-third-party-home.patch` | #64: no default task-open animation for a third-party HOME |
| `patches/packages/apps/Launcher3/0002-launcher3-drop-home-candidacy.patch` | #81: Trebuchet is not a HOME app; Quickstep keeps recents |
| `aosp/vendor/ashfall/overlay/no-rro/frameworks/base/core/res/res/drawable-*/default_wallpaper.png` | #81: AshFall default wallpaper replaces the LineageOS one |
| `aosp/vendor/ashfall/ui.mk` | overlay wiring and the list of hooks |
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
48 dp navigation bar; it is intentionally left alone. `enable_taskbar` is a
single global key, so a Launcher3 patch keeps that external-display taskbar
enabled even when the primary display follows the product default `0` (it is
that display's only navigation bar).

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

Correction after #63: this experiment used the AOSP `settings` CLI, which
writes `Settings.System`. Launcher3 actually reads `LineageSettings.System`
(`content://lineagesettings`), so the setting change described here cannot be
what made the handle disappear; the A/B was confounded by the launcher churn
described below. The *end state* observed (a 0-height taskbar window, zero
`navigationBars` inset, full-height apps) is still valid, but it is reached by
the LineageSettings values in section 2.3, not by `settings put system`.

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

### 2.3 Fix as shipped (#63): seed LineageSettingsProvider, not the AOSP table

`navigation_bar_hint` and `enable_taskbar` are `LineageSettings.System` keys: they
live in `lineagesettings.db` and are served by
`lineage-sdk/packages/LineageSettingsProvider`, not by the AOSP
`SettingsProvider`. The old #51 patch wrote them into the AOSP `system` table,
which is why `settings get system navigation_bar_hint` returned `0` while
`content query --uri content://lineagesettings/system` still had the pill value.
Two changes are needed, because two different code paths write the key:

1. **Provider defaults and migration** -
   `patches/lineage-sdk/0001-lineagesettings-provider-ashfall-navigation-defaults.patch`:
   * fresh database: `LineageDatabaseHelper.loadSystemSettings()` inserts
     `navigation_bar_hint=0` and `enable_taskbar=0`, with the values from new
     `R.integer.def_navigation_bar_hint` / `def_enable_taskbar` resources in
     `res/values/defaults.xml`;
   * existing database: `DATABASE_VERSION` 24 -> 25. The migration replaces
     `navigation_bar_hint` with `0` once (see below) and inserts
     `enable_taskbar=0` only when the key is absent. `enable_taskbar` is written
     only by the user-facing toggle, so an explicit choice is preserved.
   * after this, Launcher3's `SettingsCache`, the Settings/LineageParts toggle
     and SetupWizard all read the same provider.

   The one-time hint reset is deliberate: the SetupWizard always wrote
   `navigation_bar_hint=1` (its "hide gesture hint" switch is unchecked by
   default and `onNextPressed()` writes `if (hideHint) 0 else 1`), including on
   installs where the user never touched the setting. That write is the `1`
   seen in #63. Lowering the database version instead of migrating would make
   the provider recreate the database, so this is a normal Lineage versioned
   migration (the same pattern Lineage uses for its own default changes).

2. **SetupWizard default** -
   `aosp/vendor/ashfall/overlay/no-rro/packages/apps/SetupWizard/app/src/main/res/layout/setup_navigation.xml`:
   the "hide gesture hint" `MaterialSwitch` now starts checked, so a fresh
   install writes `navigation_bar_hint=0` at the end of setup instead of running
   over the new provider default with `1`. The overlay is statically merged
   (see `aosp/vendor/ashfall/ui.mk`), not a runtime RRO: Lineage enables
   `PRODUCT_ENFORCE_RRO_TARGETS := *`, and a layout is not a resource a runtime
   overlay should be replacing here.

3. **External display** -
   `patches/packages/apps/Launcher3/0001-launcher3-keep-external-display-taskbar.patch`:
   `enable_taskbar` is one global LineageSettings key, but the second display's
   taskbar is its navigation bar. `TaskbarActivityContext` now only honours the
   switch on the primary display; a secondary/external display keeps its taskbar
   (the stock behaviour), as before.

The old `patches/frameworks/base/0003-settingsprovider-ashfall-ui-defaults.patch`
is replaced by
`0003-settingsprovider-keep-settings-version.patch`. It no longer seeds the AOSP
table, but keeps `SETTINGS_VERSION = 237` and consumes `236 -> 237` as a no-op:
devices flashed with the previous build already have their AOSP settings
database at 237, and either lowering the version or leaving a version without a
migration step makes `UpgradeController` drop and recreate the whole settings
database.

### 2.4 Why this mechanism and not the alternatives

* **Launcher3 patch** (remove the two URIs from `SETTINGS_ENABLED_BY_DEFAULT`):
  only changes Launcher3's fallback; the provider, LineageParts and SetupWizard
  would still disagree about the value, and SetupWizard writes `1` explicitly.
* **AOSP SettingsProvider** (the old #51 patch): wrong database. Nothing on this
  device reads the AOSP `navigation_bar_hint` row.
* **Runtime RRO for SetupWizard**: with `PRODUCT_ENFORCE_RRO_TARGETS := *` the
  overlay becomes an RRO APK; static product overlays are the supported way to
  replace a layout resource at build time, and the `no-rro` prefix is already
  the Lineage convention for that (see `vendor/lineage/overlay/no-rro`).
* **init service running `settings put` at boot**: needs a new SELinux domain
  and races SetupWizard/Launcher3; a provider migration is the supported path.
* **LineageSettingsProvider defaults only**: correct for fresh `/data`, but
  SetupWizard overwrites the hint during setup. Hence the overlay above.

### 2.5 Not in scope

The Trebuchet *workspace* itself also lays out a vertical hotseat ("side space")
in this 853x480 dp landscape configuration. That is launcher code, not the
taskbar, and is being replaced by the AshFall launcher (#52); this change only
stops Trebuchet from drawing the taskbar, the bubble bar and the gesture handle.
Launcher3QuickStep stays the recents provider.

## 3. #64 - seamless HOME with the AshFall launcher

### 3.1 Symptom

Going home from an app (gesture or gamepad Home) showed the outgoing app and
AshFall side by side for ~0.6 s; Launcher3's old home task could be seen behind
menus opened during the transition. The launcher worker captured
`ShellRecents: RecentsTransitionHandler.startAnimation: no controller found`,
followed by `DefaultTransitionHandler` animating the home task, and
`OverviewComponentObserver.mIsHomeAndOverviewSame=false`. Setting
`transition_animation_scale=0` removed it, which proves it is the shell's
task-open animation, not app code.

### 3.2 Root cause

With a third-party default HOME, Quickstep's `OverviewComponentObserver` takes
the fallback path (`mIsHomeAndOverviewSame=false`, overview =
`RecentsActivity`). Starting home is a normal activity start of a task whose
activity type is `ACTIVITY_TYPE_HOME`, so no Quickstep remote animation owns the
transition and `RecentsTransitionHandler` has no controller. The shell's
`DefaultTransitionHandler` then loads the generic AOSP task-open animations:

* `task_open_enter.xml`: the incoming home task translates from `105%` to `0`;
* `task_open_exit.xml`: the outgoing app translates from `0` to `-105%`;
* both 500 ms, i.e. the app and home are both on screen while they cross.

`FallbackSwipeHandler` passes `ActivityOptions.makeCustomAnimation(context, 0,
0)` for the home intent, but a custom animation only applies to activity-level
changes, and only to a task-level change when `overrideTaskTransition` is set
(it is not here). The home launch is a task-level `TRANSIT_OPEN`, so the generic
task-open enter/exit animations still run; the outgoing app (and the stale
Launcher3 home task in the home root task) still get the exit animation. Gamepad
Home goes through `ActivityTaskManager` directly and does not even get that
override.

### 3.3 Chosen fix: instant switch, scoped to a third-party home

`patches/frameworks/base/0004-wm-shell-instant-third-party-home.patch` adds a
check in `DefaultTransitionHandler.startAnimation()`: if the transition opens a
task whose activity type is `ACTIVITY_TYPE_HOME` and whose top activity is not
`com.android.launcher3`, finish the transition immediately (apply the start
transaction, call the finish callback, return). This is exactly the outcome of
`transition_animation_scale=0` for that one transition, without touching the
device-wide setting.

Why this approach:

* the transition is the common point for gesture Home, gamepad Home and any
  other home launch; one small check covers all of them;
* it removes *both* halves of the animation (home enter and app exit), which is
  what the interaction needs - zeroing only the home side still slides the app
  out for 500 ms;
* recents is a different transition (`ACTIVITY_TYPE_RECENTS`, handled by
  Quickstep's remote animation / `RecentsTransitionHandler`), so the fallback
  recents keeps working;
* if Launcher3 is the default home (e.g. a user selects it), the package check
  keeps its normal animation;
* the issue explicitly accepts "a clean, system-like home animation **or** an
  instant switch"; an instant switch is the minimal, robust option and does not
  depend on the fallback overview's internals.

### 3.4 Alternatives rejected

* **Patch Quickstep's third-party-home path** (make the fallback animation
  target the real role holder): the fallback path is built around a
  `RecentsAnimation`/gesture contract for `RecentsActivity` and a home animation
  factory that assumes the home surfaces are part of the shell transition
  (`FallbackSwipeHandler`, `AbsSwipeUpHandler`, `OverviewComponentObserver`,
  `FallbackHomeAnimationFactory`). Making a foreign home part of that contract
  is a much larger change with a real risk of breaking fallback recents, and it
  would need several device iterations to tune.
* **Remove Launcher3's HOME candidacy** (manifest): `OverviewComponentObserver`
  resolves its own primary home intent in its constructor and dereferences
  `info.activityInfo.name` without a null check, so removing `CATEGORY_HOME`
  crashes Quickstep unless that code is patched too. It also does not remove a
  Launcher3 home task that already exists on an upgraded `/data`, and it changes
  recents component resolution. Not minimal and not needed once the transition
  does not show that task.
* **`transition_animation_scale=0` in product defaults**: device-wide, disables
  every animation, not acceptable.

### 3.5 What must keep working (device verification)

* gesture and gamepad Home reach AshFall with no side-by-side frame and no old
  Launcher3 surface;
* swipe up and hold still opens Launcher3QuickStep's fallback recents
  (`RecentsActivity`), and swiping a task away works;
* edge back gestures and gamepad Back still work;
* `dumpsys activity activities` after Home shows AshFall resumed and no visible
  Launcher3 task.

## 4. Runtime knobs (no rebuild)

```sh
# falsing (SystemUI restart required: the classifiers read them in their constructors)
device_config put systemui brightline_falsing_distance_vertical_fling_threshold_in 0.75
device_config put systemui brightline_falsing_zigzag_y_primary_deviance 0.3
device_config delete systemui brightline_falsing_distance_vertical_fling_threshold_in

# system bars: LineageSettings, not AOSP Settings (Launcher3 restart or reboot)
# read
adb shell content query --uri content://lineagesettings/system \
    --where "name='navigation_bar_hint' OR name='enable_taskbar'"
# write (userdebug shell has WRITE_SETTINGS; the value key is "value")
adb shell content call --uri content://lineagesettings/system \
    --method PUT_system --arg navigation_bar_hint --extra value:s:0
adb shell content call --uri content://lineagesettings/system \
    --method PUT_system --arg enable_taskbar --extra value:s:0
# delete (back to the provider default; needs a Launcher3 restart/reboot)
adb shell content call --uri content://lineagesettings/system \
    --method DELETE_system --arg navigation_bar_hint
```

The old build also left an AOSP `system` row (`settings get system
navigation_bar_hint`); it is unused by the UI and can be ignored or deleted.

## 5. Build verification request

`scripts/build.sh` (orchestrator, serial). Expected in the build output:

* `patches.sh apply` applies the series with `git am --3way`:
  `frameworks/base` 0002/0003/0004, `lineage-sdk` 0001 and
  `packages/apps/Launcher3` 0001. All patches were generated against the
  manifest revision blobs;
* `LineageSettingsProvider` compiles with `DATABASE_VERSION = 25`, the new
  `R.integer.def_navigation_bar_hint` / `def_enable_taskbar` resources and the
  `loadNavigationBarSettings()` migration;
* `SettingsProvider` still compiles with `SETTINGS_VERSION = 237` (the AOSP
  seed is gone);
* `WindowManagerShell` compiles with the new `ACTIVITY_TYPE_HOME` import and
  `isThirdPartyHomeTransition()` (no new dependency);
* `Launcher3` (Quickstep) compiles with the one-line
  `TaskbarActivityContext.mIsTaskbarEnabled` change (the external-display
  taskbar is not tied to the primary-display switch);
* the SetupWizard overlay is statically merged: after the build,
  `aapt2 dump xmltree` (or `unzip -p`) of
  `system_ext/priv-app/LineageSetupWizard/LineageSetupWizard.apk` shows
  `android:checked=true` on `@id/hide_navigation_hint`; there is no new RRO APK
  for `org.lineageos.setupwizard` in `/product/overlay`;
* `vendor/ashfall/ui.mk` is included by `ashfall_common.mk`, so
  `PRODUCT_PACKAGE_OVERLAYS` / `PRODUCT_ENFORCE_RRO_EXCLUDED_OVERLAYS` contain
  `vendor/ashfall/overlay/no-rro` in the product config.

## 6. Test checklist (after flashing, no wipe needed)

1. Unlock: on the lock screen, swipe up **slightly diagonally** (10-30 deg off
   vertical) from the lower third with a normal thumb motion - it unlocks.
   A short (~2 cm) vertical swipe also unlocks. A 45 deg swipe may still be
   rejected (intentional diagonal protection).
2. Pocket protection sanity: cover the proximity sensor area / put the device in
   a pocket and brush the screen - a short accidental swipe must not unlock.
3. `adb shell content query --uri content://lineagesettings/system --where
   "name='navigation_bar_hint' OR name='enable_taskbar'"` -> both `0`, on a
   fresh `/data` and on an upgrade without wiping (the migration only touches
   the hint; `enable_taskbar` stays if it was already set). Settings -> System
   -> Buttons -> Gesture navigation shows "Navigation hint" **off**.
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
9. #64: from a fullscreen app, gamepad Home and the bottom-up home gesture reach
   AshFall with no side-by-side frame. A screen recording (or
   `adb shell screenrecord --time-limit 3 /data/local/tmp/home.mp4` + frame
   extraction) shows no outgoing app and AshFall together, and logcat has no
   `task_open_enter`/`TransitionAnimationHelper` animation for the home task.
10. `adb shell dumpsys activity activities` after Home: AshFall is resumed; no
    Launcher3 task is visible (`visible=false` is fine).
11. Swipe up and hold opens the fallback recents (`com.android.launcher3/
    .uioverrides.QuickstepLauncher` or the fallback `RecentsActivity`), tasks can
    be swiped away and selected.
12. A playing game keeps working: gamepad Home then reopening the game from
    recents returns to it; gamepad Back still leaves it.
13. The second display (port 130, 1280x720) is unchanged: it still has its
    48 dp taskbar/navigation bar even though the primary display does not
    (`dumpsys window` shows a `Taskbar` window for that display).

## 7. #81 - AshFall background, Recents and the home app

The user test on 2026-11-14 found that leaving an app to Recents or to HOME
still showed the stock LineageOS wallpaper, that Trebuchet was still offered as
a home app, and that launcher dialogs were hardcoded black and could be
navigated behind. The plan agreed with the user is a two-step one: the AshFall
background becomes the **system wallpaper** now; a fully custom Recents UI
remains a later project.

### 7.1 Background is the system wallpaper

- `HomeActivity` no longer draws a background. Its window is transparent with
  `android:windowShowWallpaper`, so the wallpaper layer is visible under the
  grid, under the **fallback Recents** (`RecentsActivity` uses
  `LauncherTheme`, which already has `windowShowWallpaper=true` and a
  transparent window background) and during the app-to-home transition. This
  is what removes the stock-wallpaper flash; no Launcher3/SystemUI drawing
  patch was needed for it.
- `WallpaperController` renders solid colours, static gradients and images
  (dim baked in) with `WallpaperManager.setBitmap(...)` (system + lock); for
  the animated gradient it installs `AshFallWallpaperService` (a
  `WallpaperService` in `AshFallLauncher`) with `setWallpaperComponent`.
  A signature prevents rewriting an unchanged wallpaper; after the first
  install, a wallpaper set by another app is left alone unless the appearance
  changes. The first write on an upgraded (not wiped) `/data` is asynchronous,
  so the old wallpaper can survive the first few hundred milliseconds of the
  first HOME resume.
- The live wallpaper paints at 20 fps only while `onVisibilityChanged(true)`;
  going to another app, turning the screen off or losing the surface removes
  the frame callback. The hue phase is derived from `uptimeMillis`, so the
  speed does not depend on the frame rate.
- `LauncherSettingsActivity` uses `Theme.AshFall.Settings` (opaque, no
  `windowShowWallpaper`), so opening settings also hides the wallpaper window
  instead of keeping the live wallpaper engine drawing behind the preview.
- `vendor/ashfall/overlay/no-rro/frameworks/base/core/res/res/drawable-*/
  default_wallpaper.png` replaces LineageOS' default wallpaper in every
  density bucket with an AshFall near-black gradient. It covers the window
  between a fresh boot and the first launcher run (and a wiped `/data` before
  the launcher ever starts).
- Permissions: `SET_WALLPAPER` (normal) and `SET_WALLPAPER_COMPONENT`
  (signature|privileged, satisfied by the platform certificate); the live
  wallpaper service holds `BIND_WALLPAPER`.

Recents itself keeps its Material/dynamic-colour chrome. Its backdrop is now
the AshFall wallpaper (near-black by default), which is the "dark/black
background" part of the request; tinting every Recents action button with the
launcher accent is intentionally deferred instead of duplicating Launcher3's
Material theme in an overlay.

### 7.2 Trebuchet is not a home app any more

`patches/packages/apps/Launcher3/0002-launcher3-drop-home-candidacy.patch`:

- removes `CATEGORY_HOME` from `com.android.launcher3.Launcher` in
  `AndroidManifest.xml`. The activity never declared `CATEGORY_LAUNCHER`, so
  it was not an app-drawer entry; removing HOME only drops the home candidacy
  and the entry in Settings > Apps > Default apps > Home app;
- makes `OverviewComponentObserver`'s constructor tolerate
  `resolveActivity(own HOME intent) == null`. It previously dereferenced the
  `ResolveInfo` unconditionally; with the category gone that is a
  `NullPointerException` in the Quickstep provider. The code now uses the
  `Launcher` activity component directly for the container interface and the
  configChanges map. The overview target logic already takes the
  fallback-Recents path when another app is the default home, so
  gestures/Recents are unchanged.

Why not remove/disable the package: it is the recents/quickstep surface, as on
the stock firmware (`rp3launcher` + `Launcher3QuickStepEX`). Why not rely on
role priority only: on a no-wipe flash an old Trebuchet role holder could
linger; with no HOME filter it is not resolvable as a home activity, and the
chooser only offers AshFall.

### 7.3 Dialogs follow the system theme and are modal

- `Theme.AshFall.Dialog` (parent `Theme.DeviceDefault.Dialog.NoActionBar`) is
  used for the app-options panel and the colour picker. Their panel fill is
  `android:colorBackgroundFloating` (dark grey by default, black with
  LineageOS' pure-black theme) and their text uses the theme's text colours;
  only the accent outline, action tiles and icons are AshFall-coloured.
- Both dialogs occupy the whole display (`Ui.makeDialogModal`): the window is
  `MATCH_PARENT` with `FLAG_LAYOUT_IN_SCREEN`, so touches outside the panel
  are delivered to the dialog and never to the HOME grid or the settings list
  behind it; a tap on the empty area dismisses. The HOME activity additionally
  consumes every key/motion event while its options panel is open instead of
  forwarding unhandled events to the grid.
- Small fixes in the same test: the white focus ring of a colour swatch is now
  drawn inside the view (it used to be clipped by the next swatch), and the
  "Tune the home screen..." intro line is gone with its vertical space kept.
- The #95 follow-up removed the platform's square focus highlight from the
  swatches (`setDefaultFocusHighlightEnabled(false)` + no background) so only
  the circular white/accent rings are visible, and replaced the RGB dialog
  with a hue picker (section 7.5).

### 7.4 Test checklist (#81)

1. Set a solid colour, a gradient and an image in launcher Settings; HOME,
   Recents and the app-to-home transition show it immediately. No stock
   wallpaper is visible at any point, also right after a fresh boot.
2. Enable "Animate colors": the gradient keeps shifting on HOME and in Recents;
   open an app (the wallpaper stops drawing) and return (it resumes). Check
   `dumpsys wallpaper` shows `org.ashfall.launcher/.AshFallWallpaperService`
   while it is active.
3. Settings > Apps > Default apps > Home app lists only AshFall; Recents still
   works (swipe up and hold, select/swipe away tasks).
4. Open the app-options panel and the colour picker: D-pad/stick/touch do not
   move the grid or the settings list behind them; tap outside dismisses; the
   panel background matches the system dialog colour (dark grey, or black with
   the LineageOS black theme).
5. The focused colour swatch's white ring is complete on all four sides; the
   settings header has no intro text but the same vertical spacing.

### 7.5 #95 follow-up: swatch focus highlight and hue picker

- `ColorSwatchView` draws the circular focus ring itself; the platform default
  rectangular focus highlight was still drawn around it (a faint grey square
  in the 20261114-111954 test). It is now disabled
  (`setDefaultFocusHighlightEnabled(false)`) and the view has no background,
  so a focused swatch shows only the white circular ring (and the accent
  selected ring when selected).
- The custom colour dialog is a hue picker instead of RGB sliders:
  `ColorPickerView` is a saturation/value area (x = saturation, y = value;
  D-pad moves it in 2 % steps, touch drags the crosshair) and `HueBarView` is a
  rainbow hue track 0-360 degrees (touch drag, D-pad left/right, and L1/R1 from
  anywhere in the dialog). Both report every change immediately to the caller:
  the settings preview and the stored colour update live, and the dialog has
  its own preview swatch and hex value. There is no "Set"/"Apply" step; `Done`
  (or B/Back/Start) only closes the dialog. Both views disable the platform
  focus highlight as well, drawing their own focus indicator. The colour area
  releases D-pad down at the bottom edge so focus reaches the hue bar and
  `Done`; on a zero-brightness colour (e.g. the default black) the hue alone
  cannot change the RGB, so the colour area must be raised first.
- Dialog surface/modality is unchanged: system `Theme.DeviceDefault.Dialog`
  colours, full-screen modal window, tap outside dismisses.
- Test: open `Custom color…` on solid/gradient start/end/accent; drag the
  colour area and hue bar (and use D-pad/L1/R1) - the preview and the settings
  background update live; there are no R/G/B sliders and no Set button; the
  hue value reads 0-359 degrees; closing keeps the last colour. The accent
  swatch focus has no grey square, only the white circular ring.
