#!/usr/bin/env python3
"""Generate the AshFall boot animation.

Renders a small 3D scene in numpy: ash motes scattered in depth around the mark,
a warm bloom behind it, depth of field, motion blur and depth occlusion (motes
pass both in front of and behind the A). The intro has a deliberate speed arc -
slow, then a fast blur, then it settles as the mark fades up - driven by a speed
profile that is integrated, so streak length follows the real velocity. The
glyph still comes from `assets/brand/ashfall-mark.svg`, so the logo has one
source of truth.

Output is an Android `bootanimation.zip`:

    desc.txt
    part0/0000.png ...      intro, played once   (p 1 0 part0)
    part1/0000.png ...      ambient, loops       (p 0 0 part1)

`desc.txt` semantics: a part with count 0 loops forever, a non-zero count plays
exactly that many times, and Android stops the whole animation when boot
finishes. So the looping part has to be the branded hold - a one-shot outro is
unreachable on a fast boot and looks stuck on a slow one.

Why the loop does not read as a loop:

* every mote owns its own life cycle, offset by a random phase, so nothing
  fades in or out at the same time as anything else;
* a mote only fades near its own respawn boundary, never globally;
* the bloom is driven by wrapping 1D value noise whose period is exactly the
  loop length, so it flickers instead of pulsing;
* the loop is 12 s, longer than a typical boot, so the seam is rarely reached
  at all.

Size: the RP5 panel is 1080x1920 native portrait but SurfaceFlinger reports
1920x1080 logical (docs/rp5/display.md), so the scene is authored landscape.
`--size 1080x1920` is the fallback if a device build comes up sideways.

Usage:
    python3 assets/brand/bootanimation/generate.py
    python3 assets/brand/bootanimation/generate.py --preview preview.mp4
    python3 assets/brand/bootanimation/generate.py --loop-seconds 8 --size 1080x1920

Requires: numpy, Pillow, rsvg-convert. ffmpeg only for --preview.
Frames and the zip land in `assets/brand/bootanimation/build/` (gitignored).
"""

from __future__ import annotations

import argparse
import math
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
MARK_SVG = REPO / "assets" / "brand" / "ashfall-mark.svg"

FPS = 30
INTRO_S = 5.8
LOOP_S = 12.0
# glyph fade, in seconds from the start of the intro (deliberately slow)
GLYPH_IN = (3.70, 5.70)

# scene constants, in pixels at the mark's plane
FOCAL = 1400.0
Z_GLYPH = 1200.0
Z_NEAR = 560.0
Z_FAR = 2700.0
Z_FOCUS = 1450.0
# Speed profile of the intro, as a multiple of the ambient drift rate:
# slow -> very fast -> slow. The last value is exactly 1.0 so the drift speed
# carries straight into the loop.
SPEED_PEAK = 32.0
T_RAMP_UP = (0.50, 1.35)
T_HOLD = (1.35, 3.15)
T_RAMP_DOWN = (3.15, 4.00)
GLYPH_H = 0.40  # mark height as a fraction of frame height
EXPOSURE = 1.15

ASH = np.array([237, 233, 228], dtype=np.float32) / 255.0
EMBER = np.array([255, 138, 61], dtype=np.float32) / 255.0
EMBER_HOT = np.array([255, 211, 160], dtype=np.float32) / 255.0
DUST = np.array([172, 175, 180], dtype=np.float32) / 255.0


# --------------------------------------------------------------------------- math


def smoothstep(x: float) -> float:
    x = 0.0 if x < 0.0 else 1.0 if x > 1.0 else x
    return x * x * (3.0 - 2.0 * x)


def smoothstep_array(x: np.ndarray) -> np.ndarray:
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def ease_out(x: float) -> float:
    x = 0.0 if x < 0.0 else 1.0 if x > 1.0 else x
    return 1.0 - (1.0 - x) ** 3


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


class Rand:
    """Deterministic PRNG, so regeneration is byte-identical."""

    def __init__(self, seed: int):
        self.s = np.random.SeedSequence(seed)

    def stream(self):
        return np.random.default_rng(self.s)

    def uniform(self, a: float, b: float) -> float:
        return float(np.random.default_rng(self.s).uniform(a, b))


# --------------------------------------------------------------------------- sprites


class SpriteAtlas:
    """Soft discs at a handful of defocus levels, built once and reused.

    Defocus is approximated with a softer falloff rather than a real blur: at
    1080p inside a dark frame the difference is invisible and it keeps the
    inner loop to a single slice-add per mote.

    `stretch` elongates the disc vertically into a streak. That is the motion
    blur: sampling subframes left visible beads whenever a mote moved more than
    its own radius per subframe, and one anisotropic sprite is both smoother
    and much cheaper.
    """

    SHARP = (5.0, 3.4, 2.3, 1.5, 1.0, 0.62)
    HALO = (2.4, 1.9, 1.5, 1.2, 0.95, 0.72)
    STRETCH = (1.0, 1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 15.0, 20.0)

    def __init__(self) -> None:
        self.cache: dict[tuple[int, int, int], np.ndarray] = {}

    def get(self, radius: float, level: int, stretch: int = 0) -> np.ndarray:
        r = max(1, int(round(radius)))
        level = max(0, min(len(self.SHARP) - 1, level))
        stretch = max(0, min(len(self.STRETCH) - 1, stretch))
        key = (r, level, stretch)
        hit = self.cache.get(key)
        if hit is not None:
            return hit

        ry = r * self.STRETCH[stretch]
        half = int(math.ceil(max(r, ry) * 3.2))
        yy, xx = np.mgrid[-half : half + 1, -half : half + 1].astype(np.float32)
        rr = np.sqrt((xx / float(r)) ** 2 + (yy / float(ry)) ** 2)
        a = np.exp(-(rr * rr) * self.SHARP[level]) + 0.30 * np.exp(-rr * self.HALO[level])
        # cosine window: the sprite must reach exactly zero at its edge, or the
        # square canvas shows up as a faint rectangle against black
        edge = np.clip(rr / 3.15, 0.0, 1.0)
        a *= 0.5 * (1.0 + np.cos(np.pi * edge))
        a = np.clip(a, 0.0, 1.0)
        self.cache[key] = a
        return a


ATLAS = SpriteAtlas()


def add_sprite(
    buf: np.ndarray,
    sx: float,
    sy: float,
    radius: float,
    color,
    intensity: float,
    level: int,
    stretch: int = 0,
) -> None:
    if intensity <= 0.002:
        return
    sp = ATLAS.get(radius, level, stretch)
    h, w = sp.shape
    x0 = int(round(sx)) - w // 2
    y0 = int(round(sy)) - h // 2
    if x0 >= buf.shape[1] or y0 >= buf.shape[0] or x0 + w <= 0 or y0 + h <= 0:
        return

    cy0 = max(0, -y0)
    cx0 = max(0, -x0)
    cy1 = min(h, buf.shape[0] - y0)
    cx1 = min(w, buf.shape[1] - x0)
    buf[y0 + cy0 : y0 + cy1, x0 + cx0 : x0 + cx1] += (
        sp[cy0:cy1, cx0:cx1, None] * (np.asarray(color, dtype=np.float32) * intensity)
    )


# --------------------------------------------------------------------------- scene


class Mote:
    """One ash mote with a depth, a drift and its own life cycle.

    Position is defined in pixels *at the mark's plane*; projecting multiplies
    by scale(z) = Z_GLYPH / z, which gives the parallax and the size change.
    """

    def __init__(self, rng: np.random.Generator, cycles: int, span: float):
        self.x0 = rng.uniform(-0.85, 0.85)  # fraction of frame width
        self.y_phase = rng.uniform(0.0, 1.0)
        self.cycles = cycles
        self.phase = rng.uniform(0.0, 1.0)
        self.z = float(np.exp(rng.uniform(np.log(Z_NEAR), np.log(Z_FAR))))
        self.radius = rng.uniform(1.4, 5.6)
        self.sway = rng.uniform(5.0, 22.0)
        self.sway_phase = rng.uniform(0.0, 1.0)
        self.vx = rng.uniform(-12.0, 12.0)
        self.span = span
        self.bright = float(rng.uniform(0.35, 1.15))
        self.color = DUST if rng.random() < 0.08 else (EMBER_HOT if rng.random() < 0.22 else EMBER)

    def state(self, u: float, u_slow: float, w: int, h: int, du_frame: float):
        """Screen x, y, radius, alpha, defocus level and streak index.

        `du_frame` is how far `u` advances in one frame, which is what turns the
        world-space fall speed into a per-frame screen streak.
        """
        life = (u * self.cycles + self.phase) % 1.0
        # fade only around this mote's own respawn, staggered by `phase`
        env = smoothstep(life / 0.10) * smoothstep((1.0 - life) / 0.10)

        span = self.span
        y = (self.y_phase - 0.5) * h * 1.3 + (life - 0.5) * span
        # wrap just outside the frame, so most motes are visible at any time
        y = ((y + h * 0.85) % (h * 1.7)) - h * 0.85

        # horizontal drift runs on its own slow clock: when the field winds up
        # the motes streak away vertically instead of also sliding sideways
        life_slow = (u_slow * self.cycles + self.phase) % 1.0
        x = self.x0 * w + self.vx * (life_slow - 0.5) + self.sway * math.sin(
            math.tau * (life_slow + self.sway_phase)
        )

        scale = Z_GLYPH / self.z
        sx = w / 2.0 + x * scale
        sy = h / 2.0 + y * scale
        radius = self.radius * scale

        # aerial perspective: far motes are dimmer and cooler
        depth = (self.z - Z_NEAR) / (Z_FAR - Z_NEAR)
        haze = lerp(1.0, 0.30, depth)

        # streak length, capped in absolute pixels: a big defocused mote at full
        # speed would otherwise smear into a bright beam
        streak = min(abs(span * self.cycles * du_frame) * scale, 0.14 * h)
        stretch = int(round(streak / (2.0 * max(0.6, radius))))
        stretch = max(0, min(len(SpriteAtlas.STRETCH) - 1, stretch))
        # the same light spread over a longer streak is dimmer
        energy = 1.0 / (1.0 + 0.75 * (SpriteAtlas.STRETCH[stretch] - 1.0))
        # a fast, soft particle is a smear, not a laser: stretched motes are
        # always defocused, which keeps the streak soft-edged
        level = max(self.defocus(), min(4, stretch // 2 + 1)) if stretch else self.defocus()
        return sx, sy, radius, env * self.bright * haze * energy, level, stretch

    def defocus(self) -> int:
        d = abs(1.0 / self.z - 1.0 / Z_FOCUS) * 4600.0
        return int(min(5, max(0, round(d))))


def build_motes(rng: np.random.Generator, count: int, span: float) -> list[Mote]:
    motes = []
    for _ in range(count):
        # a few motes live much closer to camera than the mark, for bokeh
        cycles = 1 if rng.random() < 0.7 else 2
        motes.append(Mote(rng, cycles, span))
    return motes


class Bloom:
    """Warm light behind the mark, as one smooth radial falloff.

    An earlier version stacked several discs to fake volume; at this size the
    overlap showed as concentric rings. A single long-tailed sprite is smooth
    at any exposure, and there is no bright core - the glow is the event, not a
    dot inside it.
    """

    def draw(self, buf: np.ndarray, cx: float, cy: float, radius: float, level: float, flicker: float) -> None:
        if level <= 0.002:
            return
        add_sprite(buf, cx, cy, radius, EMBER, level * flicker * 0.42, level=0)


class Scene:
    def __init__(self, w: int, h: int, loop_s: float, mote_count: int):
        self.w, self.h = w, h
        self.loop_s = loop_s
        rng = np.random.default_rng(20261115)

        self.glyph_px = int(h * GLYPH_H / 0.578)
        self.glyph_mask = self._glyph_mask()
        self.bloom_xy = (w / 2.0, h / 2.0 + 44.0 / 512.0 * self.glyph_px)
        self.spill = self._spill_field()
        self.motes = build_motes(rng, mote_count, span=h * 1.45)
        self.bloom = Bloom()

        # wrapping 1D value noise control points for the loop's flicker
        self.flicker_keys = rng.uniform(0.72, 1.0, size=12)

        self.intro_n = int(INTRO_S * FPS)
        self.u_samples = self._intro_curve()
        self.offset_u = float(self.u_samples[-1]) % 1.0

    def _intro_curve(self) -> np.ndarray:
        """Cumulative drift for the intro, in loop cycles.

        The field does not run at one speed: it starts slow, winds up to a
        blur, then settles. Integrating a speed profile gives the position, and
        the local derivative gives each frame's streak length, so the motion
        blur stays honest through the ramp.
        """
        n = self.intro_n * 8
        t = np.linspace(0.0, INTRO_S, n + 1)

        def ramp(a: float, b: float, t0: float, t1: float) -> np.ndarray:
            return a + (b - a) * smoothstep_array((t - t0) / (t1 - t0))

        speed = np.where(
            t < T_RAMP_UP[0],
            0.55,
            np.where(
                t < T_RAMP_UP[1],
                ramp(0.55, SPEED_PEAK, *T_RAMP_UP),
                np.where(
                    t < T_HOLD[1],
                    SPEED_PEAK,
                    np.where(t < T_RAMP_DOWN[1], ramp(SPEED_PEAK, 1.0, *T_RAMP_DOWN), 1.0),
                ),
            ),
        )
        du = 0.5 * (speed[1:] + speed[:-1]) * np.diff(t) / self.loop_s
        return np.concatenate([[0.0], np.cumsum(du)])

    def intro_u(self, t: float) -> tuple[float, float]:
        """Drift phase and per-frame step at time t."""
        pos = np.clip(t / INTRO_S, 0.0, 1.0) * (len(self.u_samples) - 1)
        u = float(np.interp(pos, np.arange(len(self.u_samples)), self.u_samples))
        nxt = float(np.interp(min(pos + 8.0, len(self.u_samples) - 1),
                              np.arange(len(self.u_samples)), self.u_samples))
        return u, nxt - u

    # -- assets -----------------------------------------------------------
    def _glyph_mask(self) -> np.ndarray:
        tmp = Path(tempfile.mkdtemp(prefix="ashfall-glyph-"))
        try:
            svg = MARK_SVG.read_text().replace("currentColor", "#FFFFFF")
            src = tmp / "mark.svg"
            dst = tmp / "mark.png"
            src.write_text(svg)
            subprocess.run(
                ["rsvg-convert", "-w", str(self.glyph_px), "-h", str(self.glyph_px), str(src), "-o", str(dst)],
                check=True,
            )
            img = Image.open(dst).convert("RGBA")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

        mask = np.zeros((self.h, self.w), dtype=np.float32)
        y0 = int(self.h / 2 - img.height / 2)
        x0 = int(self.w / 2 - img.width / 2)
        mask[y0 : y0 + img.height, x0 : x0 + img.width] = (
            np.asarray(img.getchannel("A"), dtype=np.float32) / 255.0
        )
        return mask

    def _spill_field(self) -> np.ndarray:
        yy, xx = np.mgrid[0 : self.h, 0 : self.w].astype(np.float32)
        cx, cy = self.bloom_xy
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        return np.exp(-((d / (0.19 * self.h)) ** 2)).astype(np.float32)

    # -- light ------------------------------------------------------------
    def flicker(self, u: float) -> float:
        """Value noise over the loop, periodic in u by construction."""
        n = len(self.flicker_keys)
        t = (u % 1.0) * n
        i = int(math.floor(t))
        f = smoothstep(t - i)
        a = self.flicker_keys[i % n]
        b = self.flicker_keys[(i + 1) % n]
        slow = lerp(a, b, f)
        # second octave at half period, also wrapping
        i2 = int(math.floor(t * 2)) % n
        f2 = smoothstep(t * 2 - math.floor(t * 2))
        fast = lerp(self.flicker_keys[i2], self.flicker_keys[(i2 + 1) % n], f2)
        return 0.72 * slow + 0.28 * fast

    # -- frames -----------------------------------------------------------
    def render(self, part: str, t: float, u: float, subphase: float = 0.0) -> np.ndarray:
        w, h = self.w, self.h
        buf = np.zeros((h, w, 3), dtype=np.float32)

        if part == "intro":
            # light arrives with the slowdown, not with the speed
            bloom = smoothstep((t - 2.9) / 1.7) * 1.55
            glyph_a = smoothstep((t - GLYPH_IN[0]) / (GLYPH_IN[1] - GLYPH_IN[0]))
            settle = smoothstep((t - 3.4) / 1.8)
            flick = lerp(1.0, 0.86 + 0.24 * self.flicker(self.offset_u), settle)
        else:
            k = u
            bloom = 1.35 + 0.22 * (self.flicker(u) - 0.8) / 0.28
            glyph_a = 1.0
            flick = 0.86 + 0.24 * self.flicker(u)

        # part1 starts at the phase part0 ends on and at the speed part0 ends
        # on, so the play-once -> loop boundary is continuous
        du_frame = 1.0 / (self.loop_s * FPS) if part == "loop" else self._du_frame
        # slow clock ends exactly on the loop's start phase, so the horizontal
        # drift is continuous across the part boundary too
        u_slow = u if part == "loop" else self._u_slow

        # motes, split by whether they are in front of the mark
        behind, front = [], []
        for m in self.motes:
            sx, sy, r, a, lvl, stretch = m.state(u, u_slow, w, h, du_frame)
            if a <= 0.003:
                continue
            z = m.z
            scale = Z_GLYPH / z
            px = w / 2.0 + (sx - w / 2.0) * scale
            py = h / 2.0 + (sy - h / 2.0) * scale
            item = (px, py, r * scale, a, lvl, stretch, m.color)
            (front if z < Z_GLYPH else behind).append(item)

        if part == "intro":
            fade_in = smoothstep(t / 0.55)
            behind = [b[:3] + (b[3] * fade_in,) + b[4:] for b in behind]
            front = [f[:3] + (f[3] * fade_in,) + f[4:] for f in front]

        # warm bloom + everything behind the mark
        bx, by = self.bloom_xy
        self.bloom.draw(buf, bx, by, self.glyph_px * 0.19, bloom, flick)
        for px, py, r, a, lvl, stretch, col in behind:
            add_sprite(buf, px, py, r, col, a, lvl, stretch)

        # the mark, lit by the bloom behind it
        if glyph_a > 0.002:
            light = 0.88 + 0.12 * min(1.0, bloom / 1.55)
            tone = ASH[None, None, :] * light + EMBER[None, None, :] * (0.10 * self.spill)[..., None]
            buf += self.glyph_mask[..., None] * tone * glyph_a

        # motes in front of the mark: this is what makes the depth read
        for px, py, r, a, lvl, stretch, col in front:
            add_sprite(buf, px, py, r, col, a * (1.0 + 0.8 * (lvl / 5.0)), lvl, stretch)

        return buf

    def frame(self, part: str, index: int, total: int, motion_blur: int = 4) -> np.ndarray:
        acc = np.zeros((self.h, self.w, 3), dtype=np.float32)
        for s in range(motion_blur):
            sub = s / motion_blur
            if part == "intro":
                t = (index + sub) / FPS
                u, self._du_frame = self.intro_u(t)
                self._u_slow = t * self.offset_u / INTRO_S
                acc += self.render("intro", t, u, sub)
            else:
                u = (self.offset_u + (index + sub) / total) % 1.0
                acc += self.render("loop", 0.0, u, sub)
        acc /= motion_blur
        out = np.clip(1.0 - np.exp(-acc * EXPOSURE), 0.0, 1.0) ** (1.0 / 2.2)
        return (out * 255.0 + 0.5).astype(np.uint8)


# --------------------------------------------------------------------------- output


def write_frames(
    out: Path, scene: Scene, folder: str, part: str, count: int, blur: int, colors: int = 256
) -> None:
    d = out / folder
    d.mkdir(parents=True, exist_ok=True)
    for i in range(count):
        img = Image.fromarray(scene.frame(part, i, count, blur))
        if colors:
            # The palette is a black-to-ember ramp, so 256 adaptive colours are
            # visually lossless here and roughly halve the zip (128 bands).
            img = img.convert(
                "P", palette=Image.Palette.ADAPTIVE, colors=colors, dither=Image.Dither.FLOYDSTEINBERG
            )
        img.save(d / f"{i:04d}.png", optimize=True)
        if i % 30 == 0 or i == count - 1:
            print(f"  {folder} {i + 1}/{count}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", default="1920x1080", help="frame size, WxH (default 1920x1080)")
    ap.add_argument("--out", default=str(HERE / "build"))
    ap.add_argument("--loop-seconds", type=float, default=LOOP_S, help="ambient loop length (default 12)")
    ap.add_argument("--motes", type=int, default=150, help="mote count (default 150)")
    ap.add_argument("--colors", type=int, default=256, help="palette size, 0 to keep RGB (default 256)")
    ap.add_argument("--motion-blur", type=int, default=1, help="extra subframes per frame (default 1; streaks are baked into the sprite)")
    ap.add_argument("--preview", default=None, help="also write an mp4 preview here")
    ap.add_argument("--preview-loops", type=float, default=1.5, help="loops to include in the preview (default 1.5)")
    ap.add_argument("--preview-width", type=int, default=1280)
    args = ap.parse_args()

    w, h = (int(v) for v in args.size.lower().split("x"))
    out = Path(args.out)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    scene = Scene(w, h, args.loop_seconds, args.motes)
    intro_n = int(INTRO_S * FPS)
    loop_n = int(args.loop_seconds * FPS)

    write_frames(out, scene, "part0", "intro", intro_n, args.motion_blur, args.colors)
    write_frames(out, scene, "part1", "loop", loop_n, args.motion_blur, args.colors)

    (out / "desc.txt").write_text(f"{w} {h} {FPS}\np 1 0 part0\np 0 0 part1\n")

    zip_path = out / "bootanimation.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as z:
        z.write(out / "desc.txt", "desc.txt")
        for part in ("part0", "part1"):
            for png in sorted((out / part).glob("*.png")):
                z.write(png, f"{part}/{png.name}")
    print(f"{zip_path}  ({zip_path.stat().st_size / 1e6:.1f} MB, {intro_n}+{loop_n} frames)")

    if args.preview:
        seq = out / "seq"
        seq.mkdir()
        total = intro_n + int(loop_n * args.preview_loops)
        for i in range(total):
            if i < intro_n:
                src = out / "part0" / f"{i:04d}.png"
            else:
                # --preview-loops may ask for less than a whole loop; wrap it
                src = out / "part1" / f"{(i - intro_n) % loop_n:04d}.png"
            (seq / f"{i:04d}.png").write_bytes(src.read_bytes())
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS),
             "-i", str(seq / "%04d.png"), "-pix_fmt", "yuv420p",
             "-vf", f"scale={args.preview_width}:-2", "-movflags", "+faststart",
             args.preview],
            check=True,
        )
        shutil.rmtree(seq)
        print(args.preview)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
