# AshFall UI defaults: SystemUI / navigation / keyguard tuning for a small
# landscape handheld. Owned by the 'ui' worker. Files go in vendor/ashfall/ui/.
#
# There is nothing to install from here: both UI fixes are code changes carried
# as patches against frameworks/base, because neither can be expressed with a
# product variable, resource overlay or property (see docs/ui.md for the
# reasoning):
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
#   patches/frameworks/base/0003-settingsprovider-ashfall-ui-defaults.patch
#       #51: no visible gesture pill / taskbar. Seeds the LineageOS settings
#       navigation_bar_hint=0 (hide the stashed Launcher3 taskbar handle that
#       is drawn over app content) and enable_taskbar=0 (no tablet taskbar,
#       bubble bar or pinned-app strip on this 853dp-wide display) through a
#       SettingsProvider version migration, so it applies to fresh installs
#       and to an existing /data with the keys still absent. Values live in
#       the patch's res/values/defaults.xml (def_navigation_bar_hint,
#       def_enable_taskbar) and can be overridden by a product overlay later.
#       Gestures are unaffected: the handle window still provides the bottom
#       mandatorySystemGestures area and the edge back insets.
#
# Future UI-only overlays (strings, dimens) belong in
# vendor/ashfall/ui/overlay/<package>/res/..., wired with
# PRODUCT_PACKAGE_OVERLAYS += vendor/ashfall/ui/overlay once there is content.
