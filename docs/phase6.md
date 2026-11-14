# Phase 6 contract: GPU performance (Adreno 650) and sidebar

Status: contract, 2026-11-14. Decisions taken with the user:

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
- The **sidebar** follows the GPU work. Gamepad fusion and battery indicators
  stay out of scope (user decision). The kernel/CPU overclock is not in scope.

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

Add these steps to every speed-bin table whose stock top is 670 MHz
(`qcom,gpu-pwrlevels-0`, `-1`, `-3`). Leave the 525-top `-2` bin stock.

| MHz | qcom,level (dec) | corner | acd-level | bus-freq/min/max |
|---|---|---|---|---|
| 1000 | 416 (`0x1a0`) | TURBO_L1 | `0x802b5ffd` | 11/11/11 |
| 940  | 384 (`0x180`) | TURBO    | `0x802b5ffd` | 11/11/11 |
| 850  | 384 (`0x180`) | TURBO    | `0x802b5ffd` | 11/11/11 |
| 765  | 352 (`0x160`) | NOM_L3   | `0x802b5ffd` | 11/11/11 |
| 670  | 320 (`0x140`) | NOM_L1   | stock        | stock          |

Rules:

- Keep the table strictly descending. Insert the four steps **above** the stock
  670 entry, renumber every `reg` from 0, and keep the 0 MHz "off" entry last.
- `qcom,initial-pwrlevel` must be increased by 4 in each edited bin so the boot
  level remains the stock 305 MHz entry (bin 0/1/3: 6 -> 10).
- `qcom,throttle-pwrlevel` must also be increased by 4 (bin 0: 0 -> 4, bins 1/3:
  1 -> 5) so the LMh throttle mask still covers the stock top steps plus the
  new OC steps.
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
  from the AOSP tree) -> decompile the DTB (prebuilt `dtc`) -> apply the OC
  table -> recompile -> repack (`mkbootimg.py`) -> validate. Output
  `out/gpu/<id>/vendor_boot.img`, a manifest with the stock/output/DTB SHA-256,
  and the decompiled DTS for review. The vendor ramdisk, bootconfig, header
  fields and the 100 MB partition limit are preserved.
- `scripts/device/flash-vendor-boot.sh`: active slot only, `adb reboot
  bootloader` -> `fastboot flash vendor_boot` -> reboot, AVB verification
  disabled as for the system flash, no wipe. `--stock` restores
  `firmware/img/lun4_vendor_boot_a.bin`. The Android build/flash of
  `system.img` stays with `scripts/build.sh` / `flash-gsi.sh`.
- Workers do not run `scripts/build.sh` or `m`, and do not flash system,
  super, userdata, vendor or the other slot. The GPU worker may run the
  host-side `build-vendor-boot.sh` and static checks; the orchestrator flashes
  (vendor_boot or the system image) serially. Keep the stock vendor_boot
  recovery path ready before the first flash.
- `docs/rp5/gpu.md`: table and voltage corners, DTB edit rules, repack/flash,
  recovery, thermal behavior and the verification procedure.

## 5. Verification

Static:

- [ ] Decompiled output DTB has the four new steps in every 670-top bin, the
      525-top bin untouched, contiguous `reg`, correct
      `initial-pwrlevel`/`throttle-pwrlevel`.
- [ ] Vendor ramdisk, bootconfig, header fields and all non-GPU DT nodes are
      byte-identical/preserved; output image fits the partition.
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
recent apps and quick controls, matching Settings/Material theming; no gamepad
fusion and no battery indicators. The concrete contract/issue is written when
the GPU work lands.

## 7. Ownership

| Component | Paths | Issue |
|---|---|---|
| GPU DTB/repack/flash tooling + daemon cap + SELinux + `docs/rp5/gpu.md` | `scripts/gpu/**`, `scripts/device/flash-vendor-boot.sh`, `aosp/vendor/ashfall/rp5/hw/**`, `aosp/vendor/ashfall/sepolicy/**` | native worker |
| App Performance UI | `aosp/vendor/ashfall/apps/AshFallParts/**`, `docs/parts.md` | app worker |
| Contract, integration, builds, final flash, merge | `docs/phase6.md`, `scripts/build.sh` (orchestrator) | orchestrator |

Only the native worker uses the RP5; the app worker uses the emulator and
mocks the daemon properties. No two workers share a worktree/branch.
