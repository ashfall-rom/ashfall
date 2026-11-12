# Live RP5 snapshots (read-only captures)

These are raw, **redacted** outputs of `scripts/device/info.sh` taken from the
real device, kept so later phases can diff hardware state (partitions, VINTF,
thermal zones, LEDs, input devices) against a known-good stock baseline.

Never commit an unredacted snapshot. `scripts/device/redact.sed` removes
serials, IMEI/MEID/ICCID/IMSI, Android ID and MAC/Bluetooth addresses before a
tree lands here.

## 20261112T213932Z — first capture (fastboot / bootloader mode)

| | |
|---|---|
| taken | 2026-11-12 21:39:44 UTC |
| host | build host (`epickitrolaz@100.66.37.14`), container `ashfall-dev` |
| mode | `fastboot` (bootloader, `is-userspace` unknown) |
| adb | no device (Android was not running) |
| tools | adb 1.0.41 / fastboot 34.0.4-debian |
| USB | `18d1:d00d` on bus 1 (`Google Android`, device 009) |

What the device reported (individual `fastboot getvar`, never `getvar all`):

| var | value |
|---|---|
| `product` | `kona` |
| `variant` | `SM8 UFS` |
| `off-mode-charge` | `1` |
| `snapshot-update-status` | `none` |
| `partition-type:*` | `raw` (userdata: `ext4`) |
| `partition-size:boot_b` | `0x6000000` (96 MiB) |
| `partition-size:init_boot_b` | `0x800000` (8 MiB) |
| `partition-size:vendor_boot_b` | `0x6000000` |
| `partition-size:dtbo_b` | `0x1800000` (24 MiB) |
| `partition-size:recovery_b` | `0x6400000` |
| `partition-size:vbmeta_b`, `vbmeta_system_b` | `0x10000` (64 KiB) |
| `partition-size:abl_b` | `0x100000` |
| `partition-size:loader_b` | `0xA00000` |
| `partition-size:imagefv_b` | `0x200000` |
| `partition-size:vm-linux_b` | `0x2000000` |
| `partition-size:vm-system_b` | `0x8000000` |
| `partition-size:userdata` | `0x1A21ED3000` (~104.5 GiB) |

### Why this capture is partial

The bootloader's `getvar` implementation on this unit is intermittently flaky:
fresh sessions return `GetVar Variable Not found` or `unknown command` for
variables (`product`, `unlocked`, `current-slot`, sizes) that answer correctly a
second earlier. In this capture the `product`/`variant` queries succeeded, then
most later ones failed, and at the end of the run the device left USB
(`usb 1-3: USB disconnect` in the host kernel log, 2026-11-12T21:40:34Z). No
flash/erase/reboot was performed — the collector is read-only.

This is why `scripts/device/snapshot.sh` and the getvar helpers retry 3x, space
out queries, and abort early on `< waiting for any device >`. The on-device
values that *did* answer match the reference guide (§19) for `kona`.

### Refreshing

With the RP5 connected to the build host in any mode:

```bash
scripts/device/info.sh                 # -> out/device/<timestamp>/
# review out/device/<timestamp>/SUMMARY.md, then copy the redacted text tree:
cp -a out/device/<timestamp> docs/stock/device-live/<timestamp>
```

Prefer capturing in Android mode once stock Android boots: that adds display,
input, thermal/fan, LED, VINTF and HAL data that fastboot cannot provide.

## Files

- `_meta.txt` — tool versions, USB IDs, `adb devices` / `fastboot devices`
- `SUMMARY.txt` — collector summary
- `fastboot/getvars.txt` — individual getvars (with failures recorded)
- `fastboot/partition-sizes.txt` — `partition-size:*` / `partition-type:*`
- `fastboot/logical.txt` — `is-logical:*` probes
- `fastboot/devices.txt` — `fastboot devices -l` (serial redacted)
