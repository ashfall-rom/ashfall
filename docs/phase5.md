# Phase 5 contract: AshFall hardware tools (fan, lighting, controller)

Status: contract, 2026-11-13. Decisions taken with the user:

- **One root-level Settings entry** (working title "Handheld", subtitle "Fan, lighting,
  controller") that opens three native pages: **Fan**, **Lighting**, **Controller**.
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
  safety), Quick Settings tiles (fan mode, lighting on/off), stick deadzone settings.
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
| `persist.ashfall.fan.mode` | `off`, `quiet`, `balanced`, `max`, `manual`, `smart` | `smart` |
| `persist.ashfall.fan.manual_pct` | 0..100 (used by `manual`) | `50` |
| `persist.ashfall.fan.curve` | 2..8 `tempC:pct` pairs, ascending temp, e.g. `45:0,55:30,65:55,75:80,82:100` | that example |
| `persist.ashfall.fan.safety_c` | 70..95 | `85` |

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
| `persist.ashfall.led.mode` | `off`, `static`, `breathing`, `rainbow` | `static` |
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
  meta-data (title "Handheld", summary "Fan, lighting, controller", icon in the Settings
  icon style). No launcher icon.
- **Fan page:** mode selector, manual slider, live temperature / output / RPM / safety
  state, Smart curve editor (drag points, touch + D-pad usable), safety threshold.
- **Lighting page:** on/off main switch, mode, colour pickers (left/right + sync),
  brightness, speed, screen-off behaviour; changes are visible live on the device.
- **Controller page:** gamepad tester (drawn RP5 outline with live buttons, stick dots,
  deadzone circles, trigger bars, both calibrated input via `InputDevice`/`MotionEvent`
  and raw values via `RAW ON`), calibration wizard (release sticks -> rotate both sticks ->
  press triggers fully -> review -> save via `SET CAL`; cancel leaves everything as is),
  "Reset to factory calibration", deadzone sliders.
- **Quick Settings tiles:** "Fan" (tap cycles quiet -> balanced -> max -> smart, label shows
  the mode, long-press opens the Fan page) and "Lighting" (toggle off <-> last mode).
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
