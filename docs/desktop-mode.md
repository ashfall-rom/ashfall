# Desktop mode (issue #131)

Status: platform enablement (workstream **F1**) implemented; **not built and not
run on a device** in this session (no build host, no RP5). Nothing on this page
is a device result: the Step 0 proof on the userdebug GSI is still open in #131,
and this page carries the exact command sequence for the build/flash pass that
closes it.

Scope: Android 17's **per-display desktop windowing on the internal display**
(display 0) of the RP5, plus what the platform needs for it. A full Linux
desktop, the on-screen keyboard/IME and USB-C DisplayPort output are out of
scope (decisions in #131, 2026-11-16).

| Workstream | Contents | State |
|---|---|---|
| **F1** platform (this page) | `frameworks/base` resource overlay, freeform feature, Launcher3 taskbar patch, this doc | this PR |
| **F2** UX | `Handheld > Display` row in AshFall Parts, QS tile, density apply/restore, `persist.ashfall.pad.mode`, chord doc link | after the Lighting work |
| **B** daemon | second uinput pointer device in `ashfall-rp5-input`, mode property, chord fallback | `docs/rp5/input.md` |

## 1. Why this is configuration, not hardware

The panel is 1920x1080 at 360 dpi: **853x480 dp**, so 853 dp wide and above the
600 dp width desktop windowing wants; the stock build already logs
`ShellDesktopMode: DesktopTaskChangeListener ... displayId=0`, i.e. the WM Shell
desktop code is present and only gated off in `frameworks/base`'s `config.xml`
(`docs/ui.md` describes the same 853 dp fact for the taskbar).

Defaults in the pinned tree (LineageOS `android_frameworks_base`,
`lineage-24.0` @ `48b4df3c577586ef05a3cd7205fa1c0e71504cbd`,
`core/res/res/values/config.xml` lines 7757-7775):

| Resource | AOSP / LineageOS | AshFall | Why |
|---|---|---|---|
| `config_isDesktopModeSupported` | `false` | `true` | desktop windowing exists on this build |
| `config_canInternalDisplayHostDesktops` | `false` | `true` | display 0 may host a desktop session |
| `config_isDesktopModeDevOptionSupported` | `false` | `true` | reachable from Settings > Developer options before F2 lands |
| `config_enterDesktopByDefaultOnFreeformDisplay` | `false` | `false` (kept) | a normal boot stays fullscreen; entry is explicit |
| `config_maxDesktopWindowingActiveTasks` / `config_maxDesktopWindowingDesks` | `0` / `4` | inherited | AOSP desktop default, not touched |

The XML itself is `frameworks/base/core/res/res/values/config.xml`; the overlay
lives at
`aosp/vendor/ashfall/overlay/no-rro/frameworks/base/core/res/res/values/config.xml`
so the tree root mirrors the package resource directory, exactly like the
`default_wallpaper.png` overlay (see `aosp/vendor/ashfall/overlay/README.md`).
It is statically merged because `vendor/lineage/config/common.mk` sets
`PRODUCT_ENFORCE_RRO_TARGETS := *` and `aosp/vendor/ashfall/ui.mk` lists the
`no-rro` root in `PRODUCT_ENFORCE_RRO_EXCLUDED_OVERLAYS` *and*
`PRODUCT_PACKAGE_OVERLAYS`. No new wiring is needed for this file.

## 2. Step 0 - prove it on the current build (no rebuild)

Run this on the **userdebug GSI that is already flashed**. Everything it sets
survives a reboot: the two `persist.*` properties and the global setting, and
also the display windowing mode and the density override, which WindowManager
stores in `/data/system/display_settings.xml` and
`Settings.Secure.DISPLAY_DENSITY_FORCED` (section 2.1). Take the device lease
first: the RP5 is single-tenant (`AGENTS.md`, `docs/HANDOFF.md` §0).

`adb root` is needed for the two `setprop persist.*` commands only. `wm` and
`settings put global` work as the ordinary shell user: `wm` runs inside
`com.android.shell`, which is platform-signed, privileged and holds
`INTERNAL_SYSTEM_WINDOW` (`packages/Shell/AndroidManifest.xml:183`,
`packages/Shell/Android.bp`: `platform_apis: true`, `certificate: "platform"`,
`privileged: true`), so the `WindowManagerService.setWindowingMode()` check
(`WindowManagerService.java:8095`) passes for the shell. `lock.sh run` takes
and releases the lease per command, so keep this sequence contiguous and start
no other `adb` session while it runs.

```sh
# 0.1 who holds the RP5?
scripts/device/lock.sh status

# 0.2 root adb - only the setprop persist.* lines need it, wm does not
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb root

# 0.3 the combinations from #131, in this order
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell setprop \
        persist.wm.debug.desktop_mode_enforce_device_restrictions false
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell settings put global \
        override_desktop_mode_features 1
# the desktop-experience developer-option override (see the table below)
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell setprop \
        persist.wm.debug.desktop_experience_devopts true
# 0.4 reboot and wait for boot_completed
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb reboot
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb wait-for-device
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell \
        'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'

# 0.5 does the feature exist, and does display 0 accept freeform?
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell pm list features | grep freeform
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell wm set-display-windowing-mode -d 0 5
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell wm get-display-windowing-mode -d 0
```

Expected at 0.5 (verified command shapes in the pinned tree,
`WindowManagerShellCommand.java` `runSetDisplayWindowingMode` /
`runGetDisplayWindowingMode`):

- `pm list features` prints
  `feature:android.software.freeform_window_management` - if it is missing,
  freeform cannot be entered at all and the freeform copy in `ui.mk` (section
  3.2) is the fix;
- `wm get-display-windowing-mode -d 0` prints
  `display windowing mode=freeform for displayId=0` after the set (`fullscreen`
  before it);
- a `SecurityException: Requires INTERNAL_SYSTEM_WINDOW permission` is not
  expected from `adb shell wm` - the Shell package holds that permission - and
  would mean the caller is not `com.android.shell`; a read-back that stays
  `fullscreen` means freeform is unavailable on this build (feature missing, or
  the display refuses `WINDOWING_MODE_FREEFORM`).

What the three knobs do, checked against the pinned tree (so Step 0 records a
combination, not folklore):

| Command | Mechanism |
|---|---|
| `setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false` | `DesktopStateImpl` reads it once at construction; `false` skips the device-eligibility checks (`DesktopExperienceFlags.java:153`, `DesktopStateImpl.kt:41`) |
| `settings put global override_desktop_mode_features 1` | `Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES`, "override the availability of the desktop mode on the main display" (`Settings.java:15043-15049`) |
| `setprop persist.wm.debug.desktop_experience_devopts true` | `DesktopExperienceFlags.SYSTEM_PROPERTY_NAME` (`DesktopExperienceFlags.java:239`): turns on every desktop-experience flag that allows the developer-option override, notably `enable_desktop_first_based_default_to_desktop_bugfix` and `enable_desktop_taskbar_on_freeform_displays`. The override only applies when `config_isDesktopModeDevOptionSupported` is true - which the overlay in this PR sets - and `showDesktopExperienceDevOption` is on |

The dev-option property matters for entry: with
`enable_desktop_first_based_default_to_desktop_bugfix` on, a display whose
windowing mode is freeform is desktop-first, so `wm set-display-windowing-mode
-d 0 5` really creates a desk. Without it the legacy path consults
`config_enterDesktopByDefaultOnFreeformDisplay` (section 6.1).

Then check, in the UI and in `dumpsys`:

```sh
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell settings get global \
        override_desktop_mode_features
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell getprop \
        persist.wm.debug.desktop_mode_enforce_device_restrictions
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell getprop \
        persist.wm.debug.desktop_experience_devopts
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell dumpsys window displays | grep -i -A2 \
        "Display: mDisplayId=0"
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell dumpsys activity activities | grep -i -m5 desk
```

### What to record in #131

1. The exact set of commands that made desktop mode available, and which of the
   three knobs was actually required (try them one at a time if the combined
   set works: `desktop_mode_enforce_device_restrictions`,
   `override_desktop_mode_features`, `desktop_experience_devopts`).
2. Whether **Settings > System > Developer options** then shows a "Desktop
   mode" / "Desktop experience" entry - `config_isDesktopModeDevOptionSupported`
   is what decides that (`DesktopModeStatus.canShowDesktopModeDevOption` in the
   pinned tree).
3. Whether display 0 in freeform **is** desktop mode: does a desk/taskbar appear,
   do new apps open as freeform windows with app handles (header + three
   buttons), and does the read-back in `wm get-display-windowing-mode -d 0`
   match the UI.
4. `logcat -b all -d | grep -i -E "ShellDesktopMode|DesktopTasksController|desktop mode"`
   around the entry, and the `dumpsys` output above - these are the artefacts the
   productisation is validated against.
5. That the reset in 2.1 really took: `wm get-display-windowing-mode -d 0`
   reads `fullscreen` again, `wm density -d 0` shows `Physical density: 360`
   and no `Override density` line, and both are still like that after another
   reboot - the windowing mode and the density override are persisted, so a
   reboot is **not** a reset.

### 2.1 Resetting afterwards - the display overrides are persisted

Both display overrides outlive a reboot, so a reboot is not a reset:

- `wm set-display-windowing-mode -d 0 5` goes through
  `DisplayWindowSettings.setWindowingModeLocked`, which writes the mode into the
  per-user override settings
  (`DisplayWindowSettingsProvider.getOverrideSettingsFileForUser`,
  `/data/system/display_settings.xml`); `DisplayWindowSettingsXmlHelper` stores
  it as the `windowingMode` attribute and reads it back, and
  `DisplayWindowSettings.getWindowingModeLocked` applies the stored
  `freeform` at boot while the display still supports it.
- `wm density 320 -d 0` goes through `DisplayWindowSettings.setForcedDensity`,
  which writes `Settings.Secure.DISPLAY_DENSITY_FORCED` for display 0 (read
  back by `WindowManagerService.getForcedDisplayDensityForUserLocked`), so the
  device can boot at 320 dpi.

That is why the reset is a mandatory part of Step 0, and why F2 needs the same
two-step restore in its failure path and a boot-time guard (see section 6):

```sh
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell wm set-display-windowing-mode -d 0 1
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density reset -d 0
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell wm get-display-windowing-mode -d 0
scripts/device/lock.sh run desktop-step0 --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density -d 0
scripts/device/lock.sh release desktop-step0
```

The last two commands must print `display windowing mode=fullscreen for
displayId=0` and `Physical density: 360` with no `Override density` line.

If none of the three knobs is enough, the remaining candidate is the aconfig
surface that gates desktop mode itself (`lse_desktop_experience` namespace:
`enable_desktop_windowing_mode`, `enable_desktop_mode_through_dev_option`,
`enable_desktop_first_based_default_to_desktop_bugfix`,
`enable_desktop_taskbar_on_freeform_displays`), e.g.
`adb shell device_config put lse_desktop_experience enable_desktop_windowing_mode true`.
That is **unverified** - record the result in #131 instead of assuming it.

## 3. What this PR changes

### 3.1 Framework resource overlay

`aosp/vendor/ashfall/overlay/no-rro/frameworks/base/core/res/res/values/config.xml`
contains exactly the four bools from the decision comment (values in section 1)
and a comment per value. Everything else in `frameworks/base`'s `config.xml`
keeps its AOSP value, including `config_maxDesktopWindowingDesks=4` and the
desktop-exempt package lists.

`config_enterDesktopByDefaultOnFreeformDisplay=false` means "do not turn a
freeform display into desktop mode on its own" (`DesktopModeStatus
.enterDesktopByDefaultOnFreeformDisplay`). It does **not** block an explicit
entry: F2 runs `wm set-display-windowing-mode -d 0 5`, and the taskbar patch in
section 3.3 keys off that same windowing mode. See section 6 for the one open
question about this flag.

### 3.2 Freeform feature

`aosp/vendor/ashfall/ui.mk` now copies the unmodified platform file:

```makefile
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.freeform_window_management.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/android.software.freeform_window_management.xml
```

`TARGET_COPY_OUT_SYSTEM` is `system` (`build/make/core/envsetup.mk:256`), so the
destination is `system/etc/permissions/android.software.freeform_window_management.xml`
inside the GSI.

The check the brief asked for found a **preexisting declaration**:
`vendor/lineage/config/common_mobile.mk:56-57` already copies the same XML, but
into `$(TARGET_COPY_OUT_PRODUCT)` (LineageOS `android_vendor_lineage`,
`lineage-24.0` @ `5a1814d916321aecb72d60d13b5e52bc719251bd`). Our product inherits
that file (`ashfall_gsi_arm64.mk` -> `lineage_gsi_arm64.mk` ->
`lineage_generic_target.mk` -> `common_full_phone.mk` -> `common_mobile_full.mk`
-> `common_mobile.mk`), and on a GSI `TARGET_COPY_OUT_PRODUCT` resolves to
`system/product` (`build/make/core/board_config.mk:736-738`: no product image),
so the feature is already declared from
`/system/product/etc/permissions/`. The `/system` copy above is kept anyway: it
declares the prerequisite from the partition this product owns, and it survives
a LineageOS config change. `PackageManager` keys features by name, so declaring
the same feature from two partitions is harmless.

**PC build check (no device needed):**

```sh
# 1. nothing was silently dropped: this file must not mention the freeform XML
cat out/target/product/$TARGET_PRODUCT/product_copy_files_ignored.txt
# 2. both staged copies exist, one per destination
find out/target/product/$TARGET_PRODUCT \
    -name android.software.freeform_window_management.xml
# expect .../system/etc/permissions/... and .../system/product/etc/permissions/...
```

Why not a ninja grep or a "duplicate" error: `PRODUCT_COPY_FILES` are Make
rules, not Soong rules. Kati writes them to `out/build-*.ninja`
(`build/make/core/ninja_config.mk:7`, `KATI_OUTPUT_PATTERNS :=
$(OUT_DIR)/build%.ninja`), so `out/soong/build.ninja` has none of them. A
duplicated destination does **not** fail the build either: `build/make/core/Makefile`
de-duplicates by destination, keeps the first pair and appends the dropped ones
to `$(PRODUCT_OUT)/product_copy_files_ignored.txt`
(`build/make/core/Makefile:137-169`), without a warning - there is no
`Duplicate PRODUCT_COPY_FILES` diagnostic in this tree. The two checks above are
the ones that can actually fail. After the flash, the runtime check is
`pm list features | grep freeform` (section 7) - that is the only one that
proves the file was picked up by `PackageManager`.

### 3.3 Launcher3 taskbar patch

`patches/packages/apps/Launcher3/0001-launcher3-keep-external-display-taskbar.patch`
(extended, regenerated, validated against a pristine
`android_packages_apps_Launcher3` @ `lineage-24.0`
`ee4a31eaf8e57f8e3eeff6a58508de92a1361972`) now computes:

```java
boolean isDesktopWindowing = mDesktopState.canEnterDesktopMode()
        && mWindowContext.getResources().getConfiguration().windowConfiguration
                .getWindowingMode() == WINDOWING_MODE_FREEFORM;
mIsTaskbarEnabled = !isPrimaryDisplay() || isDesktopWindowing
        || settingsCache.getValue(URI_ENABLE_TASKBAR);
```

Why this signal:

- `DesktopState` only exposes device-wide capability
  (`canEnterDesktopMode`) and per-display *support*
  (`isDesktopModeSupportedOnDisplay`, config based) - not the display's live
  windowing mode, so `getDesktopState(...)` alone cannot answer "is display 0 in
  desktop mode right now";
- the display's windowing mode is what Launcher3 itself uses for this question
  (`SystemWindowManagerProxy#isDisplayDesktopFirst`), and
  `DisplayContent.computeScreenConfiguration()` copies the display's windowing
  mode into the display configuration
  (`services/core/java/com/android/server/wm/DisplayContent.java:2898`), so the
  taskbar's window context sees `WINDOWING_MODE_FREEFORM` after
  `wm set-display-windowing-mode -d 0 5`;
- `mIsTaskbarEnabled` is evaluated once per `TaskbarActivityContext`, and a
  windowing-mode switch is a display configuration change, so
  `TaskbarManagerImpl.onConfigurationChanged` -> `recreateTaskbarForDisplay`
  builds a new context and re-reads it (`TaskbarManagerImpl.java:1139-1170`).

Behaviour matrix (reasoned from the code, to be confirmed on the device):

| Situation | `isPrimaryDisplay()` | freeform display | `enable_taskbar` | taskbar |
|---|---|---|---|---|
| normal boot, display 0 fullscreen | true | no | 0 | **hidden** (product default, unchanged) |
| display 0 in desktop windowing | true | yes, desktop mode available | 0 | **enabled** and pinned: `TaskbarFeatureEvaluator.isPinned` is true while `DesktopVisibilityController.isInDesktopMode(displayId)` |
| user enabled the taskbar manually | true | no | 1 | enabled, as today |
| external display (port 130) | false | - | 0 | enabled, as today (its navigation bar) |

`navigation_bar_hint=0` (the #63 product default) does not fight this: it only
sets the *stashed* taskbar height to 0 (`TaskbarStashController.java:325-345`),
and a desktop-mode taskbar is pinned so it does not auto-stash
(`TaskbarStashController.shouldAllowTaskbarToAutoStash`, same file, line 745) -
unless `TASKBAR_PINNING_IN_DESKTOP_MODE` was turned off by the user. That
relation needs one device check (section 6).

## 4. Taskbar and density contract

- **Taskbar**: present in desktop mode, absent in a normal fullscreen session,
  unchanged on an external display (#63/#81 must not regress). The patch in
  section 3.3 is the only change; no Launcher3 setting is written.
- **Density**: **320 dpi** while desktop mode is active -> 960x540 dp, the
  compromise fixed in #131 (280 dpi would cross `sw600dp` and pull tablet
  resources, 360 dpi leaves only 853x480 dp). Read the previous density first and
  restore it on exit; a density change restarts every running app, so the switch
  is a visible, several-second operation and must not be done twice in a row.
  `wm density` accepts a per-display override
  (`WindowManagerShellCommand.runDisplayDensity`, `density [reset|DENSITY] [-d DISPLAY_ID]`).
- **Both overrides outlive a reboot** (section 2.1): a crash or a killed process
  between "enter" and "exit" leaves display 0 at 320 dpi (and freeform) after the
  next boot. F2 therefore needs a boot-time guard, e.g. on boot
  `wm density reset -d 0` + `wm set-display-windowing-mode -d 0 1` whenever
  `persist.ashfall.pad.mode` is not `desktop`, and a reset in every failure path;
  it must not rely on a reboot.

F2 owns the implementation; the contract is exactly:

```sh
# read the current value BEFORE entering (note "Override density:" if present)
adb shell wm density -d 0

# enter: density first, then the display windowing mode
adb shell wm density 320 -d 0
adb shell wm set-display-windowing-mode -d 0 5

# exit: windowing mode first, then put the previous density back
adb shell wm set-display-windowing-mode -d 0 1
adb shell wm density reset -d 0        # or: wm density <previous> -d 0
```

`wm density reset -d 0` is correct when the read above showed no override
(the stock 360 dpi physical density); otherwise apply the override value that
was read. Both directions must be idempotent: F2 has to tolerate desktop mode
being entered while the density is already 320 and exited while it is already
at the stored value.

## 5. Entry and exit (F2 UX, for context)

Fixed in #131: a **QS tile**, a `Handheld > Display` row in AshFall Parts and the
daemon chord **L1+R1+Start**. All three do the same two steps (section 4):

1. require the desktop-mode preset: `wm density 320 -d 0` +
   `wm set-display-windowing-mode -d 0 5`, then `persist.ashfall.pad.mode=desktop`
   (B owns the property and the pointer device);
2. on exit: `wm set-display-windowing-mode -d 0 1`, restore the stored density,
   `persist.ashfall.pad.mode=game`.

Failure handling: if `wm set-display-windowing-mode` fails, restore the density
immediately and leave the pad mode at `game`; do not report the mode change as
applied. There is no special permission to arrange: `com.android.shell` already
holds `INTERNAL_SYSTEM_WINDOW` (`packages/Shell/AndroidManifest.xml:183`), and a
platform-signed privileged Parts app or QS tile can declare the same permission
and call the same WM binder API, so F2 may either use the binder or the two
`wm`-equivalent calls above. F2 must not write `enable_taskbar` or
`navigation_bar_hint`: the taskbar is the Launcher3 patch's job.

## 6. Open points that need the build/flash pass

Record all of these in #131 with the observed output; do not assume:

1. **Does freeform alone enter desktop mode?** With
   `config_enterDesktopByDefaultOnFreeformDisplay=false`, the legacy path
   (`DesktopTasksController.isDesktopFirstLegacy`) returns false unless the
   `enable_desktop_first_based_default_to_desktop_bugfix` flag is on, while the
   newer `isDisplayDesktopFirst()` path keys off the display windowing mode.
   If Step 0 shows that `wm set-display-windowing-mode -d 0 5` yields plain
   freeform windows without a desk/taskbar, the entry route has to be the
   developer-option toggle, or the binding decision on
   `config_enterDesktopByDefaultOnFreeformDisplay` has to be revisited
   (productising `true` still would not boot into desktop, because the display
   is fullscreen at boot).
2. **Taskbar after the switch**: `dumpsys window | grep -i taskbar` shows a
   non-zero height on display 0 in desktop mode and a 0-height taskbar window in
   normal mode; `navigation_bar_hint=0` + pinned desktop taskbar (section 3.3).
3. **Density**: `wm density -d 0` reports `Physical density: 360` and
   `Override density: 320` in desktop mode, and the app relaunch finishes (no
   half-scaled UI left after exit).
4. **Overlay actually applied**: the overlay is merged into `framework-res`,
   so there is no RRO to look for in `cmd overlay list`, and the resource itself
   exists in every build (`config_isDesktopModeSupported` is an AOSP resource),
   so the check has to assert the **value**. On the build host:

   ```sh
   out/host/linux-x86/bin/aapt2 dump resources \
       out/target/product/$TARGET_PRODUCT/system/framework/framework-res.apk \
     | grep -A2 "config_isDesktopModeSupported" | grep -q "(bool) true" \
     && echo "overlay applied" || echo "OVERLAY MISSING"
   ```

   `(bool) true` in the value line under the resource is the merged value;
   `false` or no match means the overlay root did not apply (check that
   `PRODUCT_PACKAGE_OVERLAYS` still points at
   `vendor/ashfall/overlay/no-rro`). On the device the practical proofs are
   `pm list features`, whether the desktop-mode developer option appears, and
   `dumpsys window | grep -i desktop`.

## 7. Device command list for the later build/flash pass

After `scripts/build.sh` produces a `system.img` and
`scripts/device/flash-gsi.sh` has flashed it, on the running build:

```sh
# optional: only needed if you also set the persist.* properties below
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb root

# features and configuration
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell pm list features | grep -E "freeform|multi_window"

# normal boot is still fullscreen, taskbar hidden
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm get-display-windowing-mode -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell dumpsys window | grep -i taskbar

# enter desktop mode
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density 320 -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm set-display-windowing-mode -d 0 5
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm get-display-windowing-mode -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell dumpsys window | grep -i taskbar

# exit again, and confirm everything is back
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm set-display-windowing-mode -d 0 1
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density reset -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm get-display-windowing-mode -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density -d 0

# and once more after a reboot - the reset must stick (section 2.1)
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb reboot
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell \
        'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm get-display-windowing-mode -d 0
scripts/device/lock.sh run desktop-verify --ttl 3600 -- \
    scripts/device/run.sh adb shell wm density -d 0
```

Acceptance for that pass is the #131 checklist (taskbar visible in desktop mode
and hidden in normal mode, apps recover after the density change, HOME/recents
still work with the AshFall launcher). F2 adds the QS tile/Parts row on top of
the same two commands; the pointer checks belong to workstream B.

## 8. References

- #131 (contract and decisions), #63/#81 (`docs/ui.md`: taskbar defaults,
  `navigation_bar_hint`, `enable_taskbar`, the external-display taskbar),
  `docs/phase6.md` §9.7.
- AOSP: [desktop windowing](https://source.android.com/docs/core/display/desktop-windowing),
  [multi-window](https://source.android.com/docs/core/display/multi-window).
- Pinned sources used for the resource names and defaults - all on
  `lineage-24.0`:
  `android_frameworks_base` @ `48b4df3c577586ef05a3cd7205fa1c0e71504cbd`
  (`core/res/res/values/config.xml:7757-7775`,
  `core/java/android/provider/Settings.java:15043-15049`,
  `core/java/android/window/{DesktopModeFlags.java,DesktopExperienceFlags.java}`,
  `core/java/android/window/flags/lse_desktop_experience.aconfig`,
  `libs/WindowManager/Shell/shared/src/com/android/wm/shell/shared/desktopmode/{DesktopModeStatus.java,DesktopState.kt,DesktopStateImpl.kt}`,
  `libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/DesktopTasksController.kt`,
  `packages/Shell/AndroidManifest.xml:183` and `packages/Shell/Android.bp`
  (`INTERNAL_SYSTEM_WINDOW`, platform signing),
  `services/core/java/com/android/server/wm/{WindowManagerShellCommand.java,WindowManagerService.java,DisplayContent.java,DisplayWindowSettings.java,DisplayWindowSettingsProvider.java,DisplayWindowSettingsXmlHelper.java}`),
  `android_build` @ `90d0063` (`core/envsetup.mk:256`,
  `core/board_config.mk:736-738`, `core/Makefile:137-169`,
  `core/ninja_config.mk:7`, no `target/product/*.mk` freeform copy),
  `android_vendor_lineage` @ `5a1814d916321aecb72d60d13b5e52bc719251bd`
  (`config/common_mobile.mk:56-57`),
  `android_packages_apps_Launcher3` @ `ee4a31eaf8e57f8e3eeff6a58508de92a1361972`
  (`quickstep/src/com/android/launcher3/taskbar/{TaskbarActivityContext.java,TaskbarManagerImpl.java,TaskbarStashController.java,customization/TaskbarFeatureEvaluator.kt}`,
  `quickstep/src/com/android/quickstep/util/SystemWindowManagerProxy.java`,
  `quickstep/src/com/android/launcher3/statehandlers/DesktopVisibilityController.kt`),
  `android_frameworks_native` @ `a96adf2c6e62dbb22299cd83bbb9ea4bc569e985`
  (`data/etc/android.software.freeform_window_management.xml`).

## 9. F2 - Parts page and Quick Settings tile

Status: implemented in AshFall Parts; **not built and not run on a device** in
this session. The page below is the contract the build/flash pass verifies.

### 9.1 Entry points

| Entry point | Behaviour |
|---|---|
| **AshFall Parts > Handheld > Display** | a "Desktop mode" switch, the same large Settings main-switch row the Lighting page uses, plus read-only notes: internal screen only / taskbar and pointer / apps restart, and what the chord really does |
| **Quick Settings "Desktop mode" tile** | one tap toggles; no dialog, no unlock prompt, active state = display 0 really is in desktop windowing; long-press opens the Display page through `qs/TilePreferencesActivity` |

The L1+R1+Start chord is **not** a desktop-mode entry point in v1: the daemon
only switches its pointer mode, does not write `persist.ashfall.pad.mode` and
does not touch display 0 (`docs/rp5/input.md`).  The page and tile read the
display, so after a chord they keep showing the real display mode; the copy on
the page says exactly that.  A chord that enters desktop mode would need a
workstream-B follow-up.

A boot guard, `DesktopBootReceiver`, runs on `LOCKED_BOOT_COMPLETED` and
`BOOT_COMPLETED` and is deliberately *not* an entry point: it always forces
display 0 fullscreen, restores the saved density and writes
`persist.ashfall.pad.mode=game`.  A boot is therefore always fullscreen, even
after a crash between enter and exit; it never enters desktop mode by itself.
The page and tile poll the display mode every 1.5 s / 2 s while visible so a
change made elsewhere shows up.

### 9.2 State: WindowManager, not the property

`org.ashfall.parts.hw.Desktop` reads display 0's live windowing mode through
the hidden `IWindowManager` (windowing mode **5 = freeform/desktop**, **1 =
fullscreen**). The property is only the input daemon's instruction; reading
`WindowManager` keeps the switch honest when the display is switched by
Settings, by the developer option or by `wm`, and means the UI cannot claim
desktop mode while the panel is fullscreen.

The same helper also owns the density and the pad-mode write, so the page and
the tile cannot drift apart. It is reflection-only, the same pattern as
`ipc/Props`, so the Gradle preview still compiles against the public SDK; in
AOSP the app is `platform_apis`, `certificate: "platform"` and privileged, so
the two signature permissions below are granted:

| Manifest permission | Needed by |
|---|---|
| `android.permission.INTERNAL_SYSTEM_WINDOW` | `getWindowingMode(int)`, `setWindowingMode(int,int)` |
| `android.permission.WRITE_SECURE_SETTINGS` | `setForcedDisplayDensityForUser(...)`, `clearForcedDisplayDensityForUser(...)` |
| `android.permission.RECEIVE_BOOT_COMPLETED` | `DesktopBootReceiver` (`LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`) |

Hidden calls used: `WindowManagerGlobal.getWindowManagerService()`;
`IWindowManager.getWindowingMode(int)`, `setWindowingMode(int,int)`,
`getBaseDisplayDensity(int)`, `getInitialDisplayDensity(int)`,
`setForcedDisplayDensityForUser(int,int,int)`,
`clearForcedDisplayDensityForUser(int,int)`.

### 9.3 Enter: density, display, property

The order is the §4 contract. Each step only runs when the previous one
succeeded:

1. read `wm density -d 0` equivalent (`getBaseDisplayDensity(0)`); refuse to
   enter if it cannot be read, because the panel would be stuck at 320 dpi;
2. store that value in `persist.ashfall.display.density_restore` and read it
   back; if the panel is already at 320 from an interrupted exit, keep the
   key that is still there instead of overwriting it with 320;
3. if the value is not **320**, `setForcedDisplayDensityForUser(0, 320, user)`;
4. `setWindowingMode(0, 5)` and read it back; it must report 5;
5. write `persist.ashfall.pad.mode=desktop` last and read it back;
6. ask SystemUI to rebind the tile (`TileService.requestListeningState`), so
   the tile is active immediately even when the panel is closed.

The density key is a `persist.*` property, so it survives a reboot.  So do the
display windowing mode and the per-display density override: WindowManager
writes them to `/data/system/display_settings.xml` and
`Settings.Secure.DISPLAY_DENSITY_FORCED` (F1 §2.1), which is why a crash
between enter and exit can come back in desktop mode.  The boot guard in §9.1
forces fullscreen and applies the key on every boot; reading the key back is
what makes a failed property write fail the whole operation instead of leaving
the property and the display out of step.

### 9.4 Exit: display, density, property

1. `setWindowingMode(0, 1)` and read it back; it must report fullscreen;
2. only when the panel is currently at **320** (or the density cannot be
   read): re-apply `persist.ashfall.display.density_restore`, retry once, and
   verify the read-back; if the key is missing and the call started from a
   real desk (the desk was entered outside F2), clear the forced density and
   verify it too.  This also repairs a reboot that left the density at 320
   with a fullscreen display, without touching a user's own override;
3. write `persist.ashfall.pad.mode=game`;
4. refresh the tile.

If the density restore or the property write fails after the display has
already returned to fullscreen, the result is `PARTIAL`, not success: the page
and tile show "Couldn't finish switching the display. Check the display
settings." rather than claiming nothing changed.  The restore key is kept, so
the next tap or the boot guard retries.

A second "off" tap while already fullscreen writes `game` and leaves the
density alone. Entering while already in desktop windowing only writes
`desktop` and does not touch a density the user already had.

### 9.5 Boot guard (#207)

`DesktopBootReceiver` is a direct-boot receiver (the app is already
`directBootAware`) for both `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`.  It
calls `Desktop.resetForBoot()`:

1. `setWindowingMode(0, 1)` - display 0 fullscreen;
2. if `persist.ashfall.display.density_restore` exists, apply it (`wm density
   reset` when it equals the stock density, otherwise set it);
3. `persist.ashfall.pad.mode=game`.

Both broadcasts run the same idempotent path, so a WindowManager that was not
ready for the first call gets a second attempt, and a clean device simply
re-applies the same values.  The receiver sends no broadcast and starts no
worker, so it cannot loop.  A failure is logged by `Desktop` and leaves the
real display state alone; the next boot retries.

### 9.6 Failure path

| Failure | What is put back |
|---|---|
| density cannot be read or stored | nothing changed; no display/property write |
| density apply fails | nothing changed; display and property were not touched |
| `setWindowingMode(0,5)` throws or the read-back is not 5 | fullscreen is set again (best effort), the pre-entry density is restored, `persist.ashfall.pad.mode=game` |
| `persist.ashfall.pad.mode=desktop` does not read back | fullscreen is set again, the pre-entry density is restored, `persist.ashfall.pad.mode=game` |
| exit cannot set/read back fullscreen | property stays `desktop` (the display is still a desk), the page/tile show the real state and the short error message |
| exit cannot write `game` | the display is already fullscreen; the page/tile report `PARTIAL`; the next tap retries the property write |
| exit: density restore/clear fails or the read-back is still 320 | the display is fullscreen and `game` is written; the page/tile report `PARTIAL` ("Couldn't finish..."); the key stays for the next tap or the boot guard |
| enter: density rollback fails after a display failure | the display is fullscreen and `game` is written; the page/tile report `PARTIAL`; the boot guard retries the density |
| process killed between enter and exit | the next boot's `DesktopBootReceiver` forces fullscreen, applies the saved density and writes `game` |

The page shows one short message on any failure ("Couldn't switch the display
mode. Nothing changed."); the tile shows the same message as a toast. No
failure path reports desktop mode while display 0 is fullscreen.

### 9.7 What the build/device pass must confirm

1. `wm get-display-windowing-mode -d 0` changes to
   `display windowing mode=freeform` after the switch, and back to `fullscreen`
   after it, matching the page and tile within one tick.
2. `wm density -d 0` shows `Override density: 320` in desktop mode and the
   value from `persist.ashfall.display.density_restore` after exit.  Both
   overrides persist (F1 §2.1), so reboot **while desktop mode is still
   active**: the boot guard must bring the device back fullscreen at the saved
   density with `persist.ashfall.pad.mode=game`, not into a desk at 320 dpi.
3. The taskbar appears only in desktop mode (#131 F1 Launcher3 patch) and the
   normal fullscreen session still has none (#63/#81 unchanged).
4. `getprop persist.ashfall.pad.mode` is `desktop` after entry and `game`
   after exit, and `ashfall.status.pad.mode` agrees within one daemon poll
   (250 ms).  L1+R1+Start changes only the pointer; the page keeps showing the
   real display mode and its note says the display stays the same.
5. No `SecurityException` for `INTERNAL_SYSTEM_WINDOW`,
   `WRITE_SECURE_SETTINGS` or `RECEIVE_BOOT_COMPLETED` in logcat, no ANR from
   the density restart, and the page/tile/`wm` read-back agree after a forced
   Settings/`wm` change.
6. The D-pad reaches the switch and the notes, and the page follows the day /
   night theme like the other Parts pages.
