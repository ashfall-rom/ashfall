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
* **Only a few zones are read per tick** (measured constraint): every `temp`
  read makes the stock kona tsens driver print at `KERN_FATAL`
  (`temp_show ...`), 2 688 lines shortly after boot with all 16 zones at 2 s.
  The daemon therefore picks the `THERMAL_MAX_WATCH` (3) hottest zones with a
  full sweep at start and every 5 min (`THERMAL_SWEEP_MS`), and reads only
  those between sweeps (3 reads / 2 s instead of 16, ~5x less log spam).  The
  trade-off: a sudden hotspot in a *previously cool* zone is seen at the next
  sweep (up to 5 min later).  Because the watched set is the hottest three,
  the hottest of them is still the fan's input; the safety threshold has
  10 °C of headroom to the hardware throttle point.
* **The tach (`speed`) is never read while `state=0`.**  `speed_show()` in the
  stock `gpio5_pwm.ko` calls `enable_irq()`; with the fan disabled the kernel
  prints `Unbalanced enable for IRQ ...` (seen on the device).  A stopped fan
  is reported as `0` RPM without opening the node at all.
* `off` writes **`state=0` first and always attempts it**, then `duty=0`; the
  two attributes are independent, so a failed duty write can no longer leave
  the fan spinning (`out_pct = -1` makes the next tick retry).
* **No readable sensor fails safe, never off:** if no zone is readable the fan
  runs the balanced preset (46 %, ~8 100 RPM), `safety` latches, one warning is
  logged, and the zones are re-scanned every 2 s until data returns.
* Smart mode samples every 2 s, smooths with an EMA (`alpha = 0.35`) and applies
  a 3 °C hysteresis on the way down (the target is only lowered once the
  smoothed temperature is a full 3 °C below the last decision point).
* Spin-up kick: a 0 -> nonzero transition runs the fan at 100 % for 500 ms
  (`FAN_MIN_KICK_MS`) before dropping to the requested speed.  The measured
  minimum duty (4 000 ns) starts reliably on its own, so this is insurance for
  a cold unit; set the constant to 0 to disable it.
* **Safety override, every mode including `off`:** at or above
  `persist.ashfall.fan.safety_c` (70..95 °C, default 85) the fan runs at 100 %
  until the temperature is 10 °C below the threshold.

`ashfall.status.*` (written at most every 2 s, only on change):

| property | meaning |
|---|---|
| `ashfall.status.fan.temp` | hottest CPU/GPU temperature, tenths of °C (`563` = 56.3 °C) |
| `ashfall.status.fan.pct` | current fan output 0..100 (100 during a kick, `-1` if the sysfs write failed) |
| `ashfall.status.fan.rpm` | `speed` node value, `0` while the fan is off (never read then), `-1` when unavailable |
| `ashfall.status.fan.safety` | `1` while the safety override is active |

`persist.ashfall.fan.preset` is UI-only (the active preset id, <= 32 chars); the
daemon ignores it and only ever reads `mode`, `manual_pct` and `curve`.

Numbers are invalid -> default, not clamped: `manual_pct` outside 0..100,
`safety_c` outside 70..95, `led.brightness` outside 0..100 and `led.speed`
outside 1..10 use the documented default (50, 85, 60, 5), and each distinct bad
value is logged once.

> **Tuning note (2026-11-14):** the RP5 idles at **~66 °C** (hottest CPU zone,
  screen on, no load), where the contract's default curve
  (`45:0,55:30,65:55,75:80,82:100`) asks for ~57 %.  On this handheld that is
  clearly audible while idle, and the user set `fan.mode=off` for silence.
  A device-tuned default (e.g. `55:0,65:20,75:55,82:80,88:100`, or presets in
  the Parts app) is worth considering - that is a contract/UI decision, so the
  daemon keeps the documented default.

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

### Boot ordering and write retries (issue #69)

Presence is detected **by existence only** (`access(F_OK)`), never by a read
(the attributes are write-only) and never by a DAC check: `ashfall-hwd` is
`class core`, and vendor init only `chmod 0666`s the nodes in its own
`on boot` action, which is parsed *after* `class_start core`.  A writability
test at startup would therefore permanently disable the `enable` write.

Any failed write leaves `led.applied = false` and the daemon retries the whole
frame **every 500 ms** (`LED_RETRY_MS`) until it succeeds, including with
`mode=off` (so a ring the bootloader/stock left on is switched off) and after
the animation code stops calling `led_apply()` (static mode).

### Which driver backs `sn3112*` (issue #68)

The **sysfs class is named `sn3112l`/`sn3112r` but the drivers are
`htr3212l`/`htr3212r`** (LHT, I2C).  Verified on the booted device:

```
$ ls /sys/bus/i2c/drivers/ | grep -i htr
HTR3212L
HTR3212R
$ ls -l /sys/bus/i2c/drivers/HTR3212L/ 1-003c ...
lrwxrwxrwx 1-003c -> ../../../../devices/platform/soc/.../i2c-1/1-003c
lrwxrwxrwx module -> ../../../../module/htr3212l
$ ls -d /sys/class/htr*        -> none
$ ls -d /sys/class/sn3112*     -> /sys/class/sn3112l /sys/class/sn3112r
$ readlink -f /sys/class/sn3112l/led -> /sys/devices/virtual/sn3112l/led
$ echo 0 > /sys/class/sn3112l/led/enable ; echo $?   -> 0
$ echo 0-0:0:0 > /sys/class/sn3112r/led/brightness   -> 0
```

So the LED controller *is* the htr3212 (as issue #68 says about the vendor
driver), but its class devices are registered under the `sn3112` name, which is
why the sysfs ABI used here works.  There are no `/sys/class/htr3212*` nodes
to support; the backend is correct as written.

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

## 8. Integration build verification (2026-11-14, build 20261114-002243)

First build that contains this branch (`1c9ffc2`); everything below was measured
on the device as `shell` (no adb root).

| check | result |
|---|---|
| daemons | `ashfall-hwd` and `ashfall-rp5-input` both `running`, no crashes, no `avc` denials for the ashfall domains |
| fan | `mode=off` kept for the user, `state=0 duty=0`, 37-42 C idle; `ashfall.status.fan.rpm=0` |
| tach | `dmesg | grep -c "Unbalanced enable"` = **0** (the RPM read is skipped entirely while off) |
| thermal reads | **90 `temp` reads/min** = 3 watched zones / 2 s, stable over a 60 s sample (the stock driver prints **2** `temp_show` lines per read, so kmsg shows 180 lines/min; with all 16 zones it was 8 reads/s) |
| watched zones | the 3 hottest, re-picked by the 5 min sweep (observed `cpu-1-0`, `cpu-1-1`, `cpu-1-5` at ~40 C) |
| LED | `led: sn3112 backend (left=yes right=yes)` and no write errors when the same binary is run from `/data/local/tmp`; the visual result is still a user step (§4) |

**Logging at boot (fixed here):** a `class core` daemon starts before logd
accepts writes, so every startup line (LED backend, thermal watch set,
calibration source) was silently lost - the lines needed to verify exactly the
items above.  Both services now set `stdio_to_kmsg` and `ah_logf()` mirrors to
stderr, so boot-time lines land in `dmesg` (`I/ashfall-hwd: ...`).
