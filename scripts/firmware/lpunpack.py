#!/usr/bin/env python3
"""Minimal reader/extractor for Android dynamic-partition (liblp) metadata.

Used by scripts/firmware/extract.sh on the build host, where the Ubuntu
android-sdk-libsparse-utils package does not ship lpunpack.

Only what AshFall needs: list partitions/groups/free space and copy linear
extents out of a raw super image. No sparse-image support (firmware dumps are
raw).

Layout reference: system/core/fs_mgr/liblp/include/liblp/metadata_format.h
"""

from __future__ import annotations

import argparse
import os
import struct
import sys

SECTOR = 512
GEOMETRY_MAGIC = 0x616C4467
HEADER_MAGIC = 0x414C5030
GEOMETRY_OFFSET = 4096  # LP_PARTITION_RESERVED_BYTES
BLOCK_SIZE_MAP = {0: 4096}


def die(msg: str) -> "None":
    raise SystemExit(f"lpunpack: {msg}")


def find_magic(f, magic: int) -> int:
    """Return the first 4 KiB-aligned offset holding `magic` in the first 1 MiB."""
    want = struct.pack("<I", magic)
    for off in range(0, 1024 * 1024, 4096):
        f.seek(off)
        if f.read(4) == want:
            return off
    die(f"magic 0x{magic:08x} not found in the first 1 MiB")


def parse_geometry(f) -> dict:
    off = find_magic(f, GEOMETRY_MAGIC)
    f.seek(off)
    data = f.read(4096)
    if len(data) < 52:
        die("short read at geometry offset")
    magic, struct_size, _checksum = struct.unpack_from("<II32s", data, 0)
    if magic != GEOMETRY_MAGIC:
        die(f"bad geometry magic 0x{magic:08x} (not an LP super image?)")
    metadata_max_size, metadata_slot_count, logical_block_size = struct.unpack_from(
        "<III", data, 40
    )
    return {
        "geometry_offset": off,
        "metadata_max_size": metadata_max_size,
        "metadata_slot_count": metadata_slot_count,
        "logical_block_size": logical_block_size,
    }


def parse_metadata(f, geo: dict) -> dict:
    # Metadata follows the geometry blob(s); scan instead of assuming one layout.
    offset = find_magic(f, HEADER_MAGIC)
    f.seek(offset)
    hdr = f.read(256)
    magic = struct.unpack_from("<I", hdr, 0)[0]
    if magic != HEADER_MAGIC:
        die(f"bad metadata magic 0x{magic:08x}")
    major, minor, header_size = struct.unpack_from("<HHI", hdr, 4)
    tables_size = struct.unpack_from("<I", hdr, 44)[0]
    descs = {}
    for i, name in enumerate(("partitions", "extents", "groups", "block_devices")):
        off, num, esz = struct.unpack_from("<III", hdr, 80 + i * 12)
        descs[name] = {"offset": off, "num": num, "entry_size": esz}
    f.seek(offset + header_size)
    tables = f.read(tables_size)
    if len(tables) != tables_size:
        die("short read of metadata tables")

    def entries(name, size):
        d = descs[name]
        out = []
        for i in range(d["num"]):
            start = d["offset"] + i * d["entry_size"]
            out.append(tables[start : start + size])
        return out

    block_devices = []
    for raw in entries("block_devices", 60):
        first_logical_sector, alignment, alignment_offset, size = struct.unpack_from(
            "<QIIQ", raw, 0
        )
        block_devices.append(
            {
                "first_logical_sector": first_logical_sector,
                "alignment": alignment,
                "alignment_offset": alignment_offset,
                "size": size,
                "name": raw[24:60].split(b"\0", 1)[0].decode(errors="replace"),
            }
        )

    groups = []
    for raw in entries("groups", 48):
        flags = struct.unpack_from("<I", raw, 36)[0]
        maximum_size = struct.unpack_from("<Q", raw, 40)[0]
        groups.append(
            {
                "name": raw[:36].split(b"\0", 1)[0].decode(errors="replace"),
                "flags": flags,
                "maximum_size": maximum_size,
            }
        )

    extents = []
    for raw in entries("extents", 24):
        num_sectors, target_type, target_data, target_source = struct.unpack_from(
            "<QIQI", raw, 0
        )
        extents.append(
            {
                "num_sectors": num_sectors,
                "target_type": target_type,
                "target_data": target_data,
                "target_source": target_source,
            }
        )

    partitions = []
    for raw in entries("partitions", 52):
        attributes, first_extent_index, num_extents, group_index = struct.unpack_from(
            "<IIII", raw, 36
        )
        partitions.append(
            {
                "name": raw[:36].split(b"\0", 1)[0].decode(errors="replace"),
                "attributes": attributes,
                "first_extent_index": first_extent_index,
                "num_extents": num_extents,
                "group_index": group_index,
            }
        )

    for p in partitions:
        p["extents"] = extents[p["first_extent_index"] : p["first_extent_index"] + p["num_extents"]]
        p["size"] = sum(e["num_sectors"] for e in p["extents"]) * SECTOR
        p["group"] = groups[p["group_index"]]["name"] if groups else ""

    used = {}
    for p in partitions:
        used[p["group"]] = used.get(p["group"], 0) + p["size"]
    for g in groups:
        g["used"] = used.get(g["name"], 0)
        g["free"] = g["maximum_size"] - g["used"]

    return {
        "version": f"{major}.{minor}",
        "block_devices": block_devices,
        "groups": groups,
        "partitions": sorted(partitions, key=lambda p: (p["group"], p["name"])),
        "geometry": geo,
    }


def load(path: str) -> dict:
    with open(path, "rb") as f:
        geo = parse_geometry(f)
        md = parse_metadata(f, geo)
    return md


def human(n: int) -> str:
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(n) < 1024 or unit == "TiB":
            return f"{n:.1f}{unit}" if unit != "B" else f"{n}B"
        n /= 1024.0
    return f"{n}B"


def cmd_list(md: dict) -> None:
    print("# super block device(s)")
    for bd in md["block_devices"]:
        print(
            f"#   name={bd['name']!r} size={bd['size']} ({human(bd['size'])}) "
            f"first_logical_sector={bd['first_logical_sector']} "
            f"alignment={bd['alignment']} alignment_offset={bd['alignment_offset']}"
        )
    print(f"# metadata version={md['version']} logical_block_size={md['geometry']['logical_block_size']}")
    print()
    print(f"{'group':28} {'max_size':>12} {'used':>12} {'free':>12}   flags")
    for g in md["groups"]:
        print(
            f"{g['name']:28} {human(g['maximum_size']):>12} {human(g['used']):>12} "
            f"{human(g['free']):>12}   0x{g['flags']:x}"
        )
    print()
    print(f"{'partition':24} {'group':24} {'size':>12} {'sectors':>14}  extents")
    for p in md["partitions"]:
        ex = ",".join(
            f"{e['target_data']}+{e['num_sectors']}"
            if e["target_type"] == 0
            else f"zero:{e['num_sectors']}"
            for e in p["extents"]
        )
        print(f"{p['name']:24} {p['group']:24} {human(p['size']):>12} {p['size'] // SECTOR:>14}  {ex}")


def cmd_json(md: dict) -> None:
    import json

    print(json.dumps(md, indent=2))


def copy_extent(src, dst, offset: int, length: int) -> None:
    src.seek(offset)
    remaining = length
    chunk = 4 * 1024 * 1024
    while remaining > 0:
        data = src.read(min(chunk, remaining))
        if not data:
            die(f"unexpected EOF reading {length} bytes at {offset}")
        dst.write(data)
        remaining -= len(data)


def cmd_extract(md: dict, super_path: str, outdir: str, names: list[str]) -> None:
    os.makedirs(outdir, exist_ok=True)
    wanted = set(names) if names else None
    for p in md["partitions"]:
        if wanted is not None and p["name"] not in wanted:
            continue
        out = os.path.join(outdir, p["name"] + ".img")
        with open(super_path, "rb") as src, open(out, "wb") as dst:
            for e in p["extents"]:
                if e["target_type"] == 1:  # LP_TARGET_TYPE_ZERO
                    dst.seek(e["num_sectors"] * SECTOR - 1, os.SEEK_CUR)
                    dst.write(b"\0")
                else:
                    off = e["target_data"] * SECTOR
                    copy_extent(src, dst, off, e["num_sectors"] * SECTOR)
            dst.truncate(p["size"])
        print(f"{p['name']}: {p['size']} bytes -> {out}")
    if wanted:
        missing = wanted - {p["name"] for p in md["partitions"]}
        if missing:
            die(f"unknown partition(s): {', '.join(sorted(missing))}")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("super", help="raw super image")
    ap.add_argument("--list", action="store_true", help="print geometry, groups and partitions")
    ap.add_argument("--json", action="store_true", help="print parsed metadata as JSON")
    ap.add_argument("--extract", metavar="DIR", help="extract partitions into DIR")
    ap.add_argument("--partition", action="append", default=[], help="only this partition (repeatable)")
    args = ap.parse_args(argv)

    md = load(args.super)
    if args.list or not (args.extract or args.json):
        cmd_list(md)
    if args.json:
        cmd_json(md)
    if args.extract:
        cmd_extract(md, args.super, args.extract, args.partition)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
