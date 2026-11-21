# AshFall plan & contracts

AshFall: a LineageOS 24.0 (Android 17) ROM for the Retroid Pocket 5.
First a self-built GSI, then a full device ROM.

## Phases
1. **Environment** - build host, source sync, firmware analysis, device tooling.
2. **Build our own Android 17 GSI** - `ashfall_gsi_arm64` (inherits
   LineageOS's `lineage_gsi_arm64`) + ported TrebleDroid fixes needed by the RP5.
3. **Hardware test** - orchestrator flashes, user tests and reports.
4. **Fix GSI issues** (rotation, crashes, broken hardware).
5. **Base system tools** - fan control, RGB, gamepad calibration.
6. **Custom features** - overclocking, gamepad fusion, battery indicators, sidebar.
7. **Slim down.**

## Status
- **v0.1.0-gsi (2026-11-13)**: base GSI done and hardware-verified on the RP5 (stock
  vendor): boots, landscape display + touch, Wi-Fi, BT, audio, brightness, battery,
  vibration, USB adb/MTP, built-in gamepad (incl. Home/Back). Tested build
  20261113-163514 (sha256 eeac1deb...). Open: fan + stick RGB (#45, phase 5),
  flash-gsi serial auto-detect (#48).
- Next: phase 5 (fan, RGB, gamepad calibration, AshFall settings menu) and part of
  phase 6, per the user's spec. Custom kernel (overclock) comes after phases 5/6.

## Decisions (with the user, 2026-11-12)
- Own A17 GSI (not a device build first). LineageOS 24.0 ships a vanilla
  `lineage_gsi_arm64` target; TrebleDroid only has `android-16.0`, so fixes are
  ported selectively. Reference for RP5 hardware quirks: the community device
  tree at github.com/Ayn8250Dev (`android_device_retroidpocket_RP5`,
  `android_device_ayn_sxr2130p-common`, kernel, vendor), branch lineage-24.0.
- Build host: user's PC (Fedora Silverblue 45, 7800X3D, 64 GB RAM, ~930 GB free),
  over SSH, inside distrobox `ashfall-build` (Ubuntu 24.04). Nothing on the host.
- Forks of upstream repos live in the GitHub org `ashfall-rom`. While iterating,
  framework changes are `.patch` files in this repo (`patches/`); a fork is
  created only when a patch set stabilises.
- No EDL backup step (user has bootloader backup + official firmware).

## Known risks (verify early)
- **VINTF**: Android 17 framework only ships compatibility matrices for FCM
  level 7+. If the stock vendor manifest declares `target-level` < 7, expect the
  "internal problem" dialog / HAL issues.
- **Kernel**: the STOCK kernel is **5.15.148 (android13-5.15 GKI)** with EROFS,
  F2FS casefold, full BPF, cgroups, PSI, io_uring (see docs/stock-analysis.md,
  PR #10). The 4.19 kernel and `ro.bpf.kver_override` belong to the community
  device build only and do NOT apply to the GSI on stock vendor.
- **Super space**: group `qti_dynamic_partitions_a` = 6.0 GiB. A GSI replaces
  system_a and may delete stock product_a/system_ext_a (GSIs bundle them), giving
  a ~4.2 GiB budget for system.img. **Decision (revised 2026-11-13): the GSI is
  ext4.** The stock kernel supports EROFS, but the stock first-stage fstab (vendor_boot)
  only lists `system` as ext4, so an EROFS system cannot mount (issue #39: init panics
  with "Invalid ext4 superblock ... Failed to mount /system").
- **Idle power (UNSOLVED, #233)**: stock idles at 1-3 % per 10 h (~5-15 mA);
  this GSI draws ~150 mA with the panel off.  Not the daemons, the alarm wake
  loop, the radios, the displays, the WLAN PCIe runtime PM or s2idle entry -
  all measured and excluded, and userspace cannot attribute it (no ODPM).
  Suspects left are device-level (`a600000.dwc3` stays runtime-active, three
  DSPs running).  A second symptom appeared on 2026-11-21: the device powers
  itself off after ~10-15 min unplugged and idle (PMIC/`PS_HOLD`, not a kernel
  panic - pstore is empty).  Details, numbers and next steps:
  `docs/power-idle.md`.
- **Fastbootd**: the RP5 ABL rejects `fastboot reboot fastboot`; fastbootd is reached
  via `fastboot reboot recovery` + `adb reboot fastboot` (issue #38, scripts handle it).
- **Debugging boot crashes**: panics go to Qualcomm crash-dump mode (05c6:900e);
  `scripts/device/ramdump.sh` extracts the kernel log. `/dev/kmsg` is rate-limited, so
  boot once via `fastboot boot` with `printk.devkmsg=on` (issue #39).
- **Display** is natively portrait (needs rotation), gamepad is an MCU.

## Repository layout (CONTRACT)
| Path | Owner / meaning |
|---|---|
| `config/ashfall.conf` | shared config (orchestrator) |
| `scripts/lib/common.sh` | shared shell library (orchestrator; append-only) |
| `scripts/host/` | build-host bootstrap (distrobox creation, deps, doctor) |
| `scripts/remote/` | remote job runner (start/status/logs/fetch of long jobs in tmux) |
| `scripts/sync.sh`, `manifests/` | repo init/sync of `REMOTE_SRC` (+ local manifests) |
| `scripts/build.sh` | overlay `aosp/`, apply `patches/`, lunch, build, collect artifacts |
| `scripts/patches.sh` | apply/reset `patches/` onto the tree |
| `aosp/<tree-path>/...` | files mirrored verbatim into `REMOTE_SRC/<tree-path>` before a build (e.g. `aosp/vendor/ashfall/`) |
| `patches/<tree-path>/NNNN-*.patch` | `git am` patches applied onto the project at `REMOTE_SRC/<tree-path>` |
| `scripts/firmware/` | stock firmware fetch/extract/inspect |
| `scripts/device/` | flashing, log collection, device info (runs where the RP5 is plugged: the build host) |
| `docs/` | documentation; `docs/reference/` = user-provided references |

### Build-host conventions (CONTRACT)
- Every remote command goes through `host_sh` / `box_sh` / `box_sh_locked`.
- There is ONE shared tree (`REMOTE_SRC`). Anything that mutates it (sync,
  patch, overlay, build) MUST hold `REMOTE_TREE_LOCK` (`box_sh_locked`).
- Builds always start from a clean state: `repo forall -c 'git reset --hard; git clean -fdq'`
  on patched projects -> overlay `aosp/` -> apply `patches/` -> build.
- **Builds are run by the orchestrator only**, one at a time (`scripts/build.sh`,
  `BUILD_JOBS=12`). soong analysis alone peaks at ~46 GB RSS on the 62 GB host
  (+32 GB zram). Workers push their branch and report "ready to build"; they never
  run build.sh themselves unless their brief explicitly says so.
- Long jobs (sync, build) run detached in tmux on the host with a log under
  `REMOTE_LOGS`; never block an SSH session for hours.
- The RP5 is USB-connected to the build host. fastboot/adb run there.
- NEVER flash device-unique partitions (see docs/reference/rp5-recovery-guide.md §13).
