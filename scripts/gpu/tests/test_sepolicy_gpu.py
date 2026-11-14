#!/usr/bin/env python3
"""Static checks for the GPU SELinux relabel (phase 6).

The stock vendor policy labels /sys/class/kgsl/** vendor_sysfs_kgsl with
regexes, and system_ext_file_contexts is loaded before vendor_file_contexts.
The GPU attributes are therefore relabelled with *exact pathname* specs (no
unescaped meta characters), which libselinux's sort_specs() moves behind all
regex specifications.  This test keeps that property from regressing.

SPDX-License-Identifier: Apache-2.0
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SEPOLICY = REPO / "aosp/vendor/ashfall/sepolicy"

META_CHARS = ".^$?*+|[({"

# Paths (minus the /sys mount prefix) that must be relabelled.
PLATFORM_PATHS = (
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_available_frequencies",
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/devfreq/3d00000\\.qcom,kgsl-3d0/max_freq",
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/devfreq/3d00000\\.qcom,kgsl-3d0/min_freq",
)
LEGACY_PATHS = (
    "/devices/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_available_frequencies",
    "/devices/soc/3d00000\\.qcom,kgsl-3d0/devfreq/3d00000\\.qcom,kgsl-3d0/max_freq",
    "/devices/soc/3d00000\\.qcom,kgsl-3d0/devfreq/3d00000\\.qcom,kgsl-3d0/min_freq",
)


def has_meta(spec: str) -> bool:
    """True when the file_contexts spec has an unescaped regex meta char."""
    escaped = False
    for ch in spec:
        if escaped:
            escaped = False
            continue
        if ch == "\\":
            escaped = True
            continue
        if ch in META_CHARS:
            return True
    return False


def context_entries():
    entries = {}
    for line in (SEPOLICY / "file_contexts").read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) == 2:
            entries[parts[0]] = parts[1]
    return entries


class GpuSepolicyTest(unittest.TestCase):
    def test_exact_relabel_paths(self):
        entries = context_entries()
        for spec in PLATFORM_PATHS + LEGACY_PATHS:
            self.assertIn("/sys" + spec, entries)
            self.assertEqual(entries["/sys" + spec], "u:object_r:ashfall_gpu_sysfs:s0")
            self.assertFalse(has_meta(spec), f"{spec} is not an exact pathname")

    def test_no_broad_kgsl_relabel(self):
        # Only the three attributes are relabelled; the rest of the kgsl tree
        # must keep the vendor label so stock vendor domains keep working.
        for spec, context in context_entries().items():
            if "kgsl" not in spec:
                continue
            base = spec.rsplit("/", 1)[-1]
            self.assertIn(base, ("gpu_available_frequencies", "max_freq", "min_freq"),
                          f"unexpected kgsl relabel: {spec}")
            self.assertEqual(context, "u:object_r:ashfall_gpu_sysfs:s0")

    def test_genfscon_fallback(self):
        genfs = (SEPOLICY / "genfs_contexts").read_text()
        for spec in PLATFORM_PATHS:
            # genfscon paths are literal (no regex escaping).
            literal = spec.replace("\\", "")
            self.assertIn(f"genfscon sysfs {literal} u:object_r:ashfall_gpu_sysfs:s0", genfs)

    def test_domain_allow(self):
        te = (SEPOLICY / "ashfall_hwd.te").read_text()
        self.assertIn("type ashfall_gpu_sysfs, fs_type, sysfs_type;", te)
        self.assertIn("allow ashfall_hwd ashfall_gpu_sysfs:file rw_file_perms;", te)


if __name__ == "__main__":
    unittest.main(verbosity=2)
