# AshFall Parts: controller tester and calibration wizard v2

Follow-ups to `docs/parts.md` for the user tests of 2026-11-14 (issues **#84**
and **#101**, contract `docs/phase5.md` §6.2/§6.3).  This page documents the
controller package only; the rest of AshFall Parts (root entry, fan, lighting,
QS tiles) is described in `docs/parts.md`.

![Controller tester](parts/screenshots/tester.png)
![Controller tester, dark](parts/screenshots/tester-dark.png)

## 1. What the tester does now

| User report | Change |
|---|---|
| 1. "Draw a proper RP5 silhouette" | First #84 added a clean-room RP5 body.  The retest (#101) removed the silhouette again: `ui/ControllerTesterView` now draws **only the controls** - sticks with inner/outer deadzone rings, a D-pad, the A/B/X/Y diamond, L1/R1, L2/R2 analog bars, Start/Select, Home/Back and L3/R3 - enlarged to use the whole landscape width (853 dp).  Every colour comes from `colorAccent`, `textColorPrimary` and `textColorSecondary`, so light, dark and LineageOS pure-black all follow the system theme. |
| 2. "X and Y appear swapped" / "A/B are swapped" | The drawing, not the daemon, was wrong (see §2).  #84 moved X to the top and Y to the left; #101 swapped the drawn A and B (A right, B bottom). |
| 3. "L2/R2 jump to 100 % at about half travel" | The old bar filled the whole trigger rect as soon as the analog value passed 0.5 (a drawing bug), and the view only saw `AXIS_*` values.  The tester now draws the exact analog value as a fill from the bottom and shows the percentage, computed from the raw MCU counts after calibration (so the number can be compared with the raw value). |
| 4. "B, Back, Home, Select don't show" | The tester now sends `GRAB ON` on its raw-stream socket (§3).  Nothing is injected into uinput, so every button is only reported in the `RAW` mask and drawn.  Leaving: hold **Start + Select for 2 s** (progress bar under the pills) or tap **Close**.  The old "Select toggles the raw overlay" mode is gone; the raw values are always visible. |
| 5. "Fun: light each side's LED ring with its activity" | `ashfall.ui.led_override` is published at <= 29 Hz (§4) and cleared on pause. |
| 6. "Calibration waits for Start" | The wizard's timed steps were replaced by Start-paced steps (§5). |

## 2. The button layout

The physical RP5 diamond (confirmed in the user retests) is:

```
        X
    Y       A
        B
```

The MCU bits are fixed by `docs/rp5/input.md` and are **not** changed:

| MCU bit | stock key code | Key name | drawn at |
|---|---|---|---|
| 12 | `BTN_X` (0x133) | `BTN_NORTH` | top |
| 13 | `BTN_Y` (0x134) | `BTN_WEST` | left |
| 14 | `BTN_A` (0x130) | `BTN_SOUTH` | right |
| 15 | `BTN_B` (0x131) | `BTN_EAST` | bottom |

#84 moved X/Y to their real positions (the first drawing used the generic Xbox
arrangement); the retest showed that A/B were still swapped in the drawing, so
#101 swapped only the drawn positions of bits 14 and 15.  The daemon, the
`GRAB`/`RAW` protocol and the `SET CAL` format are untouched.

The sticks keep the physical arrangement without the body around it: the left
stick sits above the D-pad, the right stick below the face buttons.  Start/Select
are large pills at the bottom centre, Home/Back at the top centre.

## 3. GRAB lifecycle

`ipc/RawStream` now sends both commands on one connection:

```
RAW ON\n
GRAB ON\n
```

and reads the two replies (`OK`, or `ERR <reason>` -> `onGrabFailed`) with an
800 ms timeout, so a daemon that predates `GRAB` cannot block the tester.  While
grabbed:

* the daemon releases all held inputs and injects nothing into uinput;
* the tester's only input is the `RAW` frame (`lx ly rx ry l2 r2 buttons_hex`);
* `UI` key events do not arrive at all, which is the point: Home, Back and
  Select can be shown without the system consuming them first.

`onPause` sends `GRAB OFF`, then `RAW OFF`, and closes the socket; the daemon
also releases the grab when a client disconnects, so a crash cannot leave the
pad grabbed.  The activity is `KEEP_SCREEN_ON` for the duration.

If the socket or the daemon is unavailable the tester falls back to the ordinary
`KeyEvent`/`MotionEvent` path (plus B/Back to leave) and says so in the hint.

The on-screen **Close** button is the touch exit; with the pad grabbed the
gamepad cannot drive focus, so the Start+Select chord is the gamepad exit.  The
progress bar under the pills fills for 2 s and the activity finishes.

## 4. LED reaction

`ui/LedFeedback` writes

```
ashfall.ui.led_override = RRGGBB:RRGGBB      (left:right, empty = normal)
```

at most every 34 ms and only when the value changes.  Each side's level is the
maximum of that half's activity:

| side | inputs |
|---|---|
| left | left stick magnitude, L2, L1, L3, D-pad |
| right | right stick magnitude, R2, R1, R3, A/B/X/Y |
| both | Start/Select/Home/Back (0.55) |

The colour is the user's own `led.color_left`/`led.color_right` (falling back to
`FF2020`) scaled by `0.06 + 0.92 * activity` and by `led.brightness`; the idle
6 % glow shows which ring belongs to which side.  `clear()` writes an empty
value on pause/stop/destroy, which restores the normal lighting mode.

## 5. Calibration pacing

`CalibrationActivity` no longer uses 2/8/4-second timers.  Each step captures in
the background and ends when the user presses **Start** (the gamepad's
`KEYCODE_BUTTON_START`) or the on-screen Start button; the instructions, the
live sample count and the captured travel stay on screen for as long as needed:

1. **Let go of the sticks** - live sample count, Start when at rest.
2. **Roll both sticks around the edge** - live left/right travel, Start when
   done.  A quarter of the factory travel (or an absolute floor) is required,
   otherwise "Try again" appears; this rejects a short flick that would produce
   a skewed calibration.
3. **Pull both triggers fully** - live L2/R2 travel, same validation.
4. **Review and save** - unchanged: `SET CAL` with all 16 keys, Cancel leaves the
   file untouched.

The wizard never grabs the pad (Start must arrive as a normal key event), and B
still cancels.

## 6. Files

| file | change |
|---|---|
| `src/org/ashfall/parts/TesterActivity.java` | grab/raw lifecycle, 2 s Start+Select exit, close button, LED reaction, fallback input |
| `src/org/ashfall/parts/ui/ControllerTesterView.java` | controls-only layout (#101), mask-driven input, theme colours, exact trigger bars |
| `src/org/ashfall/parts/ui/LedFeedback.java` | new: `ashfall.ui.led_override` publisher |
| `src/org/ashfall/parts/hw/PadState.java` | new: raw counts -> calibrated/deadzoned values on the app side |
| `src/org/ashfall/parts/ipc/RawStream.java` | `GRAB ON/OFF` on the raw connection, reply handling |
| `src/org/ashfall/parts/CalibrationActivity.java` | Start-paced steps, travel validation, readable live status |
| `src/org/ashfall/parts/ControllerActivity.java` | new tester/footer strings |
| `src/org/ashfall/parts/ui/StickPreviewView.java` | correct colour-state-list resolution in dark themes |
| `res/layout/activity_tester.xml` | hint + Close row |
| `res/layout/activity_calibration.xml` | larger, readable instructions, Start hint |
| `res/values/strings_controller.xml` | all new controller strings (kept out of `strings.xml`) |
| `tools/mock/AshfallMockDaemon.c` | test-only: `GRAB`, mask animation, Start+Select hold hook |
| `docs/parts/screenshots/tester*.png`, `calibration-*.png` | new emulator captures |

## 7. Verification

Emulator preview (Android 13, 1920x1080, density 360), same sources as the
Soong module:

* `preview/prepare.sh && gradle :app:assembleDebug` -> BUILD SUCCESSFUL.
* Mock daemon: `tools/mock/build.sh --run` (test-only, never installed in the
  image) with `setenforce 0` (the debug APK cannot write properties as a
  user build, the same limitation documented in `docs/parts.md` §6.2).
* The tester connected with `GRAB ON` (mock log), streamed `RAW` frames, and the
  view's accessibility state tracked them:
  `Left -0.85 0.20, right -0.15 0.57, L2 0.13, R2 0.43, raw lx 27277 ...,
  buttons 0x0430, pressed L3 R3 Left`.  The calibrated values were recomputed by
  hand from the raw counts and the factory calibration (deadzone 5 %, outer 3 %,
  trigger 3 %) and match.
* Button positions were checked on screenshots by locating the accent-coloured
  fills: with the mock reporting `Y B R1 Select Home Back`, exactly those six
  shapes are lit, Y is **left** of the face centre and B is **below** it; with
  `X A L1 Start Up` exactly those shapes are lit, X **top** and A **right**.
  D-pad, L3/R3 rings and both trigger bars matched the reported values (e.g.
  `L2 0.57` -> bar fill 0.57 of its height), and the control sizes were measured
  on the 1920x715 px view: sticks 250 px (111 dp) diameter, face buttons
  82 px (36 dp), D-pad 186 px across, Start/Select 238x61 px - roughly double
  the first version.
* `getprop ashfall.ui.led_override` while the tester was open:
  `871111:831010`, later `8B1111:8B1111` (per-side activity), and empty after
  closing the tester with the on-screen Close button.  Closing also logged
  `GRAB OFF` on the mock.
* Start+Select exit: the mock's hold hook reported both buttons; the tester
  finished after 2 s (progress bar), logged `GRAB OFF`, and the LED override was
  cleared.
* Calibration: with Start no longer on a timer, step 1 stayed on screen for
  minutes with growing sample counts; a quick Start in step 2 produced
  "The sticks did not move far enough. ... Try again", a full roll passed,
  step 3 validated the trigger travel, and step 4 showed the 16 values with
  Save/Cancel.
* Light and dark: the controls, labels and accent were resolved through the
  theme (window background `#FAFAFA` vs `#000000`, outlines and fills visible in
  both).  Pure black uses the same attributes.

Not verified here: the RP5 hardware itself (owned by the native worker, and the
device is serialised between workers).  The device checks to repeat are the real
Start+Select chord, the LED override on the real rings (`ashfall-hwd` is the
writer), and a full-circle calibration with the sticks at rest.

## 8. Cross-worker contract notes (native #82)

* The tester depends on the documented 16-bit `RAW` mask (bit order unchanged):
  `15 B, 14 A, 13 Y, 12 X, 11 right, 10 left, 9 down, 8 up, 7 back, 6 home,
  5 R3, 4 L3, 3 start, 2 select, 1 R1, 0 L1`.  If the mask changes, this page and
  `ControllerTesterView.BTN_*` must change with it.
* `GRAB ON`/`GRAB OFF` must be accepted on the same connection that asked for
  `RAW ON`, and `GRAB` must end on disconnect (contract §6.2).  An `ERR` reply
  is shown to the user as "the input daemon refused GRAB".
* `ashfall.ui.led_override` is written at <= 29 Hz as `RRGGBB:RRGGBB` and cleared
  with an empty value; the daemon must treat empty as "normal lighting".
* Triggers are displayed from the raw counts with `(max - raw) / (max - min)`,
  so a 50 % pull must be reported as 50 % in the mask's analog values, not
  snapped.
