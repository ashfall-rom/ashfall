# AshFall launcher (issue #52)

`AshFallLauncher` is AshFall's landscape, controller-first HOME app for the
Retroid Pocket 5. It replaces Trebuchet as the home screen while leaving
`Launcher3QuickStep` installed as the recents/quickstep provider, mirroring the
stock firmware's `rp3launcher` + `Launcher3QuickStepEX` split.

- Package: `org.ashfall.launcher`
- Module: `vendor/ashfall/apps/AshFallLauncher` (`android_app`)
- Source: `aosp/vendor/ashfall/apps/AshFallLauncher/`
- License: Apache-2.0
- Dependencies: framework views only (no AndroidX, Kotlin or Compose)

This document is both the behavioural specification derived from the stock
launcher and the description of the clean-room implementation. No Retroid code,
layout, drawable or string is copied; the references below are resource and
class **names** observed while reverse-engineering the stock APK for behaviour.

## 1. What the stock launcher does

The stock app is `com.retroidpocket.rp3launcher` (`rp3launcher.apk`,
`/system/app/rp3launcher`, listed in `docs/stock/system-apps.txt`). It is a
system-uid app (82 MB APK, mostly emulation/ROM features), but its home screen
itself is a small, fixed-layout launcher.

### Screen structure

| Piece | Observation |
|---|---|
| Main layout | `res/layout/rp3_launcher_layout.xml`: vertical `LauncherStatusBar` (50 dip), flexible content area, `LauncherBottomBar` (48 dip); optional right-hand `SideBarLocateView`. |
| Focus header | `item_label_tv` (23 dip, bold, marquee) at the top of the content area, updated to the name of the focused app. |
| App pages | `workspace_layout.xml`: left/right chevrons (30x60 dip), `ViewPager` (`vpApps`), `ScrollingPagerIndicator` dots (8 dip, selected 12 dip). `apps_page_layout.xml` hosts a custom `PageAppRecyclerView`. |
| Tile | `apps_item_layout.xml`: an icon that fills the tile (`RoundedImageView`), an `app_item_bg`, and a focusable transparent `app_item_selector` with `focusable="true"` and `focusableInTouchMode="true"`. Tiles show **no labels**. |
| Status bar | `launcher_status_bar_layout.xml`: avatar on the left, battery (`battery_status_bar_view_layout.xml`) and Wi-Fi (`wifi_status_bar_view_layout.xml`) on the right. |
| Bottom bar | `launcher_bottom_bar_layout.xml`: Quit, Setup/folders, Settings, Layout, Add app; Back/OK controller hints and the clock on the right. |
| App options | `pop_win_app_settings_layout.xml`: Color, Boxart/Icon, Reposition, AppInfo, Remove, Uninstall. |
| All-apps picker | `pop_win_select_app_layout.xml`: search field plus a 6-column `RecyclerView` for adding an app. |

### Layout modes

`AppItemParam` carries row/column/icon/item sizes. Four modes exist:
2x2, 2x4, 3x4 and 3x5 (rows x columns); the `apps_layout` preference defaults
to `0`, i.e. **2 rows x 4 columns = 8 apps per page** with large 110 dip icons.
The "Layout" bottom-bar item switches modes.

### Navigation model

- D-pad/stick moves the focus; `PageAppRecyclerView` and the page containers
  keep the current page materialised.
- `Workspace.dispatchKeyEvent` maps **L1 (102)** to the previous page and
  **R1 (103)** to the next page.
- `PageAppRecyclerView` also reacts to **BUTTON_A (96)**.
- Focused tiles update `item_label_tv`; clicking/activating launches the app.
- Long-press/menu opens the app-options popup; Back closes it.
- Touch works: page swipes and tile taps, even though the primary input is the
  built-in gamepad.

### Look

Light, low-chrome theme: `primary_color #f1efeb`, text/icons `#333333`, accent
underline `#f24646`, popup background `#eeeeee`; fullscreen with no action bar
(`Theme.RP3Launcher.Fullscreen`). The status and bottom bars frame a large
icon grid, so the screen reads as a console UI rather than a phone launcher.

## 2. AshFall launcher behaviour

### Layout (1920x1080 @ 360 dpi = 853 x 480 dp)

| Region | Size | Content |
|---|---|---|
| Top status | 52 dp | Focused app name (left, marquee); Wi-Fi and battery (right). |
| Grid | flexible | 5 columns x 3 rows = **15 apps per page**, icon + label per tile, focus frame. |
| Page dots | 20 dp | One dot per page, accent for the current page. |
| Bottom | 52 dp | Settings shortcut, controller hints, clock. |

The app also adds a small label under every icon (the stock launcher only shows
the focused name), so the grid is readable without moving the focus. The
palette is deliberately dark and low-glare (`#0c1016` to `#121a24`, accent
`#f24646`) for a handheld used in the dark; a solid themed background is used
instead of the wallpaper, which the issue permits.

### Controller map

| Input | Action |
|---|---|
| D-pad, left stick | Move focus. At a horizontal edge, turn the page and keep the row. |
| A, D-pad centre, Enter | Launch the focused app. |
| Hold A (500 ms) | Open the app-options panel for the focused app. |
| Menu / Start | Open the app-options panel (same as hold-A). |
| B / Back | Close the options panel; on the home screen it is a no-op. |
| L1 / R1, Page Up / Page Down | Previous / next page. |
| Select | Open the Settings app. |
| Touch | Tap to launch, long-press for options, horizontal swipe to change page; chevrons and page buttons are tappable. |

The RP5 gamepad daemon reports A/B as `KEYCODE_BUTTON_A/B`, the D-pad as
`ABS_HAT0X/Y` (converted to `DPAD_*` by `ViewRootImpl`, see
`docs/rp5/input.md`), L1/R1 as `KEYCODE_BUTTON_L1/R1`, and Start/Select as
`KEYCODE_BUTTON_START/SELECT`. The launcher therefore handles those key codes
directly and treats the **left stick** (`AXIS_X/Y`) as analog navigation with a
45% dead zone and a 320 ms repeat.

The app registers a no-op `OnBackInvokedCallback` (and
`android:enableOnBackInvokedCallback="true"`) in addition to consuming
`KEYCODE_BACK`: on Android 17 the predictive-back gesture is separate from key
events, and without a callback a right-edge swipe would close the HOME activity.

### App list

- Enumerated with `PackageManager.queryIntentActivities(MAIN/LAUNCHER)`; the
  launcher's own package is excluded.
- Sorted by label with `Collator` (locale-aware), then by component name.
- Refreshed on `PACKAGE_ADDED`, `PACKAGE_REMOVED`, `PACKAGE_CHANGED` and
  `PACKAGE_REPLACED` (registered dynamically while the launcher is started) with
  a 250 ms debounce for batch installs; `onStart()` also reloads, covering apps
  installed while a game was in front.
- The app list is rebuilt off the main thread; icons are loaded there too.
- A reload keeps the focused app if it still exists, so an install does not
  make the grid jump.
- The focused app is **not** persisted across a fresh HOME launch: pressing Home
  recreates the activity (`clearTaskOnLaunch`) and starts at page 1, matching
  the stock launcher's reset behaviour.

### App options

`Start`/hold-A opens a compact panel with the app label, package name and:

- **Open** – launch the app.
- **App info** – `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.
- **Uninstall** – `Intent.ACTION_DELETE` for non-system apps only (the panel
  hides the action for system apps). `REQUEST_DELETE_PACKAGES` is declared so
  the Android 17 package installer accepts the request.

The panel is controller-navigable: left/right move between actions, A selects,
B/Back closes.

### Status and chrome

- Battery: sticky `ACTION_BATTERY_CHANGED`; percentage text plus an outline/fill
  gauge, charging bolt, warning colour at <=15%.
- Wi-Fi: `ConnectivityManager.registerDefaultNetworkCallback`; a connected
  Wi-Fi transport shows the normal icon, otherwise the muted/off icon.
- Clock: bottom right, honours the system 12/24-hour setting and ticks on the
  minute boundary.
- Immersive fullscreen: system bars are hidden with
  `WindowInsetsController` and come back transiently on a swipe, so the
  gesture/navigation indicator does not overlap the grid.
- Settings shortcut: `Settings.ACTION_SETTINGS` from the bottom bar and Select.

### Performance

The APK contains only the launcher: no Kotlin runtime, no AndroidX, no native
libraries, no Internet permission. The built APK is ~70 KB. Views are inflated
once; the app list and icons load on a background thread while the chrome is
already visible. On the RP5 the activity reports `Displayed ... +120ms` on first
launch (logcat, 2026-11-13 test).

## 3. Integration: default HOME without losing recents

`aosp/vendor/ashfall/apps.mk` adds `AshFallLauncher` to `PRODUCT_PACKAGES`.
`Launcher3QuickStep` is not overridden, removed or modified.

Android 17 has no `config_defaultHome`; RoleManager grants the HOME role to the
**highest-priority HOME intent filter** when no holder exists
(`HomeRoleBehavior.getFallbackHolderAsUser` in
`packages/modules/Permission/PermissionController/role-controller`, and
`ComputerEngine.getDefaultHomeActivity`). The launcher manifest therefore
declares:

```xml
<intent-filter android:priority="1000">
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.HOME" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

Launcher3 declares no priority, so a first boot with no HOME holder (fresh
`userdata`) selects AshFall. Recents keep working because the Quickstep recents
surface is provided by the installed `Launcher3QuickStep`, not by the HOME
role.

**No-wipe caveat:** RoleManager only fills an *empty* role. If the device
already has a HOME holder (for example Trebuchet from the previous image),
flashing a new `system.img` without wiping `userdata` keeps that holder. In that
case set it once from the flash/test flow or from the UI:

```bash
adb shell cmd role add-role-holder android.app.role.HOME org.ashfall.launcher
# or: Settings > Apps > Default apps > Home app
```

If the test APK is installed on the device, uninstall it before flashing the
in-tree build: a `/data` copy with the same package name shadows the
`/system/app` module (and is signed with the test key).

## 4. Verification performed

Standalone build (same sources, SDK 36, aapt2 + javac + d8 + apksigner) and
live test on the RP5 (AshFall GSI v0.1.0-gsi, 1920x1080, 360 dpi):

- D-pad movement updates the focused label and the 5x3 grid; edge presses turn
  the page and keep the row; L1/R1 and horizontal swipes change pages both
  ways; page 3 with only four apps clamps correctly.
- A launches the focused component (verified in logcat:
  `START u0 ... cmp=org.ashfall.testapps/.App08 ... from uid ...ashfall.launcher`).
- Start opens the options panel; D-pad moves Open → App info → Uninstall;
  A on App info opens `com.android.settings/.spa.SpaActivity`; A on Uninstall
  opens `com.android.packageinstaller/.v2.ui.UninstallLaunch`; B closes it.
- Back key and the predictive-back gesture do **not** close the launcher.
- Installing an app while the launcher is in front adds it to the grid and
  creates the expected pages; uninstalling it removes it and the focus falls
  back to a valid app.
- Touch: tap launches, long-press opens options, swipe pages.
- Cold start: `Displayed org.ashfall.launcher/.HomeActivity ... +120ms`.

The device was shared with the keyguard/taskbar work (#50/#51) during part of
this test; the affected navigation/back/page tests were repeated afterwards on
an exclusive device.

## 5. User test checklist (after flashing)

1. Boot to the home screen. With fresh `userdata`, AshFall is the HOME app; if
   the old home is kept, set it in Settings > Apps > Default apps > Home app.
2. Check the grid: sorted app icons and labels, focused name in the top bar,
   Wi-Fi/battery indicators, clock, Settings shortcut.
3. Controller: D-pad and left stick move the focus (including diagonals);
   push past the left/right edge to change page; L1/R1 change page; A opens the
   focused app.
4. Press Home from a game: the launcher opens on page 1; recents still shows
   the Launcher3/Quickstep recents surface.
5. Hold A or press Start: the options panel opens; A on App info opens Settings;
   A on Uninstall (a user app) opens the system uninstall confirmation; B/Back
   closes the panel.
6. Install/update/uninstall an app while the launcher is visible; the grid
   updates without a reboot and keeps a sensible focus.
7. Touch: tap, long-press and swipe work; the status bar and gesture handle do
   not overlap the 5x3 grid.
8. Cold start: pressing Home after a reboot should feel immediate (no splash
   or white flash).
