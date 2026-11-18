# Phase 5 contract: AshFall hardware tools (fan, lighting, controller)

Status: contract, 2026-11-13. Decisions taken with the user:

- **One root-level Settings entry** (working title "Handheld", Phase-5 subtitle
  "Fan, lighting, controller"; the shipped app has six pages and the summary
  "Fan, lighting, display, controller, power, memory", §3) that opened three
  native pages in this phase: **Fan**, **Lighting**, **Controller**.
  No separate-looking control app: pages use SettingsLib / the Settings theme, so they look
  like they were always part of the OS.
- Delivered as a built-in module **AshFall Parts** (`org.ashfall.parts`) whose root
  entry is *injected* into the Settings homepage (no app-drawer icon). Settings itself is
  not patched.
- Hardware is driven by **daemons**, not by the UI. The UI only writes `persist.ashfall.*`
  properties / talks to a socket; the daemons apply settings at boot and at runtime.
- Calibration: the factory file `/mnt/vendor/persist/retrostation/joys` is **never
  written**. User calibration lives in `/data/misc/ashfall/joys` with "Reset to factory".
- Extras in scope: better Smart fan (CPU/GPU sensors, editable curve, hysteresis, overheat
  safety), stick deadzone settings, and two Quick Settings tiles that open **QS dialogs**
  (like the Internet/Bluetooth tiles), see §3:
  - **Fan tile:** pick the active **fan preset**. Presets are named fan curves the user
    creates/edits on the Fan page.
  - **Lighting tile:** choose left / right / both sticks, custom colour, brightness, effect.
- Out of scope (Phase 6): battery/charging LED indicators, overclock/performance profiles.

Base firmware: pre-release stock `.31` (5.15 GKI). Hardware facts:
`docs/stock-analysis-189.md` §2, `docs/stock/fan-control.txt`, `docs/stock/mcu-fan-rgb.txt`,
`docs/rp5/input.md`.

## 1. Components and ownership

| Component | Path (owned by) | Issue |
|---|---|---|
| `ashfall-hwd` native daemon: fan + lighting | `aosp/vendor/ashfall/rp5/hw/**`, `aosp/vendor/ashfall/rp5/hw.mk` (+ include line in `rp5/rp5.mk`) | native worker |
| `ashfall-rp5-input` extensions: user calibration, deadzones, control socket | `aosp/vendor/ashfall/rp5/input/**` | native worker |
| SELinux for both daemons, props, socket, data dir | `aosp/vendor/ashfall/sepolicy/**` (+ wiring line in `ashfall_common.mk`), `patches/system/sepolicy/**` | native worker |
| AshFall Parts app (Settings pages, tester, calibration wizard, QS tiles) | `aosp/vendor/ashfall/apps/AshFallParts/**` (+ `PRODUCT_PACKAGES` line in `apps.mk`) | app worker |
| Docs | native: `docs/rp5/hw.md`, `docs/rp5/input.md`; app: `docs/parts.md` | |

Only the native worker uses the RP5 (when the orchestrator hands it over). The app worker
uses the emulator (1920x1080 landscape, 360 dpi) and mocks the daemon side with `setprop`.

## 2. Properties (the UI <-> daemon interface)

All persistent settings are `persist.ashfall.*` (writable by `system_app` only, readable by
the daemons and `system_app`). Status values are `ashfall.status.*` (written by
`ashfall-hwd` only, readable by `system_app`). Daemons must apply a changed setting within
1 s (property wait, not busy polling) and must treat missing/invalid values as the default.

### 2.1 Fan

| Property | Values | Default |
|---|---|---|
| `persist.ashfall.fan.mode` | `off`, `quiet`, `balanced`, `max`, `manual`, `smart` (`smart` = follow `curve`) | `smart` |
| `persist.ashfall.fan.manual_pct` | 0..100 (used by `manual`) | `50` |
| `persist.ashfall.fan.curve` | 2..8 `tempC:pct` pairs, ascending temp, e.g. `45:0,55:30,65:55,75:80,82:100` | that example |
| `persist.ashfall.fan.safety_c` | 70..95 | `85` |

- **Presets live in the app, not in properties** (property values are limited to 92 bytes).
  A preset = name + mode (+ `manual_pct` or curve). Activating a preset writes `mode`,
  `manual_pct` and/or `curve` above; the daemon only ever sees the active values.
  `persist.ashfall.fan.preset` (<= 32 chars) records the active preset id for the UI/tile.
- Sensor: the hottest of the CPU/GPU tsens zones, **selected by zone `type` name**
  (`cpu-*`, `cpuss-*`, `gpuss-*`), never by zone index. (Stock "Smart" reads
  `thermal_zone0` = `pm8150_tz`, a PMIC sensor; do not copy that.)
- Smart: linear interpolation on the curve, sampled every 2 s, smoothed (moving average or
  EMA), hysteresis of ~3 °C on the way down, minimum spin-up kick if the fan needs it.
- **Safety override in every mode, including `off`:** at/above `safety_c` the fan goes to
  100 % until the temperature is 10 °C below `safety_c`.
- Percent -> hardware: `/sys/class/gpio5_pwm2/{state,duty,speed}` (period 50,000 ns).
  `pct` maps onto the *measured useful duty range* of this fan (measure on the device:
  minimum duty that spins reliably .. duty above which RPM no longer rises). Document the
  measurement in `docs/rp5/hw.md`. `off` = `state=0`.
- Presets: `quiet`, `balanced`, `max` are fixed percentages chosen from the measurement
  (roughly: stock Quiet 5,000 ns, Sport 25,000 ns; max = top of useful range).

| Status property | Meaning |
|---|---|
| `ashfall.status.fan.temp` | hottest CPU/GPU temp, tenths of °C (e.g. `563`) |
| `ashfall.status.fan.pct` | current fan output 0..100 |
| `ashfall.status.fan.rpm` | RPM if the `speed`/tach node provides it, else `-1` |
| `ashfall.status.fan.safety` | `1` while the safety override is active |

Status is updated at most every 2 s, and only when a value changed.

### 2.2 Lighting (stick RGB rings)

| Property | Values | Default |
|---|---|---|
| `persist.ashfall.led.mode` | `off`, `static`, `breathing`, `rainbow`, `duo` (smooth fade between the left and right colour on both sticks) | `static` |
| `persist.ashfall.led.color_left` | `RRGGBB` | `FF2020` (AshFall red) |
| `persist.ashfall.led.color_right` | `RRGGBB` | `FF2020` |
| `persist.ashfall.led.sync` | `1` = right uses the left colour | `1` |
| `persist.ashfall.led.brightness` | 0..100 | `60` |
| `persist.ashfall.led.speed` | 1..10 (breathing/rainbow) | `5` |
| `persist.ashfall.led.screen_off` | `off` (LEDs off while the screen is off), `keep` | `off` |

Hardware on `.31`: `/sys/class/sn3112l/led/{enable,brightness,breath}`,
`/sys/class/sn3112r/led/...`; `brightness` takes `"<mode>-<r>:<g>:<b>"` (mode 1 = static).
Also present: `/sys/class/leds/{red,green,blue}` (pm8150l) - the native worker must find out
on the device what each node physically lights and document it. Use the hardware `breath`
mode if it works well, otherwise animate in the daemon (<= 30 fps, no wakeups while the
screen is off).

### 2.3 Controller

| Property | Values | Default |
|---|---|---|
| `persist.ashfall.pad.deadzone_inner` | 0..30 (% of stick travel) | `5` |
| `persist.ashfall.pad.deadzone_outer` | 0..20 (% cut at the edge, values above map to full) | `3` |
| `persist.ashfall.pad.trigger_deadzone` | 0..30 (% of trigger travel) | `3` |

Calibration files (format = stock `joys`: `key=float` lines, the 16 keys
`{left,right}_{x,y}_{min,center,max}`, `{l2,r2}_{min,max}`):

- `/data/misc/ashfall/joys.factory` - copy of the factory file made by **init** at
  `post-fs-data` (init may read `/mnt/vendor/persist`; coredomain daemons may not).
- `/data/misc/ashfall/joys` - user calibration (written only by `ashfall-rp5-input`).
- Load order: user file -> factory copy -> built-in defaults.

Control socket: `/dev/socket/ashfall_input` (init `socket`, `seqpacket`, 0660 system:system),
one text command per packet, one reply per command (`OK ...` / `ERR <reason>`):

| Command | Reply / effect |
|---|---|
| `GET CAL` | `OK <source> k=v k=v ...` (source = `user`, `factory`, `default`) |
| `SET CAL k=v ...` | validate all 16 keys (min < center < max, sane ranges), write `/data/misc/ashfall/joys` atomically, apply immediately |
| `RESET CAL` | delete the user file, apply factory/default |
| `RAW ON` / `RAW OFF` | stream raw, uncalibrated samples to this client: `RAW lx ly rx ry l2 r2 buttons_hex`, <= 100 Hz; stops automatically when the client disconnects |

Deadzones are applied in the daemon (so every app and emulator benefits), after
calibration.

## 3. AshFall Parts (`org.ashfall.parts`)

- `android_app`, `platform_apis: true`, `certificate: "platform"`, `privileged: true`,
  `android:sharedUserId="android.uid.system"` (domain `system_app`), SettingsLib
  (collapsing toolbar, main switch, slider/selector widgets) + androidx.preference.
- Root entry injected into the Settings homepage via an activity with
  `com.android.settings.category = com.android.settings.category.ia.homepage`
  meta-data (title "Handheld", summary "Fan, lighting, display, controller, power, memory", icon in the Settings
  icon style). No launcher icon.
- **Fan page:** live temperature / output / RPM / safety state; **preset list**: built-in
  presets (Off, Quiet, Balanced, Max, Smart-default; not deletable) plus user presets
  (add, rename, duplicate, delete); each preset = mode + manual % or a curve edited in a
  curve editor (drag points, touch + D-pad usable, live marker of the current temperature);
  safety threshold. Presets stored in the app (e.g. JSON in device-protected storage so
  they work before unlock); activating one writes the properties in §2.1.
- **Lighting page:** on/off main switch, mode, colour pickers (left/right + sync),
  brightness, speed, screen-off behaviour; changes are visible live on the device.
- **Controller page:** gamepad tester (drawn RP5 outline with live buttons, stick dots,
  deadzone circles, trigger bars, both calibrated input via `InputDevice`/`MotionEvent`
  and raw values via `RAW ON`), calibration wizard (release sticks -> rotate both sticks ->
  press triggers fully -> review -> save via `SET CAL`; cancel leaves everything as is),
  "Reset to factory calibration", deadzone sliders.
- **Quick Settings tiles** (`TileService`, both open a compact QS dialog via
  `showDialog()`, styled like the system Internet/Bluetooth QS dialogs; long-press opens
  the full page):
  - **Fan:** tile label = active preset name, subtitle = temperature; dialog = preset list
    (single choice) + "Edit presets" link. Tile state inactive only for Off.
  - **Lighting:** tap opens the dialog; dialog = main on/off switch, target selector
    (Both / Left / Right), colour picker (presets + custom hue/saturation picker),
    brightness slider, effect selector (static, breathing, rainbow, duo) and speed.
    Changes apply live. Tile state inactive when mode = off.
- Must be fully usable with the gamepad (D-pad focus, A = select, B = back).
- Clean-room: no Retroid code, assets or strings.

## 4. SELinux (owned by the native worker)

Prefer `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` / `SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS` pointing at
`vendor/ashfall/sepolicy/` for new policy; the existing `patches/system/sepolicy/0001`
may stay or be migrated. Needed:

- `ashfall_hwd` domain (init daemon, coredomain): read sysfs thermal; write the fan and LED
  sysfs nodes (label them with a dedicated type via `genfs_contexts` if possible, otherwise
  document what the generic `sysfs` label requires and any neverallow blocking it);
  get `ashfall_prop`, set `ashfall_status_prop`.
- `ashfall_prop` (`persist.ashfall.`): set by `system_app`; read by both daemons.
- `ashfall_status_prop` (`ashfall.status.`): set by `ashfall_hwd`; read by `system_app`.
- `ashfall_data_file` (`/data/misc/ashfall`): created by init, rw by `ashfall_rp5_input`,
  nothing else writes it.
- `system_app` may connect to the `ashfall_input` socket.
- Verify with `adb shell dmesg | grep avc` / `logcat -b events` that there are no denials
  in normal use, on an **enforcing** build.

## 5. Build and test policy

Workers never run `scripts/build.sh`. They finish with `READY TO BUILD: <branch> <sha>`
and a PR; the orchestrator builds both branches together (`integ/phase5`), flashes, and the
user tests on hardware. Standalone compile checks are fine (NDK clang for the daemons;
aapt2/javac/d8 for the app, optionally against SettingsLib/androidx jars copied read-only
from the build host's `out/soong/.intermediates`).

## 6. Follow-ups v2 (user test 2026-11-14) - contract additions

Supersedes the matching parts of §2/§3. Issue numbers in the per-area issues.

### 6.1 Properties (new / changed)

| Property | Type | Writer -> reader | Values / meaning |
|---|---|---|---|
| `persist.ashfall.fan.kick` | `ashfall_prop` | app -> hwd | `1` (default) = spin-up kick enabled |
| `persist.ashfall.fan.kick_pct` | `ashfall_prop` | app -> hwd | 10..60, default `30` (kick level; never 100 %) |
| `persist.ashfall.fan.screen_off` | `ashfall_prop` | app -> hwd | `off` (default: fan off while the screen is off) or `run`; on screen-on the fan resumes with a kick if enabled |
| `persist.ashfall.fan.safety_c` | - | - | **removed** (no UI). hwd keeps a fixed, undocumented last-resort rule: 100 % at >= 90 C (also with the screen off), released at 80 C |
| `ashfall.ui.fan_live` | new `ashfall_ui_prop` (non-persist) | app -> hwd | `0`/empty = no fan UI; otherwise an opaque token that changes on every refresh (at least every 500 ms) while a fan UI (page/editor/QS dialog) is visible. Any non-zero value selects the 500 ms status cadence (else 2 s); value changes are the heartbeat that keeps the live preview valid |
| `ashfall.ui.fan_preview_curve` | `ashfall_ui_prop` | app -> hwd | curve string; while non-empty and the active mode is `smart`, hwd uses it instead of `persist.ashfall.fan.curve` (live curve editing). Honoured only while `ashfall.ui.fan_live` has changed within the last **1.5 s**; the app clears it when the editor closes, and hwd drops it on its own after 1.5 s without a heartbeat (force-stop/crash) |
| `persist.ashfall.led.screen_off` | - | - | **removed**; LEDs are always off while the screen is off and are re-applied on screen-on |
| `ashfall.ui.led_override` | `ashfall_ui_prop` | app -> hwd | `` (empty = normal) or `RRGGBB:RRGGBB` (left:right, final colours incl. intensity); used by the gamepad tester (<= 30 Hz). Honoured only while `ashfall.ui.led_override_live` is fresh; a non-empty value is dropped after 1.5 s without a heartbeat. Screen sync (#167) publishes on the same contract with the same >= 500 ms token, and only ever clears its own value |
| `ashfall.ui.led_override_live` | new `ashfall_ui_prop` (non-persist) | app -> hwd | opaque token that changes on every refresh (at least every 500 ms) while a publisher owns `ashfall.ui.led_override`; the value has no meaning beyond "different from the previous one". hwd treats the override as expired after **1.5 s** without a change, so a force-stopped app cannot latch the rings. Cleared (with the override) when the publisher leaves |
| `ashfall.ui.led_suspend` | new `ashfall_ui_prop` (non-persist) | app -> app | changing token (`elapsedRealtime()` ms) while the gamepad tester is visible, refreshed every 500 ms; screen sync pauses and clears its own override. The service treats suspend as active only while the token changes within **1.5 s** (#193) and the tester clears the property on normal exit |
| `persist.ashfall.led.screen_sync` | UI-only flag, not read by hwd | app -> app | `0` (default) / `1`: the "Match screen" effect is selected; `led.mode` keeps the last real effect |
| `persist.ashfall.led.screen_rate` | UI-only value, not read by hwd | app -> app | `10` (default) / `15` / `30`: screen capture rate in frames per second |
| `persist.ashfall.led.screen_delay` | UI-only value, not read by hwd | app -> app | `0` (default, Off) / `100` / `250` / `500`: hold a changed left/right average until it has been stable for this many milliseconds before publishing it; `0` publishes every frame |
| `ashfall.perf.throttle_override` | new `ashfall_perf_prop` (non-persist => resets on reboot) | app -> hwd | `1` = disable software clock throttling (kernel thermal mitigation / cooling devices; hardware limits stay) |
| `persist.ashfall.perf.throttle_override_permanent` | `ashfall_perf_prop` | app -> hwd | `1` = apply the override at every boot (hidden "developer" switch, see §6.4) |

**UI override heartbeat (issue #94).**  `ashfall.ui.led_override` and
`ashfall.ui.fan_preview_curve` are owned by app code that a force-stop or crash
can kill without running its `clear()` path, so a non-persistent property alone
cannot guarantee that they are released.  While it publishes one of those
values, the app therefore rewrites a companion liveness property every
**500 ms** with an opaque token that differs from the previous one
(`ashfall.ui.led_override_live` for the tester; `ashfall.ui.fan_live` also
covers the curve preview; the app uses `SystemClock.elapsedRealtime()`).
`ashfall-hwd` honours the value only while the liveness token has changed
within the last **1.5 s** (three missed beats; the daemon samples it on every
loop tick, at most 500 ms apart).  The token has no meaning of its own and is
never parsed: only "different from the previous sample" is a heartbeat, so the
mechanism does not depend on the property service bumping a write serial for
an identical value (issue #180) and `getprop` shows the heartbeat directly.
After the timeout the daemon drops the override and re-applies the configured
lighting / `persist.ashfall.fan.curve`, and a fresh heartbeat plus value is
honoured again immediately.  A fan preview that expired is additionally not
honoured again until the editor publishes a *different* curve:
`ashfall.ui.fan_live` is shared by all fan pages, so a mere heartbeat refresh
from another fan page must not revive a curve left behind by a force-stopped
editor.  (An identical rewrite cannot be told apart without the property
serial, which issue #180 removed.  That is normally invisible: a preview is
only published for the active Smart preset, and both `save()` and preset
activation write the same curve to `persist.ashfall.fan.curve`.  However
`publishPreview()` on resume republishes the stored preset curve without
writing persist, so if the two have drifted or the earlier write failed, the
preview stays ignored until the first edit rewrites both.)  Timing uses the
daemon's own monotonic clock
(`CLOCK_MONOTONIC`, `ah_now_ms()`), never the app's clock, so a wall-clock
change cannot expire a live override or keep a dead one alive.  Empty values
are always honoured as
"no override" without a heartbeat.  The heartbeat is about ownership only: the
`ashfall.ui.*` property type, SELinux label (`ashfall_ui_prop`) and the
`system_app` -> `ashfall_hwd` permission do not change.

Lighting: rainbow base speed = previous speed / 3; breathing uses a gamma-corrected
brightness curve and a smoother update cadence (no visible steps at speed 5).

### 6.2 Input control socket (additions to §2.3)

| Command | Effect |
|---|---|
| `GRAB ON` / `GRAB OFF` | while ON (and only while this client stays connected) the daemon releases all held inputs and injects **nothing** into uinput; all inputs incl. Home, Back, Select, Start, B go only to `RAW` frames. Disconnect = automatic `GRAB OFF`. |

`RAW` frames must contain every button of the RP5 (face, shoulders, L3/R3, Start, Select,
Home, Back, D-pad) with a documented bit layout (docs/rp5/input.md).

### 6.3 App (AshFall Parts)

- Root entry renamed **"AshFall settings"**; looks identical to Settings in every theme
  (light, dark, LineageOS pure-black); all dialogs/QS dialogs follow the system theme and are
  modal (no focus/navigation of windows behind them, also with the gamepad).
- Fan: Manual mode in the UI, kick on/off, fan-off-with-screen-off switch, live curve preview,
  **stress test** in the curve editor (CPU 0-100 %, GPU 0-100 % sliders, roughly that load;
  stops when the editor closes and after max. 10 min), safety threshold removed.
- **Override throttling** (Fan or a "Performance" section): confirmation dialog warning that it
  is dangerous if misused; resets on reboot. A hidden switch deep in the settings (e.g. an
  "Advanced" screen revealed by tapping the version 7 times) makes it permanent; README mentions
  it only vaguely.
- Lighting: live hue preview, no screen-off option, same hue picker in page and QS dialog.
- Controller tester: RP5 silhouette, all buttons via `GRAB ON`, exit by holding Start+Select 2 s
  or an on-screen close button, LED reaction via `ashfall.ui.led_override`, no Select setting;
  calibration pauses after each step until Start is pressed.
- QS dialogs: no stray shadows, Done button not clipped.

### 6.4 Screen colour sync ("Match screen", #167)

Additive to §6.1; the daemon contract is unchanged.  The effect is UI-only:
`persist.ashfall.led.screen_sync=1` with `persist.ashfall.led.mode` still holding
the last real effect, so `ashfall-hwd` needs no new mode and restores the
configured lighting as soon as the override stops.

* **Capture**: `DisplayManager.createVirtualDisplay(...)` with
  `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` into an `ImageReader` (RGBA_8888, 64×36),
  guarded by `CAPTURE_VIDEO_OUTPUT` (platform-signed `system_app`, so no
  MediaProjection consent dialog).  No hidden APIs: the preview Gradle build
  compiles against the public SDK.
* **Rate**: `persist.ashfall.led.screen_rate` = 10 (default) / 15 / 30 Hz.  The
  mirror runs at panel rate, frames are dropped to the configured rate, and only
  changed colours are published (≤ 29 Hz, `docs/rp5/hw.md` §4).
* **Mapping**: left half → left ring, right half → right ring, always (the
  target selector does not change Match screen).  sRGB decode, per-half average
  in linear light, sRGB encode: the plain mean with no chroma weighting or
  value lift, so a white UI gives white rings.  An optional
  `persist.ashfall.led.screen_delay` (0 = Off, default) holds a changed pair
  until it has been stable for 100/250/500 ms before publishing, then
  `led.brightness` (read on every publish, so the slider is live) scales the
  result.  A 100 ms sampler tick advances the gate and re-publishes on a static
  screen that sends no frames (#241); it stops with the sampler.  HDR/wide-gamut
  content is clamped to SDR by the RGBA_8888 readback.  Near-black frames
  (secure/DRM surfaces, panel off) hold the last colour.
* **Override contract**: publishes `ashfall.ui.led_override` and refreshes the
  changing `ashfall.ui.led_override_live` token every 400 ms; `ashfall-hwd`
  ignores the override after 1.5 s without a fresh token (#94/#171).  Publishing
  is paced by a time budget so the selected 10/15/30 Hz rate is honoured while
  never exceeding 29 writes per second.  While the gamepad tester is visible its
  changing `ashfall.ui.led_suspend` token pauses the sampler (checked before
  every write, 1.5 s timeout), and the sampler only ever clears its own override
  value: it stands down while a foreign value is present and never writes over
  it (#192/#204).
* **Lifecycle**: `LedSyncService` (`specialUse` foreground service, "LED screen
  sync" notification with a Stop action) is started only while the effect is
  selected, never at boot; sampling runs only while the panel is on and the
  service stays alive while paused so it resumes on screen-on.  Sampling pauses
  and the override plus heartbeat are cleared on: effect change, lighting off,
  screen off/doze, the gamepad tester taking over (a fresh `ashfall.ui.led_suspend` token),
  the Stop action, service destruction and a capture failure.  A failure also
  switches the effect off, restores the previous effect and shows a short
  message.

## 7. Follow-ups v3 (user request 2026-11-15) - fan graph and output limits

**Phase 5, not phase 6.** Supersedes the matching parts of §2.1 and §3.1: the
Fan page's status read-out becomes a live graph, and two new properties limit
the fan output. Issue references are in the per-area issues.

### 7.1 Output limits (properties, enforced by the daemon)

| Property | Type | Writer -> reader | Values | Default |
|---|---|---|---|---|
| `persist.ashfall.fan.min_pct` | `ashfall_prop` | app -> hwd | 0..100 | `5` |
| `persist.ashfall.fan.max_pct` | `ashfall_prop` | app -> hwd | 0..100 | `100` |

The limits are applied in `ashfall-hwd` (the UI never writes the fan), to the
output that the active mode, the Smart curve and the screen-off hold resolved,
immediately before the output is applied:

- `pct == 0` stays `0` (mode off, or the screen-off hold is active).
- `0 < pct < min_pct` -> `0`: a target below the low limit stops the fan
  **completely** instead of running it at a speed the user called useless.
- `min_pct <= pct <= max_pct` -> unchanged.
- `pct > max_pct` -> `max_pct`: the fan never runs above the high limit.
- `min_pct = 0` disables the low cut-off, `max_pct = 100` the cap.

**Exempt from both limits:** the fixed 90 °C last-resort rule (§6.1) and the
no-sensor fail-safe (`FAN_PRESET_BALANCED_PCT`). Thermal protection must never
be cap-able; this is the only documented exception and it is not a setting.

Missing or invalid values (unparsable, outside 0..100) fall back to the
defaults, like every other `persist.ashfall.*` value. If `min_pct > max_pct`
the pair is invalid: the daemon applies both defaults and logs it once.
`ashfall.status.fan.pct` keeps reporting the output that was actually applied,
so the page always shows what the limits did.

Consequence of the shipped defaults: the low limit (`5`) is above the built-in
Quiet preset (`2 %`), so **Quiet stops the fan completely** until the low limit
is lowered. That is the intended reading of "below the low limit -> shut off",
and the page states it on the slider row.

### 7.2 Fan page: live graph replaces the status read-out

The three-column **Status** block (`res/layout/preference_status.xml`:
Temperature / Output / Fan speed plus the output gauge) is removed. The Status
category holds one **non-selectable graph row** instead:

- A scrolling line chart of the **last 60 s**: the page already reads the status
  every 500 ms while visible, and appends one sample per tick (120 samples, in
  memory in the app - no new property, no daemon-side history, resets when the
  page is reopened).
- Three series in one plot area:
  - **temperature** (`ashfall.status.fan.temp`, tenths °C) on the **left
    axis**, fixed scale **20..100 °C** with grid lines/labels at 40/60/80;
  - **fan output** (`ashfall.status.fan.pct`) on the **right axis**, 0..100 %
    with labels at 0/50/100;
  - **fan speed** (`ashfall.status.fan.rpm`) drawn against the right axis with
    a fixed **16 000 rpm** reference (the measured maximum is ~15 600 rpm), no
    axis labels of its own.
- A legend line above the plot carries the colour swatch and the **current
  exact value** of each series (`58 °C`, `75 %`, `9 800 rpm`), so the numbers
  the old read-out showed stay readable; `—` for a value that is unknown, and
  rpm is the documented `-1` when the tach node is unavailable.
- Missing samples (daemon not yet published, `-1`) are **gaps**, never zeros.
- The row is not focusable and not selectable (the D-pad skips it), is drawn
  with Canvas primitives only (no new dependency, no vector/RRO), follows the
  Settings theme in day, night and pure-black mode, is ~160 dp tall and spans
  the row width.
- While the daemon does not respond (no `ashfall.status.fan.*` values) the row
  shows the existing "The fan daemon is not responding." message instead of the
  plot. The last-resort warning line (`fan_status_safety`) stays below the
  graph.

### 7.3 Fan page: limit sliders

A new category **Fan speed limits** sits directly below **Manual output**:

| Row | Property | Range | Default | Summary |
|---|---|---|---|---|
| Minimum output | `persist.ashfall.fan.min_pct` | 0..100 | 5 | below it the fan stops completely; `0` = no low cut-off |
| Maximum output | `persist.ashfall.fan.max_pct` | 0..100 | 100 | the fan never runs above it; `100` = no cap |

Both are ordinary `ui/SliderPreference` rows (D-pad left/right, touch), write
their property on every change, and keep `min_pct <= max_pct` by moving the
other slider along with them. The page never clamps the output itself: the
daemon is the single enforcement point, and the graph/legend show the result.
