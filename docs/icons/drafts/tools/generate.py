#!/usr/bin/env python3
"""Generate AshFall Fan/Lighting icon drafts (issue #85).

Clean-room designs. Everything here is preview material: the generated
24dp vector drawables are NOT wired into the app.

Outputs (all under docs/icons/drafts/):
  vectors/<slug>.xml        Android vector drawable, 24dp, single colour
  svg/<slug>.svg            the same geometry as SVG (preview source)
  png/<slug>-<theme>-<px>.png
  index.html                contact sheet
"""

from __future__ import annotations

import math
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]          # docs/icons/drafts
SVG_DIR = ROOT / "svg"
VEC_DIR = ROOT / "vectors"
PNG_DIR = ROOT / "png"

VIEWPORT = 960
STROKE = 80          # 80/960 = 2dp at 24dp, the LineageOS 24 Settings weight
KAPPA = 0.5522847498307936

WHITE = "@android:color/white"
TINT = "?android:attr/colorControlNormal"

THEMES = {
    "light": {"bg": "#FBFBFB", "fg": "#1F1F1F"},
    "dark": {"bg": "#121212", "fg": "#F2F2F2"},
}
SIZES = (24, 48, 96)


# --------------------------------------------------------------------------
# geometry helpers
# --------------------------------------------------------------------------

def polar(cx: float, cy: float, r: float, deg: float) -> tuple[float, float]:
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def circle(cx: float, cy: float, r: float) -> str:
    """Closed circle as four cubic Béziers."""
    k = KAPPA * r
    return (
        f"M{cx - r:.2f},{cy:.2f}"
        f"C{cx - r:.2f},{cy - k:.2f} {cx - k:.2f},{cy - r:.2f} {cx:.2f},{cy - r:.2f}"
        f"C{cx + k:.2f},{cy - r:.2f} {cx + r:.2f},{cy - k:.2f} {cx + r:.2f},{cy:.2f}"
        f"C{cx + r:.2f},{cy + k:.2f} {cx + k:.2f},{cy + r:.2f} {cx:.2f},{cy + r:.2f}"
        f"C{cx - k:.2f},{cy + r:.2f} {cx - r:.2f},{cy + k:.2f} {cx - r:.2f},{cy:.2f}Z"
    )


def rounded_rect(x: float, y: float, w: float, h: float, r: float) -> str:
    return (
        f"M{x + r:.2f},{y:.2f}"
        f"L{x + w - r:.2f},{y:.2f}"
        f"Q{x + w:.2f},{y:.2f} {x + w:.2f},{y + r:.2f}"
        f"L{x + w:.2f},{y + h - r:.2f}"
        f"Q{x + w:.2f},{y + h:.2f} {x + w - r:.2f},{y + h:.2f}"
        f"L{x + r:.2f},{y + h:.2f}"
        f"Q{x:.2f},{y + h:.2f} {x:.2f},{y + h - r:.2f}"
        f"L{x:.2f},{y + r:.2f}"
        f"Q{x:.2f},{y:.2f} {x + r:.2f},{y:.2f}Z"
    )


def blade(cx: float, cy: float, base_deg: float, r0: float, r1: float,
          sweep: float, bow: float = 0.0) -> str:
    """One curved fan blade, swept clockwise from r0 to r1.

    ``bow`` pushes the control points sideways (positive = outward from the
    rotation direction) to keep the blade silhouette fan-like rather than a
    plain arc.
    """
    s = polar(cx, cy, r0, base_deg)
    e = polar(cx, cy, r1, base_deg + sweep)
    c1 = polar(cx, cy, r0 + (r1 - r0) * 0.30 + bow, base_deg + sweep * 0.12)
    c2 = polar(cx, cy, r0 + (r1 - r0) * 0.78 + bow, base_deg + sweep * 0.62)
    return (f"M{s[0]:.2f},{s[1]:.2f}"
            f"C{c1[0]:.2f},{c1[1]:.2f} {c2[0]:.2f},{c2[1]:.2f} {e[0]:.2f},{e[1]:.2f}")


def sparkle(cx: float, cy: float, size: float) -> str:
    """Four-point star with concave sides."""
    t = size * 0.11
    return (
        f"M{cx:.2f},{cy - size:.2f}"
        f"Q{cx + t:.2f},{cy - t:.2f} {cx + size:.2f},{cy:.2f}"
        f"Q{cx + t:.2f},{cy + t:.2f} {cx:.2f},{cy + size:.2f}"
        f"Q{cx - t:.2f},{cy + t:.2f} {cx - size:.2f},{cy:.2f}"
        f"Q{cx - t:.2f},{cy - t:.2f} {cx:.2f},{cy - size:.2f}Z"
    )


# --------------------------------------------------------------------------
# icon definitions
# --------------------------------------------------------------------------

def stroke(d: str, width: float = STROKE) -> dict:
    return {"d": d, "stroke": True, "width": width}


def fill(d: str) -> dict:
    return {"d": d, "fill": True}


ICONS = [
    {
        "slug": "fan-a-turbine",
        "family": "Fan",
        "label": "A - Turbine",
        "summary": "Three swept blades inside a thin shroud; the most literal "
                   "fan silhouette and the closest match to a Settings row icon.",
        "elements": [
            stroke(circle(480, 480, 330)),
            stroke(blade(480, 480, 270, 165, 270, 55, bow=10)),
            stroke(blade(480, 480, 30, 165, 270, 55, bow=10)),
            stroke(blade(480, 480, 150, 165, 270, 55, bow=10)),
            fill(circle(480, 480, 92)),
        ],
    },
    {
        "slug": "fan-b-case",
        "family": "Fan",
        "label": "B - Case fan",
        "summary": "A square fan housing with four blades. Rounded-square "
                   "silhouette makes it easy to tell apart from Lighting at 24dp.",
        "elements": [
            stroke(rounded_rect(180, 180, 600, 600, 112)),
            stroke(blade(480, 480, 45, 112, 208, 45, bow=8)),
            stroke(blade(480, 480, 135, 112, 208, 45, bow=8)),
            stroke(blade(480, 480, 225, 112, 208, 45, bow=8)),
            stroke(blade(480, 480, 315, 112, 208, 45, bow=8)),
            fill(circle(480, 480, 74)),
        ],
    },
    {
        "slug": "fan-c-airflow",
        "family": "Fan",
        "label": "C - Airflow",
        "summary": "A fan with two airflow strokes. Best fit for the QS tile, "
                   "where it reads as 'cooling active' at a glance.",
        "elements": [
            stroke(circle(380, 480, 210)),
            stroke(blade(380, 480, 270, 90, 175, 55, bow=8)),
            stroke(blade(380, 480, 30, 90, 175, 55, bow=8)),
            stroke(blade(380, 480, 150, 90, 175, 55, bow=8)),
            fill(circle(380, 480, 58)),
            stroke("M700,390C750,390 770,380 810,400"),
            stroke("M700,570C750,570 770,580 810,560"),
        ],
    },
    {
        "slug": "lighting-a-bars",
        "family": "Lighting",
        "label": "A - Light bars",
        "summary": "Three rounded light bars under a rail. Direct evolution of "
                   "the current ic_led / ic_tile_lighting asset, so the tile keeps "
                   "its meaning.",
        "elements": [
            stroke("M180,200L780,200"),
            stroke("M230,420L230,780"),
            stroke("M480,380L480,780"),
            stroke("M730,450L730,780"),
        ],
    },
    {
        "slug": "lighting-b-ring",
        "family": "Lighting",
        "label": "B - Glow ring",
        "summary": "One RGB stick ring with a sparkle. Reads as 'lit ring' "
                   "rather than generic brightness, and echoes the RP5 hardware.",
        "elements": [
            stroke(circle(440, 512, 228)),
            fill(sparkle(756, 244, 108)),
        ],
    },
    {
        "slug": "lighting-c-dual-rings",
        "family": "Lighting",
        "label": "C - Left/right rings",
        "summary": "Both stick rings plus a sparkle. The most product-specific "
                   "option for the Settings row; still legible at 24dp.",
        "elements": [
            stroke(circle(260, 566, 132)),
            stroke(circle(700, 566, 132)),
            fill(sparkle(480, 270, 90)),
        ],
    },
]


# --------------------------------------------------------------------------
# emitters
# --------------------------------------------------------------------------

def svg_doc(icon: dict, fg: str) -> str:
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'width="{VIEWPORT}" height="{VIEWPORT}" '
        f'viewBox="0 0 {VIEWPORT} {VIEWPORT}">'
    ]
    for el in icon["elements"]:
        if el.get("fill"):
            parts.append(f'<path d="{el["d"]}" fill="{fg}" stroke="none"/>')
        else:
            w = el.get("width", STROKE)
            parts.append(
                f'<path d="{el["d"]}" fill="none" stroke="{fg}" '
                f'stroke-width="{w}" stroke-linecap="round" '
                f'stroke-linejoin="round"/>'
            )
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def vector_doc(icon: dict) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- AshFall icon draft (issue #85). Clean-room, original design.",
        f"     {icon['label']}: {icon['summary']}",
        "     Preview only: not wired into the app. 24dp, single colour,",
        "     tintable. Geometry matches the LineageOS 24 Settings weight",
        "     (960 viewport, 80 = 2dp stroke). -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        f'    android:viewportWidth="{VIEWPORT}"',
        f'    android:viewportHeight="{VIEWPORT}"',
        f'    android:tint="{TINT}">',
    ]
    for el in icon["elements"]:
        if el.get("fill"):
            lines.append('    <path')
            lines.append(f'        android:fillColor="{WHITE}"')
            lines.append(f'        android:pathData="{el["d"]}" />')
        else:
            w = el.get("width", STROKE)
            lines.append('    <path')
            lines.append(f'        android:fillColor="@android:color/transparent"')
            lines.append(f'        android:strokeColor="{WHITE}"')
            lines.append(f'        android:strokeWidth="{w}"')
            lines.append('        android:strokeLineCap="round"')
            lines.append('        android:strokeLineJoin="round"')
            lines.append(f'        android:pathData="{el["d"]}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True)


def html_doc(icons: list[dict]) -> str:
    """Static contact sheet. All image paths are relative to docs/icons/drafts/."""
    families: dict[str, list[dict]] = {}
    for icon in icons:
        families.setdefault(icon["family"], []).append(icon)

    def theme_block(icon: dict, theme: str, cap: str) -> str:
        slug = icon["slug"]
        return f"""
        <div class="theme {theme}">
          <div class="theme-cap">{cap}</div>
          <div class="hero"><img src="png/{slug}-{theme}-96.png" width="96" height="96" alt="{icon['label']} on {theme}"></div>
          <div class="hero-label">96</div>
          <div class="small">
            <div class="sz"><img src="png/{slug}-{theme}-48.png" width="48" height="48" alt=""><span>48</span></div>
            <div class="sz"><img src="png/{slug}-{theme}-24.png" width="24" height="24" alt=""><span>24</span></div>
          </div>
        </div>"""

    def card(icon: dict) -> str:
        slug = icon["slug"]
        return f"""
      <article class="card">
        <div class="card-head">
          <div>
            <h3>{icon['label']}</h3>
            <div class="path">vectors/{slug}.xml</div>
          </div>
          <a class="file" href="vectors/{slug}.xml">XML</a>
        </div>
        <p class="summary">{icon['summary']}</p>
        <div class="themes">{theme_block(icon, 'light', 'Light')}{theme_block(icon, 'dark', 'Dark')}</div>
        <div class="card-foot">
          <a href="vectors/{slug}.xml">vector</a>
          <a href="svg/{slug}.svg">svg</a>
          <span>96 / 48 / 24 px</span>
        </div>
      </article>"""

    sections = []
    for family, items in families.items():
        cards = "".join(card(i) for i in items)
        sections.append(f"""
    <section>
      <div class="section-head">
        <h2>{family}</h2>
        <span>{len(items)} variants</span>
      </div>
      <div class="grid">{cards}
      </div>
    </section>""")

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AshFall icon drafts - Fan &amp; Lighting (#85)</title>
<style>
  :root {{ color-scheme: dark; }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; background: #0b0d10; color: #e7e9ee;
    font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  }}
  .wrap {{ max-width: 1180px; margin: 0 auto; padding: 48px 24px 72px; }}
  header {{ border-bottom: 1px solid #222833; padding-bottom: 28px; }}
  .eyebrow {{
    font: 600 12px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
    letter-spacing: .12em; text-transform: uppercase; color: #e8a15c;
  }}
  h1 {{ margin: 10px 0 8px; font-size: clamp(28px, 4vw, 42px); line-height: 1.1; letter-spacing: -.02em; }}
  h1 em {{ font-style: normal; color: #9aa4b5; }}
  .lede {{ margin: 0; max-width: 780px; color: #aab2c0; }}
  .chips {{ display: flex; flex-wrap: wrap; gap: 8px; margin-top: 20px; }}
  .chip {{
    border: 1px solid #2a313d; background: #12161c; color: #c3cad7;
    border-radius: 999px; padding: 6px 12px; font-size: 12.5px;
  }}
  .chip b {{ color: #f1f3f7; font-weight: 600; }}
  .warn {{
    margin-top: 22px; border-left: 3px solid #e8a15c; background: #1a1612;
    color: #d8c4ad; padding: 12px 16px; border-radius: 0 8px 8px 0; font-size: 14px;
  }}
  section {{ margin-top: 52px; }}
  .section-head {{ display: flex; align-items: baseline; gap: 12px; margin-bottom: 16px; }}
  .section-head h2 {{ margin: 0; font-size: 22px; letter-spacing: -.01em; }}
  .section-head span {{ color: #7f8998; font-size: 13px; }}
  .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(330px, 1fr)); gap: 20px; }}
  .card {{
    background: #12161c; border: 1px solid #232a35; border-radius: 16px;
    padding: 18px; display: flex; flex-direction: column; gap: 14px;
  }}
  .card-head {{ display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }}
  .card-head h3 {{ margin: 0 0 4px; font-size: 17px; letter-spacing: -.01em; }}
  .path {{ font: 11.5px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; color: #7f8998; }}
  .file {{
    flex: none; text-decoration: none; color: #e8a15c; border: 1px solid #3a2d20;
    background: #1c1711; border-radius: 7px; padding: 5px 9px; font-size: 11.5px;
  }}
  .summary {{ margin: 0; color: #aab2c0; font-size: 13.5px; min-height: 60px; }}
  .themes {{ display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }}
  .theme {{ border-radius: 11px; padding: 12px; display: flex; flex-direction: column; align-items: center; gap: 10px; }}
  .theme.light {{ background: #fbfbfb; color: #555; border: 1px solid #e6e6e6; }}
  .theme.dark {{ background: #121212; color: #aaa; border: 1px solid #2b2b2b; }}
  .theme-cap {{
    font: 600 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
    letter-spacing: .1em; text-transform: uppercase; opacity: .65; align-self: flex-start;
  }}
  .hero {{ width: 96px; height: 96px; display: grid; place-items: center; }}
  .hero img {{ display: block; }}
  .hero-label {{ font: 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace; opacity: .6; margin-top: -6px; }}
  .small {{ display: flex; align-items: flex-end; gap: 20px; min-height: 54px; }}
  .sz {{ display: flex; flex-direction: column; align-items: center; gap: 5px; }}
  .sz img {{ display: block; }}
  .sz span {{ font: 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace; opacity: .6; }}
  .card-foot {{
    display: flex; gap: 14px; align-items: center; border-top: 1px solid #222833;
    padding-top: 11px; font-size: 12px; color: #7f8998;
  }}
  .card-foot a {{ color: #9fb4d8; text-decoration: none; }}
  .card-foot a:hover {{ text-decoration: underline; }}
  .card-foot span {{ margin-left: auto; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }}
  .context {{ margin-top: 56px; }}
  .context-grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }}
  .device {{ border-radius: 16px; padding: 18px; border: 1px solid #232a35; background: #12161c; }}
  .device.light {{ background: #fbfbfb; border-color: #e3e3e3; color: #202124; }}
  .device.dark {{ background: #121212; border-color: #2a2a2a; color: #e8eaed; }}
  .device-cap {{
    font: 600 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
    letter-spacing: .1em; text-transform: uppercase; opacity: .55; margin-bottom: 12px;
  }}
  .row {{
    display: flex; align-items: center; gap: 16px; padding: 14px 16px; border-radius: 12px;
  }}
  .device.light .row {{ background: #ffffff; box-shadow: 0 1px 2px rgba(0,0,0,.06); }}
  .device.dark .row {{ background: #1b1b1b; }}
  .row + .row {{ margin-top: 10px; }}
  .row img {{ flex: none; }}
  .row-title {{ font-size: 16px; font-weight: 500; }}
  .row-sub {{ font-size: 12.5px; opacity: .62; }}
  .row-chev {{ margin-left: auto; opacity: .35; font-size: 20px; }}
  footer {{ margin-top: 56px; border-top: 1px solid #222833; padding-top: 22px; color: #7f8998; font-size: 13px; }}
  code {{ font: 12.5px ui-monospace, SFMono-Regular, Menlo, monospace; color: #c8d1df; }}
  @media (max-width: 560px) {{
    .themes, .context-grid {{ grid-template-columns: 1fr; }}
    .summary {{ min-height: 0; }}
  }}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="eyebrow">AshFall · Phase 5 · issue #85</div>
    <h1>Fan &amp; Lighting icon drafts <em>- preview only</em></h1>
    <p class="lede">
      Six clean-room 24dp vectors, three per family. Geometry follows the LineageOS 24
      Settings language (960 viewport, 80-unit stroke = 2dp, rounded joins, one colour).
      Nothing here is wired into the app.
    </p>
    <div class="chips">
      <span class="chip"><b>6</b> variants</span>
      <span class="chip"><b>24dp</b> Android vector</span>
      <span class="chip"><b>960</b> viewport</span>
      <span class="chip"><b>80</b> stroke (= 2dp)</span>
      <span class="chip"><b>1</b> colour, tintable</span>
      <span class="chip"><b>Light + dark</b> PNGs at 24/48/96</span>
    </div>
    <div class="warn">
      Drafts only. The vectors are not copied into <code>AshFallParts/res</code> and no
      app, tile or Settings code was changed. Pick a variant, then it can be promoted
      in a follow-up.
    </div>
  </header>
  {''.join(sections)}
  <section class="context">
    <div class="section-head"><h2>In context</h2><span>example: the two <b>A</b> variants at 2x (48 px)</span></div>
    <div class="context-grid">
      <div class="device light">
        <div class="device-cap">Settings · light</div>
        <div class="row">
          <img src="png/fan-a-turbine-light-48.png" width="48" height="48" alt="">
          <div><div class="row-title">Fan</div><div class="row-sub">Presets, curve and live temperature</div></div>
          <div class="row-chev">›</div>
        </div>
        <div class="row">
          <img src="png/lighting-a-bars-light-48.png" width="48" height="48" alt="">
          <div><div class="row-title">Lighting</div><div class="row-sub">Stick rings, colour and effects</div></div>
          <div class="row-chev">›</div>
        </div>
      </div>
      <div class="device dark">
        <div class="device-cap">Settings · dark</div>
        <div class="row">
          <img src="png/fan-a-turbine-dark-48.png" width="48" height="48" alt="">
          <div><div class="row-title">Fan</div><div class="row-sub">Presets, curve and live temperature</div></div>
          <div class="row-chev">›</div>
        </div>
        <div class="row">
          <img src="png/lighting-a-bars-dark-48.png" width="48" height="48" alt="">
          <div><div class="row-title">Lighting</div><div class="row-sub">Stick rings, colour and effects</div></div>
          <div class="row-chev">›</div>
        </div>
      </div>
    </div>
  </section>
  <footer>
    Preview PNGs were rendered from the SVG twins of the vector drawables; both come from
    one geometry source, <code>tools/generate.py</code>. Regenerate with
    <code>python3 docs/icons/drafts/tools/generate.py</code> (needs <code>rsvg-convert</code>).
    Clean-room: original geometry, no third-party assets or path data.
  </footer>
</div>
</body>
</html>
"""


def readme_doc(icons: list[dict]) -> str:
    rows = "\n".join(
        f"| `{i['slug']}` | {i['family']} | {i['label']} | {i['summary']} |"
        for i in icons
    )
    return f"""# AshFall icon drafts - Fan & Lighting (issue #85)

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
{rows}

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
"""


def main() -> None:
    for d in (SVG_DIR, VEC_DIR, PNG_DIR):
        d.mkdir(parents=True, exist_ok=True)

    expected_pngs: set[str] = set()
    expected_vectors: set[str] = set()
    expected_svgs: set[str] = set()
    with tempfile.TemporaryDirectory(prefix="ashfall-icons-") as td:
        for icon in ICONS:
            slug = icon["slug"]
            expected_vectors.add(f"{slug}.xml")
            expected_svgs.add(f"{slug}.svg")
            (VEC_DIR / f"{slug}.xml").write_text(vector_doc(icon), encoding="utf-8")
            # Canonical SVG is dark-on-light so it is visible in a plain browser;
            # the themed preview SVGs below are generated separately.
            (SVG_DIR / f"{slug}.svg").write_text(svg_doc(icon, "#1F1F1F"), encoding="utf-8")

            for theme, colors in THEMES.items():
                tmp = Path(td) / f"{slug}-{theme}.svg"
                tmp.write_text(svg_doc(icon, colors["fg"]), encoding="utf-8")
                for px in SIZES:
                    name = f"{slug}-{theme}-{px}.png"
                    expected_pngs.add(name)
                    out = PNG_DIR / name
                    run(["rsvg-convert", "--width", str(px), "--height", str(px),
                         "--background-color", colors["bg"], str(tmp), "-o", str(out)])

    # Drop files left behind by a renamed or removed variant.
    for stale in PNG_DIR.glob("*.png"):
        if stale.name not in expected_pngs:
            stale.unlink()
    for stale in VEC_DIR.glob("*.xml"):
        if stale.name not in expected_vectors:
            stale.unlink()
    for stale in SVG_DIR.glob("*.svg"):
        if stale.name not in expected_svgs:
            stale.unlink()

    (ROOT / "index.html").write_text(html_doc(ICONS), encoding="utf-8")
    (ROOT / "README.md").write_text(readme_doc(ICONS), encoding="utf-8")
    print(f"generated {len(ICONS)} icons + index.html -> {ROOT}")



if __name__ == "__main__":
    main()
