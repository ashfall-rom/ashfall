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

This document is both the behavioral specification derived from the stock
launcher and the description of the clean-room implementation. No Retroid code,
layout, drawable or string is copied; the references below are resource and
class **names** observed while reverse-engineering the stock APK for behavior.

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
  keep the current page materialized.
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

## 2. AshFall launcher behavior

### Layout (1920x1080 @ 360 dpi = 853 x 480 dp)

| Region | Size | Content |
|---|---|---|
| System status bar | system | The normal Android status bar (time, Wi-Fi, battery, notifications), with black background and light icons. |
| Grid | flexible | 5 columns x 3 rows = **15 apps per page**, icon + label per tile, focus frame. |
| Page dots | 20 dp | One dot per page, accent for the current page. |
| Bottom | 52 dp | Launcher-settings shortcut (left) and focused app name (right). The controller hints moved into the settings screen. |

The app also adds a small label under every icon (the stock launcher only shows
the focused name), so the grid is readable without moving the focus. The
default look is pure black with the ember-red accent, deliberately low-glare
for a handheld used in the dark. There is no top chrome of our own: the normal
Android status bar is used instead of custom Wi-Fi/battery/clock widgets.

### Controller map

| Input | Action |
|---|---|
| D-pad, left stick | Move focus. At a horizontal edge, turn the page and keep the row. |
| A, D-pad center, Enter | Launch the focused app. |
| Hold A (500 ms) | Open the app-options panel for the focused app. |
| Menu / Start | Open the app-options panel (same as hold-A). |
| Hold Menu / Start | Open the launcher settings. |
| D-pad down from the last grid row | Focus the on-screen Settings button; A opens it, Up returns to the grid. |
| B / Back | Close the options panel; on the home screen it is a no-op. |
| L1 / R1, Page Up / Page Down | Previous / next page. |
| Select | Open the launcher settings. |
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

### Appearance settings

The bottom-left **Settings** button and Select open `LauncherSettingsActivity`
(not the system Settings app). The screen is controller- and touch-navigable
and previews changes against the live background. Settings are grouped into
separate bordered cards so the background controls and the accent controls are
visually distinct:

- **Background card**: solid color (14-color palette), gradient (start and end
  palettes side by side, top-to-bottom or left-to-right direction, optional
  animated hue shift), or a custom image chosen through `ACTION_OPEN_DOCUMENT`
  with a 0–80% dim slider. Images use a persistable URI grant so the choice
  survives reboots. The choice is written to the **system wallpaper**, not
  drawn by the launcher (see "Background = system wallpaper" below): solid
  colors, static gradients and (dimmed) images through `WallpaperManager`, the
  animated gradient through the app's built-in live wallpaper. The settings
  screen previews the result locally before it is applied on the way home.
- **Accent color card**: nine presets (ember red by default, plus orange, amber,
  green, teal, blue, violet, pink and white). The accent drives the focus
  frame, page dots, app-options dialog and settings highlights and is applied
  live when returning to the home screen.
- **Custom color**: every palette (solid, gradient start, gradient end and
  accent) also has a `Custom color…` button that opens a hue-based picker
  (#95): a saturation/value area, a rainbow hue bar (0–360°, also on L1/R1),
  and a live preview swatch plus hex value. Every change is applied live to
  the settings preview - there are no R/G/B sliders and no apply/Set button,
  only `Done` (B/Back also closes). The custom swatch next to the button shows
  the current value. On a zero-brightness colour (the default solid black) the
  hue alone cannot change the RGB; drag up in the colour area first - the hue
  readout and thumb still move. Down at the bottom edge of the colour area
  moves the focus to the hue bar (and on to Done), so the D-pad is never
  trapped.
- **Controller card** (at the end): the controller mapping that used to be
  printed along the bottom of the home screen.

Settings are stored in the `appearance` `SharedPreferences` file. The default
background is pure black (`#000000`).

### Background = system wallpaper (#81)

The HOME activity does not draw the background itself. It uses a transparent
window with `android:windowShowWallpaper` and shows the **system wallpaper**, so
the fallback Recents (`RecentsActivity`, which also declares
`windowShowWallpaper`) and the app-to-home transition show exactly the same
surface. This removes the stock-LineageOS-wallpaper flash that appeared when
leaving an app.

- `WallpaperController` compares a signature of the stored appearance with the
  last one it installed. On a change it
  - renders solid/static-gradient/image (with the dim scrim baked in) at the
    display size and calls `WallpaperManager.setBitmap(...)` (the 3-argument
    overload applies to `FLAG_SYSTEM | FLAG_LOCK`); or
  - installs `AshFallWallpaperService` with `setWallpaperComponent` when the
    gradient is animated.
  The first run always installs the AshFall appearance (that is the fix). After
  that, a wallpaper set by another app is not fought over while the appearance
  signature is unchanged; only our own live wallpaper is always replaced when
  the appearance becomes static. The first bitmap write after an upgrade
  without wiping `/data` happens on the first HOME resume, so the previous
  wallpaper can remain visible for the few hundred milliseconds the render
  takes; a fresh `/data` already boots with the AshFall default below.
- `AshFallWallpaperService` is a `WallpaperService` in this package: it reads
  the same `appearance` preferences, rotates the two gradient hues once per
  24 s, and draws at 20 fps **only while visible**. `onVisibilityChanged(false)`,
  a destroyed surface or screen off removes the frame callback, so it costs
  nothing in the background. The settings activity uses an opaque theme
  without `windowShowWallpaper` (`Theme.AshFall.Settings`), so opening settings
  also hides the wallpaper window instead of animating it behind the preview.
- The product overlay replaces the framework `default_wallpaper` with an
  AshFall near-black gradient (every density bucket), so even a fresh `/data`
  boots to an AshFall background before the launcher runs.
- `SET_WALLPAPER` (normal) and `SET_WALLPAPER_COMPONENT` (signature, satisfied
  by the platform certificate) are declared; the service holds
  `BIND_WALLPAPER`.

### Dialogs (#81)

The app-options panel and the colour picker are full-screen modal dialogs: the
window covers the display, so a touch outside the panel cannot reach the HOME
grid or the settings list behind it, and the gamepad cannot navigate them
either. A tap outside still dismisses (or Back on the gamepad). Their surface
uses the system dialog background (`colorBackgroundFloating` from
`Theme.DeviceDefault.Dialog`), i.e. the default dark grey or LineageOS' pure
black, instead of a hardcoded colour; only the accent outline and the action
tiles keep the AshFall look.

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
  the stock launcher's reset behavior.

### App options

`Start`/hold-A opens a compact panel with the app label, package name and:

- **Open** – launch the app.
- **App info** – `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.
- **Uninstall** – `Intent.ACTION_DELETE` for non-system apps only (the panel
  hides the action for system apps). `REQUEST_DELETE_PACKAGES` is declared so
  the Android 17 package installer accepts the request.
- **Kill** – `ActivityManager.killBackgroundProcesses` for the target package
  (`KILL_BACKGROUND_PROCESSES` is declared). This is the same best-effort kill
  available to a non-privileged app and matches the stock launcher's "Clear".

The options panel is a full-screen modal window (see "Dialogs" above) with the
system dialog surface, an accent outline and accent action icons. It is
controller-navigable: left/right move between actions, A selects, B/Back closes.

### System chrome

- The normal Android status bar is visible; the launcher no longer draws its
  own Wi-Fi, battery or clock. The app is edge-to-edge with transparent system
  bars: the status-bar, navigation-bar and display-cutout insets are applied to
  the content, so the grid is never clipped and the gesture area is black rather
  than a separate strip.
- The focused app name moved from the old top strip to the **bottom-right**
  corner of the bottom bar; the controller hints no longer take up that row.
- Back is still handled: the home activity consumes `KEYCODE_BACK` and registers
  a no-op `OnBackInvokedCallback` so neither the key nor the predictive-back
  gesture closes the launcher.
- The bottom-left Settings button and Select open the launcher's own settings,
  not `Settings.ACTION_SETTINGS`.

### Performance

The APK contains only the launcher: no Kotlin runtime, no AndroidX, no native
libraries, no Internet permission. The built APK is well under 100 KB. Views are inflated
once; the app list and icons load on a background thread while the chrome is
already visible. On the RP5 the activity reports `Displayed ... +120ms` on first
launch (logcat, 2026-11-13 test).

## 3. Seamless HOME transition (fixed in #64)

Before `patches/frameworks/base/0004-wm-shell-instant-third-party-home.patch`,
on the real RP5 (Android 17, no wipe) pressing Home still used the
WindowManagerShell default home-task animation, not the Quickstep home
animation. Logcat shows `ShellRecents: RecentsTransitionHandler.startAnimation:
no controller found`, followed by `DefaultTransitionHandler` animating the home
task with the default `animAttr=0x8/0x9`; captured frames show the outgoing app
and AshFall side by side during the ~0.6 s transition. Launcher3's old home task
also remains in the home root task (`visible=false`, `translucent=true`) and can
be the dimmed surface behind a menu opened during that window.

The cause is outside this app: Quickstep sets
`OverviewComponentObserver.mIsHomeAndOverviewSame=false` when the default home
is not Launcher3 and uses the fallback activity interface; the shell then has
no home-animation controller and falls back to the default task animation.
Setting `windowAnimationStyle=@null` in the app does not remove the shell
animation. **Fixed** by option 2:
`patches/frameworks/base/0004-wm-shell-instant-third-party-home.patch` makes
`DefaultTransitionHandler` finish a transition that opens a non-Launcher3 HOME
task immediately (the `transition_animation_scale=0` result, scoped to that
case only). The alternatives that were rejected (patching Quickstep's
third-party-home path, or removing Launcher3's HOME candidacy to force the
same-home path) are documented in `docs/ui.md` 3.4. Note that #81 does remove
Launcher3's HOME candidacy for a different reason (home-app chooser); the
instant transition still comes from patch 0004, because AshFall remains a
third-party home for Quickstep.

## 4. Integration: default HOME without losing recents

`aosp/vendor/ashfall/apps.mk` adds `AshFallLauncher` to `PRODUCT_PACKAGES`.
`Launcher3QuickStep` is not overridden or removed; it keeps providing the
recents/quickstep surface.

As of #81 it is also no longer a HOME app:
`patches/packages/apps/Launcher3/0002-launcher3-drop-home-candidacy.patch`
removes `CATEGORY_HOME` from the Launcher activity and makes
`OverviewComponentObserver` tolerate its own home intent no longer resolving
(the constructor dereferenced the `ResolveInfo` without a null check). It never
declared `CATEGORY_LAUNCHER`, so it is not an app-drawer entry either. The
home-app chooser (Settings > Default apps > Home app) therefore only lists
AshFall, and a stale RoleManager holder from an old image is not resolvable as
a home activity any more.

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
flashing a new `system.img` without wiping `userdata` can keep that holder.
With #81, Trebuchet is no longer resolvable as a home activity (the HOME
category is gone), so HOME presses/gestures still land on AshFall; only the
stored role holder can look stale. Set it explicitly from the flash/test flow
or from the UI:

```bash
adb shell cmd role add-role-holder android.app.role.HOME org.ashfall.launcher
# or: Settings > Apps > Default apps > Home app
```

If the test APK is installed on the device, uninstall it before flashing the
in-tree build: a `/data` copy with the same package name shadows the
`/system/app` module (and is signed with the test key).

## 5. Verification performed

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
- Touch: tap launches, long-press opens options without activating the first
  action on release, swipe pages.
- D-pad HAT axes (AXIS_HAT_X/Y) move the focus one step per press and repeat
  while held; the left stick still works.
- D-pad down from the last grid row focuses the on-screen Settings button; A
  opens the launcher settings. Select and hold-Start also open settings.
- Edge-to-edge insets keep the status bar and gesture area clear of the grid;
  the launcher no longer shows a clipped top row or its own bottom strip.
- The system status bar is visible and the grid/content starts below it; the
  focused app name is in the bottom-right corner.
- Launcher settings opens from the bottom-left button: the background and
  accent cards are visibly separate, solid/gradient/image sections switch
  correctly, the gradient palettes are side by side, and the accent selection
  updates in place. Switching the accent to blue immediately updates the focus
  ring, page dots and app-options dialog after returning home.
- The custom color picker opens for the solid, gradient start/end and accent
  palettes; its preview renders correctly. (These tests used the RGB sliders of
  the #81 build; #95 replaced them with the hue/S-V picker.)
- The app-options panel uses the accent (black panel, accent outline/icons) and
  exposes Kill for system and user apps; Uninstall remains available for user
  apps.
- Cold start: `Displayed org.ashfall.launcher/.HomeActivity ... +120ms`.

The appearance work was also exercised on the Android 13 virtual RP5
(1920x1080) after the UI changes: system status bar on top, pure black default
background, bottom-right app name, and the launcher settings screen with the
background/accent controls.

On the real RP5 (Android 17 GSI, no wipe, HOME role set with
`cmd role add-role-holder`) the final fixes were verified with the gamepad
bridge itself:

- `sendevent` HAT events on `AshFall RP5 Gamepad` moved the grid focus one step
  per press; D-pad down from the last row put the accent focus ring on the
  on-screen Settings button and A opened Launcher settings.
- The status bar and gesture inset handling was checked on the 1920x1080 panel;
  the top row is no longer under the status bar and no launcher-drawn bottom
  strip remains.
- A touch long-press and a held A both opened the options panel without
  launching the focused app on release.
- The HOME transition still shows the shell default home-task animation; see
  the blocker above.

The device was shared with the keyguard/taskbar work (#50/#51) during part of
this test; the affected navigation/back/page tests were repeated afterwards on
an exclusive device.

The #81 changes (system-wallpaper background, live wallpaper, Launcher3 HOME
candidacy removal, modal/themed dialogs, swatch ring) and the #95 follow-up
(swatch focus highlight, hue picker) were compile-checked with aapt2 + javac
against the platform system stubs but not device-tested in this worktree; the
#81 part was tested as build 20261114-111954, which produced #95. See the
checklist in section 6, `docs/ui.md` 7.4 and 7.5.

## 6. User test checklist (after flashing)

1. Boot to the home screen. With fresh `userdata`, AshFall is the HOME app; if
   the old home is kept, set it in Settings > Apps > Default apps > Home app.
2. Check the grid: sorted app icons and labels, the focused app name in the
   bottom-right corner, the system status bar on top, and the launcher settings
   button bottom-left.
3. Controller: the physical D-pad (HAT axes) and left stick move the focus
   (including diagonals); push past the left/right edge to change page; L1/R1
   change page; A opens the focused app.
   Down from the last row focuses the Settings button; A opens settings; Select
   or hold-Start also opens settings.
4. Press Home from a game: the launcher opens on page 1; recents still shows
   the Launcher3/Quickstep recents surface.
5. Hold A or press Start: the options panel opens and the release that opened
   it does not activate Open; A on App info opens Settings;
   A on Uninstall (a user app) opens the system uninstall confirmation; A on
   Kill sends the target's background process a kill request; B/Back closes the
   panel.
6. Install/update/uninstall an app while the launcher is visible; the grid
   updates without a reboot and keeps a sensible focus.
7. Touch: tap, long-press and swipe work; the system status bar and gesture
   handle do not overlap the 5x3 grid.
8. Open launcher Settings: switch background to solid/gradient/image; pick a
   gradient direction and enable animation; choose an image and drag the dim
   slider; switch the accent to blue and check the focus ring, page dots,
   settings highlights and app-options dialog. Try the `Custom color…` picker
   on each palette: the hue bar and colour area update the preview live, the
   colour area is controller-navigable with the D-pad, L1/R1 change the hue,
   and `Done`/B closes it. The focused colour swatch must show only the white
   circular ring - no square grey platform focus highlight - and the settings
   page must have the blank line where the old intro text was.
9. Leave Settings and check the system wallpaper: the chosen background is
   visible in HOME, in Recents (swipe up and hold) and during the app-to-home
   transition; no stock LineageOS wallpaper appears at any point. With
   "Animate colors" on, the background keeps shifting and stops moving while
   another app is in front.
10. Open the app-options panel and the colour picker: both are modal (D-pad,
    stick and touch do not move the grid/settings behind), and their surface is
    the system dark grey (or pure black with the LineageOS black theme), not a
    hardcoded black.
11. Settings > Apps > Default apps > Home app lists only AshFall (no
    Trebuchet), while Recents still shows the Launcher3QuickStep surface.
12. Check the Controller card at the end of Settings for the button mapping.
13. Cold start: pressing Home after a reboot should feel immediate (no splash
    or white flash).
