#!/usr/bin/env python3
"""Android boot/vendor_boot image reader + DTBO table lister.

Covers boot header v0-v4 (legacy boot, vendor_boot v3/v4) and the dt_table
format used by the `dtbo` partition. Used by scripts/firmware/extract.sh and
inspect.sh on the build host; no Android SDK tools required.

References:
  system/tools/mkbootimg/include/bootimg/bootimg.h
  system/libufdt/include/libufdt.h (dt_table_header)
"""

from __future__ import annotations

import argparse
import os
import struct
import sys

BOOT_MAGIC = b"ANDROID!"
VENDOR_BOOT_MAGIC = b"VNDRBOOT"
DT_TABLE_MAGIC = 0xD7B7AB1E


def round_up(n: int, page: int) -> int:
    return ((n + page - 1) // page) * page


def parse_boot(path: str) -> dict:
    with open(path, "rb") as f:
        data = f.read()
    info: dict = {"path": path, "file_size": len(data)}
    magic = data[:8]
    if magic == VENDOR_BOOT_MAGIC:
        (header_version,) = struct.unpack_from("<I", data, 8)
        page_size, kernel_addr, ramdisk_addr = struct.unpack_from("<III", data, 12)
        (ramdisk_size,) = struct.unpack_from("<I", data, 24)
        cmdline = data[28:2076].split(b"\0", 1)[0].decode(errors="replace")
        (tags_addr,) = struct.unpack_from("<I", data, 2076)
        name = data[2080:2096].split(b"\0", 1)[0].decode(errors="replace")
        header_size, dtb_size = struct.unpack_from("<II", data, 2096)
        (dtb_addr,) = struct.unpack_from("<Q", data, 2104)
        info.update(
            kind="vendor_boot",
            header_version=header_version,
            page_size=page_size,
            kernel_addr=kernel_addr,
            ramdisk_addr=ramdisk_addr,
            ramdisk_size=ramdisk_size,
            cmdline=cmdline,
            tags_addr=tags_addr,
            name=name,
            header_size=header_size,
            dtb_size=dtb_size,
            dtb_addr=dtb_addr,
            dtb_offset=round_up(header_size, page_size) + round_up(ramdisk_size, page_size),
        )
        if header_version >= 4:
            table_size, entry_num, entry_size, bootconfig_size = struct.unpack_from("<IIII", data, 2112)
            info.update(
                vendor_ramdisk_table_size=table_size,
                vendor_ramdisk_table_entry_num=entry_num,
                vendor_ramdisk_table_entry_size=entry_size,
                bootconfig_size=bootconfig_size,
            )
            # Ramdisk fragments table (v4) and the bootconfig blob after it.
            table_off = round_up(header_size, page_size) + round_up(ramdisk_size, page_size) + round_up(dtb_size, page_size)
            frags = []
            for i in range(entry_num):
                ent = table_off + i * entry_size
                if ent + entry_size > len(data):
                    break
                size, offset, ftype = struct.unpack_from("<III", data, ent)
                fname = data[ent + 12 : ent + entry_size].split(b"\0", 1)[0].decode(errors="replace")
                frags.append({"size": size, "offset": offset, "type": ftype, "name": fname})
            info["ramdisk_fragments"] = frags
            bootconfig_off = round_up(table_off + table_size, page_size)
            info["bootconfig_offset"] = bootconfig_off
            reg = data[bootconfig_off : bootconfig_off + bootconfig_size]
            if len(reg) >= 12:
                bsize, bsum, bmagic = struct.unpack_from("<III", reg, len(reg) - 12)
                if bmagic == 0x0A7E5A9C:
                    info["bootconfig"] = reg[:bsize].decode(errors="replace").splitlines()
                    info["bootconfig_checksum"] = bsum
                else:
                    info["bootconfig"] = reg.split(b"\0", 1)[0].decode(errors="replace").splitlines()
        ramdisk_off = round_up(header_size, page_size)
        info["ramdisk_offset"] = ramdisk_off
        info["kernel_offset"] = None
        info["ramdisk_type"] = sniff_ramdisk(data[ramdisk_off : ramdisk_off + 8])
        return info

    if magic != BOOT_MAGIC:
        raise SystemExit(f"{path}: not an Android boot image (magic {magic!r})")

    (header_version,) = struct.unpack_from("<I", data, 40)
    if header_version >= 3:
        # boot_img_hdr_v3/v4: no addresses, page size is fixed at 4096.
        kernel_size, ramdisk_size, os_version, header_size = struct.unpack_from("<IIII", data, 8)
        header_version, = struct.unpack_from("<I", data, 40)
        cmdline = data[44 : 44 + 1536].split(b"\0", 1)[0].decode(errors="replace")
        page_size = 4096
        info.update(
            kind="boot",
            header_version=header_version,
            kernel_size=kernel_size,
            ramdisk_size=ramdisk_size,
            header_size=header_size,
            page_size=page_size,
            cmdline=cmdline,
            name="",
            os_version=f"{(os_version >> 25) & 0x7f}.{(os_version >> 18) & 0x7f}.{(os_version >> 11) & 0x7f}",
            os_patch_level=f"{(os_version >> 4) & 0x7f:04d}-{(os_version & 0xf):02d}",
            extra_cmdline="",
        )
        kernel_off = page_size
        ramdisk_off = kernel_off + round_up(kernel_size, page_size)
        info.update(
            kernel_offset=kernel_off,
            ramdisk_offset=ramdisk_off,
            ramdisk_type=sniff_ramdisk(data[ramdisk_off : ramdisk_off + 8]) if ramdisk_size else None,
        )
        return info

    (
        kernel_size,
        kernel_addr,
        ramdisk_size,
        ramdisk_addr,
        second_size,
        second_addr,
        tags_addr,
        page_size,
        header_version,
        os_version,
    ) = struct.unpack_from("<10I", data, 8)
    name = data[48:64].split(b"\0", 1)[0].decode(errors="replace")
    cmdline = data[64:576].split(b"\0", 1)[0].decode(errors="replace")
    info.update(
        kind="boot",
        header_version=header_version,
        kernel_size=kernel_size,
        kernel_addr=kernel_addr,
        ramdisk_size=ramdisk_size,
        ramdisk_addr=ramdisk_addr,
        second_size=second_size,
        second_addr=second_addr,
        tags_addr=tags_addr,
        page_size=page_size,
        os_version=f"{(os_version >> 25) & 0x7f}.{(os_version >> 18) & 0x7f}.{(os_version >> 11) & 0x7f}",
        os_patch_level=f"{(os_version >> 4) & 0x7f:04d}-{(os_version & 0xf):02d}",
        name=name,
        cmdline=cmdline,
        extra_cmdline=data[608:1632].split(b"\0", 1)[0].decode(errors="replace"),
    )
    if header_version >= 1:
        recovery_dtbo_size, recovery_dtbo_offset, header_size = struct.unpack_from("<IQI", data, 1632)
        info.update(
            recovery_dtbo_size=recovery_dtbo_size,
            recovery_dtbo_offset=recovery_dtbo_offset,
            header_size=header_size,
        )
    if header_version >= 2:
        (dtb_size,) = struct.unpack_from("<I", data, 1648)
        (dtb_addr,) = struct.unpack_from("<Q", data, 1652)
        info.update(dtb_size=dtb_size, dtb_addr=dtb_addr)

    kernel_off = page_size
    ramdisk_off = kernel_off + round_up(kernel_size, page_size)
    second_off = ramdisk_off + round_up(ramdisk_size, page_size)
    info.update(
        kernel_offset=kernel_off,
        ramdisk_offset=ramdisk_off,
        second_offset=second_off,
        ramdisk_type=sniff_ramdisk(data[ramdisk_off : ramdisk_off + 8]) if ramdisk_size else None,
    )
    return info


def sniff_ramdisk(head: bytes) -> str:
    if head.startswith(b"\x02\x21\x4c\x18"):
        return "lz4-legacy"
    if head.startswith(b"\x04\x22\x4d\x18"):
        return "lz4"
    if head.startswith(b"\x1f\x8b"):
        return "gzip"
    if head.startswith(b"BZh"):
        return "bzip2"
    if head.startswith(b"\xfd7zXZ"):
        return "xz"
    if head.startswith(b"070701") or head.startswith(b"070702"):
        return "cpio"
    return "unknown"


def extract_boot(info: dict, outdir: str) -> None:
    os.makedirs(outdir, exist_ok=True)
    with open(info["path"], "rb") as f:
        data = f.read()

    def save(name: str, offset: int, size: int) -> None:
        if not size:
            return
        path = os.path.join(outdir, name)
        with open(path, "wb") as out:
            out.write(data[offset : offset + size])
        print(f"{name}: {size} bytes @ {offset} -> {path}")

    if info["kind"] == "vendor_boot":
        save("vendor_ramdisk", info["ramdisk_offset"], info["ramdisk_size"])
        save("dtb", info["dtb_offset"], info["dtb_size"])
        if info.get("bootconfig_offset") is not None:
            with open(os.path.join(outdir, "bootconfig.txt"), "w") as out:
                out.write("\n".join(info.get("bootconfig", [])) + "\n")
            print(f"bootconfig.txt -> {os.path.join(outdir, 'bootconfig.txt')}")
    else:
        save("kernel", info["kernel_offset"], info["kernel_size"])
        save("ramdisk", info["ramdisk_offset"], info["ramdisk_size"])
        if info.get("second_size"):
            save("second", info["second_offset"], info["second_size"])
        if info.get("dtb_size"):
            save("dtb", info["second_offset"] + round_up(info["second_size"], info["page_size"]), info["dtb_size"])


def list_dtbo(path: str) -> int:
    with open(path, "rb") as f:
        data = f.read()
    # Qualcomm's dtbo dumps store the table big-endian; AOSP mkdtimg writes LE.
    if data[:4] == b"\xd7\xb7\xab\x1e":
        endian = ">"
    elif struct.unpack_from("<I", data, 0)[0] == DT_TABLE_MAGIC:
        endian = "<"
    else:
        raise SystemExit(f"{path}: not a dt_table (magic {data[:4].hex()})")
    magic, total_size, header_size, entry_size, count, entries_offset, page_size, version = struct.unpack_from(
        endian + "8I", data, 0
    )
    print(f"dt_table: version={version} total_size={total_size} header_size={header_size} entry_size={entry_size} count={count} page_size={page_size}")
    for i in range(count):
        off = entries_offset + i * entry_size
        size, dtb_offset = struct.unpack_from(endian + "II", data, off)
        ident, rev = struct.unpack_from(endian + "II", data, off + 8)
        print(f"  [{i}] size={size} offset={dtb_offset} id=0x{ident:08x} rev=0x{rev:08x}")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image")
    ap.add_argument("--info", action="store_true", help="print parsed header fields (default)")
    ap.add_argument("--extract", metavar="DIR", help="extract kernel/ramdisk/dtb into DIR")
    ap.add_argument("--dtbo", action="store_true", help="treat image as a dt_table (dtbo partition)")
    args = ap.parse_args(argv)
    if args.dtbo:
        return list_dtbo(args.image)
    info = parse_boot(args.image)
    for k, v in info.items():
        if k == "ramdisk_fragments":
            print(f"{k}: {len(v)}")
            for frag in v:
                print(f"  - {frag}")
        elif k == "bootconfig":
            print(f"{k}: {len(v)} lines")
            for line in v:
                print(f"  {line}")
        elif k == "cmdline":
            print(f"{k}: {v!r}")
        else:
            print(f"{k}: {v}")
    if args.extract:
        extract_boot(info, args.extract)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
