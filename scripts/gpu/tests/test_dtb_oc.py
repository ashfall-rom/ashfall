#!/usr/bin/env python3
"""Host tests for the phase-6 vendor_boot DTB transformation.

Run with::

    make -C scripts/gpu/tests check

The dtc round-trip tests need dtc on PATH (the repo build host has the AOSP
prebuilt); they are skipped otherwise.

SPDX-License-Identifier: Apache-2.0
"""

from __future__ import annotations

import json
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import dtb_oc  # noqa: E402

DTC = shutil.which("dtc")

STOCK_STEPS = (
    (0x27EF6380, 0x140),  # 670
    (0x22FCE8C0, 0x100),  # 587
    (0x1F4ADD40, 0x0E0),  # 525
    (0x1D34CE80, 0x0C0),  # 490
    (0x1A524800, 0x090),  # 441.6
    (0x17D78400, 0x080),  # 400
    (0x122DEE40, 0x040),  # 305
    (0x0, None),           # off
)
SLOW_STEPS = (
    (0x1F4ADD40, 0x0E0),  # 525
    (0x1D34CE80, 0x0C0),  # 490
    (0x1A524800, 0x090),  # 441.6
    (0x17D78400, 0x080),  # 400
    (0x122DEE40, 0x040),  # 305
    (0x0, None),
)


def _entry(index: int, freq: int, level: int | None, indent: str = "\t\t\t\t\t\t") -> str:
    lines = [f"{indent}qcom,gpu-pwrlevel@{index} {{"]
    body = indent + "\t"
    if level is None:
        lines.append(f"{body}qcom,bus-max = <0x00>;")
        lines.append(f"{body}qcom,bus-min = <0x00>;")
        lines.append(f"{body}qcom,bus-freq = <0x00>;")
    else:
        lines.append(f"{body}qcom,acd-level = <0x802b5ffd>;")
        lines.append(f"{body}qcom,bus-max = <0x0b>;")
        lines.append(f"{body}qcom,bus-min = <0x0b>;")
        lines.append(f"{body}qcom,bus-freq = <0x0b>;")
        lines.append(f"{body}qcom,level = <{level:#x}>;")
    lines.append(f"{body}qcom,gpu-freq = <{freq:#x}>;")
    lines.append(f"{body}reg = <{index:#x}>;")
    lines.append(f"{indent}}};")
    return "\n".join(lines)


def _bin(suffix: int, speed_bin: int, steps, indent: str = "\t\t\t\t\t") -> str:
    initial = next(i for i, (_, level) in enumerate(steps) if level == 0x40)
    values = [f"{indent}qcom,gpu-pwrlevels-{suffix} {{"]
    if suffix in (0, 1, 3, 4):
        values.append(f"{indent}\tqcom,throttle-pwrlevel = <{0 if suffix == 0 else 1:#x}>;")
    values.append(f"{indent}\tqcom,initial-pwrlevel = <{initial:#x}>;")
    values.append(f"{indent}\tqcom,speed-bin = <{speed_bin:#x}>;")
    values.append(f"{indent}\t#size-cells = <0x00>;")
    values.append(f"{indent}\t#address-cells = <0x01>;")
    values.append("")
    for index, (freq, level) in enumerate(steps):
        values.append(_entry(index, freq, level, indent + "\t"))
        values.append("")
    values[-1] = f"{indent}}};"
    return "\n".join(values)


def fixture_dts(*, with_bins: bool = True, top: int = 0x27EF6380,
                partial_oc: bool = False) -> str:
    """A minimal kona-like DTS with the GPU nodes the transform cares about."""
    steps = list(STOCK_STEPS)
    if top != 0x27EF6380:
        steps[0] = (top, 0x140)
    if partial_oc:
        steps.insert(0, (1000 * 1000 * 1000, 0x1A0))
    bins = ""
    if with_bins:
        bins = "\n".join(
            [
                "\t\t\t\tqcom,gpu-pwrlevel-bins {",
                '\t\t\t\t\tcompatible = "qcom,gpu-pwrlevel-bins";',
                "",
                _bin(0, 0, steps),
                "",
                _bin(1, 1, steps),
                "",
                _bin(2, 3, SLOW_STEPS),
                "",
                _bin(3, 2, steps),
                "",
                _bin(4, 4, steps),
                "\t\t\t\t};",
            ]
        )
    legacy = "\n".join(
        [
            "\t\t\t\tqcom,gpu-pwrlevels {",
            '\t\t\t\t\tcompatible = "qcom,gpu-pwrlevels";',
            "",
            _entry(0, 0x1C9C3800, 0x0C0, "\t\t\t\t\t"),
            "",
            _entry(1, 0x0, None, "\t\t\t\t\t"),
            "\t\t\t\t};",
        ]
    )
    return f"""/dts-v1/;

/ {{
\t#address-cells = <0x02>;
\t#size-cells = <0x02>;
\tmodel = "Test Kona";
\tcompatible = "qcom,kona";
\tqcom,msm-id = <0x164 0x20001>;
\tqcom,pmic-id = <0x1e 0x20 0x1f 0x21 0x00 0x00>;

\tsoc {{
\t\tcompatible = "simple-bus";
\t\t#address-cells = <0x01>;
\t\t#size-cells = <0x01>;

\t\tqcom,kgsl-3d0@3d00000 {{
\t\t\tcompatible = "qcom,kgsl-3d0";
\t\t\tqcom,initial-pwrlevel = <0x02>;
\t\t\treg = <0x3d00000 0x40000>;

\t\t\tpinctrl-test {{
\t\t\t\tpins = "gpio12", "13", "gpio14";
\t\t\t}};

{legacy}

{bins}
\t\t}};
\t}};
}};
"""


def _block(lines: list[str], node: str) -> list[str]:
    found = dtb_oc.find_node(lines, node)
    if found is None:
        raise AssertionError(f"{node} not found")
    open_idx, close_idx, _ = found
    return lines[open_idx:close_idx + 1]


def _entries(lines: list[str], bin_name: str) -> list[dtb_oc.Entry]:
    found = dtb_oc.find_node(lines, bin_name)
    assert found is not None
    open_idx, close_idx, indent = found
    children = dtb_oc.direct_children(lines, open_idx, close_idx, indent)
    return [dtb_oc.parse_entry(lines, child[1], child[2], child[3]) for child in children]


class DtsTransformTest(unittest.TestCase):
    def test_table(self):
        source = fixture_dts()
        output, report = dtb_oc.transform_dts(source)
        self.assertTrue(report.has_bins)
        self.assertTrue(report.changed)
        self.assertEqual([b.suffix for b in report.bins], ["0", "1", "2", "3", "4"])

        lines = output.split("\n")
        for suffix, throttle in (("0", 4), ("1", 5), ("3", 5), ("4", 5)):
            entries = _entries(lines, f"qcom,gpu-pwrlevels-{suffix}")
            self.assertEqual(len(entries), 12)
            freqs = [entry.freq for entry in entries]
            self.assertEqual(freqs[:5], [1000 * 1_000_000, 940 * 1_000_000, 850 * 1_000_000,
                                    765 * 1_000_000, 670 * 1_000_000])
            self.assertEqual(freqs[-1], 0)
            self.assertEqual(freqs, sorted(freqs, reverse=True))
            self.assertEqual([entry.reg for entry in entries], list(range(12)))
            for entry, (mhz, level) in zip(entries, dtb_oc.OC_STEPS):
                self.assertEqual(entry.freq, mhz * 1_000_000)
                self.assertEqual(entry.value("qcom,level"), level)
                self.assertEqual(entry.value("qcom,acd-level"), 0x802B5FFD)
                for bus in ("qcom,bus-freq", "qcom,bus-min", "qcom,bus-max"):
                    self.assertEqual(entry.value(bus), 0x0B)
            # boot level is still the 305 MHz entry
            self.assertEqual(entries[10].freq, 305_000_000)
            header = _block(lines, f"qcom,gpu-pwrlevels-{suffix}")
            self.assertEqual(dtb_oc.get_cell(header, "qcom,initial-pwrlevel"), 10)
            self.assertEqual(dtb_oc.get_cell(header, "qcom,throttle-pwrlevel"), throttle)

        # The 525 MHz table and the legacy node are untouched.
        pre = fixture_dts().split("\n")
        for name in ("qcom,gpu-pwrlevels-2", "qcom,gpu-pwrlevels"):
            self.assertEqual(_block(pre, name), _block(lines, name), name)

        # The parent initial-pwrlevel is not changed.
        self.assertEqual(len([line for line in lines
                              if line.strip() == "qcom,initial-pwrlevel = <0x02>;"]), 1)

    def test_idempotent(self):
        once, _ = dtb_oc.transform_dts(fixture_dts())
        twice, report = dtb_oc.transform_dts(once)
        self.assertEqual(once, twice)
        self.assertFalse(report.changed)
        self.assertTrue(all(bin_report.already_applied or bin_report.untouched
                        for bin_report in report.bins))

    def test_no_bins(self):
        source = fixture_dts(with_bins=False)
        output, report = dtb_oc.transform_dts(source)
        self.assertEqual(output, source)
        self.assertFalse(report.has_bins)
        self.assertFalse(report.changed)

    def test_rejects_unknown_top(self):
        with self.assertRaises(dtb_oc.DtbError):
            dtb_oc.transform_dts(fixture_dts(top=999_000_000))

    def test_rejects_partial_oc(self):
        with self.assertRaises(dtb_oc.DtbError):
            dtb_oc.transform_dts(fixture_dts(partial_oc=True))


@unittest.skipUnless(DTC, "dtc not available on PATH")
class FdtSpliceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ashfall-gpu-test."))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _compile(self, dts: str, name: str) -> Path:
        src = self.tmp / f"{name}.dts"
        src.write_text(dts)
        out = self.tmp / f"{name}.dtb"
        subprocess.run([DTC, "-I", "dts", "-O", "dtb", "-o", str(out), str(src)],
                       check=True, capture_output=True)
        return out

    def _pipeline(self, blob: bytes) -> bytes:
        stock_parts = dtb_oc.split_blob(blob)
        result = []
        for index, part in enumerate(stock_parts):
            stock_fdt = self.tmp / f"fdt-{index}.dtb"
            stock_fdt.write_bytes(part)
            dts = subprocess.run([DTC, "-I", "dtb", "-O", "dts", str(stock_fdt)],
                                 check=True, capture_output=True, text=True).stdout
            transformed, _ = dtb_oc.transform_dts(dts)
            if transformed == dts:
                result.append(part)
                continue
            oc_dts = self.tmp / f"fdt-{index}.oc.dts"
            oc_dts.write_text(transformed)
            oc_dtb = self._compile(transformed, f"fdt-{index}.oc")
            oc = dtb_oc.parse_fdt(oc_dtb.read_bytes())
            stock = dtb_oc.parse_fdt(part)
            result.append(dtb_oc.serialize_fdt(dtb_oc.replace_gpu_bins(stock, oc)))
        return b"".join(result)

    def test_verify_and_non_gpu_fidelity(self):
        stock = self._compile(fixture_dts(), "stock").read_bytes()
        out = self._pipeline(stock)
        report = dtb_oc.verify_blob(stock, out)
        self.assertEqual(len(report), 1)
        self.assertEqual(report[0].bins_oc, 4)
        self.assertEqual(report[0].bins_slow, 1)

        # The embedded NUL + digits property (dtc's ambiguous "\013" escape)
        # must survive the splice byte-for-byte.
        stock_fdt = dtb_oc.parse_fdt(stock)
        out_fdt = dtb_oc.parse_fdt(out)
        needle = "pinctrl-test"
        find = lambda node: node.name == needle  # noqa: E731
        stock_pins = stock_fdt.root.find(find).get("pins")
        out_pins = out_fdt.root.find(find).get("pins")
        self.assertEqual(stock_pins, out_pins)
        self.assertIn(b"\x0013\x00", stock_pins)

    def test_corrupted_oc_is_rejected(self):
        stock = self._compile(fixture_dts(), "stock").read_bytes()
        out = self._pipeline(stock)
        corrupted = bytearray(out)
        # The 1000 MHz level (0x1a0) appears once per 670-top table.
        needle = struct.pack(">I", 0x1A0)
        index = corrupted.find(needle)
        self.assertGreater(index, 0)
        corrupted[index:index + 4] = struct.pack(">I", 0x180)
        with self.assertRaises(dtb_oc.DtbError):
            dtb_oc.verify_blob(stock, bytes(corrupted))

    def test_serialize_roundtrip(self):
        stock = self._compile(fixture_dts(), "stock").read_bytes()
        fdt = dtb_oc.parse_fdt(stock)
        again = dtb_oc.parse_fdt(dtb_oc.serialize_fdt(fdt))
        self.assertEqual(dtb_oc.masked_signature(fdt.root, mask=False),
                         dtb_oc.masked_signature(again.root, mask=False))
        self.assertEqual(fdt.memreserve, again.memreserve)


if __name__ == "__main__":
    unittest.main(verbosity=2)
