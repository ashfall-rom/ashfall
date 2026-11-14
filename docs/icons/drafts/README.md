# AshFall icon drafts - Fan & Lighting (issue #85)

Status: **drafts only**. Nothing in this directory is wired into the app. The
vectors are preview material for the user to choose from; promoting one means
copying it into `AshFallParts/res/drawable/` (and deciding the final resource
name) in a follow-up change.

## Style basis

Inspected read-only on the build host, in the LineageOS 24 Settings sources
(`packages/apps/Settings/res/drawable/ic_*.xml`). Reference set:
`ic_settings_display_filled.xml`, `ic_settings_battery_filled.xml`,
`ic_settings_accessibility_filled.xml`, `ic_settings_privacy_filled.xml`,
`ic_settings_system_dashboard_filled.xml`.

- 24dp drawable, 960 x 960 viewport (Material Symbols geometry).
- 80-unit stroke weight = **2dp** at 24dp, with rounded caps/joins.
- One colour only: `@android:color/white` plus
  `android:tint="?android:attr/colorControlNormal"`, so the icon follows the
  theme and can be tinted by a QS tile.

These drafts reuse only that *style* (viewport, weight, corners, tint). All
geometry is original; no LineageOS/AOSP/Retroid path data was copied. Lighting
variant A deliberately evolves the existing AshFall `ic_led` /
`ic_tile_lighting` metaphor (three bars under a rail) with new geometry, rather
than inventing a new one.

## Variants

| File | Family | Variant | Notes |
|---|---|---|---|
| `fan-a-turbine` | Fan | A - Turbine | Three swept blades inside a thin shroud; the most literal fan silhouette and the closest match to a Settings row icon. |
| `fan-b-case` | Fan | B - Case fan | A square fan housing with four blades. Rounded-square silhouette makes it easy to tell apart from Lighting at 24dp. |
| `fan-c-airflow` | Fan | C - Airflow | A fan with two airflow strokes. Best fit for the QS tile, where it reads as 'cooling active' at a glance. |
| `lighting-a-bars` | Lighting | A - Light bars | Three rounded light bars under a rail. Direct evolution of the current ic_led / ic_tile_lighting asset, so the tile keeps its meaning. |
| `lighting-b-ring` | Lighting | B - Glow ring | One RGB stick ring with a sparkle. Reads as 'lit ring' rather than generic brightness, and echoes the RP5 hardware. |
| `lighting-c-dual-rings` | Lighting | C - Left/right rings | Both stick rings plus a sparkle. The most product-specific option for the Settings row; still legible at 24dp. |

## Files

- `vectors/<slug>.xml` - the 24dp Android vector drawable (the deliverable).
- `svg/<slug>.svg` - same geometry as SVG (dark on light, for inspection or
  editing); the PNGs are rendered from that same geometry.
- `png/<slug>-<light|dark>-<24|48|96>.png` - previews, themed icon colour on
  the Settings light (#FBFBFB) and dark (#121212) backgrounds.
- `index.html` - the contact sheet; open it in a browser.
- `tools/generate.py` - regenerates all of the above from one geometry source.

The PNG matrix covers the light and dark previews requested in issue #85.
LineageOS pure-black (`#000000`) needs no extra asset: the vectors are
single-colour and tinted, so the same drawable renders correctly on black.
Verify it on device when promoting.

## Regenerate

```sh
python3 docs/icons/drafts/tools/generate.py
```

Requires `rsvg-convert` (librsvg). The script only writes inside this
directory.

## Promoting a variant later

The same 24dp drawable is intended for the Settings row, the page header and
the QS tile. A QS tile may apply its own tint on top of `colorControlNormal`.

Note that the existing AshFallParts glyphs are filled 24-viewport icons, while
these drafts use the 960-viewport / 80-unit stroke language of Settings. If a
page must match its current filled glyphs instead, the same geometry can be
filled/expanded at promotion; the previews here show the Settings look.

1. Copy `vectors/<slug>.xml` to
   `aosp/vendor/ashfall/apps/AshFallParts/res/drawable/` under its final name.
2. Use the same name for the Settings row, the page header and the QS tile (or
   keep a separate tile asset if the tile needs a chunkier silhouette).
3. Verify at 24dp in light, dark and pure-black themes, and on the QS shade
   (active and inactive tint).

That work is intentionally **not** part of this PR.
