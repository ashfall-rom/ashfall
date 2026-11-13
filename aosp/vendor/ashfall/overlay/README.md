# AshFall overlays

Placeholder. Static overlay packages go here, one subdirectory per package
(`overlay/frameworks/base/core/res/...`, `overlay/packages/apps/Settings/...`).

Wire it up in `vendor/ashfall/ashfall_common.mk` once there is content:

```makefile
PRODUCT_PACKAGE_OVERLAYS += vendor/ashfall/overlay
```

The directory is intentionally empty for phase 2: the GSI is stock LineageOS
plus branding only. Display rotation and other RP5 fixes are phase 4 (#5).
