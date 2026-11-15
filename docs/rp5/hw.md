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
| init service | `aosp/vendor/ashfall/rp5/hw/ashfall-hwd.rc` (`class core`, `user root`; root is a DAC requirement for the thermal trip attributes, see §9.3) |
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
| `/sys/class/power_supply/*/{current_now,voltage_now,capacity,status,charge_counter,charge_full_design}` | read | battery telemetry (§11); the device is resolved at runtime (`battery`, then `bms`) |
| `/sys/devices/system/cpu/cpufreq/policy*/scaling_*` | read/write | per-cluster max frequency and governor (§12) |
| `/sys/block/zram0/*`, `/dev/block/zram0` | read/write | zram swap device (§13) |
| `/data/ashfall/swapfile` | create/read/unlink | swapfile (§13) |

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
  still runs `persist.ashfall.fan.kick_pct` (default 30 %) for `FAN_KICK_MS`
  (500 ms) as insurance for a cold unit.  The kick is never 100 %
  (phase 5 v2) and `persist.ashfall.fan.kick=0` disables it.

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

Phase 5 v2 additions (docs/phase5.md §6.1):

| property | values | default | effect |
|---|---|---|---|
| `persist.ashfall.fan.kick` | `1`/`0` | `1` | spin-up kick after the fan was stopped |
| `persist.ashfall.fan.kick_pct` | 10..60 | `30` | kick level (never 100 %) |
| `persist.ashfall.fan.screen_off` | `off`/`run` | `off` | `off` stops the fan while the screen is off; `run` keeps it running |
| `ashfall.ui.fan_live` | `1` | empty | status every 500 ms instead of 2 s while a fan UI is visible |
| `ashfall.ui.fan_preview_curve` | curve string | empty | while non-empty and mode=`smart`, used instead of `persist.ashfall.fan.curve` (live editor preview; the app clears it) |

Phase 5 v3 additions (docs/phase5.md §7.1, issue #121):

| property | values | default | effect |
|---|---|---|---|
| `persist.ashfall.fan.min_pct` | 0..100 | `5` | output below it stops the fan completely (`state=0`, `duty=0`); `0` disables the cut-off |
| `persist.ashfall.fan.max_pct` | 0..100 | `100` | the fan never runs above it; `100` disables the cap |

* `persist.ashfall.fan.curve`: 2..8 `tempC:pct` pairs, ascending temperature,
  default `45:0,55:30,65:55,75:80,82:100`.  Invalid values are ignored (the
  daemon keeps the default).  `ashfall.ui.fan_preview_curve` follows the same
  syntax/limits; an invalid preview is ignored.
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
* **Spin-up kick:** a 0 -> nonzero transition runs the fan at
  `persist.ashfall.fan.kick_pct` (10..60, default 30; never 100) for 500 ms
  (`FAN_KICK_MS`) before dropping to the requested speed.  A target at or above
  the kick level is applied directly (the kick would not add anything); the
  raised level is also capped at `persist.ashfall.fan.max_pct` (phase 5 v3).
  `persist.ashfall.fan.kick=0` disables the kick entirely.  This also runs when
  the fan resumes on screen-on.
* **Screen-off hold:** with `persist.ashfall.fan.screen_off=off` (default) the
  fan stops while the screen is off (`state=0`, `duty=0`); on screen-on it
  resumes with the configured kick.  The safety override still wins.
* **Safety override (fixed, not a setting):** at or above **90 °C** the fan runs
  at 100 % until the temperature is **80 °C**, in every mode and also with the
  screen off.  `persist.ashfall.fan.safety_c` was removed in phase 5 v2; the
  rule is deliberately not exposed in the UI.
* **Output limits (phase 5 v3, `min_pct`/`max_pct`):** applied at the very end
  of `fan_control_tick()`, to the percentage that the active mode, the Smart
  curve or the screen-off hold resolved, immediately before the duty is
  written.  `pct == 0` stays `0`; `0 < pct < min_pct` becomes `0` (the fan
  stops completely: `state=0` *and* `duty=0`, never a partial duty);
  `min_pct <= pct <= max_pct` is unchanged; `pct > max_pct` becomes `max_pct`.
  Every mode (Quiet/Balanced/Max, Manual, Smart, `off`) and the screen-off hold
  are covered, and `ashfall.status.fan.pct` reports the limited value that was
  applied.
* **The limits never touch thermal protection:** the 90 °C rule and the
  no-sensor fail-safe both latch `safety`, are resolved before the limiter and
  bypass it (`max_pct=40` still gives 100 % at 90 °C).  This exception is not a
  setting and cannot be configured away.
* **Quiet consequence:** the shipped `min_pct=5` is above the built-in Quiet
  preset (`2 %`), so **Quiet stops the fan completely** until the low limit is
  lowered to 0.  Balanced (46 %) and Max (100 %) sit inside the defaults.
* **Spin-up kick:** it only runs for a non-zero limited target; a target cut to
  0 writes `state=0` + `duty=0` and cancels a pending kick.  When it does run,
  the level it raises the fan to is itself capped at `max_pct` (never above the
  high limit, not even for the 500 ms kick); the safety override is exempt.

`ashfall.status.*` (written at most every 2 s, or every 500 ms while
`ashfall.ui.fan_live=1`; only on change):

| property | meaning |
|---|---|
| `ashfall.status.fan.temp` | hottest CPU/GPU temperature, tenths of °C (`563` = 56.3 °C) |
| `ashfall.status.fan.pct` | current fan output 0..100 *after* the min/max limits (the kick level during a kick, `-1` if the sysfs write failed) |
| `ashfall.status.fan.rpm` | `speed` node value, `0` while the fan is off (never read then), `-1` when unavailable |
| `ashfall.status.fan.safety` | `1` while the safety override is active |

`persist.ashfall.fan.preset` is UI-only (the active preset id, <= 32 chars); the
daemon ignores it and only ever reads `mode`, `manual_pct`, `curve`, `kick`,
`kick_pct`, `screen_off`, `min_pct` and `max_pct`.

Numbers are invalid -> default, not clamped: `manual_pct` outside 0..100,
`min_pct`/`max_pct` outside 0..100, `kick_pct` outside 10..60,
`led.brightness` outside 0..100 and `led.speed` outside 1..10 use the
documented default (50, 5/100, 30, 60, 5), and each distinct bad value is
logged once.  A `min_pct > max_pct` pair is invalid as a pair: both go back to
`5`/`100` with one warning.

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
| `breathing` | smooth brightness pulse, period `4000 / speed` ms, gamma-corrected |
| `rainbow` | hue rotation, full turn every `18000 / speed` ms; with `sync=0` the right ring is offset by 120° |
| `duo` | both sticks fade smoothly between `color_left` and `color_right` (`5000 / speed` ms per direction) |

`sync=1` makes the right side use the left colour.  `brightness` 0..100 scales
the channels; `speed` 1..10.  Screen state comes from the framework's
`debug.tracing.screen_state` (Display Power Controller; `1` = off).  Doze/AOD
states (`3`/`4`) count as screen-on, so the screen-off hold/off policy applies
to a real screen off only (pre-existing `screen_is_on()` behaviour).

**Screen-off policy (phase 5 v2):** LEDs are **always off while the screen is
off**; `persist.ashfall.led.screen_off` was removed.  On screen-on the daemon
re-applies the lighting, rewriting both `brightness` and `enable` from scratch
(the cached values are invalidated), so a ring that did not come back after a
screen cycle is rewritten rather than skipped as "unchanged".  A failed off/on
write is retried every 500 ms.  No animation wakeups happen while the screen is
off.

### Screen-on wake re-apply (issue #109)

`debug.tracing.screen_state` is not stable across the power/keyguard
transition, and the HTR3212 controllers can accept the first
`brightness`/`enable` transfers after the panel rail comes back **without
latching them**.  The attributes are write-only, so a successful `write()` is
not proof of a lit ring.  The old one-shot re-apply therefore treated an
ignored first transfer as success (`applied=true`) and never retried, which
left one ring (usually the right) or both dark until the next screen cycle or
a settings change.  The two controllers are independent I2C devices, so the
two sides can fail separately - matching the both/one/none symptom.

The daemon now:

* **debounces** the screen decision: a raw change must hold for
  `LED_WAKE_DEBOUNCE_MS` (250 ms) before it becomes the screen state used by
  the fan and lighting, so a short off/on flicker during doze/keyguard changes
  nothing;
* on a debounced off->on edge, invalidates the cached attributes and rewrites
  both `brightness` and `enable` for both sides at the edge, **+500 ms** and
  **+1.5 s** (`LED_WAKE_REAPPLY_COUNT` / `LED_WAKE_REAPPLY_OFFSET_MS`),
  regardless of `led.applied`;
* **cancels** the remaining rewrites as soon as a screen-off edge is
  committed, so the window is bounded and nothing retries while the screen is
  off;
* keeps the 500 ms `LED_RETRY_MS` path for writes that really fail.

The write order stays `brightness` then `enable`; every wake rewrite repeats
the **full pair**, so an ignored first transfer is corrected even if the
controller needs a different sequence to latch.

One `ashfall-hwd` line is logged per debounced transition
(`screen on: re-applying LED/fan settings` / `screen off: holding fan and
LEDs`); the wake rewrites add no log lines unless a write fails.

**Verification (human eye required - the nodes are write-only).** With a
visible static colour and the screen on:

```sh
adb shell logcat -c
adb shell 'i=0; while [ $i -lt 20 ]; do
  input keyevent KEYCODE_POWER; sleep 2
  input keyevent KEYCODE_POWER; sleep 0.4; wm dismiss-keyguard; sleep 3
  i=$((i+1)); done'
adb shell 'logcat -d -s ashfall-hwd | grep screen'
```

Both rings must be lit after every unlock, with the configured colour and
brightness.  The log must show one line per transition (no per-retry lines),
`getprop debug.tracing.screen_state` must be `2` after the last wake, and the
daemon must stay idle while the screen is off (no animation wakeups).
Repeat after a cold boot and with each mode (`static`, `breathing`, `rainbow`,
`duo`).  A short off/on flicker (<250 ms) must not produce a transition line.

**Effects (phase 5 v2):**

* Rainbow is three times slower than v1: one full hue turn takes
  `18000 / speed` ms (was `6000 / speed`).
* Breathing interpolates in **perceptual** brightness: the sine is raised to
  the power 2.2 (gamma) with a 2 % floor, and the daemon renders at 50 fps
  (20 ms), so there are no visible steps at speed 5.
* `ashfall.ui.led_override` (`RRGGBB:RRGGBB`, left:right) is a gamepad-tester
  override: the two colours are written verbatim as the final frame (intensity
  included), independent of mode/brightness/sync, and it is driven by property
  changes at up to 30 Hz instead of the animation timer.  Empty = normal
  lighting.

Animation runs at 50 fps in the daemon.  The hardware `breath` attribute is
**not used**: it is `--w--w---- root root` (root-only, and only a fixed
breath effect - no `duo`/`rainbow`, no exact period).  Software animation gives
the documented periods and effects.

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
| `ashfall_cpu_sysfs` | `/devices/system/cpu/cpufreq`, `/devices/system/cpu/cpu{0..7}/cpufreq` | phase 6 (#124): the `policy*` dirs are symlinks into `cpuN/cpufreq`; the platform label `sysfs_devices_system_cpu` is not writable by a coredomain (§12) |
| `ashfall_zram_sysfs` | `/devices/virtual/block/zram0`, `/block/zram0` | phase 6 (#141): `/sys/block/zram0/*` is a class symlink to the virtual block device (§13) |
| `ashfall_swap_file` | `/data/ashfall(/.*)?` | phase 6 (#141): the swapfile directory and file; created by init at `post-fs-data` so the label is inherited (§13) |
| `ashfall_gpu_sysfs` (+ `gpuclk`) | `.../kgsl/kgsl-3d0/gpuclk` (platform + legacy) | phase 6 (#124): the read-only live GPU clock behind `ashfall.status.perf.gpu_mhz`; only this attribute was added, `min_freq`/`max_freq` are unchanged (#120 stays open) |

On the test build the nodes were still labelled `u:object_r:sysfs:s0` (and the
`shell` domain that ran the test binary is a permissive domain in userdebug),
so the enforcing behaviour of these `genfscon` entries has to be confirmed on
the first integration build: after flashing, `ls -lZ` must show
`ashfall_fan_sysfs` / `ashfall_led_sysfs` and `dmesg | grep avc` must be clean
while fan and LEDs are driven.

Phase 6 also relabels `/dev/block/zram0` (`ashfall_zram_device`) and grants
`sys_admin` for swapon/swapoff; `/sys/class/power_supply` already carries the
platform `sysfs_batteryinfo` type, so the battery telemetry only needs a read
grant (no `ashfall_power_sysfs` relabel, §11).

Thermal zones need no new type: the stock vendor policy (still in place under
the GSI) labels `/devices/virtual/thermal` and `/class/thermal` as
`sysfs_thermal`.  `ashfall_hwd.te` grants read for the fan sensor and write for
the `trip_point_*_temp` attributes used by the performance override (§9); the
critical/hotplug trips are never written.  There is no AOSP neverallow against
`sysfs_thermal` writes (only generic `sysfs` is restricted for coredomains).

Properties: `ashfall_prop` (`persist.ashfall.`, set by `system_app`, read by
both daemons), `ashfall_status_prop` (`ashfall.status.`, set by both daemons,
read by `system_app`), `ashfall_ui_prop` (`ashfall.ui.`, non-persistent,
written by the app and read by `ashfall_hwd`) and `ashfall_perf_prop`
(`ashfall.perf.` and the more specific `persist.ashfall.perf.` prefix; the
override request is non-persistent, the `_permanent` switch persists).  New
policy is wired through `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` in
`vendor/ashfall/ashfall_common.mk`: a GSI replaces `system`/`system_ext` but not
the stock `vendor`, so `BOARD_SEPOLICY_DIRS` (vendor policy) would never be
flashed.

## 6. Test checklist

Run on a build that contains `ashfall-hwd` (a manually pushed binary runs as
`shell`, which is enough for sysfs but cannot set `ashfall.status.*`).

1. `adb shell getprop init.svc.ashfall-hwd` -> `running`;
   `adb shell logcat -s ashfall-hwd` shows the thermal zone list and the LED
   backend (`sn3112` left/right yes).
2. Fan: `adb shell setprop persist.ashfall.fan.mode max` spins up within 1 s;
   `quiet` is noticeably slower; `off` stops it.  `ashfall.status.fan.pct`
   follows, `ashfall.status.fan.temp` tracks `cpu-1-*`.
3. Smart: heat the device (game/benchmark); output rises along the curve and
   drops only ~3 °C later than it rose.  With `ashfall.ui.fan_live=1` the
   status properties update every ~500 ms (tach included).
4. Kick: `kick=1 kick_pct=30` and a stopped fan -> the tach shows a short
   30 % burst, never 100 %; `kick=0` -> the target speed directly.
5. Screen: `screen_off=off` -> the fan stops with the screen and resumes with
   the kick on screen-on; `run` -> keeps running.  LEDs always go dark with the
   screen and come back on screen-on (also after toggling modes while off).
   Run the 20-cycle wake test from §4 (both rings after every unlock, one log
   line per transition).
6. Safety: heat above 90 °C -> 100 % and `ashfall.status.fan.safety=1`; it
   releases at 80 °C, also with the screen off.
7. Lighting: `static` with `color_left=FF0000` lights the left ring red,
   `color_right=0000FF` (with `sync=0`) the right one blue; `breathing`,
   `rainbow` (now 3x slower) and `duo` animate smoothly at speed 5;
   `brightness 0` is dark; `ashfall.ui.led_override=FF0000:0000FF` shows the
   exact colours regardless of mode and stops the animation.
8. Performance override: `setprop ashfall.perf.throttle_override 1` ->
   `ashfall.status.perf.throttle=1`, the clock trips bound to the CPU/GPU
   cooling devices raised to 105 °C (`ddr`) / 110 °C (GPU-only trips), `0`
   restores the stock 95/100 °C.  The 75 °C `cpu-1-*` trip has no cdev and is
   left alone.  Critical trips, the 110 °C CPU-pause trip and LMh stay
   untouched (§9).
9. `adb shell dmesg | grep avc` (and `logcat -b events | grep avc`) is clean
   after exercising all of the above on an **enforcing** build.
10. Phase 6 telemetry: §11 (battery/power), §12 (CPU caps and governor) and
    §13 (zram/swapfile) carry their own property tables and enforcing checks.

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

## 9. Performance: software throttling override (issue #82)

Contract: `docs/phase5.md` §6.1.

| property | type | values |
|---|---|---|
| `ashfall.perf.throttle_override` | non-persistent `ashfall_perf_prop` | `1` = disable software clock throttling, empty/`0` = normal; resets on reboot |
| `persist.ashfall.perf.throttle_override_permanent` | `ashfall_perf_prop` | `1` = apply the override at every boot (hidden developer switch); the runtime request still wins until the next reboot |
| `ashfall.status.perf.throttle` | `ashfall_status_prop` | `1` active, `0` off, `-1` unavailable/failed |

The property is intentionally **not** a saved setting: the app has to ask for
the override explicitly after a reboot (the permanent switch is the developer
opt-in).  While the override is active the fan rules, the 90 °C safety rule and
the fixed thermal fan curve are unchanged.

### 9.1 What software throttling exists on the RP5 (live sysfs survey)

Evidence: **live `/sys/class/thermal` on build 20261114-021714** (thermal zone
and trip dump plus the kernel's own `cdevN_trip_point` bindings), `dumpsys
thermalservice`, `lsmod`, the stock `vendor_boot` device tree, and the stock
`vendor`/`vendor_dlkm` images.  The live binding is what matters: the device
tree also contains an unbound 75 °C trip on the CPU zones.

* **Kernel thermal zones/trips and their real cooling-device bindings**
  (`thermal_zoneN/cdevM_trip_point`):

  | zone(s) | trip | bound clock cooling device | effect |
  |---|---|---|---|
  | `ddr` | **95 °C** (trip 1) | `cpufreq-cpu4`, `cpufreq-cpu7`, `devfreq-…kgsl-3d0` (+ `cdsp`) | caps the big/prime CPU max frequency **and** the GPU — this is the clock throttle a game hits |
  | `gpuss-0`, `gpuss-1` | **95 °C** (trip 2) | `devfreq-…kgsl-3d0` | caps the GPU frequency |
  | `cwlan`, `video`, `q6-hvx`, `camera`, `cmpss` | **100 °C** (trip 1) | `devfreq-…kgsl-3d0` | additional GPU clock caps |
  | `npu` | **100 °C** (trip 2) | `devfreq-…kgsl-3d0` | additional GPU clock cap |
  | `cpu-1-*` | 75 °C (trip 2) | **none** | present in the DT but no cooling device is bound on this kernel; reporting only |
  | `cpu-0-*`, `cpu-1-*` | 110 °C | `thermal-pause-*` (CPU pause) | last-resort core offlining (`thermal_pause.ko`) |
  | `cpu-1-*`, `gpuss-*` | 115 °C (`reset-mon-cfg`) | — (notifier) | reset monitor |
  | `cpu-1-*`, `gpuss-*` | 125 °C (`thermal-engine`/`thermal-hal` trips) | — | reported to the thermal HAL |
  | `pm8150_tz` | 155 °C `critical` | — | PMIC/SoC hardware shutdown (the only critical trip) |

  At rest (30 °C idle) every cooling device reports `cur_state=0`, `dumpsys
  thermalservice` has `Thermal Status: 0` and **no static thresholds**, and
  every CPU runs at `cpuinfo_max_freq` (`cpu0 1804800`, `cpu4 2419200`,
  `cpu7 2841600`).

* **Cooling devices exposed to Android** (`dumpsys thermalservice`):
  `cpufreq-cpu0/4/7`, `cpu-cluster0/1/2`, `pause-cpuN`,
  `thermal-pause-1/2/4/8/10/20/40/80`, `cpu-hotplugN`,
  `devfreq-3d00000.qcom,kgsl-3d0`, `gpu`, `cdsp`, `hvx`, `npu`, `ufs1`.
  Only the devices bound to a trip (above) can be driven by the thermal core.
* **LMh** (`msm_lmh_dcvs.ko`, permanent) is the Qualcomm hardware DVFS
  limiter.  It is a hardware block fed by the PMIC/tsens, has no sysfs
  parameters on this build, cannot be disabled from userspace, and this
  override does **not** touch it (hardware limits must stay).
* **`msm_performance`** (the perf HAL's limit interface) is present:
  `/sys/kernel/msm_performance/parameters/cpu_{max,min}_freq` are `0644 root`.
  They currently read `2147483647` / `0` for every CPU (no limit), so the perf
  HAL is not throttling at rest; this override does not write them.
* **Vendor thermal-engine is not running**: `init_thermal-engine-v2.rc` has
  the `thermal-engine` service commented out and `/vendor/etc/thermal-engine.conf`
  is empty ("File empty by default").  The QTI thermal HAL
  (`android.hardware.thermal@2.0-service.qti-v2`, root) only *reports*
  temperatures/cooling devices (`IThermal` has no mitigation API).  The
  framework `ThermalService` therefore only changes its reported status.

### 9.2 What the override does

`throttle.c` surveys `/sys/class/thermal/thermal_zone*` at start and reads each
zone's `cdevM/type` plus the kernel's `cdevM_trip_point` binding.  Only cooling
devices that set a clock are targeted: `cpufreq-*` and `cpu-cluster*` (CPU),
`devfreq-*`/`gpu` (GPU).  `thermal-pause-*`, `pause-cpu*`, `cpu-hotplug*`,
`cdsp`, `hvx`, `npu` and `ufs*` are ignored.  For every trip that drives a
clock device it raises the trip temperature to:

| bound clock device | stock trip | override |
|---|---|---|
| `cpufreq-cpu4`/`cpufreq-cpu7` (on `ddr` trip 1) | 95 °C | **105 °C** |
| `devfreq-…kgsl-3d0` on `gpuss-0/1` trip 2 | 95 °C | **110 °C** |
| `devfreq-…kgsl-3d0` on `cwlan`/`video`/`q6-hvx`/`camera`/`cmpss` trip 1, `npu` trip 2 | 100 °C | **110 °C** |

A trip shared by CPU and GPU cdevs (the `ddr` trip) uses the lower CPU target
(105 °C), so the CPU pause at 110 °C remains the next protective step.  The
kernel's step-wise governor then stops applying the cpufreq/devfreq cooling
state in the normal operating range; the CPU/GPU are no longer clock-capped at
95/100 °C.

The `cpu-1-*` 75 °C trip is deliberately **not** touched: on this kernel build
it has no bound cooling device (verified with `cdevN_trip_point`), so raising
it would not change any clock; it is only reported.

On `...=0` (and on daemon shutdown) the saved temperatures are written back.
If init restarts the daemon while the override is still requested (same boot),
the scan recognises the already-raised 105/110 °C values, remembers the stock
95/100 °C as the restore value, and keeps working; an explicit `...=0` still
restores.

**Not disabled (deliberately):**

* `critical` trips (the only one is 155 °C on `pm8150_tz`) — never written.
* the 110 °C CPU-pause/hotplug trips and `thermal_pause.ko`.
* the 115 °C reset monitor and the 125 °C thermal-engine/HAL trips.
* the `ddr` trip also drives `cdsp`; raising it to 105 °C delays that
  mitigation too (it is unavoidable: the same trip is the CPU clock trip).
* the PMIC/battery/modem/camera cooling maps that are not clock devices.
* LMh hardware DVFS limits and all hardware protection.
* the fan safety rule (90 °C -> 100 %).

Because the trips still exist above the override value, the device remains
thermally protected; the override only moves the *first* (performance-capping)
step out of the gaming range.

### 9.3 Permissions

Live check on build 20261114-021714 (`ro.debuggable=0`, `adb shell` = uid 2000):

```
-rw-r--r-- 1 root root u:object_r:sysfs_thermal:s0 ... thermal_zone31/trip_point_1_temp
-rw-r--r-- 1 root root u:object_r:sysfs_thermal:s0 ... thermal_zone31/mode
run-as org.ashfall.parts id   -> package not an application
echo 105000 > .../trip_point_1_temp -> Permission denied
```

The trip, `mode` and `policy` attributes are root-owned `0644`; SELinux cannot
bypass DAC.  `ashfall-hwd` therefore starts as `user root` (it was
`user system` in phase 5 v1) — **the SELinux domain `ashfall_hwd` is still the
security boundary** and only has read/write on the fan, LED and
`sysfs_thermal` types.  `persist.ashfall.*` writes remain restricted to
`system_app`.  The QTI thermal HAL already runs as root on the same nodes.

If a future build makes the trip attributes writable by `system`, the `.rc`
`user` line can go back to `system`; the code logs the failed write and
publishes `ashfall.status.perf.throttle=-1`.

### 9.4 Verification

Host test: `make -C aosp/vendor/ashfall/rp5/hw/tests check` builds a fake zone
tree that mirrors the live bindings (a `ddr` trip 1 shared by `cpufreq-cpu4`,
`cpufreq-cpu7` and the GPU devfreq device, a `gpuss-0` 95 °C trip, a `cwlan`
100 °C trip, a 75 °C `cpu-1-*` trip with no cdev, and the 110/115/125/155 °C
protective trips).  It asserts:

* the 75 °C trip is **skipped** (no cdev bound),
* the shared `ddr` trip raises to **105 °C** (CPU target wins over GPU),
* the GPU-only 95 °C and 100 °C trips raise to **110 °C**,
* OFF restores 95000/100000 exactly, and
* after an init restart the fresh scan recognises the raised values as its own
  (stock 95/100 remembered) and OFF still restores.

On the device (build 20261114-021714, `ro.debuggable=0`, no root; `shell` can
read but not write the trip files):

```sh
adb shell 'for z in /sys/class/thermal/thermal_zone*; do
  echo "$z $(cat $z/type)"; for c in $z/cdev*; do
    [ -d "$c" ] || continue; echo "  $c $(cat $c/type) trip=$(cat ${c}_trip_point)"; done; done'
adb shell setprop ashfall.perf.throttle_override 1   # via the app/settings proxy
adb shell getprop ashfall.status.perf.throttle      # 1
adb shell dumpsys thermalservice | grep -A2 "cpufreq"
```

Confirmed live during the #82 device session: the trip, `mode` and `policy`
attributes are `0644 root:root u:object_r:sysfs_thermal:s0`, `shell` writes are
denied (`Permission denied`), `run-as org.ashfall.parts` is unavailable
(priv-app, not an application), and all cooling devices are at `cur_state=0`
while the device idles at ~30 °C.  The write path itself is therefore verified
on the host (same sysfs interface) and the SELinux policy in
`sepolicy/ashfall_hwd.te`; the end-to-end `105000`/`110000` read-back needs the
integration build (§10).

## 10. Phase 5 v2 on-device verification (build 2026-11-14+, pending)

The v2 daemons were not flashed when this branch was written (the orchestrator
builds `integ/phase5` and hands the RP5 over serially).  The checks to run:

1. `adb shell getprop init.svc.ashfall-hwd` -> `running`; `dmesg | grep
   ashfall-hwd` or `logcat -s ashfall-hwd` shows the `throttle: ...` scan
   lines (one per clock trip: `ddr` -> 105 °C, `gpuss-0/1` -> 110 °C, the
   `cwlan/video/q6-hvx/camera/cmpss/npu` GPU trips -> 110 °C) and `throttle:
   software clock throttling disabled` when the override is requested.  Note
   that `stdio_to_kmsg` needs `ro.debuggable=1`; on the release boot image the
   `ashfall.status.perf.throttle` property is the reliable indicator.
2. `adb shell 'for z in /sys/class/thermal/thermal_zone*; do for t in $z/trip_point_*_temp; do echo "$z $t $(cat $t 2>/dev/null)"; done; done'`
   at a cool temperature -> the live stock values are `ddr` trip 1 = `95000`,
   `gpuss-0/1` trip 2 = `95000`, the secondary GPU zones trip 1/2 = `100000`,
   and `cpu-1-*` trip 2 = `75000` (that one has **no** cdev and is never
   touched).  After `setprop ashfall.perf.throttle_override 1` (through the
   app, which has `ashfall_perf_prop` write access) the clock trips read
   `105000` (the shared `ddr` trip) / `110000` (GPU-only trips) and
   `ashfall.status.perf.throttle` = `1`.  `0` restores `95000` / `100000`;
   the 75/110/115/125/155 °C trips must be unchanged.  `dmesg | grep avc` must
   be clean.
3. Fish: press physical **X (top)** and **Y (left)** while
   `getevent -l /dev/input/event4` runs: `BTN_X` / `BTN_Y` respectively (the
   daemon mapping is stock; the tester drawing is the app worker's item).
   Move L2/R2 slowly: `ABS_BRAKE`/`ABS_GAS` should rise 0..255 roughly
   linearly; note the raw `l2`/`r2` counts at rest/half/full in the RAW line.
4. `ashfall.ui.fan_live=1` -> `ashfall.status.fan.rpm` updates every 500 ms;
   `ashfall.ui.fan_preview_curve` changes the smart output immediately;
   `kick`/`kick_pct` produce a 30 % burst; `fan.screen_off=off` stops the fan
   with the screen and kicks on wake.
5. LEDs: they are dark with the screen off (no `ashfall.led.screen_off`
   property exists any more) and come back on screen-on after toggling
   modes; `rainbow` is 3x slower; `breathing` is smooth at speed 5;
   `ashfall.ui.led_override=FF0000:0000FF` shows exactly red:blue.
6. Wake re-apply (#109): the 20-cycle power-button test in §4 -> both rings
   lit after every unlock, one `ashfall-hwd` line per transition, no write
   errors and no retries while the screen is off.

## 11. Battery / power telemetry (#124, #137)

One sampler, one owner (docs/phase6.md §9.3): `ashfall-hwd` is the only reader
of `/sys/class/power_supply`, and SystemUI (PlayTime) and the Power page read
properties.  The read happens on the existing status tick (`STATUS_INTERVAL_MS`,
2 s; 500 ms while `ashfall.ui.fan_live=1`), there is no second timer and no
second sysfs reader.

| property | type | meaning |
|---|---|---|
| `ashfall.status.power.current_ma` | int | rectified magnitude in mA, `-1` unknown |
| `ashfall.status.power.voltage_mv` | int | battery voltage in mV, `-1` unknown |
| `ashfall.status.power.level` | int | 0..100, `-1` unknown |
| `ashfall.status.power.charging` | int | `1` charging, `0` not charging/`Full`, `-1` unknown |
| `ashfall.status.power.charge_mah` | int | remaining charge, `-1` when no node exists |
| `ashfall.status.power.capacity_mah` | int | design capacity, `-1` when no node exists |
| `ashfall.status.perf.power_mw` | int | `current_ma * voltage_mv / 1000` from the same read; `-1` while charging and when unknown |

**Device discovery.**  The stock unit exposes both a `battery` class device and
a `bms` fuel gauge, and which one carries `current_now` is not fixed.  The
daemon walks `/sys/class/power_supply` and keeps only devices whose `type` is
`Battery`, in the order `battery`, `bms`, then the rest; every attribute is
taken from the first candidate that has it (so `charge_counter` may come from
the gauge while `voltage_now` comes from the pack).  The resolved paths are
logged once at start (`power: battery=... current=...`).

**Units and validation.**  The power-supply class documents `current_now` in uA
and `voltage_now` in uV, but some gauges report mA/mV.  A value `>= 10000` of
the plausible scale is treated as the micro unit (a 10 A draw in mA would be
implausible on this unit).  A current of `0`, `-1` or more than 20 000 mA is
`-1`, a voltage outside 2.0..6.0 V is `-1`, and a `capacity` outside 0..100 is
`-1`: never a fabricated number.  `charging=1` only for the kernel status
`Charging`; `Full`/`Not charging`/`Discharging` are `0` and the pack wattage is
`-1` while charging, because a battery-side number is meaningless then.

Verification on the device (enforcing build):

```bash
adb shell 'for d in /sys/class/power_supply/*; do echo "$d"; cat $d/type \
    $d/current_now $d/voltage_now $d/capacity $d/status \
    $d/charge_counter $d/charge_full_design 2>/dev/null; done'
adb shell 'getprop | grep -E "ashfall.status.(power|perf.power_mw)"'
adb shell dmesg | grep avc      # must be clean (sysfs_batteryinfo read)
```

Expected: `current_ma` follows a 3D load within one tick, `power_mw` is a few
thousand while idle and `-1` on the charger, `charge_mah`/`capacity_mah` are
`-1` if the gauge has no such node (that is a correct answer, not a bug).  If
`dmesg | grep avc` names a label other than `sysfs_batteryinfo` for a
power-supply attribute, the vendor policy moved it: add exact-path
`file_contexts` relabels to `ashfall_power_sysfs` following the kgsl pattern
(§5) and grant `ashfall_hwd` read.

## 12. CPU clock caps and governor (#124)

| property | type | meaning |
|---|---|---|
| `persist.ashfall.perf.cpu_max_silver` / `_gold` / `_prime` | `ashfall_perf_prop` | MHz cap, `0` = stock max |
| `persist.ashfall.perf.cpu_gov` | `ashfall_perf_prop` | one governor for all three policies, default `schedutil` |
| `ashfall.status.perf.cpu_mhz_silver` / `_gold` / `_prime` | `ashfall_status_prop` | effective `scaling_cur_freq`, `-1` unavailable |

**Policy discovery.**  The three policies are found by walking
`/sys/devices/system/cpu/cpufreq` (not by assuming `policy0/4/7`): each
`policy*` directory must expose `scaling_available_frequencies`, and the
cluster comes from its `affected_cpus` list (kona: silver = CPU 0..3, gold =
4..6, prime = 7).  If the CPU ids do not map, the remaining policies are
assigned in ascending top-frequency order (slowest silver, fastest prime), and
the resolved policy is logged once:

```
I/ashfall-hwd: cpu: silver policy0 300..1804 MHz, governor walt conservative powersave performance schedutil
```

**Clamp rules.** `scaling_available_frequencies` is in kHz and is sorted
descending.  A request of `0` writes the highest step (stock), any other
request writes the highest step whose MHz (floored) is `<=` the request, and a
request below the lowest step writes the lowest step.  The presets from #124
name the floored step (`1171` for 1171.2 MHz), which is why the comparison
floors the step and not the request.  Raising above stock is impossible: the
table's top *is* stock.  `scaling_max_freq` and `scaling_governor` are
re-applied at boot (the kernel resets both) and on every property change, and
are re-validated each time because a property can come from `adb shell`.

**Governor.** Validated against `scaling_available_governors`; only
`schedutil`, `conservative` and `powersave` are accepted (the editor exposes
exactly those).  `walt`/`performance` and unknown names become `schedutil`; if
`schedutil` is not in the list, the first of the three that is.  With
`powersave` the caps are inert (the governor pins the lowest step), as the app
documents.

**SELinux.**  The `policy*` directories are symlinks into `cpuN/cpufreq`, which
is `sysfs_devices_system_cpu` and not writable by a coredomain.  The cpufreq
tree is relabelled `ashfall_cpu_sysfs` via `genfs_contexts` (both the
`/devices/system/cpu/cpufreq` and every `cpuN/cpufreq` prefix, applied
recursively), with the exact attribute paths also listed in `file_contexts`.
Enforcing check:

```bash
adb shell ls -Z /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq
#   -> u:object_r:ashfall_cpu_sysfs:s0
adb shell ls -Z /sys/devices/system/cpu/cpufreq/policy7 | head
adb shell 'setprop persist.ashfall.perf.cpu_max_prime 1516; \
           setprop persist.ashfall.perf.cpu_gov conservative'
adb shell 'cat /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq \
                /sys/devices/system/cpu/cpufreq/policy7/scaling_governor'
#   -> 1516800 / conservative
adb shell 'getprop ashfall.status.perf.cpu_mhz_prime'
adb shell dmesg | grep avc      # must be clean
```

`setprop` from `adb shell` is denied for `persist.ashfall.*` by design (§5); use
the Power page, and treat a denial in an `ashfall_hwd` line (not a `setprop`
line) as the policy bug.

`ashfall.status.perf.gpu_mhz` is the **live** kgsl clock from `gpuclk` (Hz),
converted to MHz and read once per existing status tick: `-1` when the node is
missing or unreadable (the Power page graph draws a gap), `0` when the GPU
reports 0 (clocked off).  Phase 6 adds only this one read-only attribute to the
existing `ashfall_gpu_sysfs` relabel, as exact-path `file_contexts` entries plus
`genfs_contexts` fallbacks for the platform and legacy layouts with every dot
escaped:

```
/sys/devices/platform/soc/3d00000\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpuclk
/sys/devices/soc/3d00000\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpuclk
```

`min_freq`/`max_freq` are untouched and the #120 vendor-perf-HAL question stays
open; the cap properties (`gpu_max_mhz`, `gpu_oc`) are unchanged.

## 13. Memory: zram and swapfile (#141)

| property | type | meaning |
|---|---|---|
| `persist.ashfall.mem.zram_mb` | `ashfall_prop` | zram size, `0` = off, clamped to `MemTotal/2` |
| `persist.ashfall.mem.swap_mb` | `ashfall_prop` | swapfile size, `0` = off, clamped to 16384 MB |
| `ashfall.status.mem.zram_mb` / `swap_mb` | `ashfall_status_prop` | applied sizes; `0` off, `-1` unavailable |
| `ashfall.status.mem.zram_used_kb` / `zram_saved_kb` | `ashfall_status_prop` | `mm_stat`: compressed size and `orig - compressed` |
| `ashfall.status.mem.swap_total_kb` / `swap_used_kb` | `ashfall_status_prop` | `/proc/meminfo` `SwapTotal`/`SwapFree` |

**zram.** `/dev/block/zram0` plus `/sys/block/zram0/{disksize,reset,
comp_algorithm,comp_available,mm_stat}`.  Apply order: `swapoff` (when the
device is in use) -> `reset` -> `comp_algorithm` -> `disksize` -> **v1 swap
header** -> `swapon` (priority 1).  A freshly reset zram device is all zeros and
`swapon(2)` rejects it with `EINVAL`, so the daemon writes the same v1 header
that Android init's `mkswap` would (version 1, `last_page`, `SWAPSPACE2` at
offset 4086) before attaching it.  On the boot/restart re-attach path `swapon`
is tried first; only an `EINVAL` makes the daemon format the device once and
retry (`EBUSY` means it is already active and is success - an active device is
never formatted).  The compressor is `lz4` when `comp_available` lists it, else the
first available one, logged once (`mem: zram compressor lz4`); there is no UI
option.  The size is clamped to `MemTotal/2` (4000 MB on the 8 GB unit, 6000 on
12 GB) and to 6144 MB, so the stock 4096 MB is never applied on an 8 GB unit.
The same size is idempotent: boot and a daemon restart only re-run `swapon`
instead of reallocating the device.

**swap.** A file at `/data/ashfall/swapfile` on the f2fs data partition,
created with `fallocate` (a sparse `ftruncate` when the filesystem refuses) and
a v1 swap header written by the daemon, then `swapon` with priority 2 (zram
stays preferred, both coexist).  The request is clamped to 16384 MB (the daemon
enforces the UI cap too) and to `/data` free space minus a 2048 MB reserve; a
rejected request logs one line and publishes the effective value.  Disabling
swaps off, unlinks the file and returns the space.

**Missing nodes.**  No `/dev/block/zram0` (module not in `vendor_dlkm`) or no
`/data` mount is a logged no-op: the size is published as `-1` and the daemon
keeps running.  A swapfile allocation of several GB can take seconds on the
first enable (and at boot if the property is set); the daemon is single-threaded,
so the fan holds its last applied duty for that window.  This is documented, not
a regression: no new daemon or thread is added.

**SELinux / init.**  `/dev/block/zram0` is `ashfall_zram_device`,
`/sys/block/zram0/*` -> `ashfall_zram_sysfs`, `/data/ashfall(/.*)?` ->
`ashfall_swap_file`, and `swapon`/`swapoff` need `sys_admin`.
`ashfall-hwd.rc` creates `/data/ashfall` at `post-fs-data` with
`restorecon_recursive`, so both the directory and a runtime-created swapfile
carry `ashfall_swap_file` (a directory created by the daemon itself would
inherit the `/data` label).  Enforcing check:

```bash
adb shell 'ls -Zd /data/ashfall /data/ashfall/swapfile'
#   -> u:object_r:ashfall_swap_file:s0
adb shell ls -Z /dev/block/zram0 /sys/block/zram0/disksize
#   -> ashfall_zram_device / ashfall_zram_sysfs
adb shell 'setprop persist.ashfall.mem.zram_mb 2048; cat /proc/swaps'
adb shell 'getprop | grep ashfall.status.mem'
adb shell dmesg | grep avc      # must be clean
```

`/proc/meminfo` is `proc_meminfo`, readable by every domain in the platform
policy, so it needs no AshFall rule.

## 14. Fan output limit during the spin-up kick (#129)

`persist.ashfall.fan.max_pct` is enforced for the whole kick window, not only
when the kick is created.  When the cap is lowered while a kick is still open,
`fan_apply_pct()` drops `kick_until_ms` and writes the already limited target
on that tick instead of returning the stored (now too high) level.  The safety
override (90 °C) and the no-sensor fail-safe stay exempt.  Regression test:
`test_kick_max_lowered` in `rp5/hw/tests/test_hw.c` (kick to 30 % with max 100,
lower max to 10, next tick -> 5 % and `ashfall.status.fan.pct` 5, duty
`fan_pct_to_duty(5)`).
