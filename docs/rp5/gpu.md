# RP5 GPU: Adreno 650 overclock via the vendor_boot DTB

Phase 6, issue #112. Contract: [`docs/phase6.md`](../phase6.md) §1–§5.

The ROM boots the **stock 5.15.148 Android GKI** (`5.15.148-android13-8-gbfe94e95c709`)
and keeps the stock vendor modules.  The Adreno 650 frequency/voltage table is
data in the **`vendor_boot` DTB** (`qcom,kgsl-3d0@3d00000` →
`qcom,gpu-pwrlevel-bins`), not in the kernel image: `gpucc-sm8250.ko` and
`clk-qcom.ko` program the GPU PLL for whatever rates the DT describes, and
`msm_kgsl.ko` enforces the table.  The overclock is therefore a **DT data
change**, and nothing else here replaces or patches a kernel module.

| piece | path |
|---|---|
| DTB/repack tooling | `scripts/gpu/build-vendor-boot.sh`, `scripts/gpu/dtb_oc.py`, `scripts/gpu/vb_image.py` |
| flash/restore | `scripts/device/flash-vendor-boot.sh` |
| daemon cap | `aosp/vendor/ashfall/rp5/hw/gpu.c`, `gpu.h` (wired into `ashfall_hwd.c`) |
| SELinux | `aosp/vendor/ashfall/sepolicy/` (`ashfall_hwd.te`, `file_contexts`, `genfs_contexts`) |
| host tests | `make -C aosp/vendor/ashfall/rp5/hw/tests check` |

## 1. Stock facts

| item | value |
|---|---|
| GPU | `Adreno650v3` (`qcom,chipid = <0x6050002>`), governor `msm-adreno-tz` |
| kernel | 5.15.148 GKI, stock `msm_kgsl.ko` / `gpucc-sm8250.ko` / `clk-qcom.ko` |
| stock steps | `305 / 400 / 441.6 / 490 / 525 / 587 / 670 MHz` |
| stock top | 670 MHz (boot frequency 305 MHz) |
| devfreq cap node | `/sys/class/kgsl/kgsl-3d0/devfreq/max_freq` (0660 root:root) — the daemon opens the **resolved** path, see §4.2 |
| frequency list | `/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies` (0444) — resolved path, §4.2 |
| live clock | `/sys/class/kgsl/kgsl-3d0/gpuclk` (0444) |
| vendor_boot | `/var/home/epickitrolaz/ashfall/firmware/img/lun4_vendor_boot_a.bin` |

Stock image hashes (build `20261114-led109-bf3c952`, firmware `lun4_vendor_boot_a.bin`):

```
vendor_boot  c6e497410af928da86344edf8ffb491651fc79b3a82c130403249b48a1c6d2f4
dtb payload  eed9fed6daaa9252624a5780bd08ea45c547072981fb8e2c48b315dba77d19e8
```

### 1.1 The dtb payload is seven FDTs, not one

The `vendor_boot` header (v4) points at a `dtb` blob of 3,184,830 bytes.  That
blob is a **concatenation of seven board DTBs**, selected by the bootloader from
`qcom,msm-id` / `qcom,pmic-id`:

| # | msm-id | pmic-id | speed bins | role |
|---|---|---|---|---|
| 0 | 0x224 0x20001 | – | 0,1,2,3,4 | kona v1 variant |
| 1 | 0x164 0x10000 | 1e 20 1f 21 00 00 | 0,1,2,3,4 | kona |
| 2 | 0x164 0x20001 0x1e1 0x20001 | 1e 20 1f 21 00 00 | 0,1,2,3,4 | **RP5 (active)** |
| 3 | 0x164 0x10000 | 1e 20 1f 21 00 24 | 0,1,2,3,4 | kona |
| 4 | 0x164 0x20001 0x1e1 0x20001 | 1e 20 1f 21 00 24 | 0,1,2,3,4 | kona |
| 5 | 0x1e1 0x20000 | – | 0,1,2,3,4 | kona v2 variant |
| 6 | 0x1e1 0x00000 | – | legacy node only | no `qcom,gpu-pwrlevel-bins` |

The **active** DTB is #2: the live device reports
`qcom,msm-id = <0x164 0x20001>` and `qcom,pmic-id = <0x1e 0x20 0x1f 0x21 0 0>`
(`od /proc/device-tree/qcom,msm-id`).  The tooling transforms **every FDT** that
has the bins node and leaves #6 byte-identical, so a bootloader that picks a
different variant still gets a coherent table.

Each 670-top DTB has **five** speed-bin tables, including `qcom,gpu-pwrlevels-4`
(speed-bin 4), which the phase-6 contract did not list but which has the same
670 MHz top.  All 670-top tables get the final 8-entry table; `-2` (speed-bin
3, 525 MHz top) stays stock.

### 1.2 Voltage corners

`qcom,level` selects the GPU rail corner through the RPMh/regulator level
mapping.  Stock uses 64–320; the higher corners are defined for the rail but
unused by the stock table:

| `qcom,level` | name | used by |
|---|---|---|
| 64 (`0x40`) | LOW_SVS | 305 MHz |
| 128 (`0x80`) | SVS | 400 MHz |
| 144 (`0x90`) | SVS_L0 | 441.6 MHz |
| 192 (`0xc0`) | SVS_L1 | 490 MHz |
| 224 (`0xe0`) | SVS_L2 | 525 MHz |
| 256 (`0x100`) | NOM | 587 MHz |
| 320 (`0x140`) | NOM_L1 | 670 MHz (stock top) |
| 352 (`0x160`) | NOM_L3 | **765 MHz (OC)** |
| 384 (`0x180`) | TURBO | **850 / 940 MHz (OC)** |
| 416 (`0x1a0`) | TURBO_L1 | **1000 MHz (OC)** |

## 2. OC table

The final table is a **replacement** table, not an extension.  Every 670-top
speed-bin table (`-0`, `-1`, `-3`, `-4`) becomes:

| MHz | Hz (DT) | `qcom,level` | corner | `qcom,acd-level` | bus freq/min/max |
|---|---|---|---|---|---|
| 1000 | `0x3b9aca00` | 416 (`0x1a0`) | TURBO_L1 | `0x802b5ffd` | 11/11/11 (`0x0b`) |
| 940  | `0x38074300` | 384 (`0x180`) | TURBO    | `0x802b5ffd` | 11/11/11 |
| 850  | `0x32a9f880` | 384 (`0x180`) | TURBO    | `0x802b5ffd` | 11/11/11 |
| 765  | `0x2d98f940` | 352 (`0x160`) | NOM_L3   | `0x802b5ffd` | 11/11/11 |
| 670  | `0x27ef6380` | 320 (`0x140`) | NOM_L1   | stock | stock |
| 587  | `0x22fce8c0` | 256 (`0x100`) | NOM      | stock | stock |
| 305  | `0x122dee40` | 64 (`0x40`)   | MIN      | stock | stock |
| 0    | `0x0`        | —            | off      | —     | 0/0/0 |

The stock **525, 490, 441.6 and 400 MHz steps are dropped**.  This is required
by the RPMh GPU DCVS vote count (issue #116): `a6xx_build_rpmh_tables()` builds
`num_pwrlevels + 1` votes and rejects the table with `-ERANGE` /
`"Defined more GPU DCVS levels than RPMh can support"` when that exceeds the
RPMh `gfx.lvl` table.  Stock has 8 entries → 9 votes and boots; the old
12-entry table → 13 votes and failed before any rail level was set.  The final
8-entry table keeps the vote count at the stock 9.

Edit rules (enforced by `scripts/gpu/dtb_oc.py`, which refuses to guess):

* Apply to every speed-bin table whose stock top is **670 MHz**
  (`-0`, `-1`, `-3`, `-4`).  `-2` (525 MHz top) must stay byte-identical.
* Keep the table strictly descending; the `0 MHz` off entry stays last.
* Build the target table from the four OC steps plus the stock steps that are
  not dropped, then **renumber `reg`** from 0.
* `qcom,initial-pwrlevel` points at the 305 MHz boot entry (index 6).
  `qcom,throttle-pwrlevel` keeps the stock value (bin 0: 0; other bins: 1)
  because the dropped steps are above the LMh throttle mask's bottom entries.
* Do **not** touch the legacy `qcom,gpu-pwrlevels` node (and therefore DTB #6,
  which has no bins node), and do not change the parent
  `qcom,kgsl-3d0/qcom,initial-pwrlevel`.
* Other entry properties (bus values, acd-level of the lower stock steps) are
  carried over verbatim.

The resulting active table is:

```
1000 940 850 765 670 587 305 0 MHz
```

`gpu_available_frequencies` lists exactly that, and `max_gpuclk`/the devfreq
top become 1000 MHz.  The default **cap** stays 670 MHz (see §4).

### 2.1 Runtime result (issue #116)

- 940@384 with the 8-entry count booted: GPU present, `gpu_model=Adreno650v3`,
  `gpu_available_frequencies=940/670/587/525/490/441.6/305`, no GMU bind error.
- The final 8-entry 1000@416 image is stable: 10-minute stress at **1000 MHz,
  92–99% busy, 599,980 ms in `trans_stat` at 1000**, peak 77.5/70.1 °C,
  `thermal_pwrlevel=0`, no throttle, no GPU fault/panic, no app crash/ANR;
  auto-stop returned 305 MHz.
- An additional dmesg line is `failed to update frequency from PM QoS (-71)`;
  it does not affect scaling (the cap and the 1000 MHz step work).  It is
  tracked separately in #116.

`build-vendor-boot.sh` has debug-only `--extra-step MHz:LEVEL` /
`--drop-step MHz` overrides for probing new steps.  The default build is the
final 8-entry table above; debug artifacts use a `vendor-boot-dbg-*` id and the
manifest records `debug.extra_steps` / `debug.drop_steps`.  On failure, flash
the next variant directly over the broken one (no `--stock` between attempts).

## 3. Repack pipeline

`scripts/gpu/build-vendor-boot.sh` is host-side (no Android build).  It rsyncs
this checkout to `$BUILD_HOST`, runs there, and pulls the artifacts back:

```bash
scripts/gpu/build-vendor-boot.sh                 # stock firmware image
scripts/gpu/build-vendor-boot.sh --stock /path/to/lun4_vendor_boot_b.bin --id test
```

Steps:

1. `unpack_bootimg.py --format=info/mkbootimg` records the stock header and the
   exact repack arguments.
2. `dtb_oc.py split-blob` splits the dtb payload into `fdt-NN.dtb`.
3. For each FDT: `dtc -I dtb -O dts` → `dtb_oc.py apply` (the final table) →
   `dtc -O dtb` → `dtb_oc.py splice`.
4. `dtb_oc.py join-blob` → `dtb_oc.py verify` (structural proof, see below).
5. `mkbootimg.py --header_version 4 ... --vendor_boot` rebuilds the image with
   the stock vendor ramdisk and bootconfig.  **Two files are emitted**: the
   content-only image (12,931,072 B, the one to flash) and the zero-padded
   100,663,296-byte (96 MiB) partition image (fit proof/backup).  The padded
   image wedges the RP5 ABL on `Sending 'vendor_boot'` (issue #54).
6. `vb_image.py compare` proves the header fields, vendor ramdisk, ramdisk
   table and bootconfig are byte-identical on the padded image.
7. A second independent pass must produce the same image (reproducibility).

Output (`out/gpu/<id>/`, pulled from `$REMOTE_ARTIFACTS/gpu/<id>/`):

| file | contents |
|---|---|
| `vendor_boot.img` | the content-only image to flash (12,931,072 B, issue #54) |
| `vendor_boot-padded.img` | the 96 MiB zero-padded partition image (fit proof/backup; do not flash) |
| `manifest.json` | stock/output/padded/DTB SHA-256, per-FDT board/bin summary, header comparison, tool versions |
| `SHA256SUMS` | hashes of `vendor_boot.img` and `vendor_boot-padded.img` |
| `active-stock.dts`, `active-oc.dts` | decompiled DTB #2 (the RP5 board) before/after |
| `dts/` | every board DTB's DTS, per-bin JSON and dtc logs |
| `dtb-verify.json`, `image-compare.*` | the static proofs |

The default id is deterministic: `vendor-boot-oc-<stock sha256[:8]>`
(`vendor-boot-oc-c6e49741` for the stock `_a` image).  The final 8-entry build
has content-only `804c2f08...` (12,931,072 B), padded `df049ebd...` and DTB
`0f912a33...`; rebuilds from the same stock image produce the same hashes.

### 3.1 Why the GPU subtree is spliced, not round-tripped

The contract asks for decompile → edit → recompile.  A *full* dtc round-trip is
not byte-faithful for this DTB: the stock pinctrl node contains a byte-array
string `pins = "gpio12\013..."` where dtc's NUL escape (`\0`) is followed by
the digits `13`; dtc re-reads that as octal `013` and changes the property.  The
pipeline therefore uses the DTS transform as the source of truth for the GPU
table, but splices **only** the transformed `qcom,gpu-pwrlevel-bins` subtree
into the parsed stock FDT and serialises that.  Every non-GPU property comes
from the stock blob as raw bytes; `dtb_oc.py verify` compares the whole tree
(with the bins node masked), the memreserve map and the FDT header fields, and
would fail on any other change.

### 3.2 Static proof (`dtb_oc.py verify`)

For every FDT in the payload:

* non-GPU nodes and properties: byte-for-byte equal (property order included);
* memreserve map, version and boot CPU unchanged;
* `-2` (525 MHz) subtree: completely unchanged;
* DTB #6 (no bins): byte-identical, `payload kept byte-identical`;
* every 670-top bin: 8 entries, descending, `reg` contiguous 0..7, `0 MHz`
  last, the four OC entries match the table (freq/level/acd/bus), the four
  stock entries (670/587/305/0) are unchanged apart from `reg`, the dropped
  525/490/441.6/400 steps are absent, `initial-pwrlevel` = 6 and
  `throttle-pwrlevel` is the stock value.

## 4. Runtime cap (`ashfall-hwd`)

The DTB change alone would let `msm-adreno-tz` use 1000 MHz whenever it wants.
The default cap therefore stays **670 MHz** and `ashfall-hwd` owns the devfreq
cap:

| property | writer → reader | values |
|---|---|---|
| `persist.ashfall.perf.gpu_max_mhz` | Parts app → `ashfall-hwd` | 305..1000, default 670; missing/invalid → 670 |
| `ashfall.status.perf.gpu_max_mhz` | `ashfall-hwd` → app | actual cap in MHz; `-1` when the node is unavailable |
| `ashfall.status.perf.gpu_oc` | `ashfall-hwd` → app | `1` = cap > 670, `0` = stock/≤670, `-1` = unavailable |

Behaviour:

* At boot and on every property change the daemon reads
  `gpu_available_frequencies`, picks the **highest step ≤ the request**, writes
  it in Hz to `devfreq/max_freq`, reads the value back and publishes it.
  `persist.ashfall.perf.gpu_max_mhz` persists across reboots (it is a
  `persist.` property).
* A request above the DT top (e.g. 1000 on the stock DTB) is a logged no-op:
  the cap stays at the top step and `gpu_oc` stays 0.
* The daemon opens the *resolved* sysfs device paths (see §4.2), never the
  `/sys/class/kgsl` symlinks.
* If `devfreq/max_freq` is missing (early boot, or an image without the OC
  steps) the daemon retries every 5 s and never crashes.
* While the phase-5 throttle override is active (`ashfall.perf.throttle_override`
  or `persist.ashfall.perf.throttle_override_permanent`), the cap is clamped to
  **670 MHz** and `gpu_oc` is published as `0`; the request is re-applied when
  the override ends.  This prevents 1 GHz with the GPU clock trips raised to
  110 °C.
* devfreq refuses `max_freq < min_freq`; if the governor has raised `min_freq`
  above the requested cap, the daemon first lowers `min_freq` (never raises it)
  and retries once.

### 4.1 SELinux

`/sys/class/kgsl/**` is labelled `vendor_sysfs_kgsl` by the stock vendor policy,
and `ashfall-hwd` is a `system_ext` coredomain: it cannot reference a vendor type,
and it may not touch generic `sysfs` (`private/coredomain.te` neverallow).

The node's label is applied by userspace `restorecon` from `file_contexts`
(verified: `getfattr -n security.selinux` shows a stored xattr on `gpuclk`, and
those specific labels only exist in `vendor_file_contexts`).  `vendor_file_contexts`
loads *after* `system_ext_file_contexts`, so the system_ext policy uses
**exact pathname** specs (every `.` escaped) for exactly five attributes,
under both the `/platform` and the legacy `/devices/soc` prefixes:

```
/sys/devices/platform/soc/3d00000\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_available_frequencies
/sys/devices/platform/soc/3d00000\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpuclk
/sys/devices/platform/soc/3d00000\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage
/sys/devices/platform/soc/3d00000\.qcom,kgsl-3d0/devfreq/3d00000\.qcom,kgsl-3d0/max_freq
/sys/devices/platform/soc/3d00000\.qcom,kgsl-3d0/devfreq/3d00000\.qcom,kgsl-3d0/min_freq
```

`libselinux`'s `sort_specs()` moves specifications without meta characters
behind all regex specifications, so these exact entries are matched first no
matter which partition the competing regex came from.  They are labelled
`ashfall_gpu_sysfs` (`fs_type, sysfs_type`) and `ashfall_hwd` gets
`rw_file_perms` on that type.  The same five paths (both prefixes) are repeated
in `genfs_contexts` as a fallback (longer than the vendor `/class/kgsl` prefix).

Only these five attributes change label; `gpu_model`, `max_gpuclk`, `gpubusy`
and the rest keep `vendor_sysfs_kgsl`, so most stock vendor domains keep their
access.  `gpuclk` (live clock, #124) and `gpu_busy_percentage` (live load, #156)
are read-only telemetry for the Parts app, added after the cap.  Directory
traversal needs no new rule: the stock vendor
policy grants `search` on `vendor_sysfs_kgsl:dir` to every domain except
`isolated_app`.

Do **not** relabel the class symlinks (`/sys/class/kgsl/kgsl-3d0`,
`.../devfreq`): they stay `vendor_sysfs_kgsl` for vendor consumers such as
`thermal-engine`, which have `lnk_file` access there.

**Known enforcing regression (vendor perf HAL).**  The vendor
`vendor_hal_perf_default` domain (`PERFD-SERVER`, `vendor.qti.hardware.perf@2.3`)
reads the real `devfreq/.../min_freq` and `max_freq` through the class paths.
The stock label of both files is `vendor_sysfs_kgsl`, and the vendor policy has
`allow vendor_hal_perf vendor_sysfs_kgsl:file rw_file_perms`; after the relabel
the perf HAL gets `avc: denied { open } ... tcontext=ashfall_gpu_sysfs`
(current build is permissive, so it still works).  `ashfall_hwd` cannot be added
to the vendor-private `vendor_hal_perf_client` attribute from system_ext.

Do **not** merge an enforcing build with this unresolved.  Vendor-safe options:

1. Add a vendor policy allow for `vendor_hal_perf`/`vendor_hal_perf_default` to
   `ashfall_gpu_sysfs` (requires a vendor image update; the GSI flow does not
   flash vendor).
2. Bridge the cap through a domain that is already in `vendor_hal_perf_client`
   (`system_app` or `system_server`) and calls the HAL's GPU opcode
   `0xA`/minor `0x4` (`max_freq`) — no sysfs relabel needed.
3. Revert the devfreq relabel and keep the cap on the stock label, which needs
   one of the two paths above for `ashfall_hwd` to write it.

The issue is tracked in #116; the DTB table and the cap work on the current
permissive build.

### 4.2 Daemon paths (issue #115)

Following a `/sys/class/kgsl` symlink needs `lnk_file read` on the symlink's
`vendor_sysfs_kgsl` label, and system_ext cannot grant that.  `ashfall-hwd`
therefore opens the resolved device paths directly (the same paths that
`file_contexts`/`genfs_contexts` relabel):

```
/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_available_frequencies
/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpuclk
/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage
/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/3d00000.qcom,kgsl-3d0/max_freq
/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/3d00000.qcom,kgsl-3d0/min_freq
```

Older kona layouts without the `/platform` level are a fallback
(`/sys/devices/soc/...`); the daemon picks the first existing variant once and
logs the fallback.  The `adb shell` examples below may keep the class path:
`shell` has vendor lnk_file access, `ashfall-hwd` does not.

## 5. Flashing and recovery

### 5.1 Published artifacts (release repo)

The built image is a **hash-pinned release** in the private artifact repo
`ashfall-rom/rp5-vendor_boot` (images are release assets, never git objects;
the repo carries the provenance and the DTB sources). `config/ashfall.conf`
pins what the ROM expects:

```bash
VENDOR_BOOT_REPO="ashfall-rom/rp5-vendor_boot"
VENDOR_BOOT_TAG="vb-oc8-20261115"
VENDOR_BOOT_SHA256="804c2f08…3eee"
```

- **Publish** a new build: `scripts/gpu/publish-vendor_boot.sh --dir out/gpu/<id> --tag <tag>`.
  It verifies the image against its own `manifest.json` (size, sha256, header v4,
  content-only, `reproducible: yes`, no debug variant), uploads the image +
  `.sha256` + manifest + all-FDT DTS tarball + checksums, then downloads the
  asset back and re-hashes it. Tags are immutable: bump the tag instead of
  re-uploading; `--force` exists only for a tag that was never flashed.
- **Flash** the pinned release: `scripts/device/flash-vendor-boot.sh --release`
  (or `--release <older-tag>` to roll back). It downloads to
  `out/vendor_boot/<tag>/`, verifies the asset's `.sha256`, additionally
  requires the pinned hash when the tag is the pinned one, then runs the normal
  one-batch flash.
- **Content-only only.** The padded 96 MiB variant is never published and must
  never be flashed (issue #54); the release script refuses a partition-sized
  image and the artifact repo's `.gitignore`/rules forbid committing images.
- The repo is **private** because the image wraps the stock vendor ramdisk and
  the 222 vendor modules. Keep it that way.

### 5.2 Flashing

`scripts/device/flash-vendor-boot.sh` writes the **active slot only**.  The
`vendor_boot` and `vbmeta` partitions are **non-slotted** on the RP5: the script
flashes plain `vendor_boot`/`vbmeta` (never `--slot`), while the active-slot
guard is kept for the system slot.  It follows AGENTS.md / issue #54: the whole
write is **one detached `fastboot` batch** (`dev_fastboot_batch`), with a log +
exit file on the build host and only the three read-only preflight values before
it.  No `partition-size` getvars and no getvars between transfers.

Only the **content-only** image is flashed.  The 96 MiB zero-padded image wedges
the ABL on `Sending 'vendor_boot'` (issue #54).  The script trims any padded
image it is given to the v4 header's `content_end` (12,931,072 B for the final
table) in `$REMOTE_ARTIFACTS/device/upload/` before the batch; a content-only
image is used as-is.  `--stock` likewise trims the stock `lun4_vendor_boot_*.bin`.

```bash
# the pinned release (recommended: download + verify + flash in one step)
scripts/device/flash-vendor-boot.sh --release --dry-run
scripts/device/flash-vendor-boot.sh --release --yes

# a just-built or host-side image (host path or local path; a local file is
# uploaded first)
scripts/device/flash-vendor-boot.sh out/gpu/vendor-boot-oc-c6e49741/vendor_boot.img --dry-run
scripts/device/flash-vendor-boot.sh /var/home/epickitrolaz/ashfall/artifacts/gpu/<id>/vendor_boot.img --yes

# roll back to an older published artifact
scripts/device/flash-vendor-boot.sh --release vb-oc8-20261114 --yes

# last-resort recovery only (device unreachable, bootloader cannot be entered)
scripts/device/flash-vendor-boot.sh --stock --yes
```

During the #116 debug cycles, do **not** restore stock between variants: leave
a failed/bootlooping image in place and flash the next variant over it.  The
stock image is only for the case where the device cannot reach fastboot/adb
and the user cannot enter the bootloader.

Sequence: preflight (`product` must be `kona`, bootloader unlocked, read the
active slot once, require the stock image for that slot to exist as the
restore path) → `adb reboot bootloader` (or `fastboot reboot bootloader` from
fastbootd) → **one detached fastboot batch**: `--disable-verity
--disable-verification flash vbmeta <stock>` (AVB is off, exactly as for the
GSI system flash) → `flash vendor_boot <content-only>` → `reboot`.

Safety: the script refuses the inactive slot, refuses a locked bootloader and a
different product, never wipes, never erases anything, and validates the image
magic/header version and that the content-only image fits the static
100,663,296-byte partition.  `--stock` does not touch vbmeta (the stock image is
signed the same way as before; the current vbmeta already has verification
disabled).  The default `--vbmeta` check reads the 4-byte `AVB0` magic (not 8
bytes, which included the version field and rejected the valid stock image).

The image is not AVB-signed.  If the device is ever returned to stock vbmeta
with verification enabled, the OC image will not boot; run `--stock` first.

## 6. Thermal behaviour

* The kernel's GPU cooling device (`devfreq-3d00000.qcom,kgsl-3d0`) is bound to
  the `gpuss-0/1` 95 °C trips and to `ddr` (95 °C, shared with the CPU) and the
  secondary GPU trips at 100 °C; those caps still work at 1000 MHz.  The 115 °C
  reset monitor and the 125 °C thermal-engine trips are untouched.
* 1000 MHz at TURBO_L1 is **marginal on some SD865 units**; 940 MHz at TURBO is
  the common stable OC.  1000 MHz is opt-in and must be stress-tested (15–30
  min) before it becomes a daily setting; if a unit resets, use 940.
* Sustained clocks depend on the fan and the thermal profile.  The phase-5
  override deliberately raises the software clock trips (CPU 105 °C, GPU
  110 °C); while it is active the GPU cap is clamped to 670 MHz — the UI shows
  both through `ashfall.status.perf.*`.
* The GPU rail voltage rises with the selected corner (352/384/416); the
  regulator levels are defined by the stock PMIC/regulator DT, not added here.

## 7. Verification

Static (before flashing):

```bash
make -C aosp/vendor/ashfall/rp5/hw/tests check     # daemon logic incl. GPU clamp
scripts/gpu/build-vendor-boot.sh                   # repack + all proofs
python3 -c 'import json;m=json.load(open("out/gpu/<id>/manifest.json"));print(m["dtb"]["verify"])'
```

`image-compare.txt` must show every header field, the vendor ramdisk, the
ramdisk table and the bootconfig as `ok` on `vendor_boot-padded.img`, and the
padded image must be 100,663,296 bytes.  `vendor_boot.img` must be the
content-only 12,931,072-byte image.  `dtb-verify.json` must show 4 OC bins and
1 untouched 525 MHz bin per 670-top DTB, and FDT #6 `byte_identical`.

Runtime (after the orchestrator flashes the OC vendor_boot):

```bash
S=scripts/device/run.sh
# shell resolves the class symlinks; the daemon uses the real paths (§4.2)
$S adb -s 2f184581 shell 'cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies'
# 1000000000 940000000 850000000 765000000 670000000 587000000 305000000
$S adb -s 2f184581 shell 'ls -Z /sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/\
devfreq/3d00000.qcom,kgsl-3d0/max_freq \
/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_available_frequencies'
# ashfall_gpu_sysfs
$S adb -s 2f184581 shell getprop ashfall.status.perf.gpu_max_mhz   # 670 (default)
$S adb -s 2f184581 shell getprop ashfall.status.perf.gpu_oc        # 0
$S adb -s 2f184581 shell 'cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq'  # 670000000

# set the cap (Parts writes the property; adb is only for the test)
$S adb -s 2f184581 shell setprop persist.ashfall.perf.gpu_max_mhz 1000
$S adb -s 2f184581 shell 'cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq'  # 1000000000
$S adb -s 2f184581 shell getprop ashfall.status.perf.gpu_oc        # 1

# under the Parts GPU stress test:
$S adb -s 2f184581 shell 'cat /sys/class/kgsl/kgsl-3d0/gpuclk; \
  cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq'
$S adb -s 2f184581 shell 'dmesg | grep -iE "kgsl|gpucc|pll|gpu fault" | tail -20'
$S adb -s 2f184581 logcat -d -b all | grep -iE "avc:.*ashfall|ashfall-hwd.*gpu"
```

Expected: `gpuclk` reaches 1000 MHz under load, the GPU rail voltage (PMIC
`vdd`/`vddcx` regulators, e.g. via `cat
/sys/class/regulator/regulator.*/microvolts`) rises with the corner, no
`kgsl` faults / PLL `clk_set_rate` failures, and no `avc: denied` for
`ashfall_hwd` on `ashfall_gpu_sysfs`.  Throttle override + 1000 request must
show `ashfall.status.perf.gpu_max_mhz=670`, `gpu_oc=0` and one log line.

The final 8-entry 1000@416 image passed the 10-minute stress (2026-11-14):
1000 MHz, 92–99% busy, 599,980 ms in `trans_stat` at 1000, peak 77.5/70.1 °C,
`thermal_pwrlevel=0`, no throttle, no GPU fault/panic, no app crash/ANR; the
auto-stop returned 305 MHz.  The only new dmesg line is
`failed to update frequency from PM QoS (-71)`; it does not affect scaling and
is tracked in #116.

Recovery loop: flash the next variant directly over the current one; `--stock`
is last-resort recovery only (device unreachable and the bootloader cannot be
entered).  The bootloader still has the other slot's stock vendor_boot and
`restore-stock.sh` restores every partition.

## 8. Troubleshooting

| symptom | meaning / fix |
|---|---|
| `gpu_available_frequencies` still ends at 670 | the stock vendor_boot is active (flash did not take) or the inactive slot booted; check `getprop ro.boot.slot_suffix` and re-run the flash |
| 1000 MHz request, cap stays 670 | expected on the stock DTB; check `ashfall.status.perf.gpu_oc` (0 = no OC step) and the DT table |
| `avc: denied ... ashfall_hwd ... ashfall_gpu_sysfs` | policy build did not pick up the files; rebuild system.img (orchestrator) and check the merged `system_ext_file_contexts`/`system_ext_sepolicy.cil` |
| `avc: denied ... vendor_sysfs_kgsl ... lnk_file` for `ashfall-hwd` | the daemon is following a class symlink (stale binary?); it must open the resolved paths (§4.2) |
| `avc: denied ... ashfall_gpu_sysfs` (file) | the exact-path file_contexts entry lost; `ls -Z` the three resolved nodes, rebuild system.img |
| `fastboot: error: Failed to write` / `unknown partition` | bootloader mode required (not fastbootd); re-run `ensure_bootloader` by starting from Android |
| `Sending 'vendor_boot'` hangs / ABL wedged | the 96 MiB zero-padded image was used; flash the content-only `vendor_boot.img` (12,931,072 B), one detached fastboot batch (issue #54) |
| `kgsl-3d ... failed to update frequency from PM QoS (-71)` | dmesg-only on the OC boot; scaling and the cap still work (10-min 1 GHz stress passed). Tracked in #116 |
| `msm_kgsl` probe fails with `a6xx_gmu_bind ... -34` / `-ERANGE`, no `/sys/class/kgsl` | the DT table exceeds the RPMh `gfx.lvl` count (see §2); keep 8 entries including the 0 MHz last entry |
| `avc: denied ... PERFD-SERVER ... ashfall_gpu_sysfs` | the relabel denies the vendor perf HAL on the real devfreq min/max nodes (permissive today). Do not merge an enforcing build until the vendor-safe path lands: vendor policy allow for `vendor_hal_perf`, a system_app/system_server bridge to `vendor.qti.hardware.perf@2.3`, or a revert of the devfreq relabel (see #116) |
| device bootloops after the flash | leave it and flash the next variant directly over it; `scripts/device/flash-vendor-boot.sh --stock` is last-resort recovery only (device unreachable, bootloader not enterable) |
| `flash-vendor-boot.sh` rejects the stock vbmeta (`magic != AVB0`) | fixed: the check reads 4 bytes; update the checkout (the old 8-byte read included the AVB version field) |
| 1000 MHz resets the GPU (fault/hang) | use 940 MHz (`persist.ashfall.perf.gpu_max_mhz=940`); document the unit's limit |
