# Phase 6 contract: GPU performance (Adreno 650), feature rebuilds and sidebar

Status: contract, 2026-11-14; §8 added 2026-11-15 (GamepadFusion and the
PlayTime indicator re-included by the user). Decisions taken with the user:

- Phase 6 starts with the **GPU**. The ROM keeps the stock **5.15.148 Android
  GKI** kernel (`Linux version 5.15.148-android13-8-gbfe94e95c709`). There is no
  mainline Linux kernel in the Android boot chain and no kernel fork for this
  work: Retroid's mainline fork (Linux 6.12/7.3, used by ROCKNIX/Debian) cannot
  boot an Android GSI, which needs Android's `kgsl`, binder, and vendor modules.
- The Adreno 650 GPU frequency/voltage table lives in the **`vendor_boot` DTB**
  (`qcom,kgsl-3d0@3d00000` -> `qcom,gpu-pwrlevel-bins`), not in the kernel
  image. The GPU clock driver (`gpucc-sm8250.ko`) and KGSL
  (`msm_kgsl.ko`) are stock vendor modules. `clk-qcom.ko` provides
  `clk_gfx3d_determine_rate`/`clk_gfx3d_set_rate`, which program the GPU PLL for
  the rates described by the DT, so the overclock is a **DT data change**, not
  a kernel source change.
- OC steps up to **1000 MHz**, opt-in. The boot/default cap stays the stock
  **670 MHz**; 1 GHz is the top selectable step, never the default.
- The **sidebar** follows the GPU work. The kernel/CPU overclock is not in
  scope, and neither are the charging-LED indicators. **GamepadFusion and the
  PlayTime battery indicator were re-included by the user on 2026-11-15**
  (#138, #137) and run in parallel with the GPU work; see §8.

## 1. Stock facts (measured on build `20261114-led109-bf3c952`)

- GPU: `Adreno650v3` (SM8250, `qcom,chipid = <0x6050002>`), governor
  `msm-adreno-tz`.
- Stock frequencies:
  `305 / 400 / 441.6 / 490 / 525 / 587 / 670 MHz`;
  `gpu_model`/`gpu_available_frequencies` confirm the 670 MHz top.
- `vendor_boot` DTB has four speed-bin tables:
  `qcom,gpu-pwrlevels-0` (speed-bin 0, 670 top),
  `-1` (speed-bin 1, 670 top),
  `-2` (speed-bin 3, 525 top),
  `-3` (speed-bin 2, 670 top).
  The active RP5 unit is a 670-top bin.
- Stock voltage corners in use: `qcom,level` 320 (NOM_L1, ~1.05 V) for 670,
  256 (NOM) for 587, 224 (SVS_L2) for 525, 192 (SVS_L1) for 490, 144 (SVS_L0)
  for 441.6, 128 (SVS) for 400, 64 (LOW_SVS) for 305.
- The GPU rail also defines the higher corners: 352 (NOM_L3), 384 (TURBO),
  416 (TURBO_L1). These are the levels used by the OC table below; they are
  the values KonaBess exposes and the community Adreno 650 1 GHz tables use.

## 2. OC table

The final table is a **replacement** table, not an extension, because the RPMh
`gfx.lvl` table only covers `num_pwrlevels + 1` GPU DCVS votes (issue #116:
stock 8 entries -> 9 votes works, 12 entries -> 13 votes returns `-ERANGE`).
Apply it to every speed-bin table whose stock top is 670 MHz
(`qcom,gpu-pwrlevels-0`, `-1`, `-3`, and the extra `-4` table on the RP5).
Leave the 525-top `-2` bin stock.

| MHz | qcom,level (dec) | corner | acd-level | bus-freq/min/max |
|---|---|---|---|---|
| 1000 | 416 (`0x1a0`) | TURBO_L1 | `0x802b5ffd` | 11/11/11 |
| 940  | 384 (`0x180`) | TURBO    | `0x802b5ffd` | 11/11/11 |
| 850  | 384 (`0x180`) | TURBO    | `0x802b5ffd` | 11/11/11 |
| 765  | 352 (`0x160`) | NOM_L3   | `0x802b5ffd` | 11/11/11 |
| 670  | 320 (`0x140`) | NOM_L1   | stock        | stock          |
| 587  | 256 (`0x100`) | NOM      | stock        | stock          |
| 305  | 64 (`0x40`)   | MIN      | stock        | stock          |
| 0    | —             | off      | —            | 0/0/0          |

The stock **525, 490, 441.6 and 400 MHz steps are dropped**; the resulting table
is 8 entries including the 0 MHz last entry, the same count as stock.

Rules:

- Keep the table strictly descending. Build it from the four OC steps plus the
  stock steps that are not dropped, renumber every `reg` from 0, and keep the
  0 MHz "off" entry last.
- `qcom,initial-pwrlevel` points at the 305 MHz boot entry (index 6 in every
  edited bin).
- `qcom,throttle-pwrlevel` keeps the stock value (bin 0: 0; other bins: 1)
  because the dropped steps are above the LMh throttle mask's bottom entries.
- Do not edit the legacy `qcom,gpu-pwrlevels` node (unused when
  `qcom,gpu-pwrlevel-bins` is present) and do not change `qcom,initial-pwrlevel`
  on the parent `qcom,kgsl-3d0`.
- 1 GHz at TURBO_L1 is marginal on some SD865 units; 940 MHz at TURBO is the
  common stable OC. 1 GHz must remain opt-in and must be stress-tested; if it
  is not stable, reduce the top step to 940 and document it.
- The stock `msm_kgsl.ko` / `gpucc-sm8250.ko` / `clk-qcom.ko` stay untouched.
  The worker must prove that the 1000 MHz rate is accepted (frequency appears in
  `gpu_available_frequencies`, `gpuclk` reaches it, no PLL/clock errors) and
  that the GPU rail voltage rises when `qcom,level` 416 is selected. If the
  stock clock/regulator path clamps or rejects it, stop and report; do not
  patch the vendor modules.

## 3. Default cap and control

New property (already covered by the `persist.ashfall.perf.` ->
`ashfall_perf_prop` prefix, no new property context):

| Property | Type | Writer -> reader | Values / meaning |
|---|---|---|---|
| `persist.ashfall.perf.gpu_max_mhz` | `ashfall_perf_prop` | app -> `ashfall-hwd` | 305..1000; default `670`; missing/invalid -> `670` |

`ashfall-hwd` (new `gpu.c`/`gpu.h`, wired into the existing main loop):

- Resolve `/sys/class/kgsl/kgsl-3d0/devfreq/max_freq` (and `min_freq` only if
  needed) and write the cap in Hz at boot and whenever the property changes.
- Clamp the request to the highest frequency in `gpu_available_frequencies`
  that is <= the request; a missing node or missing OC step is a logged no-op,
  not a crash.
- Publish `ashfall.status.perf.gpu_max_mhz` (actual cap) and
  `ashfall.status.perf.gpu_oc` (`0` = stock 670 or lower, `1` = OC above 670,
  `-1` = sysfs unavailable).
- **Safety interaction:** while the phase-5 throttle override is active
  (`ashfall.perf.throttle_override`, or
  `persist.ashfall.perf.throttle_override_permanent` at boot), clamp the GPU cap
  to 670 MHz and publish `ashfall.status.perf.gpu_oc=0`; log one line. This
  prevents 1 GHz with the GPU clock trips raised to 110 C. The UI must show the
  clamp.
- The SELinux change is minimal: the node is labelled
  `vendor_sysfs_kgsl` on the device; add the `ashfall-hwd` allow (or a dedicated
  `sysfs_type` only if the vendor label cannot be used) and verify with a full
  build. The daemon is a coredomain; do not grant generic sysfs access.

App (AshFall Parts, existing Performance section on the Fan page):

- Add a "GPU max frequency" selector: `670 MHz (stock, default)`, `765 MHz`,
  `850 MHz`, `940 MHz`, and `1000 MHz (experimental)`. Writing it sets
  `persist.ashfall.perf.gpu_max_mhz`.
- 1000 MHz requires a confirmation dialog with the thermal/stability warning.
  >670 MHz shows a warning that sustained performance depends on the fan and
  thermal throttling.
- Show the actual cap and the throttle-override clamp from
  `ashfall.status.perf.*`; no separate app-drawer entry and no new page.

## 4. Tooling and flashing

- `scripts/gpu/build-vendor-boot.sh` (host-side, not an Android build):
  stock `firmware/img/lun4_vendor_boot_a.bin` -> unpack (`unpack_bootimg.py`
  from the AOSP tree) -> decompile the DTB (prebuilt `dtc`) -> apply the final
  8-entry OC table -> recompile -> repack (`mkbootimg.py`) -> validate.  Output
  `out/gpu/<id>/vendor_boot.img` (content-only, 12,931,072 B, the image to
  flash) plus `vendor_boot-padded.img` (96 MiB fit proof/backup), a manifest
  with the stock/output/padded/DTB SHA-256, and the decompiled DTS for review.
  The vendor ramdisk, bootconfig, header fields and the 100 MB partition limit
  are preserved.  The padded image wedges the RP5 ABL (issue #54).
- `scripts/device/flash-vendor-boot.sh`: active-slot guard, `adb reboot
  bootloader` -> **one detached fastboot batch** (`dev_fastboot_batch`, issue
  #54): `--disable-verity --disable-verification flash vbmeta` (non-slotted,
  no `--slot`) -> `flash vendor_boot <content-only>` (non-slotted, no `--slot`)
  -> reboot.  It trims padded images to the v4 header `content_end`, including
  `--stock`, and does not use `partition-size` getvars.  `--stock` restores
  `firmware/img/lun4_vendor_boot_a.bin` (last-resort recovery only).  The
  Android build/flash of `system.img` stays with `scripts/build.sh` /
  `flash-gsi.sh`.
- Published artifacts: the built image is a hash-pinned release in the private
  repo `ashfall-rom/rp5-vendor_boot` (images as release assets, never git
  objects; provenance + DTB sources in the repo).  `config/ashfall.conf` pins
  `VENDOR_BOOT_REPO`/`VENDOR_BOOT_TAG`/`VENDOR_BOOT_SHA256`;
  `scripts/gpu/publish-vendor_boot.sh` publishes (verifies the manifest,
  uploads, re-downloads and re-hashes), and
  `scripts/device/flash-vendor-boot.sh --release [<tag>]` downloads, verifies
  and flashes.  Content-only images only - the padded variant is never
  published (issue #54).
- Workers do not run `scripts/build.sh` or `m`, and do not flash system,
  super, userdata, vendor or the other slot. The GPU worker may run the
  host-side `build-vendor-boot.sh` and static checks; the orchestrator flashes
  (vendor_boot or the system image) serially. Keep the stock vendor_boot
  recovery path ready before the first flash.
- `docs/rp5/gpu.md`: table and voltage corners, DTB edit rules, repack/flash,
  recovery, thermal behavior and the verification procedure.

## 5. Verification

Static:

- [ ] Decompiled output DTB has the final 8-entry table in every 670-top bin,
      the 525-top bin untouched, contiguous `reg`, `initial-pwrlevel=6` and the
      stock `throttle-pwrlevel`; the dropped 525/490/441.6/400 steps are gone.
- [ ] Vendor ramdisk, bootconfig, header fields and all non-GPU DT nodes are
      byte-identical/preserved; the content-only image is 12,931,072 B and the
      padded image fits the partition.
- [ ] Host tests for the DTB transformation and the daemon cap logic pass.

Runtime (after the orchestrator flashes):

- [ ] Stock vendor_boot boots; `gpu_available_frequencies` includes 1000,
      `gpu_model` unchanged; no GPU faults/PLL errors.
- [ ] Default cap is 670 (`max_freq` / `ashfall.status.perf.gpu_max_mhz`); the
      Parts selector changes it at runtime and the value survives reboot.
- [ ] Under the Parts GPU stress test, `gpuclk` reaches 1000 MHz for the top
      step; the GPU rail voltage rises; temperature stays below the stock
      protective trips and the device does not reset.
- [ ] 1000 MHz: 15-30 min stress; if unstable, 940 MHz is the top OC step and
      the docs/UI say so. No GPU faults/hangs, Wi-Fi/BT/LED/fan unaffected.
- [ ] Throttle override + OC: hwd clamps to 670 and the UI shows it.
- [ ] Recovery: flash the OC vendor_boot, boot, restore the stock vendor_boot
      (`--stock`), boot; repeat once.

User:

- [ ] Run a real game at 940 and at 1000 with the fan in the user's normal
      profile; confirm stability, thermals and audible fan behavior; choose the
      daily default (670 unless the user opts in).

## 6. Sidebar (next)

Deferred until the GPU work is verified. Scope: a side panel with app shortcuts,
recent apps and quick controls, matching Settings/Material theming. The concrete
contract/issue is written when the GPU work lands. It is independent of the §8
feature rebuilds and does not include them.

## 7. Ownership

| Component | Paths | Issue |
|---|---|---|
| GPU DTB/repack/flash tooling + daemon cap + SELinux + `docs/rp5/gpu.md` | `scripts/gpu/**`, `scripts/device/flash-vendor-boot.sh`, `aosp/vendor/ashfall/rp5/hw/**`, `aosp/vendor/ashfall/sepolicy/**` | native worker |
| App Performance UI | `aosp/vendor/ashfall/apps/AshFallParts/**`, `docs/parts.md` | app worker |
| Contract, integration, builds, final flash, merge | `docs/phase6.md`, `scripts/build.sh` (orchestrator) | orchestrator |

Only the native worker uses the RP5; the app worker uses the emulator and
mocks the daemon properties. No two workers share a worktree/branch.

## 8. Feature rebuilds re-included (2026-11-15)

The first version of this contract scoped both features out (§3, §6). The user
re-included them on 2026-11-15, with the same rule as everything else in phase 6:
**rebuild the stock-OS apps from the ground up as ROM features**, do not port
them. The issues are the systems of record for behaviour, design, risks and
checklists; this section only fixes scope and boundaries.

- **#137 PlayTime Indicator** - native SystemUI battery-telemetry indicator
  (active-only rolling estimate, status-bar slot or in-place battery-%
  replacement) with its settings on the platform **Settings > Battery** page
  (the project's first `packages/apps/Settings` patch). No Xposed/Vector, no
  companion APK, no Quick Settings tile. Power telemetry is sampled once, in
  `ashfall-hwd`, and shared with #124.
- **#138 GamepadFusion** - a fusion mode of `ashfall-rp5-input`: one uinput
  device, external pads grabbed while it is on, per-pad Xbox/Nintendo layout and
  custom remap. Settings in the Controller page, plus one Quick Settings tile
  that opens a settings dialog. No Magisk module, no second daemon, no
  hide/per-app modes.

Boundaries:

- Both are independent of §1-§5 (GPU) and can run as parallel workers. They do
  not share files: #137 touches SystemUI, the Settings app and `rp5/hw` (#124's
  sampler), #138 touches `rp5/input` and `AshFallParts`.
- The charging-LED indicators stay out of scope (they are the "battery
  indicators" of the `docs/phase5.md` decisions), and so does the kernel/CPU
  overclock.
- The sidebar (§6) is unaffected and still starts after the GPU work is
  verified.
- Verification rules are unchanged: static + host tests, emulator for UI, RP5
  for hardware, orchestrator does the builds and the only device flash.

## 9. Wave 2 contract: Power, Memory, Fusion, PlayTime, charging, desktop, tuning

Status: contract, 2026-11-15. The seven issues (#124, #137, #138, #140, #141,
#131, #130) remain the systems of record for behaviour, rationale, risks and
per-feature checklists. This section fixes only what they share: the feature
order, one writer per path, the telemetry ownership, the Settings patch
boundary and the verification rules. Decisions marked "user" below were taken
with the user on 2026-11-15.

### 9.1 Order

Wave 1, parallel and device-free:

- **#124** AshFall Power page (clocks, presets, live stats) + Power QS tile.
- **#137** PlayTime indicator: SystemUI estimator/element and the
  `Settings > Battery` rows.
- **#138** GamepadFusion: `ashfall-rp5-input` fusion mode, Controller page and
  one QS tile/dialog.
- **#141** AshFall Memory page: swapfile (zram removed in #156).

Wave 2, gated:

- **#140** charging separation: after the `Settings > Battery` page from #137
  exists. Second patch in the same series, never a concurrent edit.
- **#131** desktop mode: after the RP5 is free and runs an AshFall build. Step 0
  is a no-rebuild proof on the device; the pointer uinput device extends
  `ashfall-rp5-input` only after the #138 daemon PR has merged.
- **#130** performance tuning: measure-first, device-exclusive. Only measured
  wins become code; everything else is documented as rejected.

### 9.2 One writer per path (wave 1)

| Worker | Issues | Owns | Never touches |
|---|---|---|---|
| hwd native | #124, #137, #141 daemon + #120, #129 | `aosp/vendor/ashfall/rp5/hw/**`, `aosp/vendor/ashfall/sepolicy/**`, `docs/rp5/hw.md` | `rp5/input/**`, `apps/**`, `patches/**` |
| input native | #138 daemon | `aosp/vendor/ashfall/rp5/input/**`, `sepolicy/ashfall_rp5_input.te`, `docs/rp5/input.md` | `rp5/hw/**`, `apps/**` |
| Power app | #124 app + #132 | `Power*.java`, `hw/Power*.java`, `ui/FanGraphView.java`, `FanFragment.java`, `qs/Power*.java`, `docs/parts-power.md` | any other feature page, `ControllerActivity`, `Memory*` |
| Memory app | #141 app | `Memory*.java`, `hw/Memory*.java`, `docs/parts-memory.md` | any other page |
| Fusion app | #138 app | `ControllerActivity.java`, `ipc/FusionClient.java`, `qs/Fusion*.java`, `ControllerSummary.java`, `docs/parts-fusion.md` | any other page, `FanFragment` |
| SystemUI | #137 SystemUI | `patches/frameworks/base/0005-*.patch`, `docs/playtime.md` | `patches/packages/**` |
| Settings | #137 Settings | `patches/packages/apps/Settings/0001-*.patch`, `docs/settings-battery.md` | `patches/frameworks/**` |

`HandheldActivity.java`, `AndroidManifest.xml`, `TilePreferencesActivity.java`,
`res/values/strings.xml` titles and the page/tile icons are committed by the
orchestrator **before** the workers start (the shell). A worker replaces only
its own stub classes and may add strings under its own prefix
(`power_*`, `mem_*`, `fusion_*`) or layout files it owns.

### 9.3 Shared telemetry: one sampler, one owner

`ashfall-hwd` is the only sampler and the only writer of `ashfall.status.*`.
The 2 s tick reads each node at most once and feeds every consumer; there is no
second timer and no second sysfs reader.

| Property | Type | Meaning |
|---|---|---|
| `ashfall.status.perf.cpu_mhz_silver/gold/prime` | int | effective `scaling_cur_freq`; `-1` unavailable |
| `ashfall.status.perf.gpu_mhz` | int | effective kgsl clock; `-1` unavailable |
| `ashfall.status.mem.swap_mb` | int | applied swapfile size; `0` off; `-1` unavailable |
| `ashfall.status.mem.swap_total_kb` / `swap_used_kb` | int | `/proc/meminfo`; `-1` unavailable |

Battery telemetry is **not** in `ashfall-hwd`: a coredomain may not read
`sysfs_batteryinfo` (`system/sepolicy/private/domain.te` neverallow) and the RP5
battery attributes are `vendor_sysfs_battery_supply`, a vendor type system_ext
cannot reference; relabelling them would break the vendor health HAL. Each
consumer samples `BatteryManager` in its own process instead (SystemUI for the
status bar, Settings for the read-out, AshFall Parts for the Power graph):
`current_ma = abs(BATTERY_PROPERTY_CURRENT_NOW)/1000`, `voltage_mv` from the
sticky `ACTION_BATTERY_CHANGED` `EXTRA_VOLTAGE`, `power_mw =
abs(current_ua) * voltage_mv / 1e6` and `-1` while charging. No process reads
battery sysfs, and no new property carries it.

Rules: invalid/missing values are `-1`, never fabricated; a rejected request is
clamped in the daemon, logged once and the *effective* value is published; the
graph and the status bar treat `-1` as a gap/`—`. Gamepad fusion state is **not**
a property: it is returned by `FUSION STATUS` on `/dev/socket/ashfall_input`
because a pad list does not fit the 92-char limit.

Settings properties this wave introduces (names fixed here so the app and the
daemon workers cannot drift):

| Property | Type | Writer -> reader | Meaning |
|---|---|---|---|
| `persist.ashfall.perf.cpu_max_silver` | `ashfall_perf_prop` | app -> hwd | MHz cap, `0` = stock max |
| `persist.ashfall.perf.cpu_max_gold` | `ashfall_perf_prop` | app -> hwd | MHz cap, `0` = stock max |
| `persist.ashfall.perf.cpu_max_prime` | `ashfall_perf_prop` | app -> hwd | MHz cap, `0` = stock max |
| `persist.ashfall.perf.cpu_gov` | `ashfall_perf_prop` | app -> hwd | one governor for all three policies; default `schedutil` |
| `persist.ashfall.power.preset` | `ashfall_prop` | app only | active preset id (UI state; the daemon applies the caps) |
| `persist.ashfall.mem.swap_mb` | `ashfall_prop` | app -> hwd | `0` = off |
| `persist.ashfall.batt.*` | `ashfall_prop` | Settings -> SystemUI | PlayTime settings (#137), read with `get_prop(system_app, ashfall_prop)` and `get_prop(platform_app, ashfall_prop)` (SystemUI runs as platform_app) |
| `persist.ashfall.pad.fusion` / `.layouts` / `.remap` | `ashfall_prop` | app -> `ashfall-rp5-input` | fusion (#138) |

### 9.4 Settings patch boundary

`packages/apps/Settings` is a new ownership boundary for this project.

- `0001-playtime-battery-page.patch` (#137) and
  `0002-charging-control.patch` (#140) are **sequential**: #140 rebases on
  #137's patch, never the other way round, and the two never edit
  `power_usage_summary.xml` or `strings.xml` concurrently.
- Baseline for #137 at contract time: `packages/apps/Settings` @ `31b1a9f`
  (lineage-24.0 manifest revision, no AshFall patch yet).
- The SystemUI patch is `patches/frameworks/base/0005-*.patch`, based on
  `22de88b7b` + patches `0001`-`0004`. The status bar in this tree is the View
  implementation (`PhoneStatusBarView`, `BatteryMeterView`), not Compose.
- Both patches must apply with `git am --3way` on a pristine project; the
  verifier checks that and reads the diff. No new APK, no launcher entry, no
  reflection into a foreign process.

### 9.5 App pages and tiles

Handheld page order: Fan, Lighting, Display, Controller, **Power**, **Memory**.
One page per feature, no tabs. QS tiles: Fan, Lighting, Power (#124), Fusion
(#138) and Desktop (#131); Memory, PlayTime and charging get none. A tile's tap opens its settings
dialog and long-press opens its page, as the Fan/Lighting tiles do. The
structural shell (rows, manifest, tile routing, summary stubs) is on `main`
before any worker starts, so no two workers edit the same page shell.

### 9.6 Verification

- Wave 1 workers do **not** touch the RP5. Hardware evidence is collected by the
  orchestrator after the integration flash.
- Native: `make -C aosp/vendor/ashfall/rp5/hw/tests check` and
  `make -C aosp/vendor/ashfall/rp5/input/tests check` must pass warning-free;
  the new logic must be testable with `-DASHFALL_HOST` and a fake sink.
- App: the `preview/` Gradle build plus the emulator (`Retroid_Pocket_5` AVD);
  daemon properties are mocked, no device.
- Patches: `git apply --check` against the exact baseline, then the integration
  build proves them.
- Each PR gets an independent verifier thread with zero owned paths; defects
  become GitHub issues, never verifier edits.

### 9.7 Fixed decisions (user, 2026-11-15)

- Fusion off by default; v1 custom remap is the raw `from->to` code list
  (record-a-button later); a motion-only node is never opened or grabbed.
- PlayTime off by default; `capacity_mah=0` means auto; View status bar.
- Memory off by default; the swapfile is the only control (priority 2).
  Android init owns zram0, so the zram feature was removed in #156; the 16 GiB
  cap is enforced in the daemon as well as the UI.
- Power presets exactly as #124 (Low power GPU 305, Balanced 670, Performance
  850); 940/1000 MHz stay manual-only.
- #120 (vendor PERFD-SERVER denial on `ashfall_gpu_sysfs`) and #129 (fan
  `max_pct` kick window) are fixed in the hwd worker; #132 (fan graph duplicate
  sample) in the Power app worker.
- Desktop mode: conditional Launcher3 taskbar, 320 dpi, entry via QS tile +
  Parts row; `L1+R1+Start` switches the pointer mode only. Internal display
  only in v1.
- Performance tuning: Eden, Winlator and one native game; p95 frame time and
  1% low decide; MGLRU/PELT stay hidden/root-only in v1.

### 9.8 Fusion control protocol (fixed so #138's daemon and app can be written in parallel)

The existing `/dev/socket/ashfall_input` seqpacket text protocol keeps its
`GET/SET/RESET CAL` and `RAW`/`GRAB` commands unchanged. Fusion adds:

| Request | Reply |
|---|---|
| `FUSION STATUS` | `OK FUSION <0\|1> PADS <n>` then one `PAD <key> <LAYOUT> <seen_ms> <name...>` per pad (name is the rest of the line, spaces allowed) |
| `FUSION ON` / `FUSION OFF` | `OK` on success, `ERR <reason>` otherwise; same effect as writing `persist.ashfall.pad.fusion` |
| `FUSION LAYOUT <key> <MODE>` | `OK` / `ERR <reason>` |
| `FUSION REMAP <key> <map>` | `OK` / `ERR <reason>`; `map` is the `from->to,from->to` list |
| `FUSION RESCAN` | `OK` after a fresh `/dev/input` scan |

`<key>` is deterministic and space-free: `vendor:product:connection`
(lowercase hex, `usb`/`bt`/`mcu`), never the enumeration order. `<LAYOUT>` is
one of `DEFAULT`, `XBOX`, `NINTENDO`, `SWAP_AB`, `SWAP_XY`, `CUSTOM`. Missing
pads or an unavailable daemon are the app's "daemon unavailable" state, not an
error in the protocol.
