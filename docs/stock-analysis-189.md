# RP5 `1.0.0.189` full OTA vs `1.0.0.31` — analysis and Phase 5 reference

Status: 2026-11-13. After the safety check
([issue #57 comment](https://github.com/Epickitrolaz/ashfall/issues/57#issuecomment-6864298164))
the decision is: **AshFall stays on the pre-release `.31` base** (Android 13 vendor, API 33,
VNDK 33, VINTF target-level 7, 5.15 GKI). This document records why `.189` is not a drop-in
upgrade, and the Retroid system-side fan/RGB/MCU interfaces that `.189` changes for Phase 5.
No Retroid binaries are committed; the text evidence is in
[`docs/stock-189/`](stock-189/README.md).

## TL;DR

* `.189` is **not a newer `.31`**. It is the retail branch: an Android 13 QSSI system on an
  **Android 11 vendor** (API 30, VNDK 30, VINTF target-level 5) with a **4.19.157** kernel
  and a legacy header-v2 boot image. `.31` is Android 13 vendor/API 33/VNDK 33/level 7 with
  a 5.15.148 GKI kernel.
* The OTA from `.31` is a one-way branch switch. It updates `boot`, `dtbo`, five dynamic
  partitions, `vbmeta`, `vbmeta_system`, `xbl`, `xbl_config`; it does **not** update
  `init_boot`, `vendor_boot`, `recovery`, `vendor_dlkm`, `system_dlkm`, ABL or the rest of
  the firmware. It also reformats `/metadata` f2fs→ext4, which loses the data-encryption
  keys.
* Phase 5 is unaffected by the decision: fan control is effectively identical on both
  (`gpio5_pwm2`, same modes, same smart curve). RGB and MCU differ (`.189` uses `htr3212`
  side LEDs and a newer MCU firmware), but on our `.31` device only the `.31` nodes exist.

## 1. Why AshFall stays on `.31`

### 1.1 Branch comparison

| | `.31` (current base) | `.189` (retail OTA) |
|---|---|---|
| system | QSSI Android 13, SDK 33 | QSSI Android 13, SDK 33 (`RP511281728`) |
| vendor | **Android 13**, SDK 33, first API 33 | **Android 11**, SDK 30, first API 30 (`RP511281803`) |
| VNDK | 33 | **30** |
| VINTF target-level | 7 | **5** |
| vendor security patch | 2024-07-05 | 2023-09-01 |
| kernel | **5.15.148**-android13-8 GKI | **4.19.157-perf** |
| boot format | header v4: `boot` (no ramdisk) + `init_boot` + `vendor_boot` | header v2: kernel + gzip ramdisk + DTB inside `boot` |
| dynamic group | `qti_dynamic_partitions_a/_b`, 7 partitions each, 6 GiB | OTA group `qti_dynamic_partitions` (becomes `_b`), **5 partitions**, 6 GiB |
| `/metadata` fstab | f2fs | **ext4** (`formattable`) |
| OTA footprint | — | includes `xbl` + `xbl_config` |

The system partition is ext4 in both, so the "GSI must be ext4" rule still holds. The
problem is the vendor/kernel side: Android 17 ships compatibility matrices for levels 7, 8,
202404, …, so `.189`'s level-5 vendor has no matching A17 FCM and its VNDK 30 vendor ABI is
older than the one the current AshFall GSI was built against. (See
[`fcm-summary-189.txt`](stock-189/fcm-summary-189.txt).)

### 1.2 Payload layout

`payload.bin` is a normal full payload (`minor_version=0`, `partial_update=false`,
`snapshot_enabled=true`) with **11 partitions**:

| partition | size | notes |
|---|---:|---|
| `boot` | 96.0 MiB | 4.19.157-perf, header v2, own ramdisk + DTB |
| `dtbo` | 8.0 MiB | 14 DTBs (`.31` had 18) |
| `odm` | 1.0 MiB | |
| `product` | 889.4 MiB | ext4 |
| `system` | 1.3 GiB | ext4 |
| `system_ext` | 508.5 MiB | ext4 |
| `vbmeta` | 8 KiB | AVB 1.0, flags `0x0`, rollback_index **0** |
| `vbmeta_system` | 4 KiB | AVB 1.0, flags `0x0`, rollback_index **1720137600** |
| `vendor` | 1.8 GiB | ext4, Android 11 vendor |
| `xbl` | 3.3 MiB | **bootloader firmware** |
| `xbl_config` | 92 KiB | **bootloader firmware** |

Full manifest: [`payload-manifest.txt`](stock-189/payload-manifest.txt).

### 1.3 What slot B gets

The target dynamic group is `qti_dynamic_partitions` with `max_size=6,438,256,640` (6 GiB)
and only **five** members: `odm`, `product`, `system`, `system_ext`, `vendor` (≈4.3 GiB).
update_engine appends the target slot suffix, so slot B gets those five partitions.

* **No `vendor_dlkm`, no `system_dlkm`** in the payload or the target group. `.189`'s own
  first-stage fstab does not mount them, so `.189` is self-consistent — but `.31`'s fstab
  (in `init_boot`/`vendor_boot`) does mount them. Which fstab wins depends on the boot flow.
* `.189`'s `vbmeta` only signs `boot`, `dtbo`, `recovery`, `vendor` (no `init_boot` /
  `vendor_boot`), and its boot image is a self-contained v2 image with its own ramdisk,
  fstab and DTB. That strongly suggests the `.31` GKI `init_boot`/`vendor_boot` are inert
  during `.189` boot, but this could not be verified without the device.
* **Not in the payload, so slot B keeps whatever is there:** `init_boot`, `vendor_boot`,
  `recovery`, `vendor_dlkm`, `system_dlkm`, and all firmware except `xbl`/`xbl_config`:
  `abl`, `aop`, `modem`, `dsp`, `bluetooth`, `tz`, `hyp`, `keymaster`, `cmnlib*`, `devcfg`,
  `featenabler`, `imagefv`, `multiimgoem`, `multiimgqti`, `qupfw`, `uefisecapp`, `spunvm`.
* `.189`'s `vbmeta` carries a hash for `recovery`, but `recovery` is not in the payload.
  If slot B's recovery is not the `.189` one, recovery fails AVB (normal boot is unaffected)
  — relevant if a factory reset is needed.

### 1.4 Kernel / KMI

```
.189 boot.kernel: Linux version 4.19.157-perf
.31  boot.kernel: Linux version 5.15.148-android13-8-gbfe94e95c709-dirty
.31  modules:     vermagic=5.15.148-gbfe94e95c709-dirty (222 .ko in vendor_boot/vendor_dlkm)
```

There is no shared KMI and no GKI. `.189` ships its own 39 modules (4.19) in
`/vendor/lib/modules`; `.31`'s 5.15 modules cannot load on the 4.19 kernel. The answer to
"is the `.189` kernel compatible with the existing `.31` `vendor_boot`?" is **no** — the
`.189` boot image must be the only kernel/module source, which its fstab/vbmeta layout
supports but which also means the pre-release GKI stack is discarded.

### 1.5 AVB, rollback and XBL

* `vbmeta`: rollback_index **0**, same as `.31`.
* `vbmeta_system`: rollback_index **1720137600**, exactly `.31`'s value.
* Post security patch is still **2024-07-05**. **No AVB rollback-index increase.**
* **But `xbl` and `xbl_config` are in the payload.** Qualcomm PBL anti-rollback is not
  visible in vbmeta: if `.189`'s XBL has a higher ARB version than the fuses, the update
  burns them and older firmware can no longer boot. ABL is *not* updated, so the result is
  a mixed `.31`-ABL + `.189`-XBL bootloader. Treat the update as one-way.

### 1.6 Data and metadata

`.31` formats `/metadata` as **f2fs**; `.189`'s fstab wants **ext4** with `formattable`.
On the first `.189` boot fs_mgr reformats metadata, the metadata-encryption keys are lost,
and `/data` cannot be decrypted → "Encryption unsuccessful"/factory-reset screen. The OTA
does not wipe, but the user must expect a data wipe.

### 1.7 Verdict

The package is internally consistent for the retail `.168 -> .189` branch, and a local
update from `.31` would probably be accepted (same release key, `pre-device=kona`, no
`pre-build`, no AVB rollback bump). It is **not a patch for `.31`**: it is a branch switch
that replaces the vendor generation, kernel and boot format, loses data, and invalidates
the A17 GSI baseline. AshFall stays on `.31`. If `.189` is ever needed, treat it as a
one-way move with an EDL/QFIL fallback and a fresh GSI bring-up.

## 2. Phase 5 interface comparison

### 2.1 Fan

Unchanged in practice. Both firmwares use `/sys/class/gpio5_pwm2/{state,duty,speed}`
(duty in ns, period 50,000 ns), the same five UI modes (Off, Quiet, Sport, High, Smart),
the same mode→duty values (Quiet 5,000; Sport 25,000; High = `fan_speed`, default 25,000)
and the same 5-second smart loop over thermal zones 0/1/3/4. `.189` adds an optional
0..100 % scale for High (25,000..35,000 ns) and keeps the fan at 10 % below 5 °C instead of
stopping it. Driver difference only: `.31` uses `gpio5_pwm.ko` from `vendor_dlkm`; `.189`
builds the same driver into the 4.19 kernel. Full values and curve in
[`fan-rgb-189.txt`](stock-189/fan-rgb-189.txt).

### 2.2 RGB / side LEDs

* `.31` (our device): `sn3112l` / `sn3112r` class nodes plus
  `/sys/class/leds/{red,green,blue}/brightness`. `brightness` takes
  `"<mode>-<r>:<g>:<b>"` (mode 1 = static colour, channels scaled by
  `led_light_brightness_percent`, default 1.0).
* `.189`: DTBO has `htr3212l@3c` / `htr3212r@3c`, the 4.19 kernel has the HTR3212L/R
  driver built in, and there are **no `sn3112` strings in the kernel Image**. RPSettings
  still writes the old `sn3112` paths and init still chmods them (stale); init drops the
  generic `leds/{red,green,blue}` chmods and adds `breath`. `.189` also adds a separate
  handle-light colour and changes the default brightness to 0.5.

=> Implement Phase 5 RGB against the **`.31` `sn3112` + `leds` nodes** and enumerate them
live. Do not port `.189`'s `htr3212` assumptions.

### 2.3 MCU / gamepad

Same architecture: MCU on `/dev/ttyHS1` at 115200 8N1, frame
`A5 D3 5A 3D | seq | cmd | len(le16) | payload | xor`; `pservice` (in `/system/bin`,
started by vendor init) bridges `/dev/rsinput`/`/dev/rscom`/`/dev/uinput`; `RsMapping.apk`
carries `librsinput.so`. The calibration file is still
`/mnt/vendor/persist/retrostation/joys`, plain `key=float` (16 keys: left/right x/y center/min/max, L2/R2 min/max), read by the
library with a built-in default.

Differences: `.189`'s `pservice`, `librsinput.so`, `RsMapping.apk` and MCU firmware all
changed; `.189` adds a touch-calibration activity and `persist.mapping.gamepad.frame`, and
ships an 18,656 B MCU firmware (`apptime=20241101_175602`) vs `.31`'s 17,992 B. Details and
hashes in [`mcu-gamepad-189.txt`](stock-189/mcu-gamepad-189.txt). AshFall keeps the `.31`
MCU firmware; the GSI-side `ashfall-rp5-input` bridge remains the replacement for the
stock `pservice`.

### 2.4 Init, props and USB

* `.189` system adds `persist.panel.rds.orientation=270`; both keep
  `persist.panel.orientation=90` and neither sets
  `ro.surface_flinger.primary_display_orientation` → the GSI display props are still needed.
* `.189` `common.rc` adds `chmod 666 /sys/class/dp_hpd/control/hpd`; otherwise same
  `pservice`/`rsgpio`/`rstouch` setup.
* `.189` `init.qcom.usb.sh` adds a `vendor.usb-gadget-hal-1-0` running guard and changes
  some SoC defaults; both still set `persist.vendor.usb.config` to `diag,adb,…` on non-user
  builds, so AshFall's USB rc (`persist.vendor.usb.config=adb`) remains required.
  Diff: [`usb-diff.txt`](stock-189/usb-diff.txt), init: [`init-rc-diff.txt`](stock-189/init-rc-diff.txt).
* `.189` launcher is `RetroidLauncher` (`.31` has `rp3launcher`); `RPSettings`,
  `GameAssistant` and `RsMapping` are still in `/system/app`, so a GSI still loses the same
  Retroid UI.
* Touch/panel hardware is unchanged (focaltech; `.189` builds `CONFIG_TOUCHSCREEN_FTS=y`
  into the 4.19 kernel, `.31` uses `focaltech_fts.ko`). Neither firmware ships a touch IDC,
  so the existing GSI IDC workaround stays relevant.

App lists: [`apps-189.txt`](stock-189/apps-189.txt); prop/package evidence:
[`props-of-interest-189.txt`](stock-189/props-of-interest-189.txt),
[`package-diff.txt`](stock-189/package-diff.txt).

## 3. Follow-ups for AshFall

* **Config:** `config/ashfall.conf` keeps `STOCK_FW_URL` / `STOCK_FW_RELEASE_PAGE` on
  `.31`. Do not replace them with `.189`. If a second base is ever supported, add separate
  `.189` keys instead of changing the `.31` contract.
* **Restore tooling:** `scripts/device/restore-stock.sh` and the one-session restore remain
  `.31`-only. `.189` cannot be restored with them: it needs payload extraction plus the
  `xbl`/`xbl_config` writes, and the bootloader mix makes it an EDL/QFIL-class operation.
* **Phase 5:** implement fan on `gpio5_pwm2`; RGB on the `.31` `sn3112` + `leds` nodes;
  reuse the `joys` calibration format in the GSI MCU bridge; do not adopt `.189`'s
  `htr3212` or newer MCU firmware.
* **GSI fixes:** no change to `aosp/vendor/ashfall/rp5/*` for the `.31` base. The display
  props, USB rc and input daemon remain valid. A move to `.189` would invalidate the
  vendor API/VNDK/FCM assumptions and require a fresh bring-up.
* **Docs:** add a cross-link from `docs/stock-analysis.md` to this document (outside this
  PR's owned paths).

## 4. Reproduction

The OTA was unpacked and parsed locally (payload images, boot ramdisk, ext4 images) with a
small stdlib-only update_engine parser kept in `/tmp` and not committed; the extracted
images were deleted after the analysis. The repository helpers were used for the parts they
cover: `bootimg.py`, `ikconfig.py`, `imgmeta.py`, `fcm_report.py`, and the text dumps were
produced with `debugfs`/`dtc`. `scripts/firmware/inspect.sh` now accepts `--workdir DIR` so a
second staged firmware can be inspected with the same pipeline. All raw text excerpts are in
[`docs/stock-189/`](stock-189/README.md).
