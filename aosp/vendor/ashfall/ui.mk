# AshFall UI defaults: SystemUI / navigation / keyguard / launcher-home tuning
# for a small landscape handheld. See docs/ui.md for the full reasoning.
#
# --- overlays ---------------------------------------------------------------
#
# Build-time overlays are statically merged, not runtime RROs: Lineage enables
# PRODUCT_ENFORCE_RRO_TARGETS := * for the whole product (vendor/lineage/config/
# common.mk), so the overlay root has to be listed in
# PRODUCT_ENFORCE_RRO_EXCLUDED_OVERLAYS as well.
#
#   overlay/no-rro/packages/apps/SetupWizard/app/src/main/res/layout/
#       setup_navigation.xml
#       #63: the SetupWizard "hide gesture hint" switch defaults to checked, so
#       completing setup writes navigation_bar_hint=0 instead of overwriting the
#       provider default with 1 on every fresh install.
#
#   overlay/no-rro/frameworks/base/core/res/res/drawable-*/default_wallpaper.png
#       #81: replace LineageOS' default wallpaper with an AshFall near-black
#       gradient. This is the wallpaper the system shows on a fresh /data
#       before the launcher has written the user's appearance, so the first
#       boot, Recents and the app-to-home transition are never stock.
#
# Future UI-only overlays (strings, dimens) go under the same root, mirroring
# the package's resource dir, e.g.
# overlay/no-rro/packages/apps/Settings/res/values/strings.xml.
PRODUCT_ENFORCE_RRO_EXCLUDED_OVERLAYS += vendor/ashfall/overlay/no-rro
PRODUCT_PACKAGE_OVERLAYS += vendor/ashfall/overlay/no-rro

# --- patches ----------------------------------------------------------------
#
# What is a patch and what is an overlay:
#
#   patches/frameworks/base/0002-systemui-tune-falsing-for-landscape-handheld.patch
#       #50: keyguard unlock swipe. BrightLine falsing thresholds are inch
#       based for a portrait phone; on the 1080px-tall landscape panel the
#       stock 1.5in vertical fling threshold is 44% of the screen height, so
#       only near-vertical, near-full-height swipes unlocked. 1.5in -> 0.75in
#       and 15% -> 30% ZigZag primary deviance. Pocket-touch protection
#       (proximity long-swipe, 3in/80% of the height) is unchanged.
#       Runtime tuning: device_config put systemui
#           brightline_falsing_distance_vertical_fling_threshold_in <inches>
#           brightline_falsing_zigzag_y_primary_deviance <fraction>
#
#   patches/lineage-sdk/0001-lineagesettings-provider-ashfall-navigation-defaults.patch
#       #63: no visible gesture pill / taskbar. navigation_bar_hint and
#       enable_taskbar are LineageSettings.System keys owned by
#       LineageSettingsProvider, so the defaults live in its
#       res/values/defaults.xml and its loadSystemSettings() (fresh /data).
#       A DATABASE_VERSION 24 -> 25 migration resets the old SetupWizard
#       navigation_bar_hint=1 once and inserts enable_taskbar=0 when absent,
#       which covers an existing /data without overwriting a user toggle where
#       we can detect one. Gestures are unaffected: the handle window still
#       provides the bottom mandatorySystemGestures area and edge back insets.
#
#   patches/packages/apps/Launcher3/0001-launcher3-keep-external-display-taskbar.patch
#       #63 follow-up: enable_taskbar is a global LineageSetting, but the second
#       display (port 130) uses its taskbar as its navigation bar. Only the
#       primary display follows the product default 0; an external display stays
#       enabled (docs/ui.md 2.1).
#
#   patches/packages/apps/Launcher3/0002-launcher3-drop-home-candidacy.patch
#       #81: Launcher3QuickStep keeps providing recents/quickstep but is no
#       longer a HOME app, so the home-app chooser only offers AshFall. The
#       manifest loses CATEGORY_HOME and OverviewComponentObserver handles the
#       now-unresolved own home intent (docs/ui.md 7).
#
#   patches/frameworks/base/0003-settingsprovider-keep-settings-version.patch
#       Keeps the AOSP SETTINGS_VERSION at 237 after removing the old #51
#       migration. Reverting the version would make SettingsProvider drop and
#       recreate its database on devices flashed with the previous build.
#
#   patches/frameworks/base/0004-wm-shell-instant-third-party-home.patch
#       #64: seamless HOME with a third-party launcher. When the HOME task is
#       not Launcher3, WindowManagerShell finishes the home transition
#       immediately instead of playing task_open_enter/task_open_exit (which
#       slides the app and home side by side for ~500ms). Fallback recents
#       (Launcher3QuickStep's RecentsActivity), gestures and gamepad Home/Back
#       are untouched.
