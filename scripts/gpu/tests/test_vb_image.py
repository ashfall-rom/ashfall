#!/usr/bin/env python3
"""Host tests for the vendor_boot v4 inspector used by the GPU repack.

SPDX-License-Identifier: Apache-2.0
"""

from __future__ import annotations

import struct
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import vb_image  # noqa: E402

PAGE = 4096
HEADER = 2128
CMDLINE = b"bootconfig buildvariant=user"


def make_image(path: Path, *, ramdisk=b"R" * 100, dtb=b"D" * 200,
               table=b"T" * 108, bootconfig=b"B" * 20,
               pad_to: int | None = None) -> None:
    def align(n):
        return (n + PAGE - 1) // PAGE * PAGE

    ramdisk_off = align(HEADER)
    dtb_off = align(ramdisk_off + len(ramdisk))
    table_off = align(dtb_off + len(dtb))
    bootconfig_off = align(table_off + len(table))
    end = align(bootconfig_off + len(bootconfig))

    header = bytearray(HEADER)
    header[0:8] = b"VNDRBOOT"
    struct.pack_into("<I", header, 8, 4)          # header version
    struct.pack_into("<I", header, 12, PAGE)      # page size
    struct.pack_into("<I", header, 16, 0x8000)    # kernel addr
    struct.pack_into("<I", header, 20, 0x1000000)  # ramdisk addr
    struct.pack_into("<I", header, 24, len(ramdisk))
    header[28:28 + len(CMDLINE)] = CMDLINE
    struct.pack_into("<I", header, 28 + 2048, 0x100)  # tags addr
    struct.pack_into("<I", header, 2080 + 16, HEADER)  # header size
    struct.pack_into("<I", header, 2080 + 16 + 4, len(dtb))
    struct.pack_into("<Q", header, 2080 + 16 + 8, 0x1F00000)
    struct.pack_into("<I", header, 2080 + 16 + 16, len(table))
    struct.pack_into("<I", header, 2080 + 16 + 20, 1)
    struct.pack_into("<I", header, 2080 + 16 + 24, 108)
    struct.pack_into("<I", header, 2080 + 16 + 28, len(bootconfig))

    data = bytearray(end)
    data[0:HEADER] = header
    data[ramdisk_off:ramdisk_off + len(ramdisk)] = ramdisk
    data[dtb_off:dtb_off + len(dtb)] = dtb
    data[table_off:table_off + len(table)] = table
    data[bootconfig_off:bootconfig_off + len(bootconfig)] = bootconfig
    if pad_to:
        data.extend(b"\0" * (pad_to - len(data)))
    path.write_bytes(bytes(data))


class VendorBootTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ashfall-vb-test."))

    def tearDown(self):
        for child in self.tmp.iterdir():
            child.unlink()
        self.tmp.rmdir()

    def test_layout(self):
        path = self.tmp / "stock.img"
        make_image(path)
        image = vb_image.VendorBoot(path)
        self.assertEqual(image.header_version, 4)
        self.assertEqual(image.page_size, PAGE)
        self.assertEqual(image.ramdisk, b"R" * 100)
        self.assertEqual(image.dtb, b"D" * 200)
        self.assertEqual(image.table, b"T" * 108)
        self.assertEqual(image.bootconfig, b"B" * 20)
        self.assertEqual(image.cmdline, CMDLINE)
        self.assertEqual(image.table_num, 1)
        self.assertFalse(image.has_avb_footer)

    def test_compare_gpu_only_change(self):
        stock = self.tmp / "stock.img"
        out = self.tmp / "out.img"
        make_image(stock)
        make_image(out, dtb=b"E" * 240, pad_to=1024 * 1024)
        result = vb_image.compare(vb_image.VendorBoot(stock), vb_image.VendorBoot(out),
                                  partition_size=1024 * 1024)
        self.assertTrue(result["ok"], [item for item in result["checks"] if not item["ok"]])

    def test_compare_rejects_ramdisk_change(self):
        stock = self.tmp / "stock.img"
        out = self.tmp / "out.img"
        make_image(stock)
        make_image(out, ramdisk=b"X" * 100, dtb=b"E" * 240, pad_to=1024 * 1024)
        result = vb_image.compare(vb_image.VendorBoot(stock), vb_image.VendorBoot(out),
                                  partition_size=1024 * 1024)
        self.assertFalse(result["ok"])

    def test_rejects_boot_image(self):
        path = self.tmp / "boot.img"
        path.write_bytes(b"ANDROID!" + b"\0" * 3000)
        with self.assertRaises(vb_image.ImageError):
            vb_image.VendorBoot(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
