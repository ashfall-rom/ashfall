# AshFall overlays

Build-time resource overlays, one subtree per package mirroring the package's
resource directory (for Soong: the module's `resource_dirs` path relative to the
tree root). They are wired up in `vendor/ashfall/ui.mk`:

```makefile
PRODUCT_ENFORCE_RRO_EXCLUDED_OVERLAYS += vendor/ashfall/overlay/no-rro
PRODUCT_PACKAGE_OVERLAYS += vendor/ashfall/overlay/no-rro
```

The `no-rro` level is required: `vendor/lineage/config/common.mk` sets
`PRODUCT_ENFORCE_RRO_TARGETS := *`, so without the exclusion an overlay becomes
a runtime RRO APK instead of being statically merged into the target package.
Static merge is what we want for the system/system_ext packages here, and it can
change resources (like a layout) that runtime overlays should not touch.

Current content:

| Path | Purpose |
|---|---|
| `no-rro/packages/apps/SetupWizard/app/src/main/res/layout/setup_navigation.xml` | #63: default the "hide gesture hint" switch to on, so setup writes `navigation_bar_hint=0` instead of 1 on a fresh install |

Add new overlays under the same root, e.g.
`no-rro/packages/apps/Settings/res/values/strings.xml`.
