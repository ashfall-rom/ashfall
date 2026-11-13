# RP5 hardware: fan and stick lighting (`ashfall-hwd`)

Phase 5, issue #60. Contract: [`docs/phase5.md`](../phase5.md) §1, §2.1, §2.2, §4.
Hardware evidence: [`docs/stock/fan-control.txt`](../stock/fan-control.txt),
[`docs/stock-189/fan-rgb-189.txt`](../stock-189/fan-rgb-189.txt),
[`docs/stock-analysis-189.md`](../stock-analysis-189.md) §2.

`ashfall-hwd` is a small system daemon (C, `liblog` only) that owns the fan and
the stick LED sysfs nodes.  The **AshFall Parts** app never touches hardware:
it writes `persist.ashfall.*` properties and reads `ashfall.status.*`; the
daemon applies settings at boot and at runtime.

| piece | path |
|---|---|
| daemon sources | `aosp/vendor/ashfall/rp5/hw/` |
| init service | `aosp/vendor/ashfall/rp5/hw/ashfall-hwd.rc` (`class core`, `user system`) |
| product wiring | `aosp/vendor/ashfall/rp5/hw.mk` -> `rp5/rp5.mk` |
| SELinux | `aosp/vendor/ashfall/sepolicy/` (`ashfall_hwd.te`, `genfs_contexts`, ...) |
| host tests | `make -C aosp/vendor/ashfall/rp5/hw/tests check` |

## 1. Hardware nodes

| node | access | meaning |
|---|---|---|
| `/sys/class/gpio5_pwm2/state` | write 0/1 | fan enable (`0` = off) |
| `/sys/class/gpio5_pwm2/duty` | write ns | PWM high time; period 50 000 ns (20 kHz) |
| `/sys/class/gpio5_pwm2/speed` | read | tach/FG feedback (see §2) |
| `/sys/class/sn3112l/led/enable` | write 0/1 | left stick ring enable |
| `/sys/class/sn3112l/led/brightness` | write `"<mode>-<r>:<g>:<b>"` | left ring colour (mode `1` = static) |
| `/sys/class/sn3112r/led/{enable,brightness}` | same | right stick ring |
| `/sys/class/thermal/thermal_zone*/{type,temp}` | read | SoC temperatures (millidegrees C) |

The vendor init script already `chmod 0666`s the fan and sn3112 attributes
(`init.qcom.rc`), so no additional boot-time permission fix is needed.

`.189` uses `htr3212l/r` LEDs instead of `sn3112l/r` and a 4.19 kernel; AshFall
stays on `.31`, so only the `sn3112` nodes are used
([`docs/stock-189/fan-rgb-189.txt`](../stock-189/fan-rgb-189.txt) §"RGB").  The
LED backend enumerates the nodes at start and logs the result.

## 2. Fan measurement (duty -> RPM range, measured 2026-11-13)

The fan is a 4-wire PWM fan: `duty` is the PWM high time in ns and `speed` is
the fan's tach/FG feedback in **RPM** (value read directly, no scaling; `0`
when stopped, noisy by +-300 at the low end).  Percentages in
`persist.ashfall.fan.*` map **linearly onto the measured useful range**
(`FAN_DUTY_MIN_NS .. FAN_DUTY_MAX_NS` in `rp5/hw/fan.h`); `0 %` means
`state=0`.

The sweep was run on the RP5 (build `20261113-213314`, SoC at ~35 C) from a
stalled state, 5 s per step:

| duty (ns) | duty (%) | `speed` (RPM) | note |
|---:|---:|---:|---|
| 2 000 | 4 | 300 -> 0 | stalls |
| 3 000 | 6 | 0 | stalls |
| 3 700 | 7.4 | 0 | does not start from rest |
| 3 900 | 7.8 | 1 800 | starts (once) |
| 4 000 | 8 | 1 500-1 800 | **starts 3/3 from rest**, keeps running |
| 4 920 | 9.8 | 1 800 | built-in quiet preset |
| 5 000 | 10 | 2 100 | stock Quiet |
| 6 000 | 12 | 2 400 | |
| 8 000 | 16 | 3 300 | |
| 12 000 | 24 | 4 500 | |
| 16 000 | 32 | 6 000 | |
| 20 000 | 40 | 7 200 | |
| 25 000 | 50 | 8 100-8 700 | stock Sport / built-in balanced preset (25 160 ns) |
| 30 000 | 60 | 10 200 | |
| 35 000 | 70 | 11 400 | |
| 40 000 | 80 | 12 600 | |
| 45 000 | 90 | 13 800 | |
| 50 000 | 100 | 14 400-15 600 | PWM period ceiling (20 kHz), stable over 20 s |

Results:

* **Minimum reliable duty = 4 000 ns** (3/3 starts from rest at ~35 C;
  3 700 ns and below stall, 3 500 ns stalls even while running).
* **Maximum useful duty = 50 000 ns**, i.e. the whole PWM period: the tach
  still rises up to 100 % (there is no plateau below the period).  This is
  higher than stock's High mode (25 000 ns, `.189` 35 000 ns), which simply
  never used the top of the fan's range.
* The tach responds within ~1 s, so the daemon's 2 s sampling is sufficient.
* A 4 000 ns start was reliable, so a spin-up kick is not required; the daemon
  still does a short 500 ms one (`FAN_MIN_KICK_MS`) as insurance for a cold
  unit.  Set the constant to 0 to disable it.

Final constants and presets:

| constant | value | meaning |
|---|---|---|
| `FAN_DUTY_MIN_NS` | 4 000 | 1 % output, ~1 800 RPM |
| `FAN_DUTY_MAX_NS` | 50 000 | 100 % output, ~15 000 RPM |
| `FAN_PRESET_QUIET_PCT` | 2 | 4 920 ns / ~1 800 RPM (stock Quiet 5 000 ns) |
| `FAN_PRESET_BALANCED_PCT` | 46 | 25 160 ns / ~8 100 RPM (stock Sport 25 000 ns) |
| `FAN_PRESET_MAX_PCT` | 100 | 50 000 ns / ~15 000 RPM |

## 3. Fan modes

`persist.ashfall.fan.mode`:

| mode | output |
|---|---|
| `off` | `state=0` (safety override can still spin the fan) |
| `quiet` / `balanced` / `max` | fixed preset percentage (see above) |
| `manual` | `persist.ashfall.fan.manual_pct` (0..100) |
| `smart` | linear interpolation on `persist.ashfall.fan.curve` |

* `persist.ashfall.fan.curve`: 2..8 `tempC:pct` pairs, ascending temperature,
  default `45:0,55:30,65:55,75:80,82:100`.  Invalid values are ignored (the
  daemon keeps the default).
* Sensor: the **hottest CPU/GPU thermal zone selected by `type`**, i.e. names
  starting with `cpu`, `cpuss`, `gpu` or `gpuss` (`cpu0-usr`, `cpuss-0-usr`,
  `gpuss-0-usr`, ...).  Stock Smart read `thermal_zone0` = `pm8150_tz`, a PMIC
  sensor, which is not SoC temperature and is deliberately not copied.
* Smart mode samples every 2 s, smooths with an EMA (`alpha = 0.35`) and applies
  a 3 °C hysteresis on the way down (the target is only lowered once the
  smoothed temperature is a full 3 °C below the last decision point).
* Spin-up kick: a 0 -> nonzero transition runs the fan at 100 % for 1.5 s
  (`FAN_MIN_KICK_MS`) before dropping to the requested speed, because the
  motor does not start reliably at a low duty.
* **Safety override, every mode including `off`:** at or above
  `persist.ashfall.fan.safety_c` (70..95 °C, default 85) the fan runs at 100 %
  until the temperature is 10 °C below the threshold.

`ashfall.status.*` (written at most every 2 s, only on change):

| property | meaning |
|---|---|
| `ashfall.status.fan.temp` | hottest CPU/GPU temperature, tenths of °C (`563` = 56.3 °C) |
| `ashfall.status.fan.pct` | current fan output 0..100 (100 during a kick, `-1` if the sysfs write failed) |
| `ashfall.status.fan.rpm` | `speed` node value, `-1` when unavailable |
| `ashfall.status.fan.safety` | `1` while the safety override is active |

`persist.ashfall.fan.preset` is UI-only (the active preset id, <= 32 chars); the
daemon ignores it and only ever reads `mode`, `manual_pct` and `curve`.

## 4. Lighting

`persist.ashfall.led.*` (colour `RRGGBB`, default AshFall red `FF2020`):

| mode | behaviour |
|---|---|
| `off` | rings disabled |
| `static` | fixed left/right colour |
| `breathing` | smooth brightness pulse, period `4000 / speed` ms |
| `rainbow` | hue rotation, full turn every `6000 / speed` ms; with `sync=0` the right ring is offset by 120° |
| `duo` | both sticks fade smoothly between `color_left` and `color_right` (`5000 / speed` ms per direction) |

`sync=1` makes the right side use the left colour.  `brightness` 0..100 scales
the channels; `speed` 1..10.  `screen_off=off` disables the rings while the
screen is off, `keep` keeps them running.  Screen state comes from the
framework's `debug.tracing.screen_state` (Display Power Controller; `1` = off);
no wakeups happen for the LEDs while they are off.

Animation runs at 30 fps in the daemon.  The hardware `breath` attribute is
**not usable** by the daemon: on the device it is `--w--w----  root root`
(write-only for root), while the daemon runs as `system`.  Software animation
also covers `duo` and `rainbow`, which the hardware does not offer.

### On-device findings (2026-11-13)

* `enable` and `brightness` are **write-only** (`-rw-rw-rw-`, reading them
  returns `EIO`), so the daemon detects them by writability and cannot read
  back what it wrote.  `brightness` accepts `"<mode>-<r>:<g>:<b>"` with
  **decimal** channels (`1-255:0:0`, not hex); the daemon writes decimal.
  `mode 1` = static colour, `mode 0` = off.
* `breath` is root-only (see above) and write-only.
* Resolved paths (also used by `genfs_contexts`):
  `readlink -f /sys/class/sn3112l/led` -> `/sys/devices/virtual/sn3112l/led`,
  same for `sn3112r`.
* `/sys/class/leds/{red,green,blue}` (pm8150l) are **not** the stick rings and
  are **not usable** from this daemon: `private/coredomain.te` has
  `neverallow { coredomain -init -ueventd -vold ... } sysfs_leds:file *`, and
  `/sys/class/leds` is genfs-labelled `sysfs_leds`.  The daemon's `leds`
  fallback backend was removed for this reason.

### Which node lights what (needs a human eye)

The LED nodes are write-only, so this cannot be verified over adb.  It is a
**user test step**: with the daemon running and the screen on,

```sh
adb shell setprop persist.ashfall.led.mode static
adb shell setprop persist.ashfall.led.sync 0
adb shell setprop persist.ashfall.led.color_left FF0000    # left should be red
adb shell setprop persist.ashfall.led.color_right 0000FF   # right should be blue
```

and confirm left/right mapping, then `breathing`/`rainbow`/`duo` and
`speed`.  `brightness 0` must be dark, `brightness 100` clearly brighter.  The
expected result (from the stock settings: `left_joystick_light_enabled`,
`right_joystick_light_enabled`, `joystick_led_light_picker_color`) is that
`sn3112l` is the left ring and `sn3112r` the right one.

## 5. SELinux

Both daemons are `coredomain`s (required: a non-coredomain may only use
`vendor_file_type` or `init_exec` as an entrypoint).  A coredomain may **not**
open generic sysfs files:

```
private/coredomain.te:
neverallow { coredomain -apexd -init -ueventd -vold } sysfs:file no_rw_file_perms;
```

so the fan and LED attributes are labelled with dedicated types in
`vendor/ashfall/sepolicy/genfs_contexts`.  The paths were verified on the
device (build `20261113-213314`):

| type | path | why |
|---|---|---|
| `ashfall_fan_sysfs` | `/class/gpio5_pwm2` | the driver registers class-level attributes, so `state`/`duty`/`speed` live directly in the class directory (it is a real directory, not a device symlink); `avc` confirms `path="/sys/class/gpio5_pwm2/state"` |
| `ashfall_led_sysfs` | `/devices/virtual/sn3112l`, `/devices/virtual/sn3112r` | `/sys/class/sn3112{l,r}/led` are symlinks into these device dirs; `avc` confirms `path="/sys/devices/virtual/sn3112l/led/brightness"` |

On the test build the nodes were still labelled `u:object_r:sysfs:s0` (and the
`shell` domain that ran the test binary is a permissive domain in userdebug),
so the enforcing behaviour of these `genfscon` entries has to be confirmed on
the first integration build: after flashing, `ls -lZ` must show
`ashfall_fan_sysfs` / `ashfall_led_sysfs` and `dmesg | grep avc` must be clean
while fan and LEDs are driven.

Thermal zones need no new type: the stock vendor policy (still in place under
the GSI) labels `/devices/virtual/thermal` and `/class/thermal` as
`sysfs_thermal`, which `ashfall_hwd.te` grants read access to.

Properties: `ashfall_prop` (`persist.ashfall.`, set by `system_app`, read by
both daemons) and `ashfall_status_prop` (`ashfall.status.`, set by
`ashfall_hwd`, read by `system_app`).  New policy is wired through
`SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` in `vendor/ashfall/ashfall_common.mk`: a GSI
replaces `system`/`system_ext` but not the stock `vendor`, so
`BOARD_SEPOLICY_DIRS` (vendor policy) would never be flashed.

## 6. Test checklist

Run on a build that contains `ashfall-hwd` (the daemon needs `system` uid for
`ashfall.status.*`; a manually pushed binary can only be run as `shell`, which
is enough for the sysfs side but cannot set properties).

1. `adb shell getprop init.svc.ashfall-hwd` -> `running`;
   `adb shell logcat -s ashfall-hwd` shows the thermal zone list and the LED
   backend (`sn3112` left/right yes).
2. Fan: `adb shell setprop persist.ashfall.fan.mode max` spins up within 1 s;
   `quiet` is noticeably slower; `off` stops it.  `ashfall.status.fan.pct`
   follows, `ashfall.status.fan.temp` tracks `cpu0-usr`.
3. Smart: heat the device (game/benchmark); output rises along the curve and
   drops only ~3 °C later than it rose.
4. Safety: set `persist.ashfall.fan.safety_c 71`, heat above 71 °C ->
   100 % and `ashfall.status.fan.safety=1`; it releases 10 °C lower.
5. Lighting: `static` with `color_left=FF0000` lights the left ring red,
   `color_right=0000FF` (with `sync=0`) the right one blue; `breathing`,
   `rainbow` and `duo` animate at a sane speed; `brightness 0` is dark;
   `screen_off=off` turns the rings off with the screen and **no** animation
   wakeups, `keep` keeps them on.
6. `adb shell dmesg | grep avc` (and `logcat -b events | grep avc`) is clean
   after exercising all of the above on an **enforcing** build.

## 7. On-device verification (2026-11-13, build 20261113-213314)

The daemons are not in that image; the static NDK binaries were pushed to
`/data/local/tmp` and run as `shell` (adb root is disabled on that build:
`ro.debuggable=0`).  `shell` is a permissive domain there, so sysfs access
works and `avc` records the denials the real domain will need.

Verified:

* `ashfall-hwd` runs, enumerates the 16 CPU/GPU tsens zones (`cpu-*-*`,
  `cpuss-*`, `gpuss-*`), finds the `sn3112` LED backend (write-only detection)
  and applies the default settings: at ~34 C the smart curve gives 0 % and the
  fan is stopped (`state=0 duty=0`), the LEDs get a static `1-153:19:19`
  write for the default `FF2020` at 60 % (no write errors).
* `ashfall-rp5-input` runs, loads the built-in defaults (no
  `/data/misc/ashfall` in that image), creates the `AshFall RP5 Gamepad`
  uinput device, opens `/dev/ttyHS1`, and the control socket (fed the fd via
  `ANDROID_SOCKET_ashfall_input`, the same contract init uses) answers
  `GET CAL` -> `OK default <16 keys>`, `GET/SET/RESET CAL` errors as
  specified (`ERR missing key 'left_x_min'`, `ERR unknown command`) and
  `RAW ON` -> `OK`.  No raw frames were delivered because the image's own
  `ashfall-rp5-input` service holds and drains the UART (it could not be
  stopped without root); raw streaming is covered by the host tests.
* Fan/LED sweep data: §2.  LED write format: §4.  genfs paths: §5.

Still needs the integration build (and a human):

* property-driven modes (`setprop` is denied to `shell`: the property area has
  no `ashfall_prop` type in that image, so even a permissive shell cannot set
  `persist.ashfall.*`), and therefore the safety override (needs > 70 C).
* `ashfall.status.*` publishing (needs `system` uid).
* enforcing-mode `avc` check with the real policy.
* which stick ring lights up (write-only nodes, see §4).
