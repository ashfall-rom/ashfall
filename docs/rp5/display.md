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

## Notes / alternatives

* `ro.sf.hwrotation` no longer exists in A17.
* A framework patch replicating the stock `persist.panel.orientation` read in
  `DisplayRotation` is unnecessary: the SF physical-orientation sysprop is the
  supported equivalent and needs no framework change.
* `touch.displayId` is optional for a single internal touchscreen (InputReader
  falls back to the internal viewport), but is kept for parity with the
  community tree and to pin the touch device to the primary panel rather than
  the second 1280x720 HWC display.
