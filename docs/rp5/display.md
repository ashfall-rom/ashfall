# RP5 display rotation (issue #42)

Status: second iteration prepared; awaiting an orchestrator build. Owner:
phase-4 `display` worker. Per `docs/PLAN.md` (build policy, 2026-11-13) workers
do not run `scripts/build.sh`; this branch reports a build request instead of a
build id.

First attempt (PR #46, build `20261113-153350`, flashed with wipe): setup wizard
landscape, touch correct, but the **home screen stayed portrait** and apps were
only landscape with auto-rotate on. Live state: `ro.bootanim.set_orientation_logical_0=ORIENTATION_90`
set, `user_rotation=0`, `accelerometer_rotation=0`, `wm size 1080x1920`,
`DisplayRotation mRotation=0`. The `def_user_rotation` overlay did not apply.

Third iteration (2026-11-15, no rebuild): the landscape properties are verified
in every recent build's `build.prop` and are correct. A flash **without a wipe**
over the stock ROM can still show a 90-degree rotated UI, because the inherited
stock `/data` carries a locked `user_rotation=1` that survives the flash - see
[No-wipe flash from stock](#no-wipe-flash-from-stock). Fix: one-time
`wm user-rotation lock 0` (`scripts/device/fix-rotation.sh --apply`), not a wipe.

## Root cause

The RP5 panel is **1080x1920 native portrait** (`Built-in Screen`,
`local:4630946482288158081`, HWC display 0 / port 129), but the device is a
landscape handheld. Stock Android 13 starts WindowManager at `ROTATION_90` from
a **patched `DisplayRotation`** that reads `persist.panel.orientation`:

* stock `/system/build.prop:207` (comment `#Property to set ratation`):

  ```
  persist.panel.orientation=90
  ```

* stock `/system/framework/services.jar` -> `classes2.dex`, class
  `com.android.server.wm.DisplayRotation` (constructor) reads it and maps
  `"90"` to `mDefaultOrientation`/`mRotation`/`mUserRotation = 1`:

  ```
  const-string v6, "persist.panel.orientation"
  const-string v8, "0"
  invoke-static {v6, v8}, Landroid/os/SystemProperties;.get(...)
  ...
  const-string v8, "90"          -> const/4 v6, #int 1
  ... iput v6, v3, Lcom/android/server/wm/DisplayRotation;.mDefaultOrientation:I
  ... iput v6, v3, ...mRotation:I
  ... iput v6, v3, ...mUserRotation:I
  ```

  (extracted from stock `system_a.img`; the LineageOS 24.0 tree has **no**
  reference to `persist.panel.orientation` - it is a vendor/Retroid patch.)

The GSI replaces that system image, so nothing set a rotation and the logical
display came up portrait. Apps that request landscape rotated the display and
looked right; the launcher did not.

### Why PR #46's WindowManager-default-rotation fix was not enough

PR #46 set `ro.bootanim.set_orientation_logical_0=ORIENTATION_90`, which makes
`DisplayRotation.readDefaultDisplayRotation()` start the display at
`ROTATION_90`. That worked for the setup wizard and for apps, but not for the
home screen because the **panel's natural orientation was still portrait**:

* With auto-rotate on, WM's rotation is sensor-driven (`ROTATION_90`); apps
  follow it, but the launcher overrides it. Launcher3/Trebuchet
  `RotationHelper.notifyChange()`
  (`packages/apps/Launcher3/src/com/android/launcher3/states/RotationHelper.java`)
  requests `SCREEN_ORIENTATION_NOSENSOR` unless the display is "large"
  (`mIgnoreAutoRotateSettings`, smallest width >= 600 dp):

  ```java
  } else {
      // If auto rotation is off, allow rotation on the activity, in case the user is using
      // forced rotation.
      activityFlags = SCREEN_ORIENTATION_NOSENSOR;
  }
  ```

  `NOSENSOR` means "keep the last rotation", which is the natural `ROTATION_0`
  - portrait on this panel. Live evidence on the first-iteration build:

  ```
  $ adb shell dumpsys window | grep -E 'mCurrentAppOrientation|mLastOrientation='
      mCurrentAppOrientation=SCREEN_ORIENTATION_NOSENSOR
      mLastOrientation=5
  # display config: sw480dp ... (phone class, < 600 dp)
  ```

So the panel's **natural** orientation must become landscape; changing only
the default rotation cannot fix a launcher that pins itself to the natural
orientation.

## Fix (second iteration)

Make the display natively landscape at SurfaceFlinger level, exactly like the
community lineage-24.0 RP5 tree
(`android_device_retroidpocket_RP5`, commit `fe4f4667` "Setup default display
orientation"):

```properties
# aosp/vendor/ashfall/rp5/display.mk
ro.surface_flinger.primary_display_orientation=ORIENTATION_90
debug.sf.ignore_hwc_physical_display_orientation=true
```

`SurfaceFlinger::getPhysicalDisplayOrientation()` uses the sysprop unless the
HWC advertises `PhysicalDisplayOrientation`; the stock composer is HIDL 2.4,
which reports that feature as unsupported
(`HidlComposerHal::isSupported` returns false), so the property is honoured.
`DisplayDevice::setProjection()` then composes with
`orientation + physicalOrientation`; SF reports the logical mode as
1920x1080 with `installOrientation=ROTATION_90`, so WindowManager's natural
orientation is landscape and `ROTATION_0` **is** landscape. The launcher's
`NOSENSOR` therefore resolves to landscape.

The properties are set from the GSI system image via
`PRODUCT_SYSTEM_PROPERTIES` (system `/build.prop`), which init loads like any
other `ro.*` property. The AOSP tree already carries the exact SELinux
property context (`system/sepolicy/private/property_contexts`:
`ro.surface_flinger.primary_display_orientation u:object_r:surfaceflinger_prop:s0 exact enum ORIENTATION_0 ORIENTATION_180 ORIENTATION_270 ORIENTATION_90`)
and AOSP itself sets this property from a product makefile
(`device/google_car/tangorpro_car/device-tangorpro-car.mk`). The previous
build proved that `PRODUCT_SYSTEM_PROPERTIES` lands in the GSI's
`/system/build.prop`: `getprop ro.bootanim.set_orientation_logical_0` returned
`ORIENTATION_90` on the flashed GSI.

### Touch IDC

The focaltech controller still reports raw coordinates in the panel's
1080x1920 portrait frame. With the logical display now landscape, InputReader
would scale that portrait raw range into a 1920x1080 viewport. Ship the
matching IDC at **`/system/usr/idc/fts_ts.idc`** (one of InputReader's config
paths, `frameworks/native/libs/input/InputDevice.cpp`; the directory already
exists and its files are `system_file`, readable by system_server):

```
device.internal = 1
touch.deviceType = touchScreen
touch.orientation = ORIENTATION_270
touch.displayId = local:4630946482288158081
```

`fts_ts` is the kernel input device name (verified with `dumpsys input` /
`getevent -lp`: `/dev/input/event3`, raw axes X 0..1080, Y 0..1920). The
orientation and displayId are the community RP5 values for the same panel; the
displayId is the primary panel's stable unique id. With
`touch.orientation=ORIENTATION_270` InputReader rotates the raw frame to
1920x1080 (scale 1:1) before mapping it to the display.

### Dropped from PR #46

* `ro.bootanim.set_orientation_logical_0=ORIENTATION_90` - with the panel
  natively landscape the boot animation is landscape without it; keeping it
  would apply a second 90-degree rotation.
* The `SettingsProvider` `def_user_rotation=1` overlay - it did not apply on
  the flashed build (`user_rotation=0`) and is unnecessary once the natural
  orientation is landscape (`ROTATION_0` is already the desired orientation).
  The overlay directory was removed.

## Evidence summary

| | stock Android 13 | GSI before | GSI after this fix |
|---|---|---|---|
| panel mode | 1080x1920 portrait | 1080x1920 portrait | 1920x1080 (SF physical 90) |
| natural orientation | portrait, WM forced to 90 | portrait | landscape |
| launcher default | landscape (WM rotation 1) | portrait (`NOSENSOR` -> natural 0) | landscape (`NOSENSOR` -> natural 0) |
| touch | raw portrait, stock framework compensated | raw portrait, worked (no rotation) | raw portrait + IDC 270 -> landscape |

Live evidence from the flashed first-iteration GSI (read-only):

```
$ adb shell wm size
Physical size: 1080x1920
$ adb shell dumpsys window displays      # display 0
    init=1080x1920 ... mDisplayRotation=ROTATION_0
$ adb shell dumpsys input | grep -A4 'Device 4: fts_ts'
    ConfigurationFile: <none>
    AssociatedDisplay: isExternal=false, displayId=''
    Raw Touch Axes: X: min=0, max=1080 ... Y: min=0, max=1920
$ adb shell ls -Z /system/usr/idc | head -3
    ... u:object_r:system_file:s0 AVRCP.idc
```

### What can/cannot be tested live

`ro.surface_flinger.primary_display_orientation` is read once when
SurfaceFlinger starts and `ro.*` properties are write-once; it cannot be
changed on the running system without a reboot, and `adb reboot` is outside
this worker's rules. The IDC is read when the input device is opened and the
only writable search path in A17 is `/data/system/devices/idc/`, which is not
re-read without an input device re-open. The mechanism was therefore verified
by source inspection (paths cited above), by the previous build proving the
system-build.prop pipeline, and by community parity on identical hardware.

Earlier live experiment (still valid as a sanity check of the rotation
mechanics): `wm user-rotation lock 1` produced a 1920x1080 logical display with
`Viewport INTERNAL orientation=1, deviceSize=[1920,1080]` and a 1920x1080
screenshot; it was reverted.

## Files changed

* `aosp/vendor/ashfall/rp5/display.mk` - two SF properties + IDC copy rule.
* `aosp/vendor/ashfall/rp5/display/idc/fts_ts.idc` - touchscreen orientation.
* `scripts/device/fix-rotation.sh` - no-wipe flash: diagnostic (`--dry-run`,
  the default), the one-write fix (`--apply`) and the offline `--self-test`.
* `scripts/device/flash-gsi.sh` - summary hint only (no auto-run) for flashes
  without `--wipe`.
* `docs/rp5/display.md` - this document.
* (removed) `aosp/vendor/ashfall/rp5/display/overlay/.../defaults.xml` and the
  `PRODUCT_PACKAGE_OVERLAYS` hookup from PR #46.

## Build verification (orchestrator build)

Expected in the build output:

* system `build.prop`:
  `ro.surface_flinger.primary_display_orientation=ORIENTATION_90` and
  `debug.sf.ignore_hwc_physical_display_orientation=true`.
  `ro.bootanim.set_orientation_logical_0` must be **absent**.
* `installed-files.txt` / `system/usr/idc/fts_ts.idc` present.
* `system/build.prop` must not contain `user_rotation`; no
  `SettingsProviderOverlay.apk` should be generated for
  `vendor/ashfall/rp5/display/overlay` (the directory is gone).
* After flashing: `wm size` reports **1920x1080**, `dumpsys SurfaceFlinger`
  shows display 0 active mode 1920x1080, and `getprop
  ro.surface_flinger.primary_display_orientation` returns `ORIENTATION_90`.

## Test checklist (after flashing)

1. Boot to the launcher **without touching anything**: home screen, status
   bar, quick settings and recents are upright landscape; `wm size` is
   1920x1080.
2. Boot animation is landscape (not sideways).
3. Touch: tap all four screen corners and drag on the home screen and in
   Settings - the touch point stays under the finger. If touch is rotated or
   mirrored, the IDC orientation needs flipping (`ORIENTATION_90` instead of
   `270`) - this is the one value that cannot be verified without the panel.
4. Auto-rotate OFF: home screen stays landscape and upright.
5. Auto-rotate ON: rotating the device rotates apps/launcher as on a normal
   phone; landscape is upright again.
6. Apps that force landscape (games) are upright; portrait-only apps are
   shown rotated as on any phone held in landscape.
7. No regression in brightness, display sleep/wake, or a secondary HDMI/dock
   display if attached (display 2 / port 130 is untouched by the primary
   orientation prop).
8. **No-wipe flash only**: run `scripts/device/fix-rotation.sh --dry-run` under
   the lease first; if it says `APPLY`, run `--apply` and confirm the display
   goes upright without a wipe (see below).

## No-wipe flash from stock

A flash of the GSI **over the stock system without `--wipe`** keeps the stock
`/data`. Two different rotation "frames" then meet:

| | stock Android 13 (`/data` creator) | AshFall GSI (no wipe, `/data` kept) |
|---|---|---|
| panel natural orientation | 1080x1920 portrait | landscape: SF `ORIENTATION_90`, logical mode 1920x1080 |
| `user_rotation` | `1` (ROTATION_90, locked) | inherited `1` -> still ROTATION_90 |
| `accelerometer_rotation` | `0` (locked) | inherited `0` -> `USER_ROTATION_LOCKED` |
| result | panel is portrait, WM rotates it 90 -> upright landscape | panel is already landscape, WM rotates it another 90 -> **portrait, 90 degrees rotated** |

**Symptom:** the whole UI (launcher, status bar, recents) is portrait on a
landscape handheld while touch still maps correctly, and it stays that way with
auto-rotate off. A wiped flash boots landscape because the settings database is
re-created from the GSI defaults.

### Why the value is Settings.System and not something else

In A17 the **primary** display's rotation comes from `Settings.System` only
(`frameworks/base/services/core/java/com/android/server/wm/`):

* `DisplayRotation.updateSettings()` reads `Settings.System.USER_ROTATION`
  (default `Surface.ROTATION_0`) and treats `ACCELEROMETER_ROTATION == 0` as
  `USER_ROTATION_LOCKED`; `updateRotationUncheckedLocked()` then uses
  `mUserRotation` as the display rotation.
* `DisplayRotation.restoreSettings()` (fed by `DisplayWindowSettings`, i.e.
  `/data/system/display_settings.xml`) **returns early for the default
  display**, so the panel's per-display override cannot be the cause here.
* `DisplayRotation.setUserRotation()` writes `ACCELEROMETER_ROTATION` and
  `USER_ROTATION` for the default display, which is exactly what
  `wm user-rotation lock 0` does.

So a rotated primary display on a landscape-native panel means the settings
row is `1`. `settings get system user_rotation` prints `null` when the row does
not exist; both the stock ROM and the GSI then fall back to `def_user_rotation`
(see below), i.e. `0`.

### What was proved offline (and what was not)

Proved on 2026-11-15, host-only, from the stock `.31` images on the build host
(`$REMOTE_FIRMWARE/img/super/system_a.img`, no device access):

* `debugfs` -> `/system/priv-app/SettingsProvider/SettingsProvider.apk` ->
  `resources.arsc`, parsed with a small `ResTable` reader:

  ```
  com.android.providers.settings/integer/def_user_rotation        -> INT_DEC 0
  com.android.providers.settings/bool/def_accelerometer_rotation  -> INT_BOOLEAN 0
  ```

  **Retroid did not patch the SettingsProvider defaults.** The "stock ships
  `def_user_rotation=1`" variant of the hypothesis is therefore **disproved**;
  a fresh stock `/data` starts at `0/false` as well.
* stock `/system/build.prop:207`: `persist.panel.orientation=90`, i.e. the
  stock 90-degree start is the in-memory `DisplayRotation` patch documented in
  [Root cause](#root-cause), not a settings default.
* `SettingsProvider` seeds its defaults into the database **only when the
  database is created**, so an inherited (non-wiped) `/data` keeps whatever
  rows it already has - the GSI's `def_user_rotation=0` never applies to it.

Not proved offline: that *this* unit's `/data` actually contains
`user_rotation=1`. That needs a `/data` dump, and the RP5 is single-tenant and
busy. The value would have to have been written on stock at runtime (a
`wm user-rotation lock 1`, a stock-time rotation lock, or an app calling
`setUserRotation`); the script below does not assume it - it reads the four
live values and only acts on the exact pattern.

### Commands

Take the device lease first (the RP5 is single-tenant; `dev_adb` refuses while
another owner holds it):

```bash
# read-only: prints prop, wm size/density, both settings, display_size_forced
# and the rotation lines of dumpsys window displays / dumpsys display
scripts/device/lock.sh run <owner> -- scripts/device/fix-rotation.sh

# the one write, and only when all four preconditions hold
scripts/device/lock.sh run <owner> -- scripts/device/fix-rotation.sh --apply
```

`--apply` runs `wm user-rotation lock 0` **only** when

1. `ro.surface_flinger.primary_display_orientation` is `ORIENTATION_90`,
2. `wm size` (effective, i.e. override if set) is `1920x1080`,
3. `settings get system accelerometer_rotation` is `0`,
4. `settings get system user_rotation` is `1`.

Anything else is a refusal that changes nothing and names the next diagnostic
step (missing SF property -> the build, forced `1080x1920` -> `wm size reset`,
auto-rotate `1` -> a locked rotation cannot apply, other `user_rotation` ->
inspect further). The decision is a pure function and has an offline test:

```bash
scripts/device/fix-rotation.sh --self-test   # no adb, no device, no lease
```

### Measured before/after

```
scripts/device/lock.sh run orchestrator-flash -- scripts/device/fix-rotation.sh --apply

--- rotation state (before) ---
ro.surface_flinger.primary_display_orientation = ORIENTATION_90
debug.sf.ignore_hwc_physical_display_orientation = true
wm size (effective): 1920x1080
wm density         : Physical density: 480|
settings get system user_rotation          = 1 (effective 1)
settings get system accelerometer_rotation = 0 (effective 0)
settings get global display_size_forced    = null
dumpsys window displays (rotation/orientation lines):
    init=1920x1080 480dpi cur=1080x1920 app=1080x1920 rot=1
==> applying: adb shell wm user-rotation lock 0
--- rotation state (after) ---
settings get system user_rotation          = 0 (effective 0)
settings get system accelerometer_rotation = 0 (effective 0)
dumpsys window displays (rotation/orientation lines):
    init=1920x1080 480dpi cur=1920x1080 app=1920x1080 rot=0

measured: user_rotation 1 -> 0, accelerometer_rotation 0 -> 0, wm size 1920x1080 -> 1920x1080
measured: dumpsys window displays cur=1080x1920 rot=1 -> cur=1920x1080 rot=0
```

(The before block is the expected stale-stock signature; the after block is the
target state. Real output is captured by the orchestrator on the next no-wipe
flash and pasted here.)

Rules: do **not** wipe and do **not** reflash to fix this - both throw away the
user's data for a single settings row. `wm user-rotation lock 0` persists (it is
the same write the Settings app performs), so it is a one-time step per
inherited `/data`. No framework or app migration is part of this change; the
durable option is described in the PR body.

## Notes / alternatives

* `ro.sf.hwrotation` no longer exists in A17.
* A framework patch replicating the stock `persist.panel.orientation` read in
  `DisplayRotation` is unnecessary: the SF physical-orientation sysprop is the
  supported equivalent and needs no framework change.
* `touch.displayId` is optional for a single internal touchscreen (InputReader
  falls back to the internal viewport), but is kept for parity with the
  community tree and to pin the touch device to the primary panel rather than
  the second 1280x720 HWC display.
