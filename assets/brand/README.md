# AshFall brand assets

Drafts, not wired into the build yet. Same status as `docs/icons/`: review
first, promote later.

| File | What it is |
|---|---|
| `ashfall-icon.svg` | App icon: circular tile, 512 viewBox, self-contained (no external refs). |
| `ashfall-mark.svg` | The same mark, one colour via `currentColor`. Use where `android:tint` applies, for the Android 13+ themed icon, or as a mask. |
| `render.sh` | Renders the GitHub rasters: org avatar (`png/`) and repo social preview cards (`social/`). `--check` diffs against the committed files. |
| `png/ashfall-avatar-*.png` | Org avatar, 512 px for GitHub plus a 1024 px copy. Square, Soot-filled, no transparent corners. |
| `social/<repo>.png` | Social preview card per repository, 1280x640, mark on the bloom + repo name + tagline. |
| `bootanimation/generate.py` | Renders the boot animation frames and packages `bootanimation.zip`. |
| `bootanimation/preview.mp4` | 23.8 s watchable preview (1280x720): the intro once, then 1.5 ambient loops. |
| `bootanimation/build/` | Generated output (gitignored). |

## The idea

A geometric **A** in two drifting ash strokes, sitting in a warm bloom on a
near-black circle. That is all: no second colour in the letterform, no
ornaments, no literal fan rotor.

The fan rotor is already taken by `ic_ashfall_fan` in Settings (`docs/icons/`),
so the system logo deliberately does not reuse it. Keeping the glyph itself
monochrome also means the mark is the same file whether it is the colourful app
icon or a flat themed icon - only the background differs.

The bloom is a single radial gradient (`#FF8A3D`, 16% -> 0) centred just below
the letterform and clipped to the tile, so it reads warm at 512 px without any
orange element surviving as a dot or a spark. At 24 px it flattens into a
near-black disc with a crisp A, which is the correct failure mode.

## Palette

| Name | Hex | Use |
|---|---|---|
| Ember | `#FF8A3D` | The icon's bloom, the boot animation's glow |
| Ash | `#EDE9E4` | Mark on dark surfaces |
| Soot | `#0B0C0F` | Icon tile, boot animation background |

`#0B0C0F` is shared with the boot animation, so a device that shows the tile and
the boot sequence never shifts colour temperature.

## Clear space and sizes

Keep at least one crossbar thickness (38/512) clear on every side. The glyph is
legible down to 16 px. The bloom is decorative: it can be dropped entirely
(ship `ashfall-mark.svg` on a flat background) where a solid fill is required.

## Boot animation

`bootanimation/generate.py` renders a small 3D scene in numpy and encodes it with
Pillow; ffmpeg is only needed for the mp4 preview.

* **Depth.** Every mote has a `z`; the screen position is `Z_GLYPH / z`, so near
  motes cross the frame quickly and far ones barely move, with size, brightness
  and defocus following depth.
* **Speed arc.** The intro does not drift at one rate. A speed profile runs
  slow -> peak (32x the ambient drift, `SPEED_PEAK`) -> slow, and *position is
  its integral*, so each frame's streak length comes from the real velocity
  rather than a guess. The ramps are symmetric (0.85 s each, `T_RAMP_UP` /
  `T_RAMP_DOWN`) around a 1.8 s hold (`T_HOLD`). Streak energy is divided by its
  length, so fast motes smear and dim instead of turning into laser lines, and
  stretched motes are forced slightly defocused so the smear stays soft-edged.
  The profile ends at exactly the ambient rate, which is why the hand-off to the
  loop has no speed step. Horizontal drift runs on its own slow clock, so the
  burst is purely vertical: motes streak up and down instead of also sliding
  sideways. A mote moves a few pixels horizontally across the whole burst while
  covering a thousand vertically.
* **Occlusion.** Motes are split by `z` against the mark's plane and drawn
  before or after the glyph, so some pass *in front of* the A. This is the
  change that stops the logo reading as a decal.
* **Optics.** Defocus is a per-mote soft falloff chosen by distance from the
  focus plane, distance haze dims far motes, and motion blur is an anisotropic
  streak sprite rather than subframe sampling (sampling left visible beads once
  a mote moved more than its own radius per subframe).
* **Bloom, no ember.** The light is one smooth long-tailed falloff behind the
  mark - no bright core, no dot. The A appears *into* the glow: the bloom
  builds first and the glyph fades up lit by it. (An earlier version stacked
  discs for volume and drew a hot ember; the overlap banded and the dot pulled
  the eye away from the A.)

Timeline:

1. **part0, once (5.8 s)** - ash drifts slowly for half a second, ramps to a
   blur over 0.85 s, holds there for 1.8 s, then decelerates over the same
   0.85 s. The bloom arrives with the slowdown, and the A fades up over 2 s
   (`GLYPH_IN`, 3.7 -> 5.7 s) lit by it, finishing right as the intro ends.
2. **part1, loops (12 s)** - the mark holds with the bloom breathing gently
   while motes drift past in front and behind it.

Why the loop does not read as a loop:

* each mote owns its own life cycle with a random phase, and only fades near
  its own respawn boundary - nothing fades in or out in unison;
* the bloom is driven by wrapping value noise whose period is exactly the loop
  length, so it flickers slightly rather than pulsing;
* the loop is 12 s, longer than a typical boot, so the seam is rarely reached;
* part1 starts at the phase *and* the speed part0 ends on, so the play-once ->
  loop boundary is continuous (measured mean difference: 0.6/255, i.e. one frame
  of motion).

`desc.txt`:

```
1920 1080 30
p 1 0 part0
p 0 0 part1
```

A part with count 0 loops forever; a non-zero count plays exactly that many
times; Android stops the animation when boot finishes. So the looping part has
to be the branded hold - a one-shot outro is unreachable on a fast boot and
looks stuck on a slow one.

```sh
python3 assets/brand/bootanimation/generate.py                # frames + zip
python3 assets/brand/bootanimation/generate.py --preview preview.mp4
python3 assets/brand/bootanimation/generate.py --loop-seconds 8 --motes 120
python3 assets/brand/bootanimation/generate.py --size 1080x1920
```

`--size 1080x1920` is the fallback if a device build comes up sideways. The
panel is 1080x1920 native portrait and SurfaceFlinger reports 1920x1080 logical
(`docs/rp5/display.md`), so the scene is authored landscape - but that is exactly
the kind of assumption worth checking once on hardware before promoting.

Current output: 174 + 360 frames, 41.7 MB. The zip must contain **stored**
(uncompressed) entries, which the generator does. Frames are saved as 256-colour
palette PNGs: the image is a black-to-ember ramp, so that is visually lossless
and roughly halves the size.

Requires numpy, Pillow and `rsvg-convert`; ffmpeg for `--preview` only.

## Where it is applied

1. `assets/brand/ashfall-icon.svg` is the icon every AshFall README shows.
2. `aosp/vendor/ashfall/apps/AshFallParts/res/drawable/ic_ashfall.xml` is the
   parts app icon, scaled to 48 dp with the bloom approximated by three
   translucent discs (no gradient inflation).
3. `aosp/vendor/ashfall/rp5/brand/bootanimation.zip` ships to
   `system/media/bootanimation.zip` via `vendor/ashfall/rp5/brand.mk`. The zip is
   checked in on purpose: the build host has no numpy/Pillow/rsvg-convert, and
   the animation has to exist at image build time.
4. GitHub: the `ashfall-rom` org avatar, its profile README, and every
   repository's README header and social preview card. See
   [`docs/branding.md`](../../docs/branding.md) for the exact files and the two
   uploads that still need the web UI.

Still to verify on hardware, in the build that carries this:

* the animation comes up landscape, not sideways;
* the parts app icon and the README icon match what was authored;
* no other package installs a second `system/media/bootanimation.zip`.

## Boot animation on the GSI

The GSI flow flashes only `system`; `/product/media/bootanimation.zip` on the
device can therefore stay from an older build, and AOSP's `bootanimation`
checks product/OEM before `system`. `patches/frameworks/base/0006-bootanimation-prefer-system.patch`
makes it check `/system/media/bootanimation.zip` first, so the AshFall
animation always wins. If the old animation shows up, verify the patch applied
and that `/system/media/bootanimation.zip` exists on the device.
