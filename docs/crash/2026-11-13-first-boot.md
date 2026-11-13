# 2026-11-13 — first AshFall GSI boot ends in Qualcomm crash dump (05c6:900e)

Triage for [#39](https://github.com/Epickitrolaz/ashfall/issues/39).

Image: `20261113-050255-ashfall_gsi_arm64-userdebug-fb3ee9c0baf3` (main `fb3ee9c`,
EROFS, 1.63 GiB, sha256 `bfca6e0f…3925`), flashed to `system_a` + stock
`vbmeta_a` with `--disable-verity --disable-verification`, userdata/metadata
erased, slot a; `product_a`/`system_ext_a` (stock) left in place; the GSI ships
`skip_mount.cfg` for `/product` and `/system_ext`.

## TL;DR — root cause confirmed

The first-stage init cannot mount `/system` because the stock fstab only
accepts **ext4** and `system_a` holds an **EROFS** image:

```
init: [libfs_mgr]Created logical partition system_a on device /dev/block/dm-2
init: DSU not detected, proceeding with normal boot
init: [libfs_avb]Invalid hash size:
init: [libfs_avb]Failed to verify vbmeta digest
init: [libfs_avb]Returning avb_handle with status: VerificationDisabled
init: Top-level vbmeta is disabled, skip Hashtree setup for /system
init: [libfs_mgr]Invalid ext4 superblock on '/dev/block/dm-2'
init: Failed to mount /system: File exists
init: Failed to mount required partitions early ...
Kernel panic - not syncing: Attempted to kill init! exitcode=0x00007f00
```

That excerpt comes from the **second** dump
(`~/ashfall/crash/20261113-140314/`), taken after a RAM-only
`fastboot boot boot-debug.img` with `printk.devkmsg=on` (see §5). The first
dump (`~/ashfall/crash/20261113-141536/`) stopped 10 init messages in because
of the `/dev/kmsg` ratelimit; §4 explains why that one could not name a cause.

Fix (orchestrator): rebuild `system.img` as ext4 (or override the fstab entry
for `system`) and repeat the boot test.

## 1. How the dumps were taken (read-only)

The device panicked at ~13:1x UTC after `fastboot reboot`; the panic path bit
the Qualcomm app watchdog (`msm_watchdog … Causing a QCOM Apps Watchdog bite!`)
and the PBL re-enumerated as `05c6:900e`, keeping RAM (battery-backed).
Nothing was written, erased, programmed or reset to capture it.

1. Build host (`epickitrolaz@100.66.37.14`, Fedora Silverblue 45, bare metal,
   not a VM) showed `05c6:900e QUSB_BULK_SN:64ADE251` at **USB 2.0 high speed**
   and **no** `qcserial`/`option`/`usb_wwan` module loaded.
2. A dedicated rootless podman box `ashfall-edl` (Ubuntu 24.04 + bkerler/edl
   in `/opt/edlenv`) is used. Two **container-local** edits are required
   (`scripts/device/ramdump.sh` applies both):
   * `usblib.py`: 900e exposes no serial string descriptor, so pyusb raises
     `ValueError: The device has no langid` during enumeration and edl aborts
     before reading the Sahara hello.
   * `sahara.py`: edl's memory-debug path ends with `cmd_reset()`; that call
     is removed so the device stays in 900e after the dump.
3. **Host prerequisite (approved change, needs root once).** The usbfs node is
   `root:root 0664` and podman is rootless, so the container cannot open it
   (the kernel's `usbfs` ioctl path requires `FMODE_WRITE`):

   ```
   /etc/udev/rules.d/51-ashfall-qcom.rules
   SUBSYSTEM=="usb", ATTRS{idVendor}=="05c6", ATTRS{idProduct}=="9008", MODE="0666", TAG+="uaccess"
   SUBSYSTEM=="usb", ATTRS{idVendor}=="05c6", ATTRS{idProduct}=="900e", MODE="0666", TAG+="uaccess"
   ```

   `sudo udevadm control --reload-rules && sudo udevadm trigger
   --subsystem-match=usb`; afterwards `/dev/bus/usb/001/014` is `crw-rw-rw-`.
   `chmod 666` also works but only until re-enumeration.
4. `edl memorydump` (plain, **not** `--debugmode`) wrote 40 regions in ~4 min.
   `--debugmode` is a trap: it enables DEBUG logging, which produced a 17 GB
   `logs/log.txt` for an 8 GiB dump (deleted; the region table was kept).
   The DDR regions are a **linear physical map**, no interleaving:

   | file | physical range |
   |---|---|
   | `DDRCS0_0.BIN` | `0x80000000` – `0x100000000` (2 GiB) |
   | `DDRCS0_1.BIN` | `0x100000000` – `0x180000000` (2 GiB) |
   | `DDRCS1_0.BIN` | `0x180000000` – `0x200000000` (2 GiB) |
   | `DDRCS1_1.BIN` | `0x200000000` – `0x280000000` (2 GiB) |

   (`mem=6366953472B` ⇒ the kernel only manages `0x80000000`–`0x1fb800000`;
   the rest is stale content from earlier boots.)

## 2. Reconstructing the ring

`scripts/device/printk_extract.py` finds the boot banner (`Linux version …`)
in the DDR files, verifies the 8-byte record header in front of it, and walks
the chain by looking for the next `seq+1` header at an 8-byte aligned offset.
Record layout observed in these dumps:

```
u32 id     = 0xfffe0000 | seq      (seq increments by one per record)
u32 zero
char text[]                        (may contain '\n', NUL padded, 8-byte aligned)
```

The chains start with the banner as `seq 0`, so the reconstructed order is
exact. Full text of both boots is committed:

* [`2026-11-13-first-boot-log.txt`](2026-11-13-first-boot-log.txt) — first
  dump, 405 records, `DDRCS0_0.BIN` offset `0x23522060` (phys `0xa3522060`),
  panic at seq 193, last record seq 404.
* [`2026-11-13-debug-boot-log.txt`](2026-11-13-debug-boot-log.txt) — debug
  boot dump, 576 records, panic at seq 355.

Two properties worth knowing before reading the logs:

* **No timestamps.** The ring stores the raw record text; printk timestamps
  only exist in the console/devkmsg rendering. The `[   12.345678]` prefix
  expected in a dmesg is *not* in RAM.
* **2-character tail repeat.** Every record's text is followed by a repeat of
  its last two characters before the newline (`…initialized` is stored as
  `…initializeded`). The extractor strips it only when `text[-2:] ==
  text[-4:-2]`, so genuine doubles survive. Cause unknown; it is present in
  every record and does not affect ordering.

Second and third `Linux version 5.15.148` occurrences (`DDRCS0_1` @
`0x106a5a08`, `DDRCS1_1` @ `0x27bb…`) are **kernel rodata** copies (adjacent
strings are `initcall`, `int (void)`), not rings: each dump contains exactly
one usable printk ring.

## 3. The panic, verbatim (first dump)

```
    0 Linux version 5.15.148-android13-8-gbfe94e95c709-dirty (build-user@build-host) (Android (8508608, based on r450784e) clang version 14.0.7 …) #1 SMP PREEMPT Sat Oct 26 10:02:37 UTC 2024
…
   11 Kernel command line: stack_depot_disable=on kasan.stacktrace=off kvm-arm.mode=protected cgroup_disable=pressure console=ttyMSM0,115200n8 loglevel=6 kpti=0 log_buf_len=256K kernel.panic_on_rcu_stall=1 rcupdate.rcu_expedited=1 rcu_nocbs=0-7 ftrace_dump_on_oops fw_devlink.strict=1 cpufreq.default_governor=performance printk.console_no_auto_verbose=1 kasan=off pcie_ports=compat qcom_dma_heaps.enable_bitstream_contig_heap=y lpm_levels.sleep_disabled=1 video=vfb:640x400,bpp=32,memsize=3072000 msm_rtb.filter=0x237 service_locator.enable=1 swiotlb=2048 loop.max_part=7 cgroup.memory=nokmem,nosocket reboot=panic_warm bootconfig buildvariant=user rootwait ro init=/init mem=6366953472B memhp_default_state=online movable_node silent_boot.mode=nonsilent
…
   73 Warning: unable to open an initial console.
   74   with arguments:
   75     /init
…
   80 init: init first stage started!
   81 init: Loading module /lib/modules/qcom_ipcc.ko with args ''
   82 qcom_ipcc: module verification failed: signature and/or required key missing - tainting kernel
   83 init: Loading module /lib/modules/qcom_hwspinlock.ko with args ''
   84 init: Loading module /lib/modules/qcom_cpu_vendor_hooks.ko with args ''
   85 init: Loading module /lib/modules/cmd-db.ko with args ''
   86 init: Loading module /lib/modules/nvmem_qcom-spmi-sdam.ko with args ''
   87 init: Loading module /lib/modules/qcom-cpufreq-hw.ko with args ''
   88 init: Loading module /lib/modules/icc-debug.ko with args ''
   89 init: Loading module /lib/modules/qnoc-qos.ko with args ''
   90 init: Loaded kernel module /lib/modules/icc-debug.ko
   91 qrng_dlkm: loading out-of-tree module taints kernel.
   92 qtee shmbridge registered default bridge with size 4194304 bytes
   93 shmbridge is enabled
…
  192 sd 0:0:0:4: [sde] Attached SCSI disk
  193 Kernel panic - not syncing: Attempted to kill init! exitcode=0x00007f00
  194 CPU: 7 PID: 1 Comm: init Tainted: G           OE     5.15.148-android13-8-gbfe94e95c709-dirty #1
  195 Hardware name: Qualcomm Technologies, Inc. kona nopm HDK (DT)
  196 Call trace:
  197  dump_backtrace.cfi_jt+0x0/0x8
  198  show_stack+0x1c/0x2c
  199  dump_stack_lvl+0x68/0x98
  200  panic+0x174/0x430
  201  do_exit+0x788/0x998
  202  do_group_exit+0xa0/0xa8
  203  __wake_up_parent+0x0/0x34
  204  invoke_syscall+0x60/0x150
  205  el0_svc_common.llvm.10320443295220960516+0xb8/0xf8
  206  do_el0_svc+0x28/0x98
  207  el0_svc+0x24/0x84
  208  el0t_64_sync_handler+0x88/0xec
  209  el0t_64_sync+0x1b8/0x1bc
…
  400 Kernel Offset: 0x28b2e00000 from 0xffffffc008000000
  401 PHYS_OFFSET: 0x80000000
  402 CPU features: 0x2,00040381,23300e42
  403 Memory Limit: 6072 MB
  404 msm_watchdog 17c10000.qcom,wdt: Causing a QCOM Apps Watchdog bite!
```

`Kernel Offset: 0x28b2e00000` explains why the kernel image/rodata (banner,
`panic` format strings, `erofs_*` symbols) live around phys `0xa1b…`/`0x21a…`
rather than at `0x80080000`.

## 4. The ratelimit lesson (why the first dump was not enough)

The first dump contains only **10** `/dev/kmsg` writes from init — exactly the
default devkmsg burst (`printk.devkmsg=ratelimit`, 10 messages per 5 s per
open fd). First-stage init `dup2()`s `/dev/kmsg` onto stdout/stderr once, so
everything after the tenth message — the module summary, every `[libfs_mgr]`
line, `selinux_setup`, the second stage and the `LOG(FATAL)` — was discarded
before it ever reached the ring. No `… suppressed due to ratelimiting` notice
appears because init died inside the same 5 s window.

Consequences, learned the hard way:

* Absence of SELinux/fs_mgr/AVB messages in a ring dump is **not evidence**
  that those steps did not run or fail.
* Do not blame `BSP`/fstab/policy/version skew from a silent ring. One
  RAM-only boot with `printk.devkmsg=on` turns a hypothesis into a fact (§5).
* `exitcode=0x7f00` is `exit_group(127)` with **no signal**: init's fatal path
  (`InitAborter` → `InitFatalReboot`) exits the process instead of aborting,
  so "no signal" is expected once the reason is a `LOG(FATAL)` in first stage.
  Only the FATAL *text* identifies the cause; the exit status does not.

## 5. The debug boot that named the cause

Built from the **stock** `boot_a` (`firmware/img/lun4_boot_a.bin`) without
touching the tree or the device:

| | |
|---|---|
| image | `/var/home/epickitrolaz/ashfall/crash/boot-debug.img` |
| sha256 | `d52765fbf1fa56cd455315c83e03ee04cfce8af542c2ce41cc47bef701692378` |
| size | 55 672 832 bytes |
| header | v4, `os_version 13.0.0`, `os_patch_level 2024-07`, ramdisk 0 |
| kernel | byte-identical to stock, sha256 `ae186dcebdf265d5fb26a06d26d797dd413f9d74796a4aa08a282bccb3a17678` |
| only change | boot-header cmdline field (`0x2c`–`0x47`): `printk.devkmsg=on loglevel=8` (28 bytes differ vs stock; every other header byte is identical) |

Repack recipe (mkbootimg/unpack_bootimg copied out of the synced tree to
`/tmp/bt2`; tree read-only):

```bash
python3 unpack_bootimg.py --boot_img firmware/img/lun4_boot_a.bin --out /tmp/bt2/out
python3 mkbootimg.py --header_version 4 --os_version 13.0.0 --os_patch_level 2024-07 \
  --kernel /tmp/bt2/out/kernel --ramdisk /tmp/bt2/out/ramdisk \
  --cmdline "printk.devkmsg=on loglevel=8" -o crash/boot-debug.img
python3 unpack_bootimg.py --boot_img crash/boot-debug.img   # verify: same sizes/cmdline
```

Use (RAM only, **nothing is flashed**; the flashed
`vendor_boot`/`init_boot`/`system` stay untouched):

```bash
# leave 900e first (the dumps are saved; this is the point of no return)
edl reset                                # or power-cycle with Vol- held
fastboot boot /var/home/epickitrolaz/ashfall/crash/boot-debug.img
# on the next panic the device returns to 900e; then:
scripts/device/ramdump.sh                # preflight + read-only dump + extraction
```

For header v4 the bootloader merges the boot image cmdline into the
`vendor_boot` cmdline. The second dump's record 11 confirms it worked:

```
... loop.max_part=7 cgroup.memory=nokmem,nosocket reboot=panic_warm bootconfig
buildvariant=user printk.devkmsg=on loglevel=8 msm_drm.dsi_display0=... rootwait ro init=/init ...
```

and the ring grew from 405 to **576** records, with the full first-stage mount
sequence present. That is how the chain in the TL;DR was recovered (records
337–355 of `2026-11-13-debug-boot-log.txt`).

## 6. Diagnosis and fix

1. `super`/logical partitions are created correctly (`system_a` →
   `/dev/block/dm-2`), so `--disable-verification` and the super metadata are
   fine. AVB reports `VerificationDisabled` and skips Hashtree, as intended.
2. fs_mgr then uses the **fs type from the stock A13 fstab** for `system`:
   `ext4`. Our `system.img` is **EROFS** → `Invalid ext4 superblock on
   '/dev/block/dm-2'` → `Failed to mount /system` → first stage
   `LOG(FATAL) << "Failed to mount required partitions early ..."` → PID 1
   fatal path → panic → watchdog bite → 900e.
3. **Fix:** rebuild the GSI system image as ext4 (drop the EROFS image
   type/compression for `system`), or supply an fstab/bootconfig override
   that describes `system` as `erofs`, then repeat the boot test. Keep the
   debug boot available: it is the cheapest way to see the next failure if
   the ext4 image trips something else (e.g. size budget, sepolicy).

Non-issues confirmed by this investigation: the kernel has EROFS **and** ext4
support (`erofs_read_superblock`, `erofs_iget`, `ext4_fill_super` in rodata),
modules load from the stock `init_boot` ramdisk, and the UFS/storage stack is
healthy up to the mount attempt.

## 7. Artefacts

On the build host, `~/ashfall/crash/`:

| path | contents |
|---|---|
| `boot-debug.img` | RAM-boot image with `printk.devkmsg=on loglevel=8` (sha256 above) |
| `20261113-141536/memory/` | first dump (ratelimited), 405 records |
| `20261113-141536/memory/DDRCS0_0.BIN` | holds the ring at `0x23522060` |
| `20261113-141536/memory/log_all.txt` | reconstructed log (405 records) |
| `20261113-140314/memory/` | debug-boot dump, 576 records, root cause visible |
| `20261113-140314/memory/log_all.txt` | reconstructed log (576 records) |
| `memory-table.txt` (both) | edl region table (phys base/length) |
| `memory/boots/`, `memory/anchors.txt`, `memory/summary.txt` | per-boot chains + anchor report |

In the repo: `scripts/device/ramdump.sh` (reproducible capture + extraction),
`scripts/device/printk_extract.py` (ring walker),
`docs/crash/2026-11-13-first-boot-log.txt` and
`docs/crash/2026-11-13-debug-boot-log.txt` (full logs).
