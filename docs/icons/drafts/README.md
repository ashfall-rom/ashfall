# AshFall icon drafts - Fan & Lighting (issue #85)

Status: **drafts only**. Nothing in this directory is wired into the app. The
vectors are preview material; promoting one means copying it into
`AshFallParts/res/drawable/` (and deciding the final resource name) in a
follow-up change.

**Revision after user review of PR #86:** the three earlier thin-line Fan
drafts and Lighting A/C were removed. **Lighting B is kept and chosen.** The
Fan set is redrawn as three filled, broad swept-blade fans.

## Style basis

Inspected read-only on the build host, in the LineageOS 24 Settings sources
(`packages/apps/Settings/res/drawable/ic_*.xml`). Reference set:
`ic_settings_display_filled.xml`, `ic_settings_battery_filled.xml`,
`ic_settings_accessibility_filled.xml`, `ic_settings_privacy_filled.xml`,
`ic_settings_system_dashboard_filled.xml`.

- 24dp drawable, 960 x 960 viewport (Material Symbols geometry).
- Filled glyphs, plus 80-unit (2dp) strokes where a frame or ring is needed.
- One colour only: `@android:color/white` plus
  `android:tint="?android:attr/colorControlNormal"`, so the icon follows the
  theme and can be tinted by a QS tile.

These drafts reuse only that *style* (viewport, weight, corners, tint). All
geometry is original; no LineageOS/AOSP/Retroid/Material path data was copied.
The Fan blades are generated parametrically as broad filled shapes, in the
spirit of the classic QS fan glyph. Lighting B is the user-chosen RGB ring.

## Variants

| File | Family | Variant | Notes |
|---|---|---|---|
| `fan-a-four-blade` | Fan | A - Four-blade | Four broad swept blades around a round hub. The classic QS-tile fan silhouette, with filled shapes that stay readable at 24dp. |
| `fan-b-three-blade` | Fan | B - Three-blade | Three wider swept blades around the same hub. More open than A and a little lighter in a Settings row. |
| `fan-c-four-blade-framed` | Fan | C - Framed four-blade | The four-blade rotor inside a thin circular frame. Reads as a housed case fan and keeps a perfectly round row icon. |
| `lighting-b-ring` | Lighting | B - Glow ring | One RGB stick ring with a sparkle. Chosen by the user; reads as a lit ring rather than generic brightness and echoes the RP5 hardware. |

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

The Fan glyphs are filled, matching Settings' filled row icons; the Lighting
ring uses the 960-viewport / 80-unit stroke language. If the app's existing
glyphs need a different weight, the same geometry can be adjusted at promotion.

1. Copy `vectors/<slug>.xml` to
   `aosp/vendor/ashfall/apps/AshFallParts/res/drawable/` under its final name.
2. Use the same name for the Settings row, the page header and the QS tile (or
   keep a separate tile asset if the tile needs a chunkier silhouette).
3. Verify at 24dp in light, dark and pure-black themes, and on the QS shade
   (active and inactive tint).

That work is intentionally **not** part of this PR.
