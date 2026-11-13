# 2026-11-13 — first AshFall GSI boot ends in Qualcomm crash dump (05c6:900e)

Triage for [#39](https://github.com/Epickitrolaz/ashfall/issues/39).

Image: `20261113-050255-ashfall_gsi_arm64-userdebug-fb3ee9c0baf3` (main `fb3ee9c`,
EROFS, 1.63 GiB, sha256 `bfca6e0f…3925`), flashed to `system_a` + stock
`vbmeta_a` with `--disable-verity --disable-verification`, userdata/metadata
erased, slot a; `product_a`/`system_ext_a` (stock) left in place; the GSI ships
`skip_mount.cfg` for `/product` and `/system_ext`.

## TL;DR

* RAM was captured read-only from the PBL's Sahara memory-debug mode
  (USB `05c6:900e`) before anything reset the device. Dump + recovered log are
  on the build host under
  `/var/home/epickitrolaz/ashfall/crash/20261113-141536/`.
* The kernel ring holds **405 records** (seq 0…404, ~28 KiB of a 256 KiB
  `log_buf_len=256K` buffer, never wrapped). It ends with:

  ```
  Kernel panic - not syncing: Attempted to kill init! exitcode=0x00007f00
  ...
  msm_watchdog 17c10000.qcom,wdt: Causing a QCOM Apps Watchdog bite!
  ```

  `0x7f00` is `exit_group(127)` (status 127, **no signal**), i.e. PID 1 `init`
  exited deliberately — this is not a kernel oops and not a SIGSEGV/SIGABRT.
* The log stops **immediately after `init: init first stage started!` +
  the eight `init: Loading module …` lines + one `Loaded kernel module`**:
  exactly **10** `/dev/kmsg` writes from init. Everything init logged after
  that was discarded by the kernel's per-fd `/dev/kmsg` ratelimit
  (`printk.devkmsg=ratelimit`, burst 10 per 5 s), so the FATAL line that
  explains 127 is **not in the dump** and absence of fs_mgr/SELinux messages
  proves nothing.
* Therefore this report does **not** guess a root cause. It documents the
  capture, the verified panic, and the next diagnostic step: a RAM-only
  `fastboot boot` of `boot-debug.img` with `printk.devkmsg=on` (built below).

## 1. How the dump was taken (read-only)

The device panicked at ~13:1x UTC after `fastboot reboot`, the panic path bit
the Qualcomm app watchdog (`msm_watchdog … Causing a QCOM Apps Watchdog bite!`)
and the PBL re-enumerated as `05c6:900e`, keeping RAM (battery-backed).

Everything below is a **read**; no `edl w/e`, no firehose programming, no
`edl reset`, and the device was not power-cycled.

1. Build host (`epickitrolaz@100.66.37.14`, Fedora Silverblue 45, bare metal,
   not a VM) showed `Bus 001 Device 014: ID 05c6:900e QUSB_BULK_SN:64ADE251`
   at **USB 2.0 high speed (480 Mbit/s)** and **no** `qcserial`/`option`/
   `usb_wwan` module loaded.
2. A dedicated rootless podman box `ashfall-edl` (Ubuntu 24.04 + bkerler/edl
   in `/opt/edlenv`) was used. Two **container-local** edits were needed
   (`scripts/device/ramdump.sh` applies both):
   * `usblib.py`: 900e does not expose a serial string descriptor, so pyusb's
     `dev.serial_number` raises `ValueError: The device has no langid` during
     enumeration and edl aborts before reading the Sahara hello.
   * `sahara.py`: edl's memory-debug path ends with `cmd_reset()`; that call is
     removed so the device stays in 900e after the dump.
3. **Host prerequisite (approved change, needs root once).** The usbfs node is
   `root:root 0664` and podman is rootless, so the container cannot open it
   (the kernel's `usbfs` ioctl path requires `FMODE_WRITE`). Installed on the
   build host:

   ```
   /etc/udev/rules.d/51-ashfall-qcom.rules
   SUBSYSTEM=="usb", ATTRS{idVendor}=="05c6", ATTRS{idProduct}=="9008", MODE="0666", TAG+="uaccess"
   SUBSYSTEM=="usb", ATTRS{idVendor}=="05c6", ATTRS{idProduct}=="900e", MODE="0666", TAG+="uaccess"
   ```

   `sudo udevadm control --reload-rules && sudo udevadm trigger
   --subsystem-match=usb`; afterwards `/dev/bus/usb/001/014` is
   `crw-rw-rw-`. `chmod 666` also works but only until re-enumeration.
4. `edl memorydump` (plain, **not** `--debugmode`; `--debugmode` enabled DEBUG
   logging and produced a 17 GB log for a 8 GiB dump) wrote 40 regions in
   ~4 min. The DDR regions are a **linear physical map**, no interleaving:

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
Record layout observed in this dump:

```
u32 id     = 0xfffe0000 | seq      (seq increments by one per record)
u32 zero
char text[]                        (may contain '\n', NUL padded, 8-byte aligned)
```

The chain starts at `DDRCS0_0.BIN` offset `0x23522060`
(phys `0xa3522060`) with the banner as `seq 0` and ends at
`0x23528d90` with the watchdog bite as `seq 404`; seq is contiguous, so
the reconstructed order is exact. The full text is committed as
[`2026-11-13-first-boot-log.txt`](2026-11-13-first-boot-log.txt).

Two properties worth knowing before reading the log:

* **No timestamps.** The ring stores the raw record text; printk timestamps
  only exist in the console/devkmsg rendering. The `[   12.345678]` prefix
  expected in a dmesg is *not* in RAM. (The small panic copy in a
  `msm_rtb`-style buffer at `DDRCS0_0` offset `0x2351d218` is also
  timestamp-free.)
* **2-character tail repeat.** Every record's text is followed by a repeat of
  its last two characters before the newline (`…initialized` is stored as
  `…initializeded`). The extractor strips it only when `text[-2:] ==
  text[-4:-2]`, so genuine doubles survive. Cause unknown; it is present in all
  405 records and does not affect ordering.

A second and third `Linux version 5.15.148` occurrence (`DDRCS0_1` @
`0x106a5a08`, `DDRCS1_1` @ `0x27bb…`) are **kernel rodata** copies (adjacent
strings are `initcall`, `int (void)`), not rings: this dump contains exactly
one usable printk ring.

## 3. The panic, verbatim

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
  162  sda: sda1 sda2 sda3 sda4 sda5 sda6 sda7 sda8 sda9 sda10 sda11 sda12 sda13 sda14 sda15 sda16 sda17 sda18 sda19 sda20 sda21 sda22 sda23
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

The `do_group_exit` frame confirms the userspace `exit_group(127)` path;
everything after record 193 is the panic itself (CPU stop traces, ftrace dump,
UFS dump, kernel offset, watchdog bite). `Kernel Offset: 0x28b2e00000` explains
why the kernel image/rodata (banner, `panic` format strings, `erofs_*` symbols)
live around phys `0xa1b…`/`0x21a…` rather than at `0x80080000`.

## 4. What the log does and does not prove

**Proven**

* The stock 5.15 GKI kernel boots, brings up PMIC/UFS, mounts the initramfs
  from `init_boot`, and runs first-stage `init` (`init=/init`).
* First-stage init loads kernel modules (`qcom_ipcc`, `qcom_hwspinlock`,
  `qcom_cpu_vendor_hooks`, `cmd-db`, `nvmem_qcom-spmi-sdam`,
  `qcom-cpufreq-hw`, `icc-debug`, `qnoc-qos`) and the two DLKM taints.
* The kernel is healthy at panic time: the panic is raised by the *kernel's
  PID-1 guard* because PID 1 exited, not by a fault in a driver. UFS
  enumeration (`sda`…`sde`) is still completing in parallel.
* The reset into 900e is the watchdog bite from the panic path, consistent
  with `reboot=panic_warm`.

**Not proven (and cannot be from this dump)**

* *Why* init exited 127. Only **10** `/dev/kmsg` writes from init are in the
  ring — exactly the default devkmsg burst (`10` messages / `5` s per open
  fd). First-stage init `dup2()`s `/dev/kmsg` onto stdout/stderr once
  (`first_stage_init.cpp`), so the module summary, all `[libfs_mgr]` mount
  lines, `selinux_setup`, the second-stage `init` and any `LOG(FATAL)` were
  ratelimited away. No `… suppressed due to ratelimiting` notice appears
  because init died inside the same 5 s window.
* Which stage failed. The first stage's last act is `execv("/system/bin/init",
  {"selinux_setup"})`; the A17 second stage then runs from `/system` and
  re-execs `second_stage`. The ring cannot tell whether mount, sepolicy or the
  A13→A17 hand-off failed.
* `exit_group(127)` with **no signal** is unusual: in AOSP/Lineage `init`, the
  `_exit(127)` sites (`first_stage_console.cpp`, `service.cpp`,
  `snapuserd_transition.cpp`, `util.cpp::ForkExec`) are all in **forked
  children**, while PID 1 `LOG(FATAL)` goes through `InitAborter` →
  `InitFatalReboot(SIGABRT)` (which would show `0x00000006`). So the 127 points
  at a helper/exec path rather than a plain `LOG(FATAL)` in PID 1 — another
  reason to capture the unratelimited log instead of inferring.

One earlier-boot fragment is worth noting but is **not** evidence about this
boot: a console buffer leftover in `DDRCS0_1` at offset `103152971`
(phys `0x10625fd4b`) contains `<6>init: [libfs_mgr]Invalid ext4 superbl…`
(with the `<6>` console prefix, so it is console output, not ring text). It
shows that on some earlier boot fs_mgr probed a partition as ext4 and found no
ext4 superblock — relevant because our `system_a` is EROFS.

## 5. Next diagnostic step: RAM-boot with unratelimited init logs

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
`/tmp/bt2`, tree read-only):

```bash
python3 unpack_bootimg.py --boot_img firmware/img/lun4_boot_a.bin --out /tmp/bt2/out
python3 mkbootimg.py --header_version 4 --os_version 13.0.0 --os_patch_level 2024-07 \
  --kernel /tmp/bt2/out/kernel --ramdisk /tmp/bt2/out/ramdisk \
  --cmdline "printk.devkmsg=on loglevel=8" -o crash/boot-debug.img
python3 unpack_bootimg.py --boot_img crash/boot-debug.img   # verify: same sizes/cmdline
```

Use (RAM only, **nothing is flashed**):

```bash
# leave 900e first (dump + log are already saved; this is the point of no return)
edl reset                                # or power-cycle with Vol- held
fastboot boot /var/home/epickitrolaz/ashfall/crash/boot-debug.img
```

For header v4 the bootloader merges the boot image cmdline into the
`vendor_boot` cmdline, so `printk.devkmsg=on` reaches the kernel while the
flashed `vendor_boot`/`init_boot` stay untouched; `loglevel=8` only affects
console verbosity. Expected: the same panic, but the ring now contains init's
`[libfs_mgr]`, `selinux_setup`, second-stage and `LOG(FATAL)` lines, plus the
`… suppressed due to ratelimiting` counter is gone. Then re-dump with:

```bash
scripts/device/ramdump.sh              # container + read-only dump + extraction
```

If the boot image cmdline is ignored (still only 10 init lines), the fallback is
to put the same two tokens into the `vendor_boot` bootconfig (needs a write, so
orchestrator decision).

## 6. Top 3 candidate fixes (ranked; all still need the unratelimited log)

1. **Match the init stage versions (stock A13 `init_boot` vs A17 `/system`).**
   *Evidence:* the first stage is the stock A13 init from `init_boot` (module
   list `/lib/modules/*.ko`, A13 first-stage wording); the second stage is our
   A17 `/system/bin/init`. First stage ends with
   `execv("/system/bin/init", {"selinux_setup"})`; the panic is PID 1 exiting
   right at that transition, and no A17 init message ever appears.
   *Fix:* flash the `init_boot.img` from the same LineageOS 24 build (or
   `fastboot boot` a v3-style image carrying a matching first stage) so both
   stages speak the same `init.rc`/property/`snapuserd` protocol.
   *Risk:* a write; needs a matching `vbmeta`/AVB decision.

2. **EROFS vs the stock A13 fstab (fs_mgr sees `ext4`).**
   *Evidence:* our `system.img` is EROFS, while the earlier-boot console
   fragment shows `init: [libfs_mgr]Invalid ext4 superblock`; the stock A13
   `fstab.qcom` describes `system` as ext4 and first-stage mount uses the
   fstab's fs type.
   *Fix:* rebuild with `BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE=ext4` (or ship an
   fstab/bootconfig override for `system`), then re-test with the debug boot.
   *Caveat:* a plain mount failure normally ends in `LOG(FATAL)`→SIGABRT
   (`0x600`), not `0x7f00`, so this alone does not explain the exit code.

3. **A17 system sepolicy against the A13 vendor image.**
   *Evidence:* stock `vendor_a`/`vendor_dlkm` are A13; the GSI brings A17
   platform policy and `skip_mount.cfg` for `/product`,`/system_ext`. A
   `SetupSelinux` failure is a `LOG(FATAL)` and would be invisible here for the
   same ratelimit reason. `SELinux: Could not load policy` does not appear in
   the ring (expected — suppressed).
   *Fix / discriminating test:* boot `boot-debug.img` once with
   `androidboot.selinux=permissive` appended (bootconfig) to separate policy
   from mount failures; if it proceeds, align the policy
   (`PLATFORM_SEPOLICY_VERSION`, vendor compatibility) instead of chasing
   mounts.
   *Caveat:* same `0x600`-vs-`0x7f00` mismatch as #2.

None of these should be flashed before the `boot-debug.img` run: the ring has
no message that distinguishes them, and the current device state (900e) is the
only forensic copy of this boot.

## 7. Artefacts

On the build host, `~/ashfall/crash/`:

| path | contents |
|---|---|
| `boot-debug.img` | RAM-boot image with `printk.devkmsg=on loglevel=8` (sha256 above) |
| `20261113-141536/memory/` | 40 dumped regions, 4×2 GiB DDR + OCIMEM/DCC/PIMEM/… |
| `20261113-141536/memory/DDRCS0_0.BIN` | holds the ring at `0x23522060` |
| `20261113-141536/memory/log_all.txt` | reconstructed log (405 records) |
| `20261113-141536/memory/anchors.txt` | anchor search over the ring |
| `20261113-141536/memory/memory-table.txt` | edl region table (phys base/length) |
| `20261113-141536/memory/boots/` | all chains found (here: one) |

In the repo: `scripts/device/ramdump.sh` (reproducible capture + extraction),
`scripts/device/printk_extract.py` (ring walker),
`docs/crash/2026-11-13-first-boot-log.txt` (full log).
