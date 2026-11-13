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

## 2. Fan measurement (duty -> RPM range)

The fan is a 4-wire PWM fan: `duty` is the PWM high time in ns and `speed` is
the fan's feedback.  Percentages in `persist.ashfall.fan.*` map **linearly onto
the measured useful range** (`FAN_DUTY_MIN_NS .. FAN_DUTY_MAX_NS` in
`rp5/hw/fan.h`); `0 %` means `state=0`.

Stock values for reference (`.31`/`.189`): Quiet 5 000 ns, Sport 25 000 ns,
High 25 000 ns (`.189` maps 50..70 % to 25 000..35 000 ns).

**Status: the on-device sweep has not been run yet** (the RP5 was busy with
another build when this landed).  Current constants are therefore provisional:

| constant | value | basis |
|---|---|---|
| `FAN_DUTY_MIN_NS` | 3 000 | below stock Quiet (5 000), assumed minimum that spins reliably |
| `FAN_DUTY_MAX_NS` | 35 000 | `.189` High maximum, assumed top of the useful range |
| `FAN_PRESET_QUIET_PCT` | 7 | ~5 200 ns (stock Quiet 5 000 ns) |
| `FAN_PRESET_BALANCED_PCT` | 68 | ~24 760 ns (stock Sport 25 000 ns) |
| `FAN_PRESET_MAX_PCT` | 100 | 35 000 ns |

Measurement procedure (userdebug GSI, `adb root`, fan at rest):

```sh
# for each duty: enable, wait ~10 s for the tach to settle, read the tach
for d in 0 2000 3000 4000 5000 6000 8000 10000 12000 16000 20000 25000 30000 35000 40000; do
  echo 1 > /sys/class/gpio5_pwm2/state
  echo $d > /sys/class/gpio5_pwm2/duty
  sleep 10
  echo "$d ns -> $(cat /sys/class/gpio5_pwm2/speed)"
done
echo 0 > /sys/class/gpio5_pwm2/state
```

Fill in:

| duty (ns) | `speed` raw | audible? | notes |
|---:|---:|---|---|
| | | | |

Then set `FAN_DUTY_MIN_NS` to the smallest duty that starts and keeps spinning
from cold, `FAN_DUTY_MAX_NS` to the duty above which the tach no longer rises,
and re-derive the three preset percentages (quiet ~ stock 5 000 ns).  Also
record whether the `speed` node is RPM or a tach count: `ashfall.status.fan.rpm`
is `-1` when the node is unreadable, and otherwise reports the raw value — if
the node turns out to be a scaled count, add the factor in `fan_read_rpm()`.

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

Animation runs at 30 fps in the daemon (the hardware `breath` attribute is
present but its behaviour is untested on this unit; a software animation is
predictable and works for all effects).

### Which node lights what (measurement pending)

* `sn3112l` / `sn3112r` are the **left / right stick rings** (stock
  `left_joystick_light_enabled` / `right_joystick_light_enabled`,
  `joystick_led_light_picker_color` with two colours).
* `/sys/class/leds/{red,green,blue}` (pm8150l) are **not** the stick rings.
  They are also not usable from this daemon: `private/coredomain.te` has
  `neverallow { coredomain -init -ueventd -vold ... } sysfs_leds:file *`, and
  `/sys/class/leds` is genfs-labelled `sysfs_leds`.  On the device, cover each
  node one at a time (`echo 255 > /sys/class/leds/red/brightness`) to record
  what it physically lights; it is expected to be a front/notification LED.
  The daemon's `leds` fallback backend was removed for this reason.

## 5. SELinux

Both daemons are `coredomain`s (required: a non-coredomain may only use
`vendor_file_type` or `init_exec` as an entrypoint).  A coredomain may **not**
open generic sysfs files:

```
private/coredomain.te:
neverallow { coredomain -apexd -init -ueventd -vold } sysfs:file no_rw_file_perms;
```

so the fan and LED attributes are labelled with dedicated types in
`vendor/ashfall/sepolicy/genfs_contexts`:

| type | paths |
|---|---|
| `ashfall_fan_sysfs` | `/devices/platform/soc/soc:pm8150l_gpio6` (the `gpio5-pwm` platform device, a `soc` simple-bus child), `/devices/virtual/gpio5_pwm2` (if the class device has no parent) |
| `ashfall_led_sysfs` | `/devices/virtual/sn3112l`, `/devices/virtual/sn3112r` (and `/devices/platform/sn3112*`) |

Only the matching entries take effect; the others are harmless.  Verify the
real paths after a flash and trim the list:

```sh
adb shell readlink -f /sys/class/gpio5_pwm2 /sys/class/sn3112l/led /sys/class/sn3112r/led
adb shell ls -lZ /sys/class/gpio5_pwm2/duty /sys/class/sn3112*/led/brightness
```

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
