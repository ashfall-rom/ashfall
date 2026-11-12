#!/usr/bin/env python3
"""Small AVB vbmeta and GPT header readers (no avbtool/gdisk needed).

  imgmeta.py vbmeta <vbmeta.img>
  imgmeta.py gpt    <gpt_header_or_partition.bin>

Used by scripts/firmware/extract.sh to capture vbmeta flags / chained
partitions and the LUN GPT layout as text artefacts.
"""

from __future__ import annotations

import struct
import sys

AVB_MAGIC = b"AVB0"


def cstr(b: bytes) -> str:
    return b.split(b"\0", 1)[0].decode(errors="replace")


def parse_vbmeta(path: str) -> int:
    data = open(path, "rb").read()
    if data[:4] != AVB_MAGIC:
        print(f"not an AVB vbmeta image (magic {data[:4]!r})")
        return 0
    (
        ver_major,
        ver_minor,
        auth_size,
        aux_size,
        alg,
        hash_off,
        hash_size,
        sig_off,
        sig_size,
        pubkey_off,
        pubkey_size,
        pubmeta_off,
        pubmeta_size,
        desc_off,
        desc_size,
        rollback_index,
        flags,
        rb_loc,
    ) = struct.unpack_from(">IIQQI" + "Q" * 11 + "II", data, 4)
    release = cstr(data[128:176])
    print(
        f"version={ver_major}.{ver_minor} algorithm={alg} flags=0x{flags:x} "
        f"rollback_index={rollback_index} rollback_location={rb_loc}"
    )
    print(f"release_string={release!r} descriptors={desc_size}B auth={auth_size}B aux={aux_size}B")
    print(f"public_key={pubkey_size}B hash={hash_size}B signature={sig_size}B")

    # Offsets in the header are relative to the auxiliary block, which starts
    # after the fixed 256-byte header and the authentication block.
    aux_off = 256 + auth_size
    off = aux_off + desc_off
    end = off + desc_size
    names = {0: "property", 1: "hashtree", 2: "hash", 3: "kernel_cmdline", 4: "chain_partition"}
    while off + 16 <= end:
        tag, num = struct.unpack_from(">QQ", data, off)
        body = data[off + 16 : off + 16 + num]
        kind = names.get(tag, f"tag{tag}")
        if tag == 0:  # property
            klen, vlen = struct.unpack_from(">QQ", body, 0)
            key = cstr(body[16 : 16 + klen])
            voff = 16 + klen + 1  # key is NUL-terminated, then the value
            val = cstr(body[voff : voff + vlen])
            print(f"  descriptor property: {key}={val}")
        elif tag == 4:  # chain partition
            rb_loc, nlen, klen, cflags = struct.unpack_from(">IIII", body, 0)
            name = cstr(body[76 : 76 + nlen])
            print(f"  descriptor chain: name={name!r} rollback_location={rb_loc} flags=0x{cflags:x} key={klen}B")
        elif tag == 2:  # hash descriptor
            image_size, = struct.unpack_from(">Q", body, 0)
            nlen, salt_len, digest_len, hflags = struct.unpack_from(">IIII", body, 40)
            pname = cstr(body[116 : 116 + nlen])
            print(f"  descriptor hash: partition={pname} image_size={image_size} flags=0x{hflags:x} digest={digest_len}B")
        elif tag == 1:  # hashtree descriptor (details not needed here)
            print("  descriptor hashtree: (details skipped)")
        elif tag == 3:
            cflags, clen = struct.unpack_from(">II", body, 0)
            print(f"  descriptor kernel_cmdline: flags=0x{cflags:x} {cstr(body[64:64 + clen])!r}")
        if num == 0:
            break
        off += 16 + num
    return 0


def parse_gpt(path: str) -> int:
    data = open(path, "rb").read()
    base = data.find(b"EFI PART")
    if base < 0:
        print("no GPT header found")
        return 0
    hdr = data[base : base + 92]
    (rev, hsize) = struct.unpack_from("<II", hdr, 8)
    (cur, alt, first, last) = struct.unpack_from("<QQQQ", hdr, 24)
    (entries_lba,) = struct.unpack_from("<Q", hdr, 72)
    (nument, entsz) = struct.unpack_from("<II", hdr, 80)
    print(
        f"revision=0x{rev:x} header_size={hsize} current_lba={cur} backup_lba={alt} "
        f"first_usable={first} last_usable={last} entries={nument}x{entsz}"
    )
    # Entries normally follow the header (LBA 2) in a raw GPT dump.
    ent_off = base + 512
    if len(data) < ent_off + nument * entsz:
        print("  (partition entries not present in this dump)")
        return 0
    for i in range(nument):
        e = data[ent_off + i * entsz : ent_off + (i + 1) * entsz]
        if e[:16] == b"\0" * 16:
            continue
        type_guid = e[:16].hex()
        first_lba, last_lba, attrs = struct.unpack_from("<QQQ", e, 32)
        name = e[56:128].decode("utf-16-le", errors="replace").split("\0", 1)[0]
        if not name:
            continue
        print(
            f"  {name:24} first_lba={first_lba:<12} last_lba={last_lba:<12} "
            f"sectors={last_lba - first_lba + 1:<12} guid={type_guid}"
        )
    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[0] not in {"vbmeta", "gpt"}:
        print(__doc__, file=sys.stderr)
        return 2
    if argv[0] == "vbmeta":
        return parse_vbmeta(argv[1])
    return parse_gpt(argv[1])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
