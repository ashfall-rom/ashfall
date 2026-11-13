# AshFall - orchestrator handoff (2026-11-13, end of session 1)

Read this first, then `docs/PLAN.md` (contracts + decisions + known risks). This file is
the complete state of the project as left by the first orchestrator session.

## 0. Roles and working style (user's instructions)
- You are the ORCHESTRATOR (skill `t3-fanout-orchestrator`). Workers run as T3 threads on
  `{provider:"opencode", model:"llmproxy/worker-free"}` via `t3_thread_launch` with
  `workspaceStrategy:{type:"worktree", branch:..., baseRef:"origin/main", startFromOrigin:true}`;
  every brief starts with "Load the skill `t3-worker-subagents`" + `gh issue view N`.
  Verifiers = separate threads, zero owned paths, file issues. Waits: `t3_thread_wait`
  with `timeoutMs: 240000` (or 3000000 only for >50 min jobs).
- GitHub is the system of record: repo **Epickitrolaz/ashfall** (private), org
  **ashfall-rom** exists for future forks (unused so far). Issues first, one PR per worker.
- The user tests on hardware and reports; the orchestrator builds, flashes, coordinates.
  Ask the user (question tool) between steps; the user wants to be talked to.
- **Never let two workers use the RP5 at the same time** (it happened: #50/#51 vs #52).
  Explicitly hand the device over ("DEVICE FREE") and tell workers what others did.
- **Workers never run `scripts/build.sh`**. Orchestrator builds serially (host OOM'd when
  two builds ran; soong_build alone ~46 GB RSS). Workers end with
  `READY TO BUILD: <branch> <sha>` + PR line. Integration: merge candidate branches into
  a temporary `integ/<x>` branch, push, `scripts/build.sh` from it, flash, user tests,
  then merge PRs and delete integ branch.
- VM disk is tight: remove finished worktrees (`git worktree remove --force` + delete
  branch) after merges. Archive: `/root/archive/`.
- User can't be reached while sleeping; power off the build PC only if asked
  (`DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus gnome-session-quit --power-off --no-prompt`
  works without sudo; `systemctl poweroff` needs auth).

## 1. Project, plan, decisions
- AshFall: LineageOS 24.0 (Android 17) ROM for the **Retroid Pocket 5**. First a GSI on the
  stock vendor (done: tag `v0.1.0-gsi`), later a full device ROM.
- Phases: 1 env (done), 2 GSI build (done), 3 HW test (done), 4 fix GSI (done except
  launcher), 5 base system tools (fan, RGB, gamepad calibration, AshFall settings menu),
  6 custom features (overclock, gamepad fusion, battery indicators, sidebar, launcher),
  7 slim down. **User order: phase 5 + part of 6 next; custom kernel/overclock AFTER
  base tools and custom features. The user will give the phase 5 spec.**
- Decided with user: own A17 GSI (not community device build first); start-fresh repo;
  build host = user's PC; fork org `ashfall-rom`; launcher = **our own clean-room
  launcher** (not reuse stock rp3launcher); navigation = gestures ON, no pill/nav bar,
  gamepad Home/Back unchanged (will be remappable later).

## 2. Hardware facts (RP5)
- SoC SM8250 "kona", Adreno 650, 8/12 GB RAM, UFS (KIOXIA), A/B, dynamic partitions,
  super 12 GiB, group `qti_dynamic_partitions_a` 6.0 GiB (stock used 4.6 GiB: system 1.39,
  vendor 1.78, product 0.875, system_ext 0.486 GiB, + odm, system_dlkm, vendor_dlkm).
- **Stock kernel 5.15.148-android13-8 GKI** (EROFS, F2FS, BPF, cgroups ok). The 4.19 kernel
  belongs only to the community Ayn8250Dev tree. Boot header v4 (vendor_boot), init_boot.
- Vendor: Android 13, API 33, VNDK 33, VINTF target-level 7 (A17 accepts it).
- **Stock first-stage fstab mounts system as ext4 only -> GSI must be ext4** (EROFS GSI
  panicked: "Invalid ext4 superblock ... Failed to mount /system", issue #39).
- Panel 1080x1920 native portrait, 360 dpi; touch = fts_ts; display id
  `local:4630946482288158081`.
- Gamepad: MCU on UART `/dev/ttyHS1` 115200 8N1, frames `A5 D3 5A 3D | seq | cmd | len(le16) | payload | xor`,
  init frame `A5 D3 5A 3D 01 01 0A 00 05 01 00 00 00 05 00 00 00 01 0A`; kernel driver
  `rs_gpio` powers MCU with screen (`/sys/devices/platform/rsgpio/driver_ctl`). Stock
  bridged it in the system image (pservice + RsMapping/librsinput.so). Factory calib
  `/mnt/vendor/persist/retrostation/joys` (world-readable; backup in
  `/root/archive/rp5-persist-retrostation-joys.txt`, sha256 8e847b7c64b6b430...).
- Fan/RGB: stock did it from system-side Retroid code (RPSettings). Nodes documented in
  `docs/stock/fan-control.txt`, `docs/stock/mcu-fan-rgb.txt`, `docs/stock/rpsettings-sysfs-strings.txt`
  (stock fan interface is `gpio5_pwm2`, NOT `fan_info`; LEDs sn3112l/r + leds/{red,green,blue}).
- Bootloader (ABL) quirks: **rejects `fastboot reboot fastboot`** -> reach fastbootd via
  `fastboot reboot recovery` -> stock recovery ("No command" screen, adb works) ->
  `adb reboot fastboot`. **Wedges when a new fastboot process opens between transfers /
  after getvar bursts** -> restore everything in ONE fastboot invocation (#54). Never
  `fastboot getvar all`. Unwedge = user holds Power 15 s, then Vol Down + Power.
- Kernel panics go to Qualcomm crash-dump mode (USB 05c6:900e); `reboot=panic_warm`.
  EDL = 05c6:9008. Button combos and NEVER-flash partition list: `docs/reference/rp5-recovery-guide.md`
  (§5, §13). Device serial 2f184581.

## 3. Infrastructure
- **Orchestrator VM** (this machine): repo at `/root/.t3/projects/ashfall`, worktrees in
  `/root/.t3/worktrees/ashfall/`, gh authed as Epickitrolaz.
- **Build host** = user's PC `epickitrolaz@100.66.37.14` (Tailscale), Fedora Silverblue 45
  (immutable: never install on host, use containers), Ryzen 7 7800X3D 16 threads, 62 GB RAM
  + 32 GB zram, ~600 GB free. Tailscale exit node active with `--exit-node-allow-lan-access=true`
  (set by us; needed so the PC reaches the LAN, e.g. wireless adb at 192.168.0.x).
  Host has udev rule `/etc/udev/rules.d/51-ashfall-qcom.rules` (05c6:9008|900e MODE 0666)
  installed by us with the user's sudo password (sudo needs password; use `request_secret`).
- Containers (distrobox/podman, Ubuntu 24.04): `ashfall-build` (AOSP build env, tmux,
  repo), `ashfall-fw` (firmware analysis), `ashfall-dev` (adb/fastboot, USB; no tmux),
  `ashfall-edl` (bkerler/edl in /opt/edlenv, patched to not reset after memorydump).
  Use `distrobox enter` (as user) for USB, not `podman exec` (root can't see re-enumerated USB).
  Kill hung fastboot with `pkill -x fastboot` (NOT `pkill -f ...`: kills own ssh shell).
- Remote layout (`config/ashfall.conf`): `/var/home/epickitrolaz/ashfall/{src/lineage (synced
  lineage-24.0 tree, 160 GB, 1239 projects), ccache (80G), ws, artifacts, logs, firmware,
  jobs, crash}`; tree lock `.tree.lock`. `BUILD_JOBS=12`.
- Stock .31 firmware extracted at `/var/home/epickitrolaz/ashfall/firmware/img/`
  (lun4_*_a.bin, lun0_super.bin, lun0_vbmeta_system_a.bin, super/ unpacked, vendordump,
  vendor_ramdisk). Only slot-A images extracted. Source: TheGammaSqueeze
  `Android13_RP5_V1.0.0.31_20241026_190102_user` (pre-release).
- Crash dumps: `~/ashfall/crash/20261113-141536` and `-140314` (8 GB each, can be deleted),
  `~/ashfall/crash/boot-debug.img` (stock boot + `printk.devkmsg=on loglevel=8`, use only
  with `fastboot boot`).

## 4. Repo map (main @ 8e584cb + docs/HANDOFF.md)
- `config/ashfall.conf` (contract), `scripts/lib/common.sh` (contract: load_config, host_sh,
  box_sh, box_sh_locked, ws_push, pull_artifacts).
- `scripts/host/{bootstrap,doctor}.sh`, `scripts/sync.sh`, `scripts/remote/{job,lib,manifests}.sh`.
- `scripts/build.sh` (default ext4; `--fs`, `--fetch <id> [--wait]`, `--dry-run`); artifacts
  `$REMOTE_ARTIFACTS/<build-id>/` with build-info.txt, build.prop, installed-files.txt,
  system.img(+sha256), vbmeta.img. Incremental build ~5 min (SystemUI changes ~9 min),
  clean ~3 h. `scripts/patches.sh` applies `patches/<tree-path>/*.patch` (git am).
- `scripts/device/`: `run.sh` (adb/fastboot in ashfall-dev; `run.sh status`), `info.sh`,
  `logs.sh`, `flash-gsi.sh <img> [--wipe] [--slot a] --serial 2f184581 --yes` (works; from
  Android it goes adb->fastbootd; from bootloader via recovery), `restore-stock.sh` (BROKEN
  on this ABL, see #54 - use the one-session script below), `ramdump.sh` +
  `printk_extract.py` (900e dump -> kernel log).
- `scripts/firmware/{fetch,extract,inspect}.sh` + python helpers (lpunpack, bootimg, imgmeta,
  ikconfig, fcm_report) - re-run these on new firmware.
- `aosp/vendor/ashfall/`: `products/ashfall_gsi_arm64.mk` (inherits lineage_gsi_arm64,
  GSI_FILE_SYSTEM_TYPE ext4), `ashfall_common.mk` includes `ui.mk`, `apps.mk`,
  `rp5/rp5.mk` -> `rp5/{display,input,usb}.mk`; `Android.bp` (Calendar stub).
  Fixes: display = `ro.surface_flinger.primary_display_orientation=ORIENTATION_90` +
  `debug.sf.ignore_hwc_physical_display_orientation=true` + `/system/usr/idc/fts_ts.idc`
  (`touch.orientation = ORIENTATION_90`, NOT 270 as in community tree); usb =
  `/system/etc/init/ashfall_usb.rc` (sets persist.vendor.usb.config/vendor.usb.config=adb on
  boot; vendor script otherwise picks diag,adb,serial_cdev on non-user builds); input =
  `ashfall-rp5-input` daemon (C, uinput "AshFall RP5 Gamepad", Home = KEY_HOMEPAGE).
- `patches/frameworks/base/`: 0001 display-config parse fallback (TrebleDroid), 0002 SystemUI
  falsing (vertical fling 0.75 in, zigzag 30%), 0003 SettingsProvider defaults
  (navigation_bar_hint=0, enable_taskbar=0, SETTINGS_VERSION 237). `patches/system/sepolicy/0001`
  (ashfall_rp5_input domain).
- Docs: PLAN.md, building.md, build-host.md, flashing.md, stock-analysis.md + docs/stock/**,
  treble-patches.md, crash/2026-11-13-first-boot.md, rp5/{display,input,usb}.md, ui.md,
  reference/rp5-recovery-guide.md (user's unbrick guide).

## 5. Verified state (tag v0.1.0-gsi + #53)
Working on hardware: boot, landscape display + touch, auto-rotate, Wi-Fi, BT, audio,
brightness, battery, vibration, USB adb + MTP, gamepad (all buttons, sticks, analog
triggers, L3/R3, Home/Back, D-pad nav, survives screen off/on), lock screen swipe, no
gesture pill, gestures. Last flashed GSI build: `20261113-181817-ashfall_gsi_arm64-userdebug-2703f2c67af5`.
Not working / open: fan + RGB (#45, phase 5), launcher (#52).

## 6. Open issues / work in flight
- **#52 AshFall launcher**: worker thread `mcp:fe0e0d4b-6e43-4e28-bf94-735a2f336901`
  (status completed, user steered it to test on an EMULATOR with RP5 screen specs). Branch
  `feat/launcher-52`, worktree `/root/.t3/worktrees/ashfall/feat-launcher-52-6cb3272d5e2c5b02`,
  2 local commits (27da735) NOT pushed, no PR yet. User feedback: no side dock/hotseat
  wasting space; clean-room (no Retroid code/assets); keep Launcher3QuickStep as recents.
  Next: ask the worker (t3_thread_send) to push + open PR with READY TO BUILD, then build/flash/test.
- #54 restore-stock.sh must use ONE fastboot session (details in issue).
- #48 flash-gsi serial auto-detect with stale wireless adb entries.
- #45 fan + RGB (phase 5).

## 7. CURRENT STEP: firmware update .31 -> .189 (agreed plan with user)
Why: the GSI uses everything except /system from stock (kernel, vendor, dtbo, Qualcomm fw);
retail fixes (.152 audio/charging, docks, MCU fw?) live there; .189 is very likely the
final RP5 firmware (RP5 discontinued 2026-09-14). Full .189 image is not publicly available
(user searched); only TheGammaSqueeze .31 dump exists. Retail versions seen: .91, .106,
.152, .167/.168/.169, .189. User's unit originally shipped/ran V1.0.0.189 before a brick.
Steps:
1. DONE: GSI snapshot saved (`/root/archive/ashfall-gsi-on-fw31-snapshot.tgz`), calib saved.
2. DONE: stock .31 restored to slot A with the one-session script
   `/var/home/epickitrolaz/ashfall/jobs/restore/run.sh` (started via
   `setsid nohup distrobox enter --name ashfall-dev --no-tty -- sh run.sh > log.txt`),
   EXIT=0, userdata erase 351 s; booted stock Android (USB 18d1:4ee1 MTP).
3. NOW: user does minimal setup + enables USB debugging (Build number x7, USB debugging,
   accept prompt). Then capture `adb logcat` (all buffers) while the user runs
   Settings -> System -> Retroid Pocket FOTA -> Check version (possibly several hops);
   record the download URLs (incremental packages are only useful as documentation; a full
   package with payload.bin could be extracted directly).
4. After it reports .189 and boots once (likely on slot b): user enters EDL (unplug, power
   off 15 s, hold Vol Up + Vol Down and plug USB; screen stays black; 05c6:9008). Read all
   SOFTWARE partitions with edl in container `ashfall-edl` (firehose loader
   `prog_ufs_firehose_sm8250_lite_lp5.elf` is NOT extracted yet - extract it from the .31
   archive in `/var/home/epickitrolaz/ashfall/firmware/dl/` with 7z, see guide §7.5): boot/init_boot/
   vendor_boot/dtbo/vbmeta/vbmeta_system for BOTH slots, super, recovery, and the firmware
   set (aop cmnlib cmnlib64 devcfg dsp featenabler hyp imagefv keymaster modem multiimgoem tz
   uefisecapp qupfw multiimgqti xbl xbl_config abl), sha256 everything. NEVER read/write
   userdata or touch device-unique partitions (guide §13). Store under
   `/var/home/epickitrolaz/ashfall/firmware-189/`. Anti-rollback: once on .189, assume no
   way back to .31 Qualcomm firmware.
5. Worker: re-run scripts/firmware/{extract,inspect}.sh against .189 (parameterise the
   firmware dir), diff vs .31 (fstab ext4?, init.qcom.usb.sh, VINTF, kernel version/config,
   MCU protocol / RsMapping+librsinput changes, fan/RGB nodes, props), write
   docs/stock-analysis-189.md; update config (STOCK_FW_*), restore script for .189.
6. Flash AshFall on .189 with --wipe (flash-gsi.sh; slot = active slot after FOTA), user
   retests the section-5 checklist. Then phase 5 per user's spec.

## 8. Lessons / gotchas
- /dev/kmsg is rate-limited (10 lines/5 s): to see init errors boot once via
  `fastboot boot boot-debug.img` with `printk.devkmsg=on`, then `ramdump.sh`.
- `erase userdata` takes 351 s after a super flash, 0.09 s otherwise.
- Wireless adb on the RP5 needs PC and RP5 on the same LAN; `adb root` restarts adbd and
  changes the wireless port (find it by TCP scan 30000-50000 from the host).
- LineageOS `lunch ashfall_gsi_arm64-cp2a-userdebug` (release cp2a).
- Launcher3's landscape "side dock" = hotseat (home screen), not the taskbar.
- Worker reports sometimes mix up /tmp/opencode files from other projects (shared dir) -
  ignore files that don't belong to AshFall.
