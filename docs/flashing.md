# Flashing & testing a GSI on the RP5 (Phase 3 loop)

This is the operational loop for Phase 3: **flash a GSI -> let the user test ->
collect logs -> fix -> repeat**, with a one-command bail-out back to stock.

Everything here runs from this repo on the orchestrator VM. adb/fastboot never
run on this VM: the RP5 is USB-connected to the **build host** and the tools
live in the dedicated distrobox container **`ashfall-dev`**. `scripts/device/`
wraps all of that.

> **Hard rules** (they are also encoded in the scripts):
> - Never flash anything on the guide's [NEVER-flash
>   list](reference/rp5-recovery-guide.md#13-partitions-you-must-never-flash-from-someone-elses-dump)
>   (`persist`, `modemst*`, `fsg`/`fsc`, `devinfo`, `frp`/`keystore`/`secdata`/`storsec`,
>   `cdt`/`ddr`, `nvdata*`, `qpdata*`, `*_gpt_header`/`*_last_parti`, and the
>   runtime partitions `userdata`/`metadata`/`misc`).
> - Never run `fastboot getvar all` (interrupting it wedges the bootloader --
>   guide [§8.1](reference/rp5-recovery-guide.md#81-sanity-checks-first)).
> - Community firmware dumps are **read-only reference**, not a source to flash
>   device-unique data from.
> - Ask the user before changing anything on the build host outside the
>   `ashfall-dev` container (`/etc/udev/rules.d` etc.). Nothing here needs it:
>   the USB node already carries a `rw` ACL for the logged-in user.
> - **One `fastboot` session per bootloader-side operation** (issue #54). This
>   ABL wedges when a new fastboot process opens between transfers or after a
>   burst of `getvar`s. Build a single command line (`fastboot flash a x flash
>   b y ... erase userdata set_active a`), run it detached on the build host
>   with a log + exit file, and do getvars only once, before it. Kill a hung
>   fastboot with `pkill -x fastboot`, never `pkill -f` (that matches and kills
>   your own ssh/distrobox command line).
> - Use `distrobox enter --name ashfall-dev` (as the user), never `podman
>   exec`/root: root cannot see the USB device after it re-enumerates.

## 1. Layout

| Path | Purpose |
|---|---|
| `scripts/device/run.sh` | run adb/fastboot on the build host; `ensure`/`status`/`shell`/`recreate` |
| `scripts/device/lib/device.sh` | shared helpers: container, device mode/serial, getvar retries |
| `scripts/device/snapshot.sh` | read-only collector (runs on the build host) |
| `scripts/device/info.sh` | snapshot -> `out/device/<ts>/`, redacted, with `SUMMARY.md` |
| `scripts/device/flash-gsi.sh` | flash a `system.img` GSI (fastbootd + vbmeta + optional wipe) |
| `scripts/device/fix-rotation.sh` | diagnose/fix the 90-degree rotated UI after a flash without `--wipe` |
| `scripts/device/collect-logs.sh` | log collector (runs on the build host) |
| `scripts/device/logs.sh` | pull logcat/dmesg/pstore/tombstones/ANRs/bugreport |
| `scripts/device/restore-stock.sh` | bail-out: flash stock boot/vendor_boot/.../super |
| `scripts/device/redact.sed` | serial/IMEI/MAC redaction rules |

All scripts: `bash`, `set -euo pipefail`, source `scripts/lib/common.sh` +
`load_config`, support `--help`, and are idempotent. Every remote command goes
through `host_sh` (see `docs/PLAN.md` conventions) -- never a bare `ssh`.

## 2. One-time setup (build host)

```bash
scripts/device/run.sh ensure     # create ashfall-dev if missing + install adb/fastboot/usbutils
scripts/device/run.sh status     # container, tool versions, USB IDs, adb/fastboot devices, mode
```

What `ensure` does:

1. `distrobox create --name ashfall-dev --image docker.io/library/ubuntu:24.04
   --additional-flags "--device=/dev/bus/usb"` (only if the container is missing);
2. `apt-get install adb fastboot usbutils` inside it (only if `adb`/`fastboot`
   are not on `PATH` there);
3. sanity-checks `adb version` / `fastboot --version`.

Notes:

- The container name is **`ashfall-dev`**, owned by this device-tooling work.
  Do not reuse `ashfall-build` (builds) or `ashfall-fw` (firmware) -- other
  workers own those.
- Everything is inside the container; **nothing is installed on Silverblue**.
- USB access is the `--device=/dev/bus/usb` passthrough plus the desktop's
  `uaccess` ACL on the device node (`crw-rw-r--+ epickitrolaz`). If `lsusb`
  shows the RP5 on the host but `run.sh status` does not, check the ACL
  (`getfacl /dev/bus/usb/*/*`) before thinking about udev rules.
- Recovering from an interrupted first-time provisioning:
  `scripts/device/run.sh recreate` (destroys and recreates the container).
- Overrides (in `config/ashfall.local.conf` or the environment): `DEV_BOX`,
  `DEV_BOX_IMAGE`, `DEV_BOX_PACKAGES`, `FB_RETRY`, `FB_TRANSITION_TIMEOUT`
  (per-step mode-transition wait, default 180 s), `DEV_JOBS_DIR` (batch
  state/logs on the build host, default `$REMOTE_ROOT/device/jobs`),
  `DEV_FB_BATCH_TIMEOUT` (hard limit per bootloader batch, default 3600 s) and
  `DEV_FB_STALL_SECONDS` (no-log-growth timeout before `pkill -x fastboot`,
  default 420 s).

```bash
scripts/device/run.sh adb devices -l
scripts/device/run.sh fastboot getvar product      # single vars only!
scripts/device/run.sh shell                        # interactive bash in the container
```

## 3. Device modes and button combos

| Goal | How | USB ID |
|---|---|---|
| Fastboot / bootloader | from off: **Volume Down + Power** | `18d1:d00d` |
| Fastbootd (userspace) | from the bootloader: `fastboot reboot recovery`, wait for adb `recovery`, then `adb reboot fastboot`; from Android or recovery: `adb reboot fastboot` | `18d1:4ee0` |
| Recovery (adb) | `fastboot reboot recovery` from the bootloader, boot menu, or `adb reboot recovery` | `18d1:d001` |
| Android (adb + MTP) | normal boot | `18d1:4ee1` |
| EDL (Qualcomm 9008) | guide §5/§12; not used by these scripts | `05c6:9008` |
| Hard power-off | hold **Power ~15 s** | -- |

**This ABL rejects `fastboot reboot fastboot`** from the bootloader:

```
$ fastboot reboot fastboot
Rebooting into fastboot                            FAILED (remote: 'unknown command')
```

That is why `flash-gsi.sh` goes through stock recovery on the RP5. Stock
recovery shows the **"No command"** screen with the dead Android logo and its
buttons do nothing -- that is normal, not a hang: adb is running there
(`adb devices` -> `recovery`) and `adb reboot fastboot` enters fastbootd. Do not
press buttons on that screen.

`run.sh status` prints the current mode, including `fastbootd` (via
`is-userspace: yes`).

## 4. Read-only snapshot: `info.sh`

```bash
scripts/device/info.sh                 # -> out/device/<UTC timestamp>/
scripts/device/info.sh --mode android  # force a mode
scripts/device/info.sh --serial <S>    # several devices connected
```

- **Fastboot/bootloader:** individual `fastboot getvar <var>` values (never
  `getvar all`) + partition sizes/types. **Android/recovery:** `getprop`,
  `/proc/version`/`cmdline`, mounts, `/dev/block/by-name`, `lpdump`, VINTF
  manifests + HAL list, `dumpsys display`, `wm size/density`, `getevent -lp`,
  input devices, thermal/hwmon/PWM (fan), LEDs (RGB), power supply, kgsl,
  cpufreq, audio, sensors, camera, USB state.
- Runs `scripts/device/snapshot.sh` on the build host, rsyncs the result back,
  applies `scripts/device/redact.sed` (serials, IMEI/MEID/ICCID/IMSI, Android
  ID, MAC/Bluetooth) and writes `SUMMARY.md`.
- No reboot, no flash, no erase: safe to run at any time.

**Bootloader getvars are flaky.** Observed on 2026-11-12: a fresh fastboot
session intermittently answers `GetVar Variable Not found` / `unknown command`
for variables that work a second later, and hammering it eventually dropped the
device off USB. The scripts retry each variable (3x), space out the queries and
stop early when the device disappears (`< waiting for any device >`). If most
values fail: unplug/re-plug, hard power-off (15 s), re-enter fastboot.

**Never interleave getvars with writes.** On 2026-11-13 two `restore-stock.sh`
runs wedged the ABL: the first hung on `Sending 'boot_a'` right after eight
per-partition `partition-size:*` getvars, the second on `Sending
'init_boot_a'`; both needed a 15 s power-off (issue #54). A restore is now one
fastboot process, and image sizes are checked against the documented stock
table (`docs/stock/device-live/README.md`) instead of querying the bootloader.

The first live snapshot of the stock device is committed under
[`docs/stock/device-live/`](stock/device-live/) -- see its README for what was
captured and what was missed (the device was unplugged mid-session).

## 5. Flash a GSI: `flash-gsi.sh`

Always dry-run first:

```bash
scripts/device/flash-gsi.sh /path/to/system.img --dry-run
# no device at hand? still prints the plan:
scripts/device/flash-gsi.sh /path/to/system.img --dry-run --no-device
```

The dry-run performs read-only preflight (`product`, `unlocked`, `current-slot`,
image type/size) and prints every command it *would* run. Nothing is written.

Real flash:

```bash
scripts/device/flash-gsi.sh /path/to/system.img --wipe --yes
```

Options: `--slot a|b`, `--wipe`, `--vbmeta PATH`, `--keep-stock`
(alias `--keep-product`), `--serial S`, `--yes`, `--dry-run`, `--no-device`
(plan only), `--help`.

Sequence:

1. **Preflight** -- `product` must be `kona`, the bootloader must be unlocked
   (`unlocked: yes`; on Android, `ro.boot.flash.locked=0`). Anything else is a
   hard refusal.
2. **Image type** -- accepts Android sparse, **EROFS** (the A17 GSI default,
   mounted by the stock 5.15 GKI kernel) and ext4 (fallback). The filesystem
   magic is checked; sparse images are unpacked by fastbootd.
3. **Reach fastbootd** -- this ABL rejects `fastboot reboot fastboot`
   (`FAILED (remote: 'unknown command')`), so the routes are:
   - **bootloader** -> `fastboot reboot recovery` -> wait for adb state
     `recovery` -> `adb reboot fastboot` -> wait for `is-userspace: yes`;
   - **Android / recovery** -> `adb reboot fastboot` -> same wait.

   Both waits are bounded by `FB_TRANSITION_TIMEOUT` (default 180 s each) and
   fail closed with the last observed mode and a manual recovery hint. From the
   bootloader the device passes through the stock recovery's "No command"
   screen -- expected, adb is alive there.
4. **Space budget** -- `super` is split into two slot groups of 6.0 GiB
   (`qti_dynamic_partitions_a/_b`); stock uses ~4.6 GiB (`system` 1.39,
   `vendor` 1.78, `product` 0.875, `system_ext` 0.49, `vendor_dlkm` 0.065,
   `system_dlkm` 0.0004, `odm` 0.001 GiB; lpdump in PR #10, `feat/stock-fw-analysis`).
   The script computes
   `image + vendor + odm + product + system_ext + system_dlkm + vendor_dlkm`
   against the 6.0 GiB budget and prints the arithmetic. Android-sparse images
   are budgeted by their **expanded** size (`total_blks * blk_sz` from the
   sparse header), not by the on-disk file size.
   **Phase 7 default:** the stock `product_<slot>` and `system_ext_<slot>` are
   deleted even when the image would fit, because the GSI bundles its own
   product/system_ext and the stock partitions carry Retroid/Google apps
   (Calendar, Chrome, DeskClock, SnapdragonMusic, QtiSoundRecorder, GMS).
   `--keep-stock` (alias `--keep-product`) restores the old space-only
   behaviour and keeps those apps. `vendor_<slot>`/`odm_<slot>` and the dlkm
   partitions are never touched. If the image is too large even after the
   deletions, the run refuses before writing anything. The budget is
   overridable with `ASH_GROUP_MAX_BYTES` (default `6442450944`).
   `restore-stock.sh` brings the stock super back. With `--keep-stock` and a
   deficit, `--dry-run` prints `would REFUSE` and exits non-zero (the real run
   dies before any deletion). The target slot is validated to be exactly `a` or
   `b`, including when it came from the device-reported `current-slot`.
5. **Flash `system`** -- `fastboot --slot <slot> flash system <img>` (fastbootd
   resizes the logical partition; fastboot sparse-splits large images using
   `max-download-size`).
6. **vbmeta** -- the stock `vbmeta` from `$REMOTE_FIRMWARE`
   (`lun4_vbmeta_<slot>.bin` / `vbmeta.img`) is flashed with
   `--disable-verity --disable-verification`. If no stock image exists, a
   **256-byte empty vbmeta with flags=3** (hashtree + verification disabled) is
   generated with python3 and flashed instead. Either way AVB is off for the
   unsigned GSI -- without this the bootloader refuses to boot it.
7. **Wipe (optional)** -- `fastboot erase userdata` and `fastboot erase
   metadata` (vold regenerates metadata-encryption keys on the next boot).
   Runs in the bootloader, not fastbootd. This script only rewrites `system`
   inside super, so the erase stays in the seconds range (0.09 s observed
   2026-11-13); the ~351 s case is a physical super rewrite in
   `restore-stock.sh`.
8. **Slot** -- `set_active <slot>` when the target is not the current slot
   (takes effect on the next boot).

The bootloader steps (7 + 8) are merged into **one detached `fastboot`
process** with a log + exit file (issue #54). Do not insert a separate
fastboot/getvar call between them; that is what wedges the ABL.

Hard blocks: every partition name is checked against the NEVER-flash list before
any write. The script only writes `system` and `vbmeta`; it deletes the logical
`product_<slot>`/`system_ext_<slot>` by default (phase 7 debloat; `--keep-stock`
keeps them) and erases `userdata`/`metadata` only with `--wipe`.
`vendor_<slot>` and `odm_<slot>` are never touched.

Logs: `out/device/<ts>/flash-gsi.log`.

### Post-flash step: rotation after a flash **without** `--wipe`

A no-wipe flash keeps the stock `/data`, and stock `.31` can leave
`Settings.System user_rotation=1` (auto-rotate off) behind. AshFall's panel is
natively landscape, so that inherited lock rotates the UI another 90 degrees:
portrait UI on a landscape handheld. **Do not reflash and do not wipe for it** -
one settings write fixes it, and the script refuses to write anything if the
signature does not match:

```bash
# read-only: SF property, wm size/density, both settings, display_size_forced,
# the rotation lines of dumpsys window displays / dumpsys display
scripts/device/lock.sh run <owner> -- scripts/device/fix-rotation.sh
# one-time fix: wm user-rotation lock 0, only when all four preconditions hold
scripts/device/lock.sh run <owner> -- scripts/device/fix-rotation.sh --apply
scripts/device/fix-rotation.sh --self-test   # offline decision table, no adb
```

`flash-gsi.sh` prints these two lines in its summary when `--wipe` was not used
(it never runs them and never waits for boot). Preconditions for the write:
`ro.surface_flinger.primary_display_orientation=ORIENTATION_90`, effective
`wm size` 1920x1080, `accelerometer_rotation=0`, `user_rotation=1`. Any other
combination is a refusal that prints the next diagnostic step. Rationale,
offline evidence from the stock images and the measured before/after format are
in [`docs/rp5/display.md`](rp5/display.md#no-wipe-flash-from-stock).

### Timings on this device

| Step | Time |
|---|---|
| small partitions | seconds |
| `super` (12 GiB stock) | ~90 s (7 sparse chunks, ~19 s each via `max-download-size`) |
| `fastboot erase userdata` | **~351 s after a physical super flash**, **0.09 s otherwise** (observed 2026-11-13, issue #54) |
| `flash system` (2--3 GiB GSI) | 1--3 min |
| whole `restore-stock.sh` batch (super + wipe + set_active) | **~492 s** (issue #54) |

### Common failures

| Symptom | Meaning / fix |
|---|---|
| `getvar ... FAILED (remote: 'GetVar Variable Not found')` on everything | flaky/wedged fastboot session -- retries, then hard power-off 15 s and re-enter fastboot |
| `fastboot devices` works but commands hang (especially right after `Sending ...` or a getvar burst) | wedged bootloader -- this is issue #54: use one fastboot process per bootloader operation, then hard power-off 15 s and re-enter fastboot |
| batch says `no output for 420s` | script killed the hung fastboot with `pkill -x fastboot`; the ABL may still need a 15 s hard power-off |
| `Rebooting into fastboot  FAILED (remote: 'unknown command')` | this ABL has no `reboot fastboot`: from the bootloader run `fastboot reboot recovery`, wait until `adb devices` shows `recovery`, then `adb reboot fastboot` |
| `Not enough space to resize partition` | the budget was mis-read (flaky getvar) or `--keep-product` is set; re-run or free `product_<slot>`/`system_ext_<slot>` |
| image is EROFS | supported (stock 5.15 kernel); ext4 is only a fallback |
| `device did not enter fastbootd` | check the screen; from the bootloader run `fastboot reboot recovery`, confirm `adb devices` shows `recovery`, then `adb reboot fastboot`; if nothing helps, hard power-off 15 s |
| `FAILED (remote: 'Device is locked')` | bootloader not unlocked -- do not try to bypass it |
| flash succeeds, Android bootloops | boot to recovery, then `scripts/device/logs.sh`; bail out with `restore-stock.sh` |
| UI is portrait / 90 degrees rotated after flashing **without** `--wipe` | inherited stock `Settings.System user_rotation=1` on a landscape panel - `scripts/device/fix-rotation.sh --apply` (do not wipe, do not reflash) |
| `adb: unauthorized` | accept the prompt on the device (user builds); userdebug GSIs with `ro.adb.secure=0` have no prompt |

### GPU OC vendor_boot (`flash-vendor-boot.sh`)

The phase-6 GPU OC lives in the `vendor_boot` DTB.  `vendor_boot` and `vbmeta`
are **non-slotted** on the RP5, so the script never passes `--slot` for them
(the active-slot guard is still read once).  It also follows issue #54:

* **Flash the content-only image**, not the 96 MiB zero-padded partition image.
  A padded `Sending 'vendor_boot'` wedges the ABL; the phase-6 content-only
  image is 12,931,072 B and flashes in ~0.3 s.
* The build emits `vendor_boot.img` (content-only) and
  `vendor_boot-padded.img` (partition fit proof/backup).  The flash script
  trims any padded image it is given to the v4 header's `content_end` before
  the batch; `--stock` trims the stock `lun4_vendor_boot_*.bin` the same way.
* The whole write is one detached `fastboot` batch (`dev_fastboot_batch`):
  `--disable-verity --disable-verification flash vbmeta <stock>` (no `--slot`)
  → `flash vendor_boot <content-only>` (no `--slot`) → `reboot`.  There are no
  `partition-size` getvars and no getvars between transfers.

```bash
scripts/device/flash-vendor-boot.sh <artifact>/vendor_boot.img --dry-run
scripts/device/flash-vendor-boot.sh <artifact>/vendor_boot.img --yes
scripts/device/flash-vendor-boot.sh --stock --yes   # last-resort recovery only
```

Do **not** restore stock between OC debug variants: flash the next variant over
the failed one.  See `docs/rp5/gpu.md` §2/§5 for the 8-entry RPMh limit and the
10-minute 1 GHz stability result.

## 6. Phase 3 user-test checklist

Have the user confirm, in this order (it maps to the risks in `docs/PLAN.md`):

1. Boots to the launcher; no "internal problem with your device" dialog (the
   VINTF target-level risk -- check the vintf files in the snapshot).
2. Display: correct rotation (panel is natively portrait; after a flash without
   `--wipe` the inherited stock rotation lock is fixed with
   `scripts/device/fix-rotation.sh --apply`), touch alignment, brightness
   slider, no tearing, HDMI out if used.
3. Gamepad: sticks, triggers, face buttons, MCU/mode switch, mapping.
4. Wi-Fi + Bluetooth (scan/connect); USB MTP + adb.
5. Audio: speakers, headphone jack, mic; volume keys.
6. Battery/charging, sleep/wake, power button.
7. Thermals + fan: `thermal.txt`/`hwmon.txt` from `info.sh`; fan responds.
8. RGB LEDs: `leds.txt` (existence + settable brightness -- Phase 5 builds the UI).
9. Sensors: accelerometer/gyro (auto-rotate), hall sensor.
10. Storage: internal + microSD.
11. Camera(s), if the SKU has them.
12. Performance sanity: `cpufreq.txt`, `kgsl.txt`, no throttling at idle.

For anything broken: capture a snapshot (`info.sh`) **and** logs (`logs.sh`) and
attach the redacted summary to the issue.

## 7. Logs: `logs.sh`

```bash
scripts/device/logs.sh                # -> out/device/<ts>-logs/
scripts/device/logs.sh --bugreport    # + full adb bugreport (slow, large)
scripts/device/logs.sh --redact       # scrub serials/IMEI/MAC before sharing
```

Collects: `logcat -b all` (main/system/crash/kernel), properties, dmesg
(+`su -c dmesg` attempt), `/proc/last_kmsg`, `/sys/fs/pstore`, tombstones, ANRs,
dropbox `system_app_anr`/`system_server_crash`, `activity exit-info`, boot
reason, USB/adb configuration, and `adb pull` of `/data/tombstones`,
`/data/anr`, `/sys/fs/pstore` (permission failures are recorded in the files).

### Early-boot adb on a broken (userdebug) GSI

adbd starts from init as soon as Android's first stage is up, so a bootlooping
GSI is still reachable:

- `ro.adb.secure=0` (userdebug/eng) -> no "Allow USB debugging" prompt, adb just
  works;
- `persist.sys.usb.config=adb` (or `sys.usb.config`) keeps the adb USB function
  enabled without unlocking the device -- set it once from a working build, or
  rely on the userdebug default;
- on a **user** build adbd needs the on-device prompt: `adb devices` shows
  `unauthorized` until the user accepts. The scripts report that instead of
  hanging.

`out/` is gitignored: logs may contain personal data. Never commit them without
`--redact`.

## 8. Bail-out: `restore-stock.sh`

Flashing a broken GSI back to stock, per guide
[§8](reference/rp5-recovery-guide.md#8-procedure-a-reflash-android-over-fastboot):

```bash
scripts/device/restore-stock.sh --dry-run                 # plan + size checks
scripts/device/restore-stock.sh --wipe --yes              # flash stock
scripts/device/restore-stock.sh --images DIR --slot both --wipe --yes   # explicit
```

Images are read from `$REMOTE_FIRMWARE/img` when it exists (the extracted tree
from the firmware analysis), else `$REMOTE_FIRMWARE`; override with `--images`.
The slot defaults to `auto`: both slots when both have images, otherwise the
slot whose images are present (with a warning).

What it flashes (both slots when available): `boot`, `init_boot`, `vendor_boot`,
`dtbo`, `recovery`, `vbmeta`, `vbmeta_system`, `vm-linux`, `vm-system`
(optional ones are skipped with a warning when their image is missing), plus
`splash` and `super`. `--no-super` keeps the current system; `--no-splash`
skips splash; `--skip-size-check` disables the image-vs-partition check (only
when the mismatch is understood).

It must run in the **bootloader's fastboot** (not fastbootd) and refuses
otherwise. No step needs fastbootd: `super` is the 12 GiB **physical** partition
and the stock flow flashes it from the bootloader (`fastboot flash super
lun0_super.bin`, guide §8.3); the dynamic/logical partitions inside it are only
relevant to `flash-gsi.sh`. `fastboot reboot fastboot` is not used here (this
ABL rejects it anyway). Accepted image names: `lun4_<part>_<slot>.bin`,
`lun0_<part>_<slot>.bin`, `<part>_<slot>.img`, `<part>.img`, `<part>.bin`.

**One fastboot session for the whole restore** (issue #54). Everything --
every flash, `erase userdata`/`erase metadata` (with `--wipe`), `set_active`
and `reboot` -- becomes a single `fastboot` command line, started detached on
the build host (`setsid nohup distrobox enter --name ashfall-dev ...`) with a
log and an `exit` file. The command returns immediately and the script follows
the log; an SSH drop does not kill the restore. Image sizes are checked against
the documented stock partition table, not `partition-size:*` getvars, so the
only getvars are the three preflight reads (`mode`, `product`, `unlocked`)
before the batch. If the log stops growing for `DEV_FB_STALL_SECONDS` (default
420 s, above the 351 s userdata erase) or the hard `DEV_FB_BATCH_TIMEOUT`
(default 3600 s) is hit, the script runs `pkill -x fastboot` inside the
container and tells you to hard power-off the ABL; it never uses `pkill -f`.

`--wipe` erases userdata in **~351 s after a physical super flash** (the common
case here), and in ~0.09 s when super was not rewritten (observed 2026-11-13).
Do not interrupt a long `Erasing 'userdata'`; the log + exit file makes waiting
safe.

It never touches `devinfo`, `persist`, radio/NV data or any other device-unique
partition. If the device is in the "U-Boot / Loader Mode" state described in the
guide (§9), that is a `devinfo` problem and needs the EDL procedure -- this
script does not do EDL.

## 9. What is *not* automated (on purpose)

- EDL flashing/readback (`edl`, firehose) -- high risk, needs USB 2.0 passthrough
  and the exact loader (guide §12).
- `devinfo` cleaning -- device-unique (guide §9).
- Flashing device-unique partitions, firmware sets, or `abl`/`loader` outside
  the stock restore path.
- Anything that reboots the device on its own outside an explicit flash run.
  `info.sh` and `logs.sh` never reboot.
