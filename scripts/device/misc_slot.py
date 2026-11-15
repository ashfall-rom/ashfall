#!/usr/bin/env python3
"""Inspect (and optionally repair) the Android A/B bootloader control block in
the `misc` partition.

The RP5 is stuck on a slot that panics, and its bootloader reports
"Invalid Slot" for `fastboot set_active a`. This tool reads the 2 KiB
`bootloader_control` block at offset 2048 of `misc`, validates its CRC, and can
rewrite only the slot metadata (slot_suffix + slot_info) with a valid CRC.

Usage:
  misc_slot.py show  <misc.bin>                 # parse + CRC check
  misc_slot.py flip  <misc.bin> <out.bin> --slot a
                                                # make <slot> the preferred slot
Safety: `flip` refuses to write when the input block does not validate, so a
garbage dump can never be turned into a garbage write.

Layout (AOSP system/core/bootloader_message/include/bootloader_message.h):
  offset 2048, packed:
    0  u8   version
    1  char slot_suffix[4]      ("_a", "_b", ...)
    5  u8   nb_slot
    6  u8   recovery_tries_remaining
    7  u8   merge_status
    8  u8   reserved[3]
    11       slot_info[3]        each 5 bytes:
               priority, tries_remaining, successful_boot, verity_mode, reserved
    26 u32  crc32                crc32_le(~0, bytes[0:26])
"""
from __future__ import annotations

import argparse
import struct
import sys
import zlib

OFFSET = 2048
BLOCK = 30  # sizeof(struct bootloader_control), packed, including crc32
SLOT_NAMES = ("a", "b", "c")


def load(path: str) -> bytes:
    with open(path, "rb") as fh:
        return fh.read()


def aosp_crc(data: bytes) -> int:
    """AOSP bootloader_control CRC: crc32_le(~0, data), i.e. zlib ^ 0xffffffff."""
    return (zlib.crc32(data) ^ 0xFFFFFFFF) & 0xFFFFFFFF


def crc_candidates(data: bytes) -> dict[str, int]:
    """The three conventions seen in the wild; the device tells us which one."""
    return {
        "aosp(crc32_le(~0))": (zlib.crc32(data) ^ 0xFFFFFFFF) & 0xFFFFFFFF,
        "zlib(data)": zlib.crc32(data) & 0xFFFFFFFF,
        "zlib(data,~0)": zlib.crc32(data, 0xFFFFFFFF) & 0xFFFFFFFF,
        "zlib(data,~0)^~0": (zlib.crc32(data, 0xFFFFFFFF) ^ 0xFFFFFFFF) & 0xFFFFFFFF,
    }


def parse(buf: bytes) -> dict:
    if len(buf) < OFFSET + BLOCK:
        raise SystemExit(f"file too short: {len(buf)} bytes (need {OFFSET + BLOCK})")
    b = buf[OFFSET:OFFSET + BLOCK]
    out = {
        "version": b[0],
        "suffix": b[1:5],
        "nb_slot": b[5],
        "recovery_tries_remaining": b[6],
        "merge_status": b[7],
        "reserved": b[8:11],
        "slots": [],
        "crc": struct.unpack_from("<I", b, 26)[0],
    }
    for i in range(3):
        off = 11 + i * 5
        priority, tries, successful, verity, res = b[off:off + 5]
        out["slots"].append({
            "index": i,
            "name": SLOT_NAMES[i],
            "priority": priority,
            "tries_remaining": tries,
            "successful_boot": successful,
            "verity_mode": verity,
            "reserved": res,
        })
    out["crc_matches"] = [
        name for name, value in crc_candidates(b[:26]).items() if value == out["crc"]
    ]
    out["crc_ok"] = bool(out["crc_matches"])
    return out


def describe(buf: bytes) -> dict:
    info = parse(buf)
    print(f"misc size              : {len(buf)} bytes")
    print(f"bootloader_control @   : {OFFSET}")
    print(f"version                : {info['version']}")
    print(f"slot_suffix            : {info['suffix']!r}")
    print(f"nb_slot                : {info['nb_slot']}")
    print(f"recovery_tries_remaining: {info['recovery_tries_remaining']}")
    print(f"merge_status           : {info['merge_status']}")
    print(f"stored crc32           : 0x{info['crc']:08x}")
    print(f"crc check              : {'OK via ' + ', '.join(info['crc_matches']) if info['crc_ok'] else 'MISMATCH'}")
    print("slots:")
    for s in info["slots"]:
        print(
            f"  {s['name']}: priority={s['priority']:>3} tries={s['tries_remaining']} "
            f"successful={s['successful_boot']} verity={s['verity_mode']}"
        )
    return info


def init_block(buf: bytes, target: str) -> bytes:
    """Write a fresh, valid bootloader_control for a device with no A/B metadata.

    Also clears the stale bootloader_message command (a pending
    "bootonce-bootloader" would otherwise send the next boot to fastboot).
    """
    out = bytearray(buf)
    out[0:OFFSET] = b"\x00" * OFFSET  # clear command/status/recovery/stage
    b = bytearray(BLOCK)
    b[0] = 1  # version
    b[1:5] = (f"_{target}" + "\x00\x00\x00\x00").encode()[:4]
    b[5] = 2  # nb_slot
    preferred = SLOT_NAMES.index(target)
    for i in range(3):
        off = 11 + i * 5
        if i == preferred:
            b[off:off + 5] = bytes([15, 6, 1, 0, 0])
        elif i < 2:
            b[off:off + 5] = bytes([14, 0, 0, 0, 0])
    struct.pack_into("<I", b, 26, aosp_crc(bytes(b[:26])))
    out[OFFSET:OFFSET + BLOCK] = b
    return bytes(out)


def flip(buf: bytes, target: str) -> bytes:
    info = parse(buf)
    if not info["crc_ok"]:
        raise SystemExit("refusing to rewrite: the bootloader_control CRC does not validate")
    if target not in SLOT_NAMES[: max(info["nb_slot"], 2)]:
        raise SystemExit(f"slot {target!r} does not exist (nb_slot={info['nb_slot']})")
    if info["suffix"].rstrip(b"\x00").decode(errors="replace") == f"_{target}":
        raise SystemExit(f"slot {target} is already the current slot")

    out = bytearray(buf)
    b = out[OFFSET:OFFSET + BLOCK]
    b[1:5] = (f"_{target}" + "\x00\x00\x00\x00").encode()[:4]
    # Preferred slot: full priority and retries, marked successful; every other
    # slot steps down so the bootloader keeps slot <target> as its first choice.
    for s in info["slots"]:
        off = 11 + s["index"] * 5
        if s["index"] == SLOT_NAMES.index(target):
            b[off:off + 5] = bytes([15, 6, 1, 0, 0])
        elif s["priority"] >= 15:
            b[off:off + 5] = bytes([14, 0, 0, s["verity_mode"], 0])
    crc = zlib.crc32(bytes(b[:26])) & 0xFFFFFFFF
    # Use whichever convention the input validated with, so we never change the
    # device's CRC flavour.
    for name in info["crc_matches"]:
        if name == "zlib(data,~0)^~0":
            crc = (zlib.crc32(bytes(b[:26]), 0xFFFFFFFF) ^ 0xFFFFFFFF) & 0xFFFFFFFF
        elif name == "zlib(data,~0)":
            crc = zlib.crc32(bytes(b[:26]), 0xFFFFFFFF) & 0xFFFFFFFF
        break
    struct.pack_into("<I", b, 26, crc)
    out[OFFSET:OFFSET + BLOCK] = b
    return bytes(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_show = sub.add_parser("show", help="parse a misc dump and validate the CRC")
    p_show.add_argument("input")

    p_flip = sub.add_parser("flip", help="make another slot the preferred one")
    p_flip.add_argument("input")
    p_flip.add_argument("output")
    p_flip.add_argument("--slot", required=True, choices=SLOT_NAMES)
    p_flip.add_argument("--init", action="store_true",
                        help="write a fresh block when the existing one does not validate")

    args = ap.parse_args()
    buf = load(args.input)

    if args.cmd == "show":
        info = describe(buf)
        return 0 if info["crc_ok"] else 1

    describe(buf)
    if args.init:
        out = init_block(buf, args.slot)
    else:
        out = flip(buf, args.slot)
    with open(args.output, "wb") as fh:
        fh.write(out)
    print(f"\nwrote {args.output} ({len(out)} bytes), slot {args.slot} preferred")
    print("verify:")
    describe(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
