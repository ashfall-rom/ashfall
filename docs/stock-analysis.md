# Stock RP5 firmware analysis for an Android 17 (LineageOS 24.0) GSI

Issue: [#2](https://github.com/Epickitrolaz/ashfall/issues/2) · Phase 1 research.
Target: Retroid Pocket 5 (SM8250 "kona"), stock Android 13, unlocked bootloader.

**Bottom line:** an Android 17 GSI can boot on the *stock* vendor. The vendor
declares VINTF `target-level="7"` and the Android 17 tree still ships the
level-7 compatibility matrix, so the runtime VINTF check has no version
mismatch. The stock kernel is a **5.15.148 GKI kernel** (not 4.19) with every
kernel feature Android 17 asks for. The real blockers are operational, not
architectural: **only 1.4 GiB is free in `super`**, AVB is fully enabled, and
display rotation / touch IDC / fan-RGB control live in the stock *system*
partition that a GSI replaces.

All facts below come from the stock firmware and are reproducible with
`scripts/firmware/*`; the extracted text artefacts are in `docs/stock/`.

---

## Method

```bash
scripts/firmware/fetch.sh      # 7z.001..005 -> $REMOTE_FIRMWARE/dl (resumable)
scripts/firmware/extract.sh    # images, super unpack (lpunpack.py), ramdisk, vbmeta, dumps
scripts/firmware/inspect.sh    # docs/stock/: props, VINTF, fstab, kernel, FCM report
```

Everything runs on `epickitrolaz@100.66.37.14` inside the `ashfall-fw` distrobox
container (Ubuntu 24.04, `p7zip`, `lz4`, `cpio`, `debugfs`, `erofs-utils`,
`device-tree-compiler`). Nothing is installed on the immutable Fedora host and
no other worker's container is touched. No binary image is committed.

Firmware: `STOCK_FW_URL`, release `Android13_RP5_V1.0.0.31_20241026_190102_user`
(GammaSqueeze dump). Archive verified with `7z t` (15,742,679,582 bytes
unpacked, 3,118,121,176 packed).

| fact | value |
|---|---|
| vendor fingerprint | `qti/kona/kona:13/TKQ1.240621.001/RP510261930:user/release-keys` |
| system fingerprint (QSSI) | `qti/qssi/qssi:13/TKQ1.240621.001/RP510261838:user/release-keys` |
| vendor build id / incremental | `TKQ1.240621.001` / `eng.RP5.20241026.193331` |
| security patch | 2024-07-05 |
| device / board | `kona` / `SM8250` (Adreno 650) |

Note: the stock system is a **QSSI** image (`ro.product.system.device=qssi`)
with a kona vendor. That is the Treble split a GSI expects.

---

## 1. Vendor VINTF

Source: `/vendor/etc/vintf/manifest.xml` (copied to
[`docs/stock/vintf/manifest.xml`](vintf/manifest.xml)) plus 32 fragments under
`/vendor/etc/vintf/manifest/` (see [`vintf-files.txt`](vintf-files.txt)).

* Manifest: `<manifest version="5.0" type="device" target-level="7">`
* 54 `<hal>` entries in the main manifest, 42 more in the fragments.
* All HALs are HIDL except the AIDL ones the QSSI build already adopted
  (`android.hardware.power`, `health`, `drm`, `light`, `vibrator`, `keymint`,
  `identity`, `bluetooth.audio`, `wifi.*`, `memtrack`).

Android 17 (`android17-release` of `hardware/interfaces`) ships framework
compatibility matrices for levels **7, 8, 202404, 202504, 202604, 202704**
(checked in the `compatibility_matrices` directory). Therefore
`target-level="7"` selects the level-7 FCM at runtime and the "internal
problem" path is not taken.

Full comparison in [`fcm-report.md`](fcm-report.md):

* **Version mismatches against FCM 7: none.** Every stock vendor HAL version is
  inside the level-7 allowed ranges.
* **55 vendor HALs are not listed in any Android 17 matrix** — mostly QTI
  extensions (`vendor.qti.hardware.*`, `vendor.display.*`, IMS, `com.dsi.ant`)
  and two AIDL ones (`vendor.qti.hardware.bttpi`,
  `vendor.qti.hardware.wifi.supplicant`). They are ignored with a warning;
  since Android 16 the `optional` attribute has no runtime effect and HAL
  presence is a VTS concern, not a boot blocker.
* AIDL `android.hardware.power` v3 and `bluetooth.audio` v2 are accepted at
  level 7 but `ver!` at level 8+. This would only matter if the vendor declared
  target-level 8; it declares 7.

The vendor also carries a device compatibility matrix
(`/vendor/etc/vintf/compatibility_matrix.xml`) requiring `vendor-ndk` 33 and
`system-sdk` 33 from the framework, plus the HIDL `android.frameworks.*`,
`android.hidl.*` and `android.system.wifi.keystore` HALs. **Consequence:** the
A17 GSI must still provide the VNDK 33 vendor snapshot/compat libraries those
vendor binaries link against (see risk 5).

---

## 2. Vendor properties

Source: [`props-vendor.txt`](props-vendor.txt) (`/vendor/build.prop`),
[`props-system.txt`](props-system.txt), [`props-of-interest.txt`](props-of-interest.txt).

| property | value | why a GSI cares |
|---|---|---|
| `ro.vendor.build.version.sdk` | `33` | vendor API level |
| `ro.vendor.build.version.release` | `13` | |
| `ro.vndk.version` | `33` | required VNDK level (also in the device matrix) |
| `ro.board.api_level` | `33` | board API |
| `ro.board.first_api_level` | `33` | board first API |
| `ro.product.first_api_level` | `33` | product first API → `ro.vendor.api_level` = 33 |
| `ro.boot.dynamic_partitions` | `true` | GSI flashing goes through fastbootd |
| `ro.virtual_ab.enabled` | `true` | |
| `ro.crypto.volume.metadata.method` | `dm-default-key` | metadata encryption v2 |
| `ro.crypto.dm_default_key.options_format.version` | `2` | |
| `ro.crypto.volume.filenames_mode` | `aes-256-cts` | |
| `ro.hardware.egl` / `ro.hardware.vulkan` | `adreno` | vendor GL/VK drivers |
| `ro.opengles.version` | `196610` | GLES 3.2 |
| `ro.sf.lcd_density` | `360` | panel density |
| `ro.surface_flinger.has_wide_color_display` | `true` | HDR/wide colour panel |
| `ro.surface_flinger.has_HDR_display` | `true` | |
| `ro.surface_flinger.use_color_management` | `true` | |
| `ro.surface_flinger.running_without_sync_framework` | `true` | *system* prop, SF sync config |
| `persist.sys.force_sw_gles` | `1` | *system* prop, stock forces software GLES in some cases |
| `persist.panel.orientation` | `90` | *system* prop, stock rotation hint |
| `ro.treble.enabled` | `true` | *system* prop |
| `ro.product.vendor.model` | `Retroid Pocket 5` | |
| `ro.product.vendor.manufacturer` | `Moorechip` | |

`ro.surface_flinger.primary_display_orientation` is **not set** in stock; the
stock framework/panel stack uses `persist.panel.orientation=90` (see §5).

---

## 3. Kernel

The stock boot image contains a 5.15 kernel with embedded IKCONFIG. This
contradicts the "kernel 4.19" assumption in `docs/PLAN.md` — 4.19 is the
*community device tree's* kernel, not the stock one.

```
Linux version 5.15.148-android13-8-gbfe94e95c709-dirty
```

Source: [`kernel-version.txt`](kernel-version.txt),
[`kernel-config.txt`](kernel-config.txt) (extracted from the Image with
`scripts/firmware/ikconfig.py`), [`kernel-modules.txt`](kernel-modules.txt).
The vendor_boot ramdisk ships 222 `.ko` modules whose `vermagic` is
`5.15.148-gbfe94e95c709-dirty`, consistent with the boot kernel.

| Android 17 need | config in stock kernel | status |
|---|---|---|
| EROFS | `CONFIG_EROFS_FS=y` (+zip, xattr, security) | yes |
| F2FS + casefold | `CONFIG_F2FS_FS=y`, `CONFIG_UNICODE=y`, F2FS compression/zstd/lz4 | yes |
| eBPF | `CONFIG_BPF_SYSCALL=y`, `CONFIG_BPF_JIT=y`, `CONFIG_BPF_JIT_ALWAYS_ON=y`, `CONFIG_CGROUP_BPF=y`, `CONFIG_DEBUG_INFO_BTF=y` | yes |
| binder | `CONFIG_ANDROID_BINDER_IPC=y`, `CONFIG_ANDROID_BINDERFS=y` | yes |
| cgroups v2 / memory | `CONFIG_CGROUPS=y`, `CONFIG_MEMCG=y`, `CONFIG_MEMCG_SWAP=y` | yes |
| PSI | `CONFIG_PSI=y` | yes |
| io_uring | `CONFIG_IO_URING=y` | yes |
| userfaultfd | `CONFIG_USERFAULTFD=y` | yes |
| metadata encryption | `CONFIG_DM_DEFAULT_KEY=y`, `CONFIG_DM_VERITY=y` (+FEC) | yes |
| incremental FS / overlayfs | `CONFIG_INCREMENTAL_FS=y`, `CONFIG_OVERLAY_FS=y` | yes |
| zram / fscrypt | `CONFIG_ZRAM=m`, `CONFIG_FS_ENCRYPTION(_INLINE_CRYPT)=y` | yes |
| SELinux | `CONFIG_SECURITY_SELINUX=y` | yes |

**Verdict for the kernel: no blockers.** The community tree's
`ro.bpf.kver_override=5.10.239` hack exists because *their* build ships a 4.19
kernel; on the stock 5.15 vendor it is unnecessary.

---

## 4. Partitions, fstab, AVB, boot headers

Source: [`super-lpdump.txt`](super-lpdump.txt),
[`fstab-vendor-ramdisk.txt`](fstab-vendor-ramdisk.txt),
[`vbmeta.txt`](vbmeta.txt), [`boot-headers.txt`](boot-headers.txt),
[`dtbo-table.txt`](dtbo-table.txt).

### super (12 GiB, logical block 4096, LP metadata 10.2)

| group | max | used | free |
|---|---|---|---|
| `qti_dynamic_partitions_a` | 6.0 GiB | 4.6 GiB | **1.4 GiB** |
| `qti_dynamic_partitions_b` | 6.0 GiB | 0 | 6.0 GiB |

| partition (slot A) | size | notes |
|---|---|---|
| `system_a` | 1.39 GiB | QSSI system, ext4 |
| `system_ext_a` | 498 MiB | stock Settings/SystemUI/launcher |
| `product_a` | 896 MiB | |
| `vendor_a` | 1.78 GiB | all QTI vendor blobs |
| `odm_a` | 1.2 MiB | |
| `vendor_dlkm_a` | 64.7 MiB | vendor kernel modules |
| `system_dlkm_a` | 428 KiB | GKI modules (5.15.148) |

`slot_b` logical partitions exist in metadata but are all **zero-sized**; this
is an A-only install. There is no free space beyond group A's 1.4 GiB unless
the super metadata is repacked (group B's 6 GiB are unreachable from group A).

### fstab (first stage, from the vendor_boot ramdisk)

```
system      /system      ext4  ... wait,slotselect,avb=vbmeta_system,logical,first_stage_mount,avb_keys=/avb/q-gsi.avbpubkey:/avb/r-gsi.avbpubkey:/avb/s-gsi.avbpubkey
system_ext  /system_ext  ext4  ... avb=vbmeta_system,logical,first_stage_mount
product     /product     ext4  ... avb=vbmeta_system,logical,first_stage_mount
vendor      /vendor      ext4  ... avb,logical,first_stage_mount
vendor_dlkm /vendor_dlkm ext4  ... avb=vbmeta,logical,first_stage_mount
system_dlkm /system_dlkm ext4  ... avb=vbmeta,logical,first_stage_mount
odm         /odm         ext4  ... avb,logical,first_stage_mount
metadata    /metadata    f2fs  ... first_stage_mount
userdata    /data        f2fs  ... fileencryption=...,metadata_encryption=aes-256-xts:wrappedkey_v0,checkpoint=fs
```

All dynamic partitions are **ext4** (the GSI system image must be ext4, not
erofs, even though the kernel supports erofs). The fstab already allows
Google-signed GSIs (`avb_keys=/avb/{q,r,s}-gsi.avbpubkey`) — the vendor was
Treble/GSI certified.

### AVB

* `vbmeta_a`: AVB 1.0, algorithm 2 (SHA256_RSA4096), **flags 0x0** (verity and
  verification enabled), chain descriptor for `vbmeta_system` (rollback
  location 2), hash descriptors for `boot`, `dtbo`, `init_boot`, `recovery`,
  `vendor_boot`, hashtree descriptors for the vendor side.
* `vbmeta_system_a`: AVB 1.0, algorithm 1 (SHA256_RSA2048), rollback index
  `1720137600` (2024-07-05), hashtree descriptors for `system`, `product`,
  `system_ext`.

A custom GSI is not signed with Retroid's key nor with the q/r/s GSI keys, so
verification must be disabled when flashing (see risk 2).

### Boot chain

| image | header | contents |
|---|---|---|
| `boot_a` | v4 | kernel 55,667,200 B (5.15.148), **no ramdisk** |
| `init_boot_a` | v4 | 2,010,376 B lz4-legacy generic ramdisk |
| `vendor_boot_a` | v4 | 9,725,209 B lz4-legacy vendor ramdisk + 3,184,830 B DTB + 124 B bootconfig |
| `dtbo_a` | dt_table | 18 DTBs (big-endian table, 4,263,184 B) |

Bootconfig passed by the stock vendor_boot:
`androidboot.hardware=qcom`, `androidboot.memcg=1`,
`androidboot.usbcontroller=a600000.dwc3`,
`androidboot.load_modules_parallel=true`. The vendor_boot cmdline also carries
`bootconfig buildvariant=user` and `cgroup.memory=nokmem,nosocket`.

The GPT header dumps (`lun0/lun4_gpt_header.bin`) are zero-filled placeholders;
the partition table lives in `last_parti` and is not needed for this analysis.

---

## 5. Display, input, MCU, fan, RGB

### Display

* The panel is physically portrait; the stock system exposes
  `persist.panel.orientation=90` (system/build.prop).
* Stock framework relies on that + the QTI composer. There is **no**
  `ro.surface_flinger.primary_display_orientation` property in stock.
* The community A17 tree adds, in `vendor.prop`:

  ```properties
  debug.sf.ignore_hwc_physical_display_orientation=true
  ro.surface_flinger.primary_display_orientation=ORIENTATION_90
  ```

  A GSI must replicate these from the system side (bake them into the GSI's
  system build.prop) or the UI will come up rotated/cropped.

### Touch / input

* Vendor keylayout: `vendor/keylayout/gpio-keys.kl` only.
* Stock system carries the touch IDC/keylayout under `/system/usr/idc` (the
  community tree explicitly copies `fts_ts.idc` into `/vendor/usr/idc`), i.e.
  a GSI loses it unless the IDC is included in the GSI or in `/vendor`.
* Gamepad: kernel `gpio-keys` + the MCU; the stock mapping/overlay UX lives in
  system apps (see below). Generic input should still enumerate on a GSI.

### MCU / fan / RGB

The vendor provides **drivers, permissions and firmware only**; the control
logic is a stock *system* app (`RPSettings`), which a GSI replaces.

| feature | interface | provider |
|---|---|---|
| fan | `/sys/devices/virtual/fan_info/fan_info_data/{duty,state}` | kernel `fan_info` driver (built-in), exposed by vendor init |
| RGB side LEDs | `/sys/class/sn3112l/led/{enable,brightness,vled_enable,blackvalue}`, `/sys/class/sn3112r/led/{enable,brightness}` | `sn3112` LED driver; vendor init chmods 0666 |
| RGB (generic) | `/sys/class/leds/{red,green,blue}/brightness` | kernel LED class |
| backlight | `/sys/class/gpio5_pwm2/{duty,en_backlight,state}`, `/sys/class/backlight/panel0-backlight/*` | kernel/vendor init |
| MCU | `vendor/firmware/mcuapp_firmware.bin`, `GameAssistant`/`RsMapping` apps | vendor firmware + stock system apps |

The sysfs paths were recovered from the stock `RPSettings.apk`
([`fan-rgb-sysfs.txt`](fan-rgb-sysfs.txt) — generated by `inspect.sh`).

**Stock system apps a GSI loses** (`system-apps.txt`, `system_ext-apps.txt`):
`RPSettings` (fan/RGB/backlight), `GameAssistant` (per-game mapping/overlay),
`RsMapping`, `rp3launcher` (Retroid launcher), `colorservice`,
`PerformanceMode`, `PowerSaveMode`, plus stock `Settings`/`SystemUI`/
`Launcher3QuickStepEX` from `system_ext`. Phase 5 ("base system tools") must
reimplement fan/RGB/gamepad mapping on top of the sysfs nodes above.

### Vendor services

174 `service` entries under `/vendor/etc/init`
([`init-vendor-services.txt`](init-vendor-services.txt)), including the QTI
display composer/allocator, perf, power, thermal, sensors, wifi/bluetooth, USB,
keymint, gatekeeper. These stay with the stock vendor when only `system` is
replaced by the GSI.

---

## 6. Comparison with the community A17 device tree

Sources: `Ayn8250Dev/android_device_{retroidpocket_RP5,ayn_sxr2130p-common}` and
`android_kernel_ayn_sxr2130p`, branch `lineage-24.0`; copies in
[`docs/stock/community/`](community/).

| item | community A17 tree | stock vendor (this firmware) | GSI implication |
|---|---|---|---|
| VINTF | own manifest, `target-level="7"`, 23 HALs + qcom-caf matrices | 96 HAL entries, `target-level="7"` | level 7 matches; no VINTF blocker |
| API level | `BOARD_SHIPPING_API_LEVEL := 30` | board/api/first = 33 | stock is *newer*; fine |
| kernel | builds `kernel/ayn/sxr2130p` **4.19.325** | stock **5.15.148** GKI | GSI on stock vendor keeps 5.15; no BPF override |
| super | 12 GiB, dynamic group 6,438,256,640 B (~6.0 GiB) | 12 GiB, group A 6.0 GiB, used 4.6 GiB | same geometry; GSI must fit 1.4 GiB |
| filesystems | ext4 system/vendor/product/system_ext/odm | ext4, same | GSI system must be ext4 |
| AVB | test key RSA4096, `--flags 3` when `WITH_AVB != true` | Retroid key, flags 0 | GSI needs verification disabled |
| early boot | boot header v2 + DTB in boot, own recovery | boot v4, init_boot v4, vendor_boot v4 | GSI keeps stock boot/init_boot/vendor_boot |
| rotation | `ro.surface_flinger.primary_display_orientation=ORIENTATION_90`, `debug.sf.ignore_hwc_physical_display_orientation=true` | only `persist.panel.orientation=90` | **must replicate in GSI** |
| BPF | `ro.bpf.kver_override=5.10.239` | n/a (5.15) | not needed |
| SELinux | `androidboot.selinux=permissive` | enforcing | GSI bring-up may need permissive |
| graphics | `persist.sys.force_sw_gles=1`, `supports_background_blur=1`, sync props | stock has `force_sw_gles=1`, no blur prop | verify blur/HWC props in GSI |
| input | copies `fts_ts.idc`, `mcuapp_firmware.txt` to vendor | vendor keylayout only | include IDC in GSI |
| audio/BT/wifi props | large QTI vendor.prop | stock vendor has them | kept by stock vendor |

The community tree is a **full ROM** (it replaces vendor too and builds a 4.19
kernel). We only replace `system` with a GSI, so the stock 5.15 kernel and all
vendor blobs/HALs stay. The parts it still has to teach the system are:
rotation/display props, touch IDC, blur/HWC knobs, and optionally permissive
SELinux for bring-up.

---

## 7. Verdict and ranked risks

**An Android 17 GSI can boot on the stock RP5 vendor.** VINTF, kernel features
and partition/fstab format are all compatible. The stock system is a QSSI
image, which is exactly the Treble layout a GSI expects.

Ranked risks for Phase 2/3, with mitigations:

1. **`super` free space (high).** Only 1.4 GiB free in the A group; a
   LineageOS 24 GSI `system.img` will likely be larger. `fastboot flash system`
   will fail with "not enough space". Mitigations: measure the built GSI first;
   build a slim GSI; or repack `super` with a larger group A (group B's 6 GiB
   are unused — an `lpmake` super that keeps the stock partitions and grows
   group A to e.g. 10 GiB, flashed as a whole `super`). Do not shrink
   `vendor`/`product`/`system_ext`.
2. **AVB verification (high, easy fix).** `vbmeta` flags are 0 and
   `vbmeta_system` has hashtrees over `system`. Flash with verification
   disabled (`fastboot --disable-verity --disable-verification flash vbmeta
   vbmeta.img`, plus `fastboot -w`). The bootloader is unlocked, so this is
   expected to work; document the "orange state" warning for the user.
3. **Display rotation (high UX).** Bake
   `ro.surface_flinger.primary_display_orientation=ORIENTATION_90` and
   `debug.sf.ignore_hwc_physical_display_orientation=true` into the GSI system
   image (and keep `persist.panel.orientation=90`).
4. **Touch/input IDC (medium).** Include `fts_ts.idc` (and the gpio keylayout
   if needed) in the GSI or a vendor overlay; otherwise touch axes/rotation are
   wrong.
5. **VNDK 33 / vendor ABI (medium).** The vendor DCM requires vendor-ndk 33 and
   system-sdk 33. Confirm the built GSI still provides the VNDK 33 vendor
   snapshot/compat libraries; otherwise vendor binaries (camera, sensors,
   display) can fail to load. This is a GSI build-config item, not a stock
   blocker.
6. **SELinux (medium).** An A17 system policy on an A13 vendor will produce
   denials; expect to need permissive boot during bring-up (the community tree
   sets `androidboot.selinux=permissive`). The stock boot chain does not pass
   that flag, so plan a modified boot/vendor_boot or a permissive-compatible
   policy for the first bring-up.
7. **Blur/HWC performance (low).** The community tree advertises
   `ro.surface_flinger.supports_background_blur=1`; stock vendor does not.
   Enable/verify in the GSI if the UI enables blur.
8. **Fan/RGB/MCU/gamepad UX (low for boot, high for usability).** Drivers and
   sysfs nodes stay, but the stock `RPSettings`/`GameAssistant`/`RsMapping`
   apps are lost. Phase 5 must build fan/RGB/button-mapping tools against the
   sysfs interfaces listed above.
9. **VINTF noise (low).** 55 QTI HALs are unknown to the A17 matrices; they are
   ignored with warnings. No version mismatch at level 7. The GSI will still
   log "not in compatibility matrix" lines.
10. **Filesystem choice (low).** Keep the GSI `system` image **ext4**; the
    stock fstab mounts ext4 and the vendor's fstab does not list erofs for
    `system`.

### Corrections to the current plan

* `docs/PLAN.md` says "kernel 4.19". The **stock** kernel is 5.15.148. Only the
  community ROM builds 4.19. On a GSI-over-stock-vendor approach the 5.15
  kernel stays, which removes the EROFS/BPF concerns from the plan.
* The plan's "only FCM 7+" VINTF risk is real but benign here: the vendor
  declares 7 and Android 17 still ships FCM 7.
* Rotated panel and lost stock system apps are confirmed; treat them as
  expected work, not surprises.

---

## Artefact index (`docs/stock/`)

| file | contents |
|---|---|
| `super-lpdump.txt` | groups, partition sizes, free space |
| `fstab-vendor-ramdisk.txt`, `fstab-first-stage-mount.txt` | stock first-stage fstab |
| `vbmeta.txt` | AVB flags, chain descriptors, hash/hashtree partitions |
| `boot-headers.txt`, `boot-header.txt`, `init_boot-header.txt`, `vendor_boot-header.txt`, `bootconfig.txt` | boot/init_boot/vendor_boot v4 headers, dtb, bootconfig |
| `dtbo-table.txt` | 18 DTBs in the dtbo partition |
| `props-vendor.txt`, `props-system.txt`, `props-of-interest.txt` | build.prop material |
| `vintf/` | stock vendor manifest + fragments + device compatibility matrix |
| `fcm-report.md` | vendor manifest vs Android 17 FCM levels |
| `init-vendor-services.txt`, `init-vendor-rc-files.txt`, `init-system-services.txt` | init service inventory |
| `mcu-fan-rgb.txt`, `fan-rgb-sysfs.txt` | MCU/fan/RGB references and sysfs nodes |
| `kernel-version.txt`, `kernel-config.txt`, `kernel-modules.txt` | stock 5.15.148 kernel facts |
| `*-apps.txt` | stock system/system_ext/product/vendor app inventory |
| `community/` | A17 device-tree files used for comparison |
