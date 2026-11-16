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

import re
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SEPOLICY = REPO / "aosp/vendor/ashfall/sepolicy"

META_CHARS = ".^$?*+|[({"

# kgsl attributes that ashfall-hwd publishes or caps for the Parts app
# (read-only telemetry plus the devfreq cap/floor), so they carry
# ashfall_gpu_sysfs and not the vendor label.  Every other kgsl attribute
# (gpu_model, max_gpuclk, gpubusy, ...) and the /sys/class/kgsl symlinks must
# keep vendor_sysfs_kgsl: stock vendor domains such as PERFD-SERVER still read
# them through the vendor policy.
KGSL_ATTRIBUTES = (
    "gpu_available_frequencies",  # DT step table (read)
    "max_freq",                   # devfreq cap (read/write)
    "min_freq",                   # devfreq floor (write)
    "gpuclk",                     # live clock, #124 (read)
    "gpu_busy_percentage",        # live load, #156 (read)
)

# Paths (minus the /sys mount prefix) that must be relabelled.
PLATFORM_PATHS = (
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_available_frequencies",
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpuclk",
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage",
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/devfreq/3d00000\\.qcom,kgsl-3d0/max_freq",
    "/devices/platform/soc/3d00000\\.qcom,kgsl-3d0/devfreq/3d00000\\.qcom,kgsl-3d0/min_freq",
)
LEGACY_PATHS = (
    "/devices/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_available_frequencies",
    "/devices/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpuclk",
    "/devices/soc/3d00000\\.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage",
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
        # <path> [<file type>] <context>: a type-qualified entry is still a
        # relabel, so keep it visible to the negative test below.
        if len(parts) == 2:
            entries[parts[0]] = parts[1]
        elif len(parts) == 3:
            entries[parts[0]] = parts[2]
    return entries


class GpuSepolicyTest(unittest.TestCase):
    def test_exact_relabel_paths(self):
        entries = context_entries()
        for spec in PLATFORM_PATHS + LEGACY_PATHS:
            self.assertIn("/sys" + spec, entries)
            self.assertEqual(entries["/sys" + spec], "u:object_r:ashfall_gpu_sysfs:s0")
            self.assertFalse(has_meta(spec), f"{spec} is not an exact pathname")

    def test_no_broad_kgsl_relabel(self):
        # Only the attributes in KGSL_ATTRIBUTES are relabelled, under both DT
        # prefixes, and every spec stays an exact pathname.  A new or broadened
        # kgsl entry (gpu_model, max_gpuclk, a /sys/class symlink, a prefix
        # regex, ...) keeps stock vendor domains working, so it must fail here
        # unless it is added to the allow-list deliberately.
        entries = context_entries()
        expected = {"/sys" + spec for spec in PLATFORM_PATHS + LEGACY_PATHS}
        kgsl = {spec for spec in entries if "kgsl" in spec}
        self.assertEqual(kgsl, expected)
        for spec in sorted(kgsl):
            base = spec.rsplit("/", 1)[-1]
            self.assertIn(base, KGSL_ATTRIBUTES, f"unexpected kgsl relabel: {spec}")
            self.assertEqual(entries[spec], "u:object_r:ashfall_gpu_sysfs:s0")
            self.assertFalse(has_meta(spec), f"{spec} is not an exact pathname")

    def test_genfscon_fallback(self):
        genfs = (SEPOLICY / "genfs_contexts").read_text()
        for spec in PLATFORM_PATHS:
            # genfscon paths are literal (no regex escaping).
            literal = spec.replace("\\", "")
            self.assertIn(f"genfscon sysfs {literal} u:object_r:ashfall_gpu_sysfs:s0", genfs)

    def test_daemon_paths_match_policy(self):
        # Regression guard for issue #115: the daemon must open the resolved
        # device paths (never /sys/class/...), and every path it opens must be
        # covered by both the file_contexts relabel and the genfscon fallback.
        header = (REPO / "aosp/vendor/ashfall/rp5/hw/gpu.h").read_text()
        paths = re.findall(r'"(/sys/devices/[^"]+)"', header)
        self.assertGreaterEqual(len(paths), 3)
        for path in paths:
            self.assertNotIn("/sys/class/", path)
        entries = {spec.replace("\\", ""): context
                   for spec, context in context_entries().items()}
        genfs = (SEPOLICY / "genfs_contexts").read_text()
        for path in paths:
            self.assertEqual(entries.get(path), "u:object_r:ashfall_gpu_sysfs:s0")
            literal = path[len("/sys"):]
            self.assertIn(f"genfscon sysfs {literal} u:object_r:ashfall_gpu_sysfs:s0",
                          genfs)

    def test_domain_allow(self):
        te = (SEPOLICY / "ashfall_hwd.te").read_text()
        self.assertIn("type ashfall_gpu_sysfs, fs_type, sysfs_type;", te)
        self.assertIn("allow ashfall_hwd ashfall_gpu_sysfs:file rw_file_perms;", te)


if __name__ == "__main__":
    unittest.main(verbosity=2)
