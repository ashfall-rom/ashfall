# TrebleDroid fixes → LineageOS 24.0 (Android 17) for the RP5

Third-pass triage (review round 3). The first pass assumed a 4.19 kernel; the stock firmware analysis
([`docs/stock-analysis.md`](stock-analysis.md), issue #2 / PR #10) proves otherwise, so every
verdict below is re-derived from the device the GSI will actually run on.

| fact | value | source |
|---|---|---|
| SoC | Snapdragon 865 / SM8250 "kona", Adreno 650 | stock props |
| **stock kernel (the GSI's kernel)** | **5.15.148-android13-8 GKI**: `CONFIG_BPF_SYSCALL/JIT/JIT_ALWAYS_ON/CGROUP_BPF/BTF=y`, `CONFIG_EROFS_FS=y`, `CONFIG_EXFAT_FS=y`, cgroups (`MEMCG`, `CPUSETS`, `CPUACCT`, `BLK_CGROUP`, `CGROUP_SCHED`), PSI | `docs/stock/kernel-config.txt` |
| community full-ROM kernel (not ours) | 4.19.325 (`android_kernel_ayn_sxr2130p`) | community tree |
| vendor | Android 13, API 33, `ro.vndk.version=33`, **VINTF `target-level="7"`** measured, no rejected HAL versions | `docs/stock/vintf/`, `fcm-report.md` |
| vendor HAL styles | audio/effect HIDL **6.0**, Bluetooth HIDL **1.0**, gatekeeper HIDL 1.0, **keymint AIDL v2** (+ legacy keymaster 4.0 HIDL binaries), AIDL light/power/health/drm/vibrator | `docs/stock/vintf/manifest*.xml` |
| system to replace | QSSI Android 13 image (`ro.product.system.device=qssi`) | stock props |
| display | physically portrait; stock hint `persist.panel.orientation=90` lives in the replaced **system** image; no `/vendor/etc/displayconfig/`; vendor framework resources come via `/vendor/overlay/FrameworksResTarget_Vendor.apk` | `docs/stock/props-system.txt`, #19 |
| `/vendor/apex` | does **not** exist | #19 |
| device compatibility matrix | requires `vendor-ndk 33`, `system-sdk 33` and six legacy framework HIDL HALs | `docs/stock/vintf/compatibility_matrix.xml` |
| camera / modem | none in use | device spec |

**Conclusion:** an A17 GSI over this vendor runs on a fully capable 5.15 GKI kernel with a
clean level-7 VINTF match. Almost nothing from TrebleDroid applies. `ro.bpf.kver_override`
must **not** be set (it is a 4.19 device-build workaround and would only downgrade kernel
feature detection), and the 4.19-only patch family is wrong for this device.

One patch survives; everything else is dropped with the stock evidence that makes it
unnecessary, or kept as an explicit `maybe` with the first-boot trigger that would justify it.

## Sources and method

| Source | What was used |
|---|---|
| `ponces/treble_aosp` @ `android-16.0` | `patches/trebledroid/**.patch` — 228 curated `git format-patch` files (the TrebleDroid fix set for Android 16) |
| `TrebleDroid/device_phh_treble` @ `android-16.0` | `system.prop`, `rw-system.sh`, `vndk-detect`, `remove-telephony.sh` |
| `MisterZtr/LineageOS_gsi` + `treble_manifest` @ `lineage-23.2` | closest published LineageOS GSI patch set (Android 16) |
| `Ayn8250Dev/*` @ `lineage-24.0` | community RP5 full-ROM tree (4.19) — used for device quirks only |
| `LineageOS/android_*` @ `lineage-24.0`, AOSP `android17-release` | Android 17 sources (pinned in [Appendix](#appendix-pinned-source-revisions)) |
| `docs/stock/**` (PR #10) | kernel config/version, VINTF manifests + FCM report, props, fstab, init scripts, partition layout |

"Applies with `git am`" was never accepted as "needed"; each verdict cites the A17 code path
and/or the stock artefact. Verifier issues [#15](https://github.com/ashfall-rom/ashfall/issues/15)–[#19](https://github.com/ashfall-rom/ashfall/issues/19), [#24](https://github.com/ashfall-rom/ashfall/issues/24) and [#25](https://github.com/ashfall-rom/ashfall/issues/25) are folded in.

## Triage table

Legend — **Needed?** `yes` = ported to `patches/`; `no` = not needed (evidence in reason);
`maybe` = only if the device log shows the symptom.

### Framework / boot

| Source patch | What it fixes | Needed? | Reason |
|---|---|---|---|
| `frameworks_base/0001` disable vendor mismatch warning | "internal problem with your device" system-error dialog | maybe | **not ported — verify on first boot (phase 3).** `Build.isBuildConsistent()` is `VintfObject.verifyBuildAtBoot()`, and on this vendor that check should pass: `target-level` 7 matches, no HAL version is rejected, and libvintf has no `optional`-attribute handling, so the device matrix's `optional="false"` HALs are not enforced at runtime. If the dialog *does* appear, re-add TD `0001` (it hides a genuine VINTF warning, which stays in `logcat` either way) |
| `frameworks_base/0002` brightness parse fallback | system_server crash when the global (resource) display config is unparsable | **yes** | ported — `initFromGlobalXml()` reads framework resources that the stock **vendor RRO** (`FrameworksResTarget_Vendor.apk`) overrides with A13 values; a rejected brightness array kills system_server || `frameworks_base/0039` ignore cgroup creation errors | zygote/AMS abort when a cgroup controller is missing | maybe | not ported — 5.15 exposes every controller (`MEMCG`, `CPUSETS`, `CPUACCT`, `BLK_CGROUP`, `CGROUP_SCHED`), so this cannot fail *for lack of a controller*. See [cgroups](#cgroups-maybe) |
| `frameworks_base/0055` splice() fallback | `adb install` on kernel < 4.14 | no | 5.15 |
| `frameworks_base/0008` service order without qtaguid | SystemServer order on kernels without qtaguid | no | eBPF traffic accounting works |
| `frameworks_base/0041` linear backlight prop | brightness slider curve | maybe | prop-gated (`persist.sys.phh.linear_brightness`); only if the AMOLED curve feels wrong |
| `frameworks_native/0012` gpuservice on BPF-less kernel | gpuservice crash without BPF | no | full BPF (`CONFIG_BPF_JIT_ALWAYS_ON=y`) |
| `frameworks_native/0013` SF HWC backpressure | jank on old HWC | maybe | performance only; revisit after first boot |
| `system_core/0017` ignore dm-verity failures | boot with broken AVB | no | GSI flashed with verification disabled; vendor verity untouched |
| `system_core/0018` service start without cgroupv2 | init refuses to start a service when `createProcessGroup()` fails | maybe | not ported — A17 still aborts service start (`init/service.cpp:759`), but the kernel has every controller; see [cgroups](#cgroups-maybe) |
| `system_core/0015,0019` project quota | old ext4 quota | no | 5.15 ext4 quota present |
| `system_core/0009,0010,0021` FDE/fscrypt hacks | pre-Android-10 FDE | no | vendor 33 uses FBE + metadata encryption v2 (`dm-default-key`) |
| `system_core/0004,0006,0013,0014` panic/uinput/watchdogd/healthd | old-device bring-up tweaks | no | no fingerprint sensor, health HAL 2.1 supported, vendor HALs unmodified |
| `system_core/0001,0012,0023,0024,0026` APEX chain (`ro.apex.updatable`) | vendor APEXes on kernel < 5.0 | no | 5.15, and `/vendor/apex` does not exist on the stock vendor (#19) |
| `build/0001,0002,0003` phh-tree build plumbing | llkd, `init.vndk-nodef.rc`, vndk-28 sepolicy | no | we inherit `lineage_gsi_arm64` |
| `bootable_recovery/0001` bootctrl | unusable recovery slot | no | we do not ship a recovery |

### BPF / netd / SELinux

All of these exist for kernels ≤ 4.14 without eBPF. The stock 5.15 GKI has
`CONFIG_BPF=y`, `CONFIG_BPF_SYSCALL=y`, `CONFIG_BPF_JIT=y`, `CONFIG_BPF_JIT_ALWAYS_ON=y`,
`CONFIG_CGROUP_BPF=y`, `CONFIG_DEBUG_INFO_BTF=y`, and the vendor is itself an Android 13
BPF/netd user. `ro.bpf.kver_override` is a **community 4.19 workaround** and must not be set
on this device (it makes `bpfloader` load a 5.10-era program set on a 5.15 kernel and flips
`is_pre_5_11_kernel` paths for no reason).

| Source patch | What it fixes | Needed? | Reason |
|---|---|---|---|
| `system_bpf/0001,0002,0003` non-fatal BPF loading | `bpfloader` exits/panics on load failure | no | nothing in the stock 5.15 kernel rejects the A17 program set; the patch would hide genuine BPF breakage |
| `system_sepolicy/0002` generic `fs_bpf` for `network_stack` | Tethering denied on kernels < 5.10 | no | 5.15 can label the per-purpose `/sys/fs/bpf` subdirectories. **Also: as specified it does not build** — see [#15](https://github.com/ashfall-rom/ashfall/issues/15) |
| `system_sepolicy/0001` revert "Remove 28.0 compat" | vendor policy compat for API 28 | no | vendor is API 33 |
| `system_netd/0001..0004` rpfilter/FTP/no-bpf exits | tethering/BPF failure handling | no | no BPF failures expected; the exits remain correct for genuinely broken BPF |
| `Connectivity/0001..0009` BPF-less degradation | firewall/stats/tethering fallbacks | no | same |
| `DnsResolver/0001` BPF-less DNS helper | crash without BPF | no | same |

### VNDK / linker

| Source patch | What it fixes | Needed? | Reason |
|---|---|---|---|
| `packages_modules_vndk/0001,0002`, `prebuilts_vndk_v29` | bring back VNDK 28/29 | no | A17 builds `com.android.vndk.v31`…`v34`; vendor needs 33 |
| `system_linkerconfig/0001` disable vndklite | `ro.vndk.lite=true` devices | no | full VNDK 33 vendor |
| `build/0003` vndk-28 sepolicy | sepolicy for vndk-28 | no | — |

### Display / audio / HALs

| Item | Verdict |
|---|---|
| **Display rotation** | **No patch — product properties.** `persist.panel.orientation=90` is a stock **system** prop (replaced by the GSI) and **no A17 component reads it** (checked `frameworks/{base,native,av}`). A17 only honours `ro.surface_flinger.primary_display_orientation` (`SurfaceFlingerProperties.sysprop:196`) and `debug.sf.ignore_hwc_physical_display_orientation` (`SurfaceFlinger.cpp:573`), neither of which stock sets. See recommendations |
| **Touch IDC** | **No patch — product files.** Stock ships the touch IDC under `/system/usr/idc` (the community tree copies `fts_ts.idc` into `/vendor/usr/idc`); a GSI loses it → rotated/wrong touch. Include it in the GSI |
| **hwcomposer** | **No patch.** Vendor HWC HIDL 2.x; A17 still ships `HidlComposerHal` |
| **gralloc** | **No patch.** HIDL allocator 4.0/mapper support still referenced from `frameworks/native/libs/ui/Android.bp` |
| **audio** | **No patch.** Stock vendor is HIDL audio **6.0** (`docs/stock/vintf/manifest.xml`); A17 still builds `libaudiohal@6.0` and lists HIDL 6.0 in `FactoryHal.cpp`. TD `frameworks_av/0025` only revives *5.0* |
| **brightness** | **No patch** (the parse fallback above is different: config parsing). Vendor provides **AIDL** `android.hardware.light.ILights`; A17 supports v1–v3 |
| **sensors** | **No patch.** HIDL sensors 2.1 multihal in stock (`android.hardware.sensors@2.1-multihal.xml`), still built by A17 |
| **keymaster / keymint** | **No patch.** Stock uses **AIDL keymint v2** plus legacy keymaster 4.0 HIDL; A17 supports both |
| **gatekeeper** | **No patch.** HIDL gatekeeper 1.0 in stock; still in A17 |
| **Bluetooth** | **No patch.** Stock is HIDL Bluetooth **1.0**; A17 still builds the HIDL HCI HAL |
| **camera** | n/a — the device has none |
| `frameworks_av/*` (25), `frameworks_base/*` Samsung/Moto/MTK/FOD/IR (≈40), `hardware_interfaces/*` | dropped — wrong vendors/platforms; revisit individual audio-routing patches only if speaker/headphone switching misbehaves |

### Storage / vold

| Source patch | What it fixes | Needed? | Reason |
|---|---|---|---|
| `system_vold/0004` exFAT under `sdfat`/`texfat` | microSD exFAT when the kernel driver is named differently | no | stock 5.15 has `CONFIG_EXFAT_FS=y` (`CONFIG_EXFAT_DEFAULT_IOCHARSET="utf8"`), i.e. the mainline driver named `exfat`, exactly what A17 vold mounts |
| `system_vold/0001..0003,0005` symlink/facedata/rw-system/adoptable | phh-tree and old-device behaviour | no | no `rw-system.sh`, no face unlock |
| `system_extras/0001`, `external_selinux/*` | build-time leniency for the phh device tree | no | we build neither `device/phh/treble` nor a vendor policy |

### Telephony / IMS

RP5 has **no modem use**. The TD `frameworks_opt_telephony`, `frameworks_opt_net_ims` and
telephony-API patches repair specific QcRil/Samsung stacks; none is needed for a SIM-less
device. Removal is a product decision (below).

### cgroups (maybe)

Verifier issue [#18](https://github.com/ashfall-rom/ashfall/issues/18) is right that the stock **vendor** init scripts use cgroup **v1**
paths (`/sys/fs/cgroup/memory/.../tasks`, `mount cgroup none /dev/blkio blkio` in
`vendor/etc/init/hw/init.qcom.rc`) while A17 init mounts its own cgroup2 hierarchy from
`/system/etc/cgroups.json` (`blkio`, `cpu`, `cpuset`) — a mixed-hierarchy setup. A17 still
treats failures as fatal in two places:

- `system/core/init/service.cpp:759-767` — `return Error() << "createProcessGroup(...) failed for service ..."`;
- `frameworks/base/core/jni/com_android_internal_os_Zygote.cpp` (`fail_fn`) and
  `services/core/java/com/android/server/am/ProcessList.java` (`AssertionError`).

The kernel has every controller A17 asks for, and init mounts cgroup2 in first stage (before
vendor init runs), so this should work. It is *not proven* until a boot log exists. Trigger to
promote these back into the patch set:

```
logcat | grep -E "createProcessGroup|Failed to activate cgroups|cgroups_activated"
dmesg  | grep -i cgroup
adb shell cat /proc/cgroups; adb shell mount | grep cgroup
```

If any appears, port TD `system_core/0018` (init) and/or `frameworks_base/0039`
(zygote/AMS) — both are `maybe`, deliberately not shipped.

## Ported patch

| File | Tree | Target | Why |
|---|---|---|---|
| `patches/frameworks/base/0001-display-config-parse-fallback.patch` | `frameworks/base` | `services/core/java/com/android/server/display/DisplayDeviceConfig.java` | the global brightness/display values are framework resources, overlaid by the stock A13 vendor RRO; an array this framework's parser rejects would crash system_server |

It keeps the original author in `From:` and cites the TrebleDroid **blob** URL in
`Ported-from:` — the `blob/` URLs are the only ones that resolve; the original `From:` commit
ids do not exist in `ponces/treble_aosp`
([#19](https://github.com/ashfall-rom/ashfall/issues/19),
[#25](https://github.com/ashfall-rom/ashfall/issues/25)). It does not touch
`system/sepolicy`, so the neverallow problem of
[#15](https://github.com/ashfall-rom/ashfall/issues/15) cannot recur.

The vendor-mismatch dialog patch (TD `frameworks_base/0001`) was **removed** in round 3: A17
still ships `android.frameworks.sensorservice@1.0` HIDL
(`frameworks/hardware/interfaces/sensorservice/1.0/`, verified at the pinned revision) and
libvintf does not enforce the device matrix's `optional="false"` HAL entries at runtime, so
`verifyBuildAtBoot()` is expected to pass on this stock vendor
([#24](https://github.com/ashfall-rom/ashfall/issues/24)). It is listed as a `maybe` above,
with the first-boot trigger that brings it back.

## Recommendations for `aosp/vendor/ashfall` (issue #4)

| Item | Value | Why |
|---|---|---|
| `ro.surface_flinger.primary_display_orientation` | `ORIENTATION_90` | **required** — the only A17 rotation switch for the portrait panel |
| `debug.sf.ignore_hwc_physical_display_orientation` | `true` | rotate in SurfaceFlinger instead of the vendor HWC |
| `persist.panel.orientation` | `90` | stock's hint, **no A17 reader**; set only for parity. It is a system prop, so the GSI must re-add it |
| touch IDC `fts_ts.idc` | `/system/usr/idc/` (or vendor overlay) | lost with the stock system partition; without it touch is rotated |
| VNDK 33 | ensure `com.android.vndk.v33` is installed (it is `system_ext_specific` in `packages/modules/vndk/apex/Android.bp`) | vendor device matrix requires `vendor-ndk 33` |
| `system-sdk` | nothing to set — A17 declares 29…37 (`PLATFORM_SYSTEMSDK_MIN_VERSION := 29`) | matrix requires 33, satisfied |
| legacy framework HIDL HALs | verify `android.hidl.{allocator,memory,token,manager}@1.0`, `android.system.wifi.keystore@1.0` and `android.frameworks.sensorservice@1.0` are in the image | all `optional="false"` in the stock device matrix. All six still exist in A17 (`system/libhidl`, `system/hardware/interfaces`, `frameworks/hardware/interfaces/sensorservice/1.0`); libvintf ignores the `optional` attribute at runtime, so this is a VTS concern, not a boot blocker |
| `arm64.memtag.process.system_server` | not needed | 5.15 without MTE; A17 arms MTE only where the kernel advertises it |
| `ro.bpf.kver_override` | **do not set** | 5.15 kernel; 4.19-only workaround |
| `persist.sys.force_sw_gles` | **do not copy** | stock sets `1` in system, but nothing in A17 reads it; forcing SW GLES on Adreno 650 would be disastrous if anything ever did |
| `ro.surface_flinger.supports_background_blur` | verify, don't guess | community sets `1`, stock does not; enable only if the UI blurs and HWC handles it |

### Telephony / IMS / camera removal (product level)

`lineage_gsi_arm64` inherits `$(SRC_TARGET_DIR)/product/full_base_telephony.mk` for a device
with no SIM slot: inherit `full_base.mk` instead, or `PRODUCT_PACKAGES -= TeleService
TelephonyProvider MmsService CarrierConfig ImsService`; drop
`android.hardware.telephony.{gsm,cdma}.xml` so `FEATURE_TELEPHONY` is false; same for camera
feature XMLs/apps. Do it after the first successful boot.

## If/when the community 4.19 kernel is used

The community full-ROM path (`android_kernel_ayn_sxr2130p`, 4.19.325, replacing vendor) is the
only place the 4.19 assumptions apply. In that world, re-introduce — in this order:

1. `ro.bpf.kver_override=5.10.239` (`properties/product.prop` of the community tree).
2. `system/bpf` non-fatal loader patches (TD `0001..0003`) — a 4.19 kernel will reject part of
   the modern program set and must not boot-loop.
3. The generic `fs_bpf` sepolicy rule (TD `system_sepolicy/0002`) **plus** an amendment to
   `private/bpfloader.te:57` adding `-network_stack` to the neverallow exception list, exactly
   as [#15](https://github.com/ashfall-rom/ashfall/issues/15) describes. Without both, `m sepolicy` fails on the neverallow; TrebleDroid
   only gets away with it because `device_phh_treble/base.mk` sets
   `SELINUX_IGNORE_NEVERALLOWS := true`, which LineageOS does not.
4. `system/vold` exFAT `sdfat`/`texfat` fallbacks (TD `0004`) — a 4.19 Qualcomm/Samsung kernel
   may not ship the mainline `exfat` driver.
5. `system_core/0018` / `frameworks_base/0039` cgroup resilience, for a 4.19 vendor layout.

## Cross-checks for the build owners (not patches)

1. **Filesystem**: `docs/PLAN.md` decides EROFS for the GSI, but the stock first-stage fstab
   declares `system … ext4` (`docs/stock/fstab-vendor-ramdisk.txt`) and the stock analysis
   (§7.10) recommends ext4. Confirm the stock `fs_mgr` mounts an EROFS `system.img` before
   committing to EROFS (issue #1/#4).
2. **SELinux bring-up**: stock bootconfig has no `androidboot.selinux`; A17 policy on an A13
   vendor will deny a lot. Plan a permissive first boot (modified boot/vendor_boot cmdline or
   a tolerant policy) before phase 3.
3. **super space**: only 1.4 GiB free in group A — the GSI must fit or `super` must be
   repacked (issue #1).
4. **Fan/RGB/gamepad**: stock `RPSettings`/`GameAssistant`/`RsMapping` live in the replaced
   system image; the sysfs interfaces survive (phase 5).

## Follow-ups (deliberately not ported)

1. **Vendor-mismatch dialog (maybe, phase 3)** — if the "internal problem with your device"
   dialog appears on the first boot, re-add TD `frameworks_base/0001`. It only suppresses the
   dialog; the `VintfObject.verifyBuildAtBoot()` failure stays in `logcat`, and that log is
   what decides whether a real fix (a legacy HIDL shim or a vendor-matrix change) is needed.
2. **cgroup resilience** — see [cgroups](#cgroups-maybe) for the exact log lines that would
   promote TD `system_core/0018` / `frameworks_base/0039`.
3. **Linear brightness** (`frameworks_base/0041`) and **SF HWC backpressure**
   (`frameworks_native/0013`) — only if the hardware shows the symptom.
4. **Audio routing** (`frameworks_av/0005,0012,0015`) — only if switching misbehaves.
5. **phh settings app / treble_app / vendor_hardware_overlay** — a feature surface that would
   pull `device/phh/treble` into the build.

## Verification

The patch was applied with `git am --3way` onto a **pristine** `lineage-24.0` checkout, locally
and on the build host, the latter cloned from the real synced tree. It does not touch
`system/sepolicy`, `system/bpf` or `system/vold`, so there are no neverallow or kernel-gated
code paths to build-check (the sepolicy patch of an earlier head, which
[#15](https://github.com/ashfall-rom/ashfall/issues/15) proved uncompilable, is gone).

Local:

```
$ git -C frameworks_base rev-parse --short HEAD    # 22de88b7b, pristine
$ git -C frameworks_base am --3way patches/frameworks/base/0001-display-config-parse-fallback.patch
Applying: DisplayManager: survive an unparsable global display configuration
PASS
```

Build host (`epickitrolaz@100.66.37.14`). The LineageOS tree **is synced now**
(`$REMOTE_SRC=/var/home/epickitrolaz/ashfall/src/lineage`, `frameworks/base` at
`22de88b7b` plus the two patches an earlier build run applied); this verification clones it
read-only with `--shared` into `~/ashfall/scratch/`, resets to the pristine base and applies
the current set — no tree lock needed, nothing mutated:

```
$ git clone --shared ~/ashfall/src/lineage/frameworks/base fb && git -C fb checkout 22de88b7b
-- tree HEAD now: 22de88b7b (base 22de88b7b)
== 0001-display-config-parse-fallback.patch
Applying: DisplayManager: survive an unparsable global display configuration
RESULT: ALL PATCHES APPLIED
```

Not verifiable without hardware: that the parse fallback is exercised at all (it only runs if
the vendor RRO's brightness arrays are rejected), and whether the vendor-mismatch dialog
appears. The first boot log (phase 3) settles both.

## Open risks

- **VINTF / dialog**: level 7 matches, no stock HAL version is rejected, and libvintf does not
  enforce the device matrix's `optional="false"` HALs at runtime, so the "internal problem"
  dialog is not expected. If it still appears, TD `frameworks_base/0001` is the one-line
  suppressor to re-add (see the `maybe` row); `logcat` keeps the underlying reason either way.
- **VNDK / system-sdk**: the vendor device matrix requires `vendor-ndk 33` and `system-sdk 33`;
  A17 satisfies both, but the built GSI must actually install `com.android.vndk.v33` — a
  build-config check for issue #4, not a patch.
- **Audio**: `libaudiohal@6.0` exists in A17 today; if a later lineage-24.0 sync drops HIDL
  6.0, port the equivalent of TD `frameworks_av/0025`.
- **Escape hatch**: every dropped patch is listed with the evidence and the exact trigger that
  would bring it back.

## Appendix: pinned source revisions

| Project | Revision |
|---|---|
| `frameworks/base` | `22de88b7b8eb979c816669c7963dee66ae4953cd` |
| `system/bpf` | `1787201ef62581ee5532fdb1e5b0cbc3fb137dab` |
| `system/sepolicy` | `d61fd80832f558d52de026097d30d695634e8b0f` |
| `system/vold` | `11d8982f824d52e8122c7aa732207ba66b8f23c8` |
| `system/core` | `65d661b1a536701e8b118be4e9728d7b5ca727c9` |
| `system/apex` | `ce2bb9f710183c864040401d859874b5723e44d8` |
| `build` | `90d006387398ae6899145e6531a304500cfa0f70` |
| `frameworks/av` | `1c5914737a1a89d0c5b3e52e8289019eab3f8cbf` |
| `frameworks/native` | `a96adf2c6e62dbb22299cd83bbb9ea4bc569e985` |
| `packages/modules/Connectivity` | `7c04d866538e6f689823b769fb5c1e614ca0e295` |
| `hardware/interfaces` | `a0221f11248a924532444fc7de85d41998b9fe1e` |
| `vendor/lineage` (`lineage_gsi_arm64.mk`) | `5a1814d916321aecb72d60d13b5e52bc719251bd` |
| AOSP `packages/modules/vndk` (`android17-release`) | `bcab18ef7ad195c3452e78b2eaf9bf7e4998c171` |
| AOSP `system/linkerconfig` (`android17-release`) | `f6b4bd983b909a907cf7e9fae8a6365d827638b6` |
| AOSP `packages/modules/DnsResolver` (`android17-release`) | `0aeaf9d222ec761b9e0443a01c7a06590b9d1b4f` |
| AOSP `system/libhidl` (`android17-release`) | `061055fc4dad78e5d25a77d9217001794656f16b` (`allocator`, `transport/{manager,memory,token}` present) |
| AOSP `system/hardware/interfaces` (`android17-release`) | `9c3469aa3d4aeec5b2bc8e8ae8fdb4437dab4edb` (`wifi/keystore/1.0`) |
| AOSP `frameworks/hardware/interfaces` (`android17-release`) | `9de6d032a248523c22d0bd86e224d1762355c41e` (`sensorservice/1.0` HIDL **and** `sensorservice/aidl` both present) |
