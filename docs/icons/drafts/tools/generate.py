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


def swept_blade(cx: float, cy: float, base_deg: float, r_in: float, r_out: float,
                half_base: float, sweep: float, tip_half: float) -> str:
    """One broad, filled fan blade, swept clockwise around the hub.

    The leading edge is convex; the trailing edge is scooped, so the blade is
    narrow at the hub and broad at the rounded outer tip. All coordinates are
    absolute (no SVG transforms), so the same pathData works in Android.
    """
    def pt(r: float, deg: float) -> tuple[float, float]:
        return polar(cx, cy, r, deg)

    b1 = pt(r_in, base_deg - half_base)
    c1 = pt(r_in + (r_out - r_in) * 0.30, base_deg - half_base - 2)
    c2 = pt(r_in + (r_out - r_in) * 0.74,
            base_deg + sweep - tip_half - 12)
    p1 = pt(r_out, base_deg + sweep - tip_half)
    pc = pt(r_out + 28, base_deg + sweep)
    p2 = pt(r_out, base_deg + sweep + tip_half)
    c3 = pt(r_in + (r_out - r_in) * 0.72,
            base_deg + sweep + tip_half - 12)
    c4 = pt(r_in + (r_out - r_in) * 0.32, base_deg + half_base - 6)
    b2 = pt(r_in, base_deg + half_base)
    return (
        f"M{b1[0]:.2f},{b1[1]:.2f}"
        f"C{c1[0]:.2f},{c1[1]:.2f} {c2[0]:.2f},{c2[1]:.2f} {p1[0]:.2f},{p1[1]:.2f}"
        f"Q{pc[0]:.2f},{pc[1]:.2f} {p2[0]:.2f},{p2[1]:.2f}"
        f"C{c3[0]:.2f},{c3[1]:.2f} {c4[0]:.2f},{c4[1]:.2f} {b2[0]:.2f},{b2[1]:.2f}"
        f"A{r_in:.2f},{r_in:.2f} 0 0 0 {b1[0]:.2f},{b1[1]:.2f}Z"
    )


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


def fan_elements(blades: int, *, r_in: float = 80, r_out: float = 340,
                 half_base: float = 18, sweep: float = 28,
                 tip_half: float = 14, hub: float = 80,
                 offset: float = 0, frame: float | None = None) -> list[dict]:
    """A filled fan rotor: N swept blades plus a round hub (and optional shroud)."""
    elements = []
    if frame is not None:
        elements.append(stroke(circle(480, 480, frame)))
    for k in range(blades):
        base = offset + 360.0 * k / blades
        elements.append(fill(swept_blade(480, 480, base, r_in, r_out,
                                         half_base, sweep, tip_half)))
    elements.append(fill(circle(480, 480, hub)))
    return elements


ICONS = [
    {
        "slug": "fan-a-four-blade",
        "family": "Fan",
        "label": "A - Four-blade",
        "summary": "Four broad swept blades around a round hub. The classic "
                   "QS-tile fan silhouette, with filled shapes that stay readable "
                   "at 24dp.",
        "elements": fan_elements(4, tip_half=18),
    },
    {
        "slug": "fan-b-three-blade",
        "family": "Fan",
        "label": "B - Three-blade",
        "summary": "Three wider swept blades around the same hub. More open "
                   "than A and a little lighter in a Settings row.",
        "elements": fan_elements(3, half_base=28, tip_half=24, hub=82, offset=30),
    },
    {
        "slug": "fan-c-four-blade-framed",
        "family": "Fan",
        "label": "C - Framed four-blade",
        "summary": "The four-blade rotor inside a thin circular frame. Reads "
                   "as a housed case fan and keeps a perfectly round row icon.",
        "elements": fan_elements(4, r_in=78, r_out=288, tip_half=18, hub=78,
                                 frame=360, offset=45),
    },
    {
        "slug": "lighting-b-ring",
        "family": "Lighting",
        "label": "B - Glow ring",
        "chosen": True,
        "summary": "One RGB stick ring with a sparkle. Chosen by the user; "
                   "reads as a lit ring rather than generic brightness and "
                   "echoes the RP5 hardware.",
        "elements": [
            stroke(circle(440, 512, 228)),
            fill(sparkle(756, 244, 108)),
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
        "     tintable. 960 viewport; 80 = 2dp where stroked. -->",
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
        badge = ('<span class="chosen">chosen</span>'
                 if icon.get("chosen") else "")
        return f"""
      <article class="card">
        <div class="card-head">
          <div>
            <h3>{icon['label']}{badge}</h3>
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
        <span>{len(items)} variant{'s' if len(items) != 1 else ''}</span>
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
  .grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 20px; }}
  .card {{
    background: #12161c; border: 1px solid #232a35; border-radius: 16px;
    padding: 18px; display: flex; flex-direction: column; gap: 14px;
  }}
  .card-head {{ display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }}
  .card-head h3 {{ margin: 0 0 4px; font-size: 17px; letter-spacing: -.01em; }}
  .chosen {{
    display: inline-block; margin-left: 8px; vertical-align: 3px;
    font: 600 9.5px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
    letter-spacing: .08em; text-transform: uppercase; color: #9fe0b0;
    border: 1px solid #234a33; background: #10241a; border-radius: 999px;
    padding: 4px 7px;
  }}
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
      Four clean-room 24dp vectors: three new Fan variants (four-blade,
      three-blade and framed four-blade) plus the chosen Lighting B. The fans are
      filled, broad swept blades in the LineageOS 24 Settings weight; the ring is
      the selected RGB metaphor. Nothing here is wired into the app.
    </p>
    <div class="chips">
      <span class="chip"><b>{len(icons)}</b> variants</span>
      <span class="chip"><b>24dp</b> Android vector</span>
      <span class="chip"><b>960</b> viewport</span>
      <span class="chip"><b>80</b> stroke (= 2dp)</span>
      <span class="chip"><b>1</b> colour, tintable</span>
      <span class="chip"><b>Light + dark</b> PNGs at 24/48/96</span>
    </div>
    <div class="warn">
      Fan drafts only. The vectors are not copied into <code>AshFallParts/res</code> and no
      app, tile or Settings code was changed. Lighting B is the user's choice; pick one
      Fan variant and it can be promoted in a follow-up.
    </div>
  </header>
  {''.join(sections)}
  <section class="context">
    <div class="section-head"><h2>In context</h2><span>example: Fan A + Lighting B at 2x (48 px)</span></div>
    <div class="context-grid">
      <div class="device light">
        <div class="device-cap">Settings · light</div>
        <div class="row">
          <img src="png/fan-a-four-blade-light-48.png" width="48" height="48" alt="">
          <div><div class="row-title">Fan</div><div class="row-sub">Presets, curve and live temperature</div></div>
          <div class="row-chev">›</div>
        </div>
        <div class="row">
          <img src="png/lighting-b-ring-light-48.png" width="48" height="48" alt="">
          <div><div class="row-title">Lighting</div><div class="row-sub">Stick rings, colour and effects</div></div>
          <div class="row-chev">›</div>
        </div>
      </div>
      <div class="device dark">
        <div class="device-cap">Settings · dark</div>
        <div class="row">
          <img src="png/fan-a-four-blade-dark-48.png" width="48" height="48" alt="">
          <div><div class="row-title">Fan</div><div class="row-sub">Presets, curve and live temperature</div></div>
          <div class="row-chev">›</div>
        </div>
        <div class="row">
          <img src="png/lighting-b-ring-dark-48.png" width="48" height="48" alt="">
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
