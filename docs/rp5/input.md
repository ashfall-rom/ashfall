# RP5 input (built-in gamepad) - issue #43

## Summary

The Retroid Pocket 5 gamepad is **not** a USB/HID device and its buttons do not
come from the kernel `gpio-keys` device.  A small MCU samples the buttons, both
sticks and both triggers and reports them over the **115200-baud UART on
`/dev/ttyHS1`**.  Stock Android bridges that UART to `/dev/uinput` from the
stock system image (`pservice` + `RsMapping`/`librsinput.so`).  A GSI replaces
that system image, so the bridge disappears and Android never sees a gamepad
input device.

The fix is a small system-side daemon (`ashfall-rp5-input`) that speaks the
same MCU protocol and creates a standard Android gamepad through uinput.  No
stock binary or APK is reused; the protocol was recovered from the stock
library and verified on the live device.

## Stock data path (what a GSI loses)

The stock system image contains these Retroid pieces:

| piece | partition | role |
|---|---|---|
| `RsMapping.apk` | `/system/app` | mapping UI; ships `lib/arm64-v8a/librsinput.so` |
| `GameAssistant.apk` | `/system/app` | per-game overlay/mapping UI |
| `RPSettings.apk` | `/system/app` | fan/RGB/backlight settings |
| `pservice` | `/system/bin` | renames `/dev/ttyHS1` to `/dev/rscom` at boot, exposes `/dev/rsinput`/`/dev/rscom` sockets and the `PServerBinder` service |

`librsinput.so` contains the strings and symbols that identify the real path:

```
/dev/ttyHS1        (only pservice references it in the stock images)
/dev/rscom         (pservice renames ttyHS1 here at boot)
/dev/rsinput       (socket exposed by pservice)
/dev/uinput        (event injection)
mcupower 1 / mcupower 0
/sys/devices/platform/rsgpio/driver_ctl
/mnt/vendor/persist/retrostation/joys     (factory stick/trigger calibration)
```

In `pservice`'s `device_manager` the sequence is: `rename("/dev/ttyHS1",
"/dev/rscom")`, then create the Unix sockets.  `librsinput.so`'s
`reopen_uart_fd()` calls `uart_init("/dev/rscom", 115200)` and starts parsing
frames.  The kernel side (`rs_gpio`) only provides the MCU power/GPIO control
nodes (`/dev/rsgpio`, `/dev/rstouch`, `driver_ctl`); it does **not** create an
input device.  Its power state follows the screen:

```
$ adb shell dmesg | grep mcu_power_control
RP5 [mcu_power_control(457)]mcu_power_status=[1]   # screen on
RP5 [mcu_power_control(457)]mcu_power_status=[2]   # screen off
```

On the GSI the device nodes are still present because the stock vendor image is
kept:

```
$ adb shell ls -l /dev/ttyHS1 /dev/rsgpio /dev/uinput
crw-rw-rw- 1 root root   495, 1 ... /dev/ttyHS1
crw-rw-rw- 1 root root    10,124 ... /dev/rsgpio
crw-rw---- 1 uhid uhid   10,223 ... /dev/uinput
```

but `pservice`, `RsMapping` and `librsinput.so` are gone, and there is no
gamepad input device:

```
$ adb shell cat /proc/bus/input/devices
N: Name="qpnp_pon" ...
N: Name="gpio-keys" ...        # one key only (KEY 0x8000000000000)
N: Name="qti-haptics" ...
N: Name="fts_ts" ...           # touchscreen
N: Name="kona-qrd-snd-card Headset Jack" ...
N: Name="kona-qrd-snd-card Button Jack" ...
```

## MCU protocol (verified live)

Frames are little-endian, length-prefixed and XOR-checksummed:

```
A5 D3 5A 3D | seq | cmd | len_lo len_hi | payload[len] | checksum
```

`checksum = XOR(bytes from seq through the last payload byte)`.

The host starts the MCU with a "set parameters" command.  For the RP5 the
device type is 5 (`rsdev_pid_detect()` maps `ro.product.model == "Retroid
Pocket 5"` to 0x12 and `send_data_to_MCU()` maps that to type 5):

```
A5 D3 5A 3D 01 01 0A 00 05 01 00 00 00 05 00 00 00 01 0A
             seq cmd len=10  data...                    checksum
```

After that the MCU streams `CMD_STATUS` (`cmd=0x02`) frames.  A captured,
idle frame is:

```
A5 D3 5A 3D AD 02 0E 00 00 00 EF 05 05 06 8D 00 1A 00 46 00 0B 00 92
             seq cmd len=14 keys  L2    R2    LX    LY    RX    RY   csum
```

14-byte status payload:

| offset | type | meaning |
|---|---|---|
| 0..1 | u16 BE | 16 key bits (table below) |
| 2..3 | u16 LE | left trigger raw (L2) |
| 4..5 | u16 LE | right trigger raw (R2) |
| 6..7 | s16 LE | left stick X |
| 8..9 | s16 LE | left stick Y |
| 10..11 | s16 LE | right stick X |
| 12..13 | s16 LE | right stick Y |

The key word is **big-endian** (`data[0]` is the high byte), while the analog
values are little-endian.  The stock parser walks the key word from bit 15
down to bit 0 against the default table at `librsinput.so + 0x2e82c`:

| payload bit | Linux code | Android button |
|---|---|---|
| 15 | `BTN_B` (0x131) | B |
| 14 | `BTN_A` (0x130) | A |
| 13 | `BTN_Y` (0x134) | Y |
| 12 | `BTN_X` (0x133) | X |
| 11 | `ABS_HAT0X` (+) | D-pad right |
| 10 | `ABS_HAT0X` (-) | D-pad left |
| 9 | `ABS_HAT0Y` (+) | D-pad down |
| 8 | `ABS_HAT0Y` (-) | D-pad up |
| 7 | `KEY_BACK` (0x9e) | Android back |
| 6 | `KEY_HOMEPAGE` (0xac) | Android home (`KEY_HOME` 0x66 maps to keyboard MOVE_HOME in Generic.kl) |
| 5 | `BTN_THUMBR` (0x13e) | right stick click |
| 4 | `BTN_THUMBL` (0x13d) | left stick click |
| 3 | `BTN_START` (0x13b) | start |
| 2 | `BTN_SELECT` (0x13a) | select |
| 1 | `BTN_TR` (0x137) | R1 |
| 0 | `BTN_TL` (0x136) | L1 |

Analog mapping follows AOSP's `VirtualGamepad` convention:
`LX -> ABS_X`, `LY -> ABS_Y`, `RX -> ABS_Z`, `RY -> ABS_RZ`,
`L2 -> ABS_BRAKE` (Android `AXIS_LTRIGGER`), `R2 -> ABS_GAS`
(Android `AXIS_RTRIGGER`).

The MCU's ADC direction is opposite to Android's: both the stock
`librsinput.so` converter (which multiplies the normalised value by
-32767) and the mainline `rsinput` driver negate it, so the daemon emits
`(center - raw)` rather than `(raw - center)`.  A small deadzone of 1500
counts (~5 % of full travel) suppresses the calibration offset at rest.

The D-pad is a special case.  The default table stores `BTN_DPAD_*` codes
(0x220..0x223), but before injection the stock library calls
`gamepad_codes_convert()`, which rewrites those events to
`ABS_HAT0X`/`ABS_HAT0Y` (±1).  This is required because AOSP's `Generic.kl`
has no `BTN_DPAD_*` entries; `ViewRootImpl` turns the HAT axes into
`DPAD_*` navigation.  The daemon does the same conversion.

The stock library also tells us the uinput IDs: `gp_create()` uses
`vendor 0x2020`, `product 0x3001` for the normal ("Retroid Pocket
Controller") mode on the RP5 (0x2022/0x3001 for the XInput mode).  The daemon
uses 0x2020/0x3001, so `Generic.kl` and the generic joystick mapper apply;
no stock keylayout is needed.  The old RP5-ROM keylayouts
(`Vendor_2020_Product_0111/0112.kl`) belong to the other Retroid models
(types 2/7) and are not used here.

Factory calibration is stored in
`/mnt/vendor/persist/retrostation/joys`; the live unit contains exactly these
values (checked after the 2026-11-13 reflash), so the built-in defaults above
are identical to the factory data.

The file is labelled `u:object_r:mnt_vendor_file:s0`, and platform policy has
a `coredomain` neverallow on `mnt_vendor_file:dir`, so a system daemon may not
read it.  Since phase 5 **init copies it once per boot** to
`/data/misc/ashfall/joys.factory` (see §"Calibration, deadzones and the control
socket"), and the daemon never opens `/mnt/vendor` itself.  The built-in
defaults above are identical to the factory values, so every path (user file
-> factory copy -> built-in defaults) gives the same numbers on a stock unit.

```
left_x_center=125.0  left_x_min=-1134.0  left_x_max=1196.0
left_y_center=31.0   left_y_min=-1146.0  left_y_max=1174.0
right_x_center=82.0  right_x_min=-1121.0 right_x_max=1218.0
right_y_center=-6.0  right_y_min=-1167.0 right_y_max=1192.0
l2_min=92.0 l2_max=1266.0  r2_min=0.0 r2_max=1362.0
```

At rest the raw trigger values are *above* `l2_max`/`r2_max` (~1519/1541 on
this unit) and pulling a trigger lowers them towards the minimum, so the
daemon uses `(max - raw) / (max - min)` clamped to 0..255.

### Live transcript

Reading `/dev/ttyHS1` after sending the init frame (device running the GSI,
2026-11-13):

```
$ printf '\245\323\132\075\001\001\012\000\005\001\000\000\000\005\000\000\000\001\012' > /dev/ttyHS1
$ dd if=/dev/ttyHS1 bs=512 count=... | xxd
00000000: a5d3 5a3d ad02 0e00 0000 ef05 0506 8d00  ..Z=............
00000010: 1a00 4600 0b00 92a5 d35a 3dae 020e 0000  ..F......Z=.....
...
```

This is the evidence that the MCU path works on the GSI as soon as a host
speaks the protocol; the missing piece was only the stock userspace bridge.

## Fix

`aosp/vendor/ashfall/rp5/input/`:

* `ashfall_rp5_input.c` - daemon: opens `/dev/ttyHS1` (falls back to
  `/dev/rscom` for stock), sets 115200 8N1, sends the RP5 init frame, parses
  `CMD_STATUS` frames, applies the calibration and the user deadzones, streams
  raw samples to control-socket clients and creates/feeds a uinput gamepad
  named `AshFall RP5 Gamepad`.  It re-sends the init frame when the MCU goes
  quiet and reopens the UART if it hangs up, so a screen off/on cycle does not
  lose the gamepad.
* `calib.c` / `calib.h` - the stock `joys` format: 16 `key=float` lines,
  validation, atomic user-file writes and the user -> factory copy -> default
  load order.
* `mapping.c` / `mapping.h` - raw MCU counts -> Android axes (the inverted ADC
  direction from the stock converter) and the stick/trigger deadzones.
* `fusion.c` / `fusion.h` - GamepadFusion (#138): pad registry state,
  classification (which `/dev/input` nodes are pads), the merge/remap rules,
  property parsing and the `FUSION STATUS` text.  Free of evdev/uinput so the
  host tests cover all of it; the daemon owns the fds.
* `uinput_dev.c` / `uinput_dev.h` - the one uinput device: identity, capability
  table, `ff_effects_max` and the exact ioctl order, behind a syscall seam so
  the host tests assert them (#156).
* `control.c` / `control.h` - the `/dev/socket/ashfall_input` server
  (`GET/SET/RESET CAL`, `RAW ON/OFF`, `GRAB ON/OFF`, `FUSION ...`).
* `input.c` / `input.h` / `plat.c` / `plat.h` - shared state, logging and
  property helpers.
* `ashfall-rp5-input.rc` - runs the daemon as `system:system` with the
  `input`/`uhid` groups from `class core`, creates the control socket
  (`socket ashfall_input seqpacket 0660 system system`) and, at
  `post-fs-data`, creates `/data/misc/ashfall` and copies the factory
  calibration file out of `/mnt/vendor/persist`.
* `Android.bp` - builds it into `/system/bin`.
* `rp5/input.mk` - adds it to `PRODUCT_PACKAGES`.

SELinux: a GSI replaces `system` and `system_ext` but not the stock vendor
partition, so the policy lives in `aosp/vendor/ashfall/sepolicy/` wired through
`SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` (see `ashfall_rp5_input.te`,
`file_contexts`, `property_contexts`).  It defines the `ashfall_rp5_input`
domain, labels the binary, `/dev/ttyHS1`, `/dev/socket/ashfall_input` and
`/data/misc/ashfall`, and lets only `system_app` connect to the socket.
Phase 5 replaced the old `patches/system/sepolicy/0001` patch with this
policy directory (one source of truth, and it also covers `ashfall-hwd`).

No framework patch and no stock binary/APK is required.  The daemon is
Apache-2.0 source in this repository.

## Calibration, deadzones and the control socket (phase 5)

Contract: [`docs/phase5.md`](../phase5.md) §2.3.  Issue #60.

### Files and load order

| path | written by | contents |
|---|---|---|
| `/data/misc/ashfall/joys` | `ashfall-rp5-input` only (atomic replace via `joys.tmp` + `rename`) | user calibration |
| `/data/misc/ashfall/joys.factory` | init, once per boot (`copy` in the service rc) | copy of the stock factory file |
| built-in defaults | - | the factory values listed above |

The daemon loads **user -> factory copy -> built-in defaults** and logs the
source it used (`calibration: user file|factory copy|built-in defaults`).
`/mnt/vendor/persist/retrostation/joys` is never written.

Format (identical to stock):

```
left_x_center=125.0
left_x_min=-1134.0
left_x_max=1196.0
... (16 lines: {left,right}_{x,y}_{min,center,max}, l2_min/max, r2_min/max)
```

Validation: sticks need `min < center < max`, values within +-20000 and a
travel span of 100..40000 counts; triggers need `min < max`, values within
0..65535 and a span of at least 50.  A malformed calibration is rejected as a
whole (the previous one stays active).

### Deadzones

`persist.ashfall.pad.*`, applied in the daemon after calibration so every app
and emulator benefits:

| property | range | default | effect |
|---|---|---|---|
| `persist.ashfall.pad.deadzone_inner` | 0..30 % | 5 | radial deadzone; the remaining travel is rescaled to full |
| `persist.ashfall.pad.deadzone_outer` | 0..20 % | 3 | the last N % of travel is compressed to full scale |
| `persist.ashfall.pad.trigger_deadzone` | 0..30 % | 3 | trigger values below N % are 0, the rest is rescaled 0..255 |

Values are re-read every 250 ms and applied without restarting the daemon.

### Control socket

`/dev/socket/ashfall_input`, created by init (`seqpacket`, `0660 system
system`); one text command per packet, one reply per command:

| command | reply |
|---|---|
| `GET CAL` | `OK <source> k=v k=v ...` (`source` = `user`, `factory`, `default`; all 16 keys) |
| `SET CAL k=v ...` | `OK` after validation, atomic write and immediate apply, else `ERR <reason>` (all 16 keys required, unknown/duplicate keys rejected) |
| `RESET CAL` | `OK`; deletes the user file and re-applies factory/default |
| `RAW ON` / `RAW OFF` | `OK`; streams raw samples to that client |
| `GRAB ON` / `GRAB OFF` | `OK`; `ERR busy` when another client owns the grab |

Raw sample format: `RAW lx ly rx ry l2 r2 buttons_hex` (raw MCU counts, no
calibration or deadzone), at most 100 Hz per client.  Streaming stops by
itself when the client disconnects or sends `RAW OFF`.

### GRAB ON/OFF (phase 5 v2, docs/phase5.md §6.2)

While a client owns `GRAB ON`:

* every input already reported to Android is released (a key-up per held key,
  `0` for every non-resting axis, one `SYN_REPORT`);
* the daemon injects **nothing** into uinput for any button, stick or trigger;
* the `RAW` stream keeps carrying **every** button, including Home, Back,
  Select, Start and B, so the Controller tester can show the full pad without
  driving the focused app/game;
* the owner's disconnect is an automatic `GRAB OFF` (also when the socket is
  reset or the daemon restarts), and a second client gets `ERR busy`.

`GRAB OFF` arms a full re-report: the next MCU frame sends the current physical
button/axis state from scratch, so a button held across the transition comes
back correctly.

### RAW bit layout

`buttons_hex` is the MCU's 16-bit key word, exactly as received (`%04x`, high
nibble first).  Bit 15 is the most significant bit:

| bit | input | Linux code injected when not grabbed |
|---:|---|---|
| 15 | B | `BTN_B` (0x131) |
| 14 | A | `BTN_A` (0x130) |
| 13 | Y | `BTN_Y` (0x134) |
| 12 | X | `BTN_X` (0x133) |
| 11 | D-pad right | `ABS_HAT0X` = +1 |
| 10 | D-pad left | `ABS_HAT0X` = -1 |
| 9 | D-pad down | `ABS_HAT0Y` = +1 |
| 8 | D-pad up | `ABS_HAT0Y` = -1 |
| 7 | Back | `KEY_BACK` (0x9e) |
| 6 | Home | `KEY_HOMEPAGE` (0xac; the stock table stores `KEY_HOME` 0x66, see below) |
| 5 | Right stick click (R3) | `BTN_THUMBR` (0x13e) |
| 4 | Left stick click (L3) | `BTN_THUMBL` (0x13d) |
| 3 | Start | `BTN_START` (0x13b) |
| 2 | Select | `BTN_SELECT` (0x13a) |
| 1 | R1 | `BTN_TR` (0x137) |
| 0 | L1 | `BTN_TL` (0x136) |

The table above is exactly the array at `librsinput.so + 0x2e82c` read from
bit 15 down to bit 0; it is also what the app maps in the tester.

Example (userdebug, as root):

```sh
printf 'GET CAL\n' | nc -U -q1 /dev/socket/ashfall_input
```

### Session handling and the control socket (issues #70, #71)

The control socket is serviced **independently of the MCU session**.  The
uinput retry loop, the MCU reconnect wait and the MCU-quiet wait all call
`control_poll()` instead of `sleep(1)`, so `GET CAL`/`SET CAL` answer even when
`/dev/ttyHS1` or `/dev/uinput` is missing - exactly the situation in which they
are needed for diagnosis.

The screen-off/on path cycles the MCU's power, not the UART: `/dev/ttyHS1`
stays open and the MCU just stops sending status frames.  When no valid status
frame has arrived for 2 s the daemon now calls `session_release()`
(`session.c`) once: a key-up for every key it last reported (D-pad bits have no
key, they are HAT axes), a `0` for every axis that is not at rest, one
`SYN_REPORT`, and then marks all axes dirty (`INT_MIN`) so the next frame
re-reports them.  A button or stick held while the MCU loses power can no
longer stay logically pressed in Android.  The same release runs before a UART
reconnect.

Numeric `persist.ashfall.pad.*` values are invalid -> default, not clamped:
`deadzone_inner` outside 0..30, `deadzone_outer` outside 0..20 and
`trigger_deadzone` outside 0..30 use 5/3/3 and log the bad value once.

### On-device verification (2026-11-14, build 20261114-002243)

* **`GET CAL` with the screen off and no MCU frames** (the #70 case): the
  daemon was started against an inherited socket (`ANDROID_SOCKET_ashfall_input`,
  the init contract) with the screen off; it logged `waiting for MCU status
  frames` and still answered `GET CAL` -> `OK default <16 keys>` and an unknown
  command -> `ERR unknown command`.
* The init socket is `srw-rw---- system system u:object_r:ashfall_input_socket`
  (0660).  A plain `adb shell` client (uid 2000) is rejected by **DAC** with
  `connect: Permission denied`, not SELinux; the Parts app is the client that
  works (it runs as uid `system`).  Testing from `adb shell` therefore needs a
  system-uid helper (or root, which this build does not allow).
* **`/data/misc/ashfall/joys.factory` is NOT being used on this build**: the
  Parts Controller page reports `Source: default` although
  `/mnt/vendor/persist/retrostation/joys` exists (282 B, all 16 keys, 0666),
  the service rc (installed at `/system/etc/init/ashfall-rp5-input.rc`) has the
  `copy` line, our rc block clearly ran (`/data/misc/ashfall` exists with
  `0770 system system` and the `ashfall_data_file` label) and no `init` avc
  denials are recorded.  The daemon's own `calibration:` line is the thing to
  check after the next build (see `docs/rp5/hw.md` §8, boot logging).  A
  shell-run instance cannot tell us more: it is denied by DAC on the 0770
  directory (`cannot open .../joys.factory: Permission denied`).
* `MCU went quiet; released held input` appears in logcat after the screen
  cycle, so the quiet path (#71) runs on the device with no events to emit
  (nothing was held).

### On-device verification (2026-11-13)

The daemons are not in build `20261113-213314`; the static NDK binary was
pushed to `/data/local/tmp` and run as `shell`, with a small helper providing
the inherited listening socket (the `ANDROID_SOCKET_ashfall_input` contract
init uses).  Verified: the daemon loads the built-in defaults (that image has
no `/data/misc/ashfall`, so the init rc and the factory copy are not installed
yet), creates the `AshFall RP5 Gamepad` uinput device, opens `/dev/ttyHS1`,
and the socket answers `GET CAL` -> `OK default <16 keys>`, a partial
`SET CAL` -> `ERR missing key 'left_x_min'`, an unknown command ->
`ERR unknown command`, and `RAW ON` -> `OK`.  `SET CAL` cannot be completed on
that image because `/data/misc/ashfall` does not exist (and `shell` may not
create it); `RAW` frames went to the image's own running
`ashfall-rp5-input` service, which holds the UART (it cannot be stopped
without root).  Both are covered by the host tests and remain user steps on
the integration build.

### Calibration source (#80)

`ashfall.status.pad.cal_source` (`user`, `factory`, `default`) is published by
`ashfall-rp5-input` whenever the calibration load result changes.  This makes
the source visible in the UI and in tests even when the boot log is lost
(`stdio_to_kmsg` writes `/dev/kmsg_debug`, which only exists with
`ro.debuggable=1`), and it is the state the app's Controller page displays.

The factory-copy path was re-checked:

* the parser accepts the archived `/mnt/vendor/persist/retrostation/joys`
  byte-for-byte (its keys are in arbitrary order, minimums are negative and
  `r2_min=0.0`); a host regression test keeps that exact content
  (`test_factory_rp5` in `tests/test_input.c`);
* because a `class core` daemon can read the calibration before init's
  `post-fs-data` `copy` becomes visible (or the copy can fail silently), the
  daemon now **retries the load order every 2 s for the first minute while the
  source is `default`**.  A factory file that appears after startup is picked
  up without a restart; `SET`/`RESET CAL` still switch immediately.

### Controller labels and trigger range (#82)

**X/Y at the source.**  The stock parser was disassembled to confirm the two
things that could swap the face buttons:

* the key word is built big-endian: `w20 = data[1] | (data[0] << 8)`
  (`0x1d29c`: `ldrb w20,[sp,#0xe9]`; `0x1d2a8`: `bfi w20,w8,#8,#8`);
* the table at `0x2e82c` is walked with a mask that starts at bit 15 and
  shifts right, and is indexed by the same counter, so `table[0]` is bit 15.
  The array is
  `0x131, 0x130, 0x134, 0x133, 0x223, 0x222, 0x221, 0x220, 0x9e, 0x66,
  0x13e, 0x13d, 0x13b, 0x13a, 0x137, 0x136`, i.e. bit 12 = `BTN_X` and
  bit 13 = `BTN_Y` exactly as implemented in `mcu_keymap[]`.

So the daemon's X/Y mapping is the stock one and was **not** changed.  The
RP5's face buttons are the Nintendo layout (X top, Y left, A right, B bottom;
Retroid sells Xbox-layout replacement buttons for users who prefer the other
layout), while `ControllerTesterView.drawFaceButtons()` draws `BTN_Y` at the
top and `BTN_X` on the left.  Pressing the physical top button (X) therefore
highlights the on-screen label "Y": this is an **app drawing issue** (the
tester should draw X top / Y left to match the device), not a daemon mapping
bug.  Reported for the app worker.

**L2/R2 analog range.**  The daemon maps the MCU's unipolar trigger counts
linearly from the calibration (`trigger_map`: `(max - raw) / (max - min)`),
clamped to 0..255; at rest the raw value is above `max` (1519/1541 vs
1266/1362 on this unit) and therefore 0, and a full pull reaches 255 at the
calibrated minimum.  The tester's trigger pill uses
`ControllerTesterView.drawTrigger(..., pressed = mPressed[BTN_L2] ||
mL2 > 0.5f, ...)`: at exactly 50 % the whole pill switches from outline to
solid accent, which reads as "jumping to 100 %" even though the analog bar
keeps following the axis.  That is the likely explanation of the report and is
again an app rendering detail.

Device verification (`getevent -l` on `/dev/input/event4` while pressing the
physical buttons and triggers, plus the RAW `l2`/`r2` counts) is listed in the
checklist below; if the raw counts saturate before the physical end stop, the
calibration min/max are wrong for the unit and `SET CAL`/the wizard fixes them
(the daemon mapping itself is linear).

## GamepadFusion (phase 6, #138)

Fusion is a **mode of this daemon**, not a second daemon and not a Magisk
module.  Contract: [`docs/phase6.md`](../phase6.md) §9.8; behaviour reference:
the user's stock-app `fusiond.c` (MIT), reimplemented - its process,
permission and configuration model does not apply here.

### What it does

| fusion | uinput device | external pads |
|---|---|---|
| off (default) | `AshFall RP5 Gamepad`, `2020:3001`, phase 5 capabilities | untouched, every app sees them |
| on | `AshFall Fusion Controller`, `045e:028e`, plus `EV_FF`/`FF_RUMBLE` | opened, `EVIOCGRAB`ed, merged into the one device |

There is never a second uinput device.  The identity cannot be changed in
place, so a toggle destroys and recreates the device (a few milliseconds; the
switch is user-initiated and the app is told to re-read `FUSION STATUS`).

Merge rules, all covered by the host tests:

* **classification** - a node is a pad only if it carries `EV_KEY` with one of
  `BTN_GAMEPAD`/`BTN_SOUTH`/`BTN_A`/`BTN_TRIGGER` **and** at least one `EV_ABS`
  axis.  A motion-only node (accelerometer, `ABS_X/Y/Z`, no gamepad button) is
  probed **read-only** once to read its capabilities, then closed: never
  grabbed, never held open, so Android's sensor path keeps it exactly as
  before (the capabilities cannot be read without opening it).  Our own uinput
  device is skipped by vendor/product **and** name.
* **buttons** - union: any pad pressed means pressed.
* **axes** - per axis the most recent *non-resting* value wins, so letting go
  on one pad falls back to the other pad's deflection instead of snapping to
  centre.  Triggers are the **maximum** of the pads.
* **remap** - per pad, `DEFAULT`/`XBOX` (identity), `NINTENDO` (A<->B and
  X<->Y), `SWAP_AB`, `SWAP_XY`, `CUSTOM` (identity + the from->to map), all
  applied *before* the merge.
* **output** - deltas only, one `SYN_REPORT` per batch; the full state is
  re-reported after every topology change (pad add/remove, toggle, rescan) and
  a release goes through the same `session.c` rules as the MCU path.
* **system keys** - `KEY_BACK`/`KEY_HOMEPAGE`/`KEY_MENU`/volume/play-pause pass
  through (they are in the advertised key set; a pad code outside it is
  dropped because uinput capabilities cannot grow after creation).

Axis ranges differ per pad too: a Bluetooth pad may report sticks as -127..127
or triggers as 0..1023.  Every axis slot therefore keeps the pad's `EVIOCGABS`
range and is rescaled to the uinput range (sticks around `0`, triggers
`0..255`, hats `-1..1`); an identity range is passed through untouched, so the
MCU path and standard HID pads have no rounding drift at all.

External pads disagree on which axes are the triggers.  The daemon classifies
per pad at open time: `ABS_BRAKE`/`ABS_GAS` always win; otherwise a unipolar
`ABS_Z`/`ABS_RZ` (0..255) is a trigger and the right stick is `ABS_RX`/`ABS_RY`;
anything else keeps the right stick on `ABS_Z`/`ABS_RZ`.  This is the fresh
heuristic the contract asks for (the stock app's resting-`ABS_Z`-as-trigger bug)
and `FUSION RESCAN` re-runs it.

### Device construction (#156)

The kernel reports almost everything about a uinput device the same way:
`UI_DEV_CREATE` fails with `EINVAL`, long after the offending bit was set.  The
capability plan and the ioctl sequence therefore live in `uinput_dev.c`
(`struct uinput_dev_spec` + `struct uinput_dev_ops`) instead of the hardware
loop, and the host tests capture the sequence - a wrong bit or order fails
`make -C aosp/vendor/ashfall/rp5/input/tests check`, not the device.

The rule that cost the phase 6 build (`wave2-integ7`: `UI_DEV_CREATE failed:
Invalid argument` once per second and no fusion device at all):

```
drivers/input/misc/uinput.c, uinput_create_device():
    if (test_bit(EV_FF, dev->evbit) && !udev->ff_effects_max)
            return -EINVAL;      /* "ff_effects_max should be non-zero" */
```

`EV_FF` is only ever set together with `uinput_setup.ff_effects_max =
UINPUT_DEV_FF_EFFECTS` (16).  If a kernel refuses `EV_FF`/`FF_RUMBLE`, the
device is created again without them and logcat says
`kernel refused EV_FF/FF_RUMBLE, rumble relay off` - a pad without rumble
instead of a daemon that retries a device which can never be created.  Fusion
off is untouched: that identity advertises no force feedback at all.

The order, asserted by the tests: `EV_KEY`/`EV_ABS`/`EV_SYN` event bits, key
bits, `EV_FF` + `UI_SET_FFBIT(FF_RUMBLE)`, `UI_SET_ABSBIT` +
`UI_ABS_SETUP` per axis (ranges identical to the merge rescale, from the same
helper), `UI_DEV_SETUP` (identity + `ff_effects_max`), `UI_DEV_CREATE`.

### Settings

The **app** is the only writer (`system_app`); the daemon only reads, and
adopts a value only when the property text changes - so a `FUSION` command is
not reverted by the 250 ms poll.  A setting changed over the socket lives
until the daemon restarts unless the app also writes the property (which it
should, that is what makes it survive a reboot).

| property | value | default |
|---|---|---|
| `persist.ashfall.pad.fusion` | `0`/`1` | `0` |
| `persist.ashfall.pad.layouts` | `key=LAYOUT;key=LAYOUT` | empty |
| `persist.ashfall.pad.remap` | `key=304->305,305->304;...` | empty |

`key` is `vendor:product:connection`, lowercase hex and space-free:
`2020:3001:mcu` (the internal pad), `0b05:1b21:usb`, `0b05:1b21:bt`.  It is
stable across reboots and re-enumeration, never the enumeration order.  Two
identical pads on the same transport share that key; the second gets a `~2`
suffix (the only documented collision case - it inherits the first pad's layout
unless the app addresses the `~2` key in `persist.ashfall.pad.layouts`).
Malformed properties, unknown layout names, maps with a bad pair or a code
outside 1..`KEY_MAX` are skipped one entry at a time - never a crash, never a
half-applied file.  The property value limit is 92 bytes, so only non-default
entries are stored; the daemon logs when the serialised text would not fit.

### Control socket commands (§9.8)

| request | reply |
|---|---|
| `FUSION STATUS` | `OK FUSION <0\|1> PADS <n>` then one `PAD <key> <LAYOUT> <seen_ms> <name...>` per pad |
| `FUSION ON` / `FUSION OFF` | `OK` / `ERR <reason>` |
| `FUSION LAYOUT <key> <MODE>` | `OK` / `ERR <reason>` |
| `FUSION REMAP <key> <map>` | `OK` / `ERR <reason>` (`map` = `from->to,from->to`) |
| `FUSION RESCAN` | `OK` (fresh `/dev/input` scan) |

`<seen_ms>` is the age in ms since that pad's last event (0 = active now); the
name is the rest of the line, spaces allowed, control characters replaced by
spaces so a kernel-provided name cannot break the framing.  `GET/SET/RESET CAL`
and `RAW`/`GRAB` are unchanged, and `GRAB ON` suppresses the fusion output too
(the tester keeps working with fusion on).  An unknown command or subcommand is
`ERR unknown fusion command`; the app shows "daemon unavailable" when the
socket itself is missing, which is not a protocol error.

### Grab, hotplug and rumble

* Fusion on: every external pad is grabbed at open time (`EVIOCGRAB`), and a
  pad that arrives later is grabbed on the next scan.  Fusion off, shutdown and
  fd close release the grab explicitly, so a crash can never leave a pad
  hidden; `PC`/`QL` in `getevent` is the way to check.
* Hotplug comes from `inotify` on `/dev/input` plus a 2 s fallback scan (and
  `FUSION RESCAN`).  Every `event*` create/delete/move/attrib (and a queue
  overflow) logs `fusion: hotplug <node>, rescanning` and scans on the spot
  instead of waiting for the tick; a scan that changes the pad set logs
  `fusion: scan: N pad(s) merged`.  A pad that is unplugged mid-press releases
  its buttons on the next flush.
* Rumble: the fusion device advertises `FF_RUMBLE`; an upload is remembered
  and replayed to every pad that can take it (read-write pads only; `EVIOCSFF`
  and write errors - `ENODEV`, `EINVAL` - are logged and skipped, never fatal).
  The force-feedback events are read from our own event node; the uinput node
  is opened `O_NONBLOCK` because a blocking read on an empty queue would park in
  `uinput_read()` holding the device mutex and stop the whole loop.  Known
  limitation: the first `EVIOCSFF` upload to a pad whose transport has hung can
  still wait for the HID/BT timeout (one effect upload, never per event).

### SELinux

`ashfall_rp5_input.te` allows `input_device` dir `{ getattr search open read
watch }` and chr_file `{ getattr open read write ioctl }`.  Nothing is
relabelled: `/dev/input/*` keeps its platform label so every other domain is
unaffected (issue #120).  `/dev/uinput` and `serial_device` stay as they were.
The settings properties need no new rule - the app writes them and
`get_prop(ashfall_rp5_input, ashfall_prop)` already exists.

## Phase 5 test checklist (in addition to the one below)

1. `adb shell getprop init.svc.ashfall-rp5-input` -> `running`;
   `adb shell getprop ashfall.status.pad.cal_source` -> `factory` on a stock
   unit (or `user` after the wizard; `default` means the copy/parse failed);
   `adb shell ls -lZ /data/misc/ashfall` shows `joys.factory` and the
   `ashfall_data_file` label (or `u:object_r:ashfall_data_file:s0`).
2. `GET CAL` returns `OK factory ...` with the values above.
3. In the AshFall Parts tester, `RAW ON` shows live raw values; `GRAB ON`
   releases the gamepad and every physical button (Home/Back/Select/Start/B
   included) still appears in the RAW stream; closing the tester/its socket
   restores normal input without stuck buttons.
4. Calibration wizard: `SET CAL` with the collected values -> `OK`; sticks
   re-centre exactly.  `RESET CAL` -> `OK`, back to the factory values.
5. `adb shell setprop persist.ashfall.pad.deadzone_inner 15` -> small stick
   movements are ignored in a gamepad tester within ~1 s (no restart).
6. Enforcing build: `dmesg | grep avc` is clean after the above.

## Test checklist (after flashing)

1. `adb shell getevent -pl` or `adb shell dumpsys input | grep -i gamepad`
   shows `AshFall RP5 Gamepad`.
2. In a gamepad tester app or `adb shell getevent -l /dev/input/eventX`:
   * A/B/X/Y -> `BTN_SOUTH`/`BTN_EAST`/`BTN_NORTH`/`BTN_WEST`;
   * D-pad -> `ABS_HAT0X`/`ABS_HAT0Y` (Android `AXIS_HAT_X/Y` →
     `DPAD_UP`/`DOWN`/`LEFT`/`RIGHT`);
   * L1/R1 -> `BTN_TL`/`BTN_TR`;
   * Start/Select -> `BTN_START`/`BTN_SELECT`;
   * stick clicks -> `BTN_THUMBL`/`BTN_THUMBR`;
   * Back/Home -> `KEY_BACK`/`KEY_HOMEPAGE`.
3. Left stick -> `ABS_X`/`ABS_Y`, right stick -> `ABS_Z`/`ABS_RZ`;
   both re-center when released.
4. L2/R2 -> `ABS_BRAKE`/`ABS_GAS` (Android `AXIS_LTRIGGER`/`AXIS_RTRIGGER`),
   resting at 0 and reaching full scale.
5. Screen off/on (the MCU power is cycled by `rs_gpio`): the gamepad still
   works after wake; the daemon re-sends the init frame if the MCU asks for
   it.
6. Reboot: the service starts automatically (`adb shell getprop
   init.svc.ashfall-rp5-input` -> `running`).

## Fusion test checklist (phase 6, #138)

1. `setprop persist.ashfall.pad.fusion 1` (or `FUSION ON` from the app):
   `getevent -pl` shows `AshFall Fusion Controller` with `045e:028e`,
   `KEY_BACK`/`KEY_HOMEPAGE` and `FF`; the physical pads no longer appear as
   separate devices while they keep working through the fusion pad.  `logcat`
   shows `uinput AshFall Fusion Controller created (045e:028e, rumble)` and
   **no** `UI_DEV_CREATE failed` line (#156: with `EV_FF` set,
   `ff_effects_max` must be non-zero).
2. `FUSION STATUS` lists the internal `2020:3001:mcu` pad plus every connected
   pad with its layout and age; a pad you just pressed has age 0-50 ms.
3. `FUSION LAYOUT 0b05:1b21:usb NINTENDO` swaps A/B and X/Y live and survives
   a reboot when the app also writes `persist.ashfall.pad.layouts`; a garbage
   value (`FUSION LAYOUT <key> WAT`) is `ERR unknown layout`.
4. `FUSION REMAP <key> 304->305,305->304`: A and B swap live, `FUSION STATUS`
   shows `CUSTOM`; `RESET`-style recovery is `FUSION LAYOUT <key> DEFAULT`.
5. `setprop persist.ashfall.pad.fusion 0`: both physical pads are visible
   again **without a reboot** (`getevent -pl`) and `getevent` shows no
   lingering `PC`/`QL` grab.
6. Hotplug while fusion is on: connect/disconnect a USB and a BT pad (also
   with the screen off/on and with a button held) - no stuck button, no
   duplicate device, the new pad is grabbed and merged automatically.
7. Motion: `dumpsys sensorservice` (or a sensor app) shows the same sensors
   before and after enabling fusion, and `fusion` never opens the
   accelerometer node (`logcat` has no `fusion: pad` line for it).
8. Rumble: a rumble test in an app (or the Controller page) reaches the
   physical pad; disconnecting the pad mid-rumble does not stall input.
9. Enforcing build: `dmesg | grep avc` is clean after all of the above.
10. `make -C aosp/vendor/ashfall/rp5/input/tests check` and
    `make -C aosp/vendor/ashfall/rp5/hw/tests check` pass warning-free.

## Notes / follow-ups

* Fan and RGB are driven by `ashfall-hwd` in phase 5 (#60), see
  [`docs/rp5/hw.md`](hw.md); this daemon does not touch those nodes.
* The control socket, calibration files and deadzones are described in the
  phase 5 section above.
* Without fusion the daemon does not remap buttons; the per-pad layout/remap
  above is the fusion feature (#138).  `GameAssistant`/`RsMapping` per-game
  mapping stays a separate UX feature.
* A pad that exposes motion and buttons on the *same* node loses its motion
  while grabbed (documented limitation, #138 "Motion").  The second uinput
  motion device is only allowed after measuring such a pad.
* The app is the writer of `persist.ashfall.pad.*`; a change made over the
  socket alone lasts until the daemon restarts.
* If the stock `joys` calibration is missing or corrupt, the daemon falls back
  to the values above, so the sticks are still usable.
