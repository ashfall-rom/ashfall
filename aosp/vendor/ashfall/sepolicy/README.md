# AshFall sepolicy

Device policy for the RP5 daemons (phase 5, issue #60): `ashfall-hwd` (fan +
stick lighting) and `ashfall-rp5-input` (gamepad bridge, calibration, deadzones,
control socket).

Wired into the build from `vendor/ashfall/ashfall_common.mk`:

```makefile
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/ashfall/sepolicy
```

## Why system_ext and not vendor

AshFall is a GSI: `/system` and `/system_ext` are replaced by our image, but the
`/vendor` partition stays stock (and its policy with it).  `BOARD_SEPOLICY_DIRS`
goes into the vendor policy, so nothing there would ever be loaded on the
device.  `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` is compiled into the system_ext
policy (`system_ext_sepolicy.cil`), which init loads next to the platform
policy, and `system_ext_file_contexts` / `system_ext_property_contexts` are
merged into the contexts the image build and libselinux use.  The old
`patches/system/sepolicy/0001` patch was replaced by this directory.

## Files

| file | contents |
|---|---|
| `ashfall_hwd.te` | `ashfall_hwd` domain, sysfs types for fan/LED attributes, thermal read + trip write for the performance override, GPU devfreq cap type |
| `ashfall_rp5_input.te` | `ashfall_rp5_input` domain, `/data/misc/ashfall`, control socket |
| `ashfall_props.te` | `ashfall_prop` (`persist.ashfall.`), `ashfall_status_prop` (`ashfall.status.`), `ashfall_ui_prop` (`ashfall.ui.`) and `ashfall_perf_prop` (`ashfall.perf.`, `persist.ashfall.perf.`) |
| `file_contexts` | binaries, `/dev/socket/ashfall_input`, `/data/misc/ashfall` |
| `genfs_contexts` | sysfs types for `gpio5_pwm2`, the `sn3112` LED classes and the three relabelled kgsl devfreq attributes |
| `property_contexts` | the four property prefixes (the `persist.ashfall.perf.` entry is more specific than `persist.ashfall.`) |

## Notes

* Both daemons are `coredomain`s.  A coredomain may not open generic sysfs
  (`private/coredomain.te` neverallow), which is why the fan/LED attributes get
  dedicated `genfscon` types.  `/sys/class/leds` is already labelled
  `sysfs_leds` and may not be used from a coredomain at all; it is the PMIC
  notification LED, not the stick rings (see `docs/rp5/hw.md` §4).
* Thermal zones need no new label: the stock vendor policy labels
  `/devices/virtual/thermal` and `/class/thermal` as `sysfs_thermal`.
* Property writing is restricted to `system_app` (the AshFall Parts app) for
  `persist.ashfall.*` and to `ashfall_hwd` for `ashfall.status.*`.
* `genfs_contexts` lists the plausible resolved paths for the fan/LED class
  devices; verify them on the device (`readlink -f /sys/class/...`) after the
  first flash and trim the list (docs/rp5/hw.md §5).
* **GPU cap (phase 6).**  `/sys/class/kgsl/**` is labelled `vendor_sysfs_kgsl`
  by the stock vendor policy.  A system_ext policy cannot reference vendor
  types, and the node's label is stored by userspace `restorecon` from
  `vendor_file_contexts` (which is loaded *after* `system_ext_file_contexts`),
  so a plain allow/genfscon is not enough.  `file_contexts` relabels exactly
  `gpu_available_frequencies`, `devfreq/.../max_freq` and
  `devfreq/.../min_freq` with `ashfall_gpu_sysfs`, using **exact pathnames**
  (every dot escaped).  libselinux `sort_specs()` moves exact pathnames behind
  all regex specifications, so they win over the vendor regexes regardless of
  load order; `genfs_contexts` carries the same three paths as a fallback.
  Directory traversal needs no new rule: the stock vendor policy grants
  `search` on `vendor_sysfs_kgsl:dir` to all domains.  The daemon must open
  these **resolved** paths directly: following the `/sys/class/kgsl` symlinks
  needs `lnk_file read` on `vendor_sysfs_kgsl`, which system_ext cannot grant
  (issue #115).  Verify on the device after the system build with `ls -Z` on
  the three files (docs/rp5/gpu.md §4.2/§7).
