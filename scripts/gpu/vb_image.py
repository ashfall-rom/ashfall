#!/usr/bin/env python3
"""Inspect and compare Android ``vendor_boot`` images (header v4).

Used by ``scripts/gpu/build-vendor-boot.sh`` to prove that the GPU-overclock
repack only changed the DTB: the vendor ramdisk, the ramdisk table, the
bootconfig, the command line and every header field must be identical, and the
new image must fit the vendor_boot partition.

SPDX-License-Identifier: Apache-2.0
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

MAGIC = b"VNDRBOOT"
HEADER_SIZE_V4 = 2128
CMD_LINE_SIZE = 2048
NAME_SIZE = 16
AVB_FOOTER_MAGIC = b"AVBf"


class ImageError(Exception):
    pass


def align(n: int, page: int) -> int:
    return (n + page - 1) // page * page


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class VendorBoot:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.data = self.path.read_bytes()
        if len(self.data) < HEADER_SIZE_V4:
            raise ImageError(f"{self.path}: too small for a vendor_boot image")
        (self.magic, self.header_version, self.page_size, self.kernel_addr,
         self.ramdisk_addr, self.vendor_ramdisk_size) = struct.unpack_from(
             "<8sIIIII", self.data, 0)
        if self.magic != MAGIC:
            raise ImageError(f"{self.path}: bad magic {self.magic!r} (expected VNDRBOOT)")
        self.cmdline = self.data[28:28 + CMD_LINE_SIZE].split(b"\0", 1)[0]
        self.tags_addr = struct.unpack_from("<I", self.data, 28 + CMD_LINE_SIZE)[0]
        name_offset = 28 + CMD_LINE_SIZE + 4
        self.name = self.data[name_offset:name_offset + NAME_SIZE].split(b"\0", 1)[0]
        (self.header_size, self.dtb_size) = struct.unpack_from("<II", self.data, name_offset + NAME_SIZE)
        (self.dtb_addr,) = struct.unpack_from("<Q", self.data, name_offset + NAME_SIZE + 8)
        (self.table_size, self.table_num, self.table_entry_size,
         self.bootconfig_size) = struct.unpack_from("<IIII", self.data,
                                                   name_offset + NAME_SIZE + 16)
        if self.header_version != 4:
            raise ImageError(f"{self.path}: header version {self.header_version}, expected 4")
        if self.page_size not in (2048, 4096, 8192, 16384):
            raise ImageError(f"{self.path}: implausible page size {self.page_size}")

        self.ramdisk_offset = align(self.header_size, self.page_size)
        self.dtb_offset = align(self.ramdisk_offset + self.vendor_ramdisk_size, self.page_size)
        self.table_offset = align(self.dtb_offset + self.dtb_size, self.page_size)
        self.bootconfig_offset = align(self.table_offset + self.table_size, self.page_size)
        self.content_end = align(self.bootconfig_offset + self.bootconfig_size, self.page_size)
        if self.content_end > len(self.data):
            raise ImageError(f"{self.path}: content ends at {self.content_end}, "
                             f"file is {len(self.data)} bytes")

    # -- slices ---------------------------------------------------------
    @property
    def ramdisk(self) -> bytes:
        return self.data[self.ramdisk_offset:self.ramdisk_offset + self.vendor_ramdisk_size]

    @property
    def dtb(self) -> bytes:
        return self.data[self.dtb_offset:self.dtb_offset + self.dtb_size]

    @property
    def table(self) -> bytes:
        return self.data[self.table_offset:self.table_offset + self.table_size]

    @property
    def bootconfig(self) -> bytes:
        return self.data[self.bootconfig_offset:self.bootconfig_offset + self.bootconfig_size]

    @property
    def padding(self) -> bytes:
        return self.data[self.content_end:]

    @property
    def has_avb_footer(self) -> bool:
        return len(self.data) >= 64 and self.data[-64:-60] == AVB_FOOTER_MAGIC

    def summary(self) -> dict:
        return {
            "path": str(self.path),
            "size": len(self.data),
            "header_version": self.header_version,
            "page_size": self.page_size,
            "kernel_addr": self.kernel_addr,
            "ramdisk_addr": self.ramdisk_addr,
            "tags_addr": self.tags_addr,
            "dtb_addr": self.dtb_addr,
            "cmdline": self.cmdline.decode("latin-1"),
            "name": self.name.decode("latin-1"),
            "vendor_ramdisk_size": self.vendor_ramdisk_size,
            "dtb_size": self.dtb_size,
            "ramdisk_table_size": self.table_size,
            "ramdisk_table_entries": self.table_num,
            "ramdisk_table_entry_size": self.table_entry_size,
            "bootconfig_size": self.bootconfig_size,
            "content_end": self.content_end,
            "padding": len(self.padding),
            "has_avb_footer": self.has_avb_footer,
            "sha256": sha256(self.data),
            "dtb_sha256": sha256(self.dtb),
            "ramdisk_sha256": sha256(self.ramdisk),
            "table_sha256": sha256(self.table),
            "bootconfig_sha256": sha256(self.bootconfig),
        }


def compare(stock: VendorBoot, out: VendorBoot, partition_size: int | None) -> dict:
    checks: list[dict] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append({"check": name, "ok": bool(ok), "detail": detail})

    fields = (
        "header_version", "page_size", "kernel_addr", "ramdisk_addr",
        "tags_addr", "dtb_addr", "cmdline", "name", "header_size",
        "table_size", "table_num", "table_entry_size", "bootconfig_size",
    )
    for field in fields:
        left, right = getattr(stock, field), getattr(out, field)
        check(f"header:{field}",
              left == right,
              f"stock={left!r} out={right!r}" if left != right else "")

    check("vendor ramdisk byte-identical",
          stock.ramdisk == out.ramdisk,
          f"{stock.summary()['ramdisk_sha256']} vs {out.summary()['ramdisk_sha256']}")
    check("vendor ramdisk table byte-identical", stock.table == out.table)
    check("vendor bootconfig byte-identical", stock.bootconfig == out.bootconfig)
    check("dtb payload present", out.dtb_size > 0 and out.dtb == out.dtb)
    check("dtb changed by the OC table", stock.dtb != out.dtb,
          f"stock {stock.dtb_size} B -> out {out.dtb_size} B")
    check("output image is full-size zero padding after the content",
          out.data[out.content_end:] == b"\0" * len(out.data[out.content_end:]))
    if partition_size is not None:
        check(f"fits the {partition_size}-byte partition",
              out.content_end <= partition_size,
              f"content {out.content_end} B")
        check("output is padded to the partition size",
              len(out.data) == partition_size,
              f"{len(out.data)} B")
    else:
        check("fits the stock image size", out.content_end <= len(stock.data))

    return {
        "ok": all(item["ok"] for item in checks),
        "checks": checks,
        "stock": stock.summary(),
        "out": out.summary(),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="vendor_boot v4 inspector")
    sub = parser.add_subparsers(dest="cmd", required=True)

    info = sub.add_parser("info")
    info.add_argument("--image", required=True)
    info.add_argument("--json", default=None)

    cmp_parser = sub.add_parser("compare")
    cmp_parser.add_argument("--stock", required=True)
    cmp_parser.add_argument("--out", required=True)
    cmp_parser.add_argument("--partition-size", type=int, default=None)
    cmp_parser.add_argument("--json", default=None)

    args = parser.parse_args(argv)
    try:
        if args.cmd == "info":
            summary = VendorBoot(args.image).summary()
            if args.json:
                Path(args.json).write_text(json.dumps(summary, indent=2) + "\n")
            print(json.dumps(summary, indent=2))
            return 0

        result = compare(VendorBoot(args.stock), VendorBoot(args.out), args.partition_size)
        if args.json:
            Path(args.json).write_text(json.dumps(result, indent=2) + "\n")
        failed = [item for item in result["checks"] if not item["ok"]]
        for item in result["checks"]:
            mark = "ok  " if item["ok"] else "FAIL"
            print(f"{mark} {item['check']}"
                  + (f" ({item['detail']})" if item["detail"] else ""))
        return 0 if result["ok"] else 1
    except ImageError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
