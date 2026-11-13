#!/usr/bin/env python3
"""Recover the Linux printk ring from a Qualcomm Sahara memory dump.

AshFall crash triage (#39). Read-only: this only reads dump files.

What the dump contains
----------------------
`edl memorydump` (Sahara "memory debug" mode, USB 05c6:900e) writes one file per
region listed by the primary boot loader: DDRCS0_0/0_1/1_0/1_1 (2 GiB each,
linear physical 0x80000000..0x280000000), plus OCIMEM/DCC/PIMEM/... For a 6 GiB
`mem=` kernel only the first ~0x1fb800000 is kernel-managed RAM, but stale ring
buffers from *earlier* boots can survive anywhere in DDR.

The kernel's printk ring (`log_buf_len=`, here 256K) is stored as a run of
records. In the dumps we have seen each record is:

    u32 id        == 0xfffe0000 | (seq & 0xffff)
    u32 zero
    char text[]   (may contain newlines, NUL padded to an 8-byte boundary)

and seq increments by one per record, so a record chain can be followed by
searching for the next id at an 8-byte aligned offset. A boot's chain starts
with the "Linux version ..." banner (seq 0). Records are recovered in seq order,
which is chronological.

Observed artifact: in this dump every record's text is followed by a repeat of
its final two characters ("...initialized" is stored as "...initializeded").
The extractor strips that repeat only when text[-2:] == text[-4:-2], so real
double characters are preserved.

Usage
-----
    printk_extract.py --memory-dir DIR [--out-dir DIR] [--current N]

Exit status: 0 if at least one chain was found, 1 otherwise.
"""

import argparse
import os
import re
import struct
import sys

DDR_ORDER = ["DDRCS0_0", "DDRCS0_1", "DDRCS1_0", "DDRCS1_1"]

ANCHORS = [
    "Kernel panic",
    "Attempted to kill init",
    "init:",
    "first_stage",
    "selinux_setup",
    "SELinux",
    "fs_mgr",
    "libfs_mgr",
    "avb",
    "erofs",
    "EROFS",
    "Unable to",
    "failed",
    "Failed",
    "Watchdog",
    "BUG:",
    "Call trace",
    "Loaded kernel module",
]

HDR_MAGIC = 0xFFFE0000


def load_memory_table(path):
    """Map FILE -> (phys_base, length) from the edl-dumped load.cmm/table."""
    table = {}
    if not os.path.isfile(path):
        return table
    rx = re.compile(r"^(\S+?)\(.*?\):\s*Offset\s+(0x[0-9a-fA-F]+),\s*Length\s+(0x[0-9a-fA-F]+)")
    with open(path, "r", errors="replace") as fh:
        for line in fh:
            m = rx.match(line.strip())
            if m:
                table[m.group(1)] = (int(m.group(2), 16), int(m.group(3), 16))
    return table


def header_at(buf, pos):
    if pos < 0 or pos + 8 > len(buf) or pos % 8:
        return None
    ident, zero = struct.unpack_from("<II", buf, pos)
    if zero or (ident >> 16) != (HDR_MAGIC >> 16):
        return None
    return ident & 0xFFFF


def look_header(seq):
    return struct.pack("<I", HDR_MAGIC | (seq & 0xFFFF)) + b"\x00\x00\x00\x00"


def printable(blob):
    return all(32 <= b < 127 or b in (9, 10, 13) for b in blob)


def clean_text(raw):
    """Strip NUL padding and the observed trailing 2-character repeat.

    The repeat sits before the trailing newline ("...initializeded\\n"), so
    the check is made on the body and the newline is put back afterwards.
    """
    text = raw.rstrip(b"\x00")
    newline = b""
    body = text
    if body.endswith(b"\n"):
        body, newline = body[:-1], b"\n"
    if len(body) >= 4 and body[-2:] == body[-4:-2]:
        body = body[:-2]
    return body + newline


def walk_chain(buf, pos, limit=200000, window=65536):
    """Follow a record chain from `pos`; return [(addr, seq, text), ...]."""
    recs = []
    while len(recs) < limit:
        seq = header_at(buf, pos)
        if seq is None:
            break
        nxt = None
        want = look_header(seq + 1)
        start = (pos + 9) & ~7
        end = min(len(buf), start + window)
        find = buf.find
        while True:
            q = find(want, start, end)
            if q < 0:
                break
            if q % 8 == 0:
                nxt = q
                break
            start = q + 1
        if nxt is None:
            break
        recs.append((pos, seq, clean_text(buf[pos + 8:nxt])))
        pos = nxt
    return recs


def chain_ok(recs):
    if len(recs) < 20:
        return False
    first = recs[0][2]
    return first.startswith(b"Linux version ")


def scan_banner_seeds(buf):
    """Offsets of printable records whose text starts with the boot banner."""
    seeds = []
    needle = b"Linux version "
    pos = 0
    while True:
        pos = buf.find(needle, pos)
        if pos < 0:
            return seeds
        if (pos - 8) % 8 == 0 and header_at(buf, pos - 8) is not None:
            seeds.append(pos - 8)
        pos += 1


def scan_fallback(buf, cap=4000):
    """Wrapped-ring fallback: seed from any plausible header with ASCII text."""
    seeds = []
    needle = b"\xfe\xff\x00\x00\x00\x00"
    pos = 0
    while len(seeds) < cap:
        pos = buf.find(needle, pos)
        if pos < 0:
            break
        p = pos - 2
        pos += 1
        if p % 8 or header_at(buf, p) is None:
            continue
        head = buf[p + 8:p + 40]
        if len(head) == 32 and printable(head):
            seeds.append(p)
    return seeds


def dedupe(chains):
    seen = {}
    for c in chains:
        key = (c["file"], c["start"])
        if key not in seen or len(c["recs"]) > len(seen[key]["recs"]):
            seen[key] = c
    out = list(seen.values())
    out.sort(key=lambda c: (-len(c["recs"]), c["file"], c["start"]))
    return out


def phys(c, table):
    base, _ = table.get(c["file"], (0, 0))
    return base + c["start"]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--memory-dir", required=True)
    ap.add_argument("--out-dir", default=None)
    ap.add_argument("--current", type=int, default=None,
                    help="1-based chain index to treat as the current boot")
    args = ap.parse_args()

    mem = os.path.abspath(args.memory_dir)
    out = os.path.abspath(args.out_dir or mem)
    os.makedirs(out, exist_ok=True)
    table = load_memory_table(os.path.join(mem, "memory-table.txt"))
    if not table:
        table = load_memory_table(os.path.join(mem, "load.cmm"))

    chains = []
    for name in DDR_ORDER:
        path = os.path.join(mem, name + ".BIN")
        if not os.path.isfile(path):
            continue
        with open(path, "rb") as fh:
            buf = fh.read()
        seeds = scan_banner_seeds(buf)
        found = []
        for s in dict.fromkeys(seeds):
            recs = walk_chain(buf, s)
            if chain_ok(recs):
                found.append({"file": name, "start": s, "recs": recs})
        if not found:
            for s in scan_fallback(buf):
                recs = walk_chain(buf, s)
                if len(recs) >= 50:
                    found.append({"file": name, "start": s, "recs": recs,
                                  "wrapped": True})
        for c in found:
            c["phys"] = phys(c, table)
        chains.extend(found)
        del buf

    chains = dedupe(chains)
    if not chains:
        print("no printk record chains found", file=sys.stderr)
        return 1

    # The kernel's own log buffer lives in kernel-managed low RAM
    # (DDRCS0_*); chains elsewhere are leftovers from other boots.
    for i, c in enumerate(chains, 1):
        panic = any(b"Kernel panic" in r[2] for r in c["recs"])
        c["panic"] = panic
        c["score"] = (2 if c["file"].startswith("DDRCS0") else 0) + \
                     (4 if panic else 0) + min(len(c["recs"]) // 100, 3)
    current = chains[0]
    if args.current:
        current = chains[args.current - 1]
    else:
        best = max(chains, key=lambda c: (c["score"], len(c["recs"]),
                                          -c["phys"]))
        # Prefer a low-RAM chain that contains a panic.
        panics = [c for c in chains if c["panic"] and c["file"].startswith("DDRCS0")]
        current = max(panics or chains, key=lambda c: len(c["recs"]))
        del best

    boots = os.path.join(out, "boots")
    os.makedirs(boots, exist_ok=True)
    summary = []
    summary.append(f"memory dir : {mem}")
    summary.append(f"chains     : {len(chains)}")
    for i, c in enumerate(chains, 1):
        tag = "current" if c is current else "other"
        text = []
        for _, seq, t in c["recs"]:
            text.append(f"{seq:5d} {t.decode('utf-8', 'replace')}")
        body = "\n".join(text) + "\n"
        with open(os.path.join(boots, f"boot-{i:02d}-{c['file']}.txt"), "w") as fh:
            fh.write(f"# {c['file']} offset {c['start']:#x} phys {c['phys']:#x} "
                     f"records {len(c['recs'])} panic={c['panic']} ({tag})\n")
            fh.write(body)
        if c is current:
            with open(os.path.join(out, "log_all.txt"), "w") as fh:
                fh.write(body)
        summary.append(
            f"  boot-{i:02d} {c['file']:<9} off={c['start']:#011x} "
            f"phys={c['phys']:#013x} records={len(c['recs']):<5} "
            f"seq={c['recs'][0][1]}..{c['recs'][-1][1]} panic={c['panic']} {tag}")

    # Anchor report over the current boot (and raw hits in other chains).
    lines = []
    for i, c in enumerate(chains, 1):
        recs = c["recs"]
        for anchor in ANCHORS:
            pat = anchor.encode()
            hits = [(j, r) for j, r in enumerate(recs) if pat in r[2]]
            if hits:
                lines.append(f"[boot-{i:02d}] {anchor!r}: {len(hits)} record(s), "
                             f"first seq {hits[0][1][1]}")
                for _, r in hits[:6]:
                    snippet = r[2].decode("utf-8", "replace").replace("\n", " | ")
                    lines.append(f"    seq {r[1]:5d} {snippet[:200]}")
    with open(os.path.join(out, "anchors.txt"), "w") as fh:
        fh.write("\n".join(lines) + "\n")
    with open(os.path.join(out, "summary.txt"), "w") as fh:
        fh.write("\n".join(summary) + "\n")

    print("\n".join(summary))
    print(f"wrote {out}/log_all.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
