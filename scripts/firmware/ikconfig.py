#!/usr/bin/env python3
"""Extract an embedded Linux kernel config (IKCONFIG) from a boot Image.

Stock Android kernels usually enable CONFIG_IKCONFIG; the gzip blob sits
between the IKCFG_ST / IKCFG_ED markers. This avoids needing the kernel
source tree's extract-ikconfig script on the build host.
"""

from __future__ import annotations

import re
import sys
import zlib


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "--version":
        data = open(argv[1], "rb").read()
        m = re.search(rb"Linux version [0-9][^ \x00]*", data)
        print(m.group(0).decode() if m else "")
        return 0 if m else 1
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    data = open(argv[0], "rb").read()
    start = data.find(b"IKCFG_ST")
    if start < 0:
        print("no IKCONFIG blob (IKCFG_ST not found)")
        return 1
    gz = data.find(b"\x1f\x8b\x08", start, start + 128)
    if gz < 0:
        print("IKCFG_ST found but no gzip stream after it")
        return 1
    try:
        out = zlib.decompressobj(31).decompress(data[gz:])
    except zlib.error as exc:
        print(f"failed to decompress kernel config: {exc}")
        return 1
    sys.stdout.write(out.decode(errors="replace"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
