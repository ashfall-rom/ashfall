#!/usr/bin/env python3
"""Compare a stock vendor VINTF manifest against the Android 17 framework
compatibility matrices (FCM levels 7, 8, 202404, 202504, 202604, 202704).

Inputs:
  --matrices DIR   directory with compatibility_matrix.<level>.xml files
  --manifest FILE  vendor manifest.xml (repeatable; fragments too)
  --level N        matrix level used for the required-HAL check (default 7)

Output is Markdown, suitable for docs/stock/.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

VENDOR_MANIFEST_RE = re.compile(r"@(\d+)\.(\d+)::(\w+)/(\S+)")


def parse_versions(elem) -> list[str]:
    return [v.text.strip() for v in elem.findall("version") if v.text]


def parse_matrix(path: str) -> dict:
    root = ET.parse(path).getroot()
    level = root.get("level")
    hals = []
    for hal in root.findall("hal"):
        hals.append(
            {
                "format": hal.get("format"),
                "name": hal.findtext("name", "").strip(),
                # In a framework matrix an entry without an explicit
                # optional="false" is optional (the framework does not require
                # the vendor to implement it).
                "optional": hal.get("optional") != "false",
                "versions": parse_versions(hal),
                "regex_instances": [r.text for r in hal.findall(".//regex-instance") if r.text],
                "interfaces": [i.findtext("name", "").strip() for i in hal.findall("interface")],
            }
        )
    return {"level": level, "hals": hals, "path": path}


def parse_manifest(path: str) -> list[dict]:
    root = ET.parse(path).getroot()
    out = []
    for hal in root.findall("hal"):
        fmt = hal.get("format")
        name = hal.findtext("name", "").strip()
        versions = parse_versions(hal)
        fqnames = [f.text.strip() for f in hal.findall("fqname") if f.text]
        if not versions and fqnames and fmt == "hidl":
            for fq in fqnames:
                m = VENDOR_MANIFEST_RE.search(fq)
                if m:
                    versions.append(f"{m.group(1)}.{m.group(2)}")
        out.append(
            {
                "format": fmt,
                "name": name,
                "versions": sorted(set(versions)),
                "fqnames": fqnames,
                "source": os.path.basename(path),
            }
        )
    return out


def parse_range(spec: str) -> tuple[int, ...] | None:
    """'1-2' -> (1,2); '7.0-1' -> (7,0,1); '6.0' -> (6,0)."""
    m = re.fullmatch(r"(\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?", spec)
    if not m:
        return None
    base = m.group(1)
    if "." in base:
        parts = [int(x) for x in base.split(".")]
    else:
        parts = [int(base)]
    if m.group(2):
        parts.append(int(m.group(2)))
    return tuple(parts)


def version_tuple(spec: str) -> tuple[int, ...] | None:
    v = parse_range(spec)
    return v[:2] if v and len(v) >= 2 else v


def in_range(version: str, allowed: list[str]) -> bool:
    v = version_tuple(version)
    if v is None:
        return False
    for spec in allowed:
        rng = parse_range(spec)
        if rng is None:
            continue
        if len(rng) == 1:  # aidl exact
            if v[0] == rng[0]:
                return True
        elif len(rng) == 2:  # 6.0 exact / aidl 1-2 handled below
            if v == rng:
                return True
            if len(rng[0:1]) == 1 and rng[0] <= v[0] <= rng[1]:
                return True
        elif len(rng) == 3:  # 7.0-1 (major.minor..max minor)
            if v[0] == rng[0] and rng[1] <= v[1] <= rng[2]:
                return True
    return False


def fmt_hal(h: dict) -> str:
    return f"{h['format']}:{h['name']}"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--matrices", required=True)
    ap.add_argument("--manifest", action="append", required=True)
    ap.add_argument("--level", default="7")
    ap.add_argument("--out", default="-")
    args = ap.parse_args(argv)

    matrices = {}
    for path in sorted(glob.glob(os.path.join(args.matrices, "compatibility_matrix.*.xml"))):
        m = parse_matrix(path)
        matrices[str(m["level"])] = m
    if not matrices:
        raise SystemExit(f"no compatibility matrices in {args.matrices}")

    vendor = []
    seen = set()
    for path in args.manifest:
        for h in parse_manifest(path):
            key = (h["format"], h["name"], tuple(h["versions"]))
            if key in seen:
                continue
            seen.add(key)
            vendor.append(h)

    levels = sorted(matrices, key=lambda x: (x.isdigit() and len(x) <= 2, int(x) if x.isdigit() else 0, x))
    # Stable, chronological order for the Android 17 set.
    preferred = ["7", "8", "202404", "202504", "202604", "202704"]
    levels = [l for l in preferred if l in matrices] + [l for l in levels if l not in preferred]

    lines = []
    w = lines.append
    w("# VINTF matrix comparison")
    w("")
    w(f"Vendor manifest(s): {', '.join(os.path.basename(p) for p in args.manifest)}")
    w("")
    w("Matrix levels: " + ", ".join(f"`{l}`" for l in levels))
    w("")

    # Where each vendor HAL appears.
    w("## Vendor HALs vs Android 17 compatibility matrices")
    w("")
    w("`yes` = the HAL/version is accepted by that FCM level, `--` = not listed, `opt` = listed as optional.")
    w("")
    w("| format | vendor HAL | versions | " + " | ".join(levels) + " |")
    w("|---|---|---|" + "|".join("---" for _ in levels) + "|")
    extras = []
    for h in sorted(vendor, key=lambda x: (x["format"], x["name"])):
        cells = []
        for l in levels:
            mm = [x for x in matrices[l]["hals"] if x["format"] == h["format"] and x["name"] == h["name"]]
            if not mm:
                cells.append("--")
                continue
            entry = mm[0]
            okv = any(in_range(v, entry["versions"]) for v in h["versions"]) if h["versions"] else True
            if not entry["versions"]:
                cells.append("opt" if entry["optional"] else "yes")
            elif okv:
                cells.append("opt" if entry["optional"] else "yes")
            else:
                cells.append("ver!")
        if all(c == "--" for c in cells):
            extras.append(h)
        w(f"| {h['format']} | `{h['name']}` | {', '.join(h['versions']) or '-'} | " + " | ".join(cells) + " |")
    w("")

    w("## Vendor HALs not in any Android 17 matrix")
    w("")
    if extras:
        for h in extras:
            w(f"- `{fmt_hal(h)}` ({', '.join(h['versions']) or 'no version'})")
    else:
        w("None.")
    w("")

    # Version mismatches at --level (the only VINTF runtime error class in A17;
    # since Android 16 the optional attribute is ignored and HAL presence is
    # enforced by VTS, not by VintfObject).
    lvl = args.level
    if lvl in matrices:
        w(f"## Vendor HALs whose version is not accepted by FCM `{lvl}`")
        w("")
        bad = []
        for h in sorted(vendor, key=lambda x: (x["format"], x["name"])):
            mm = [x for x in matrices[lvl]["hals"] if x["format"] == h["format"] and x["name"] == h["name"]]
            if not mm or not h["versions"]:
                continue
            if not any(in_range(v, mm[0]["versions"]) for v in h["versions"]):
                bad.append((h, mm[0]))
        if bad:
            for h, mh in bad:
                w(f"- `{fmt_hal(h)}` vendor {', '.join(h['versions'])}; matrix allows {', '.join(mh['versions'])}")
        else:
            w("None.")
        w("")
        w("Android 17 no longer enforces the `optional` attribute at runtime "
          "(deprecated after Android 15); missing HALs are a VTS concern, not a "
          "boot-blocking VINTF error. Unknown vendor HALs are ignored with a "
          "warning; version mismatches are what produce the \"internal problem\" dialog.")
        w("")

    # What a newer vendor would have to add (roadmap only).
    new_in_later = []
    for l in levels:
        if l == lvl:
            continue
        for mh in matrices[l]["hals"]:
            at7 = [x for x in matrices[lvl]["hals"] if x["format"] == mh["format"] and x["name"] == mh["name"]]
            if not at7:
                new_in_later.append((l, mh))
    w("## Mandatory-looking HALs that appear only in newer matrices (not needed at level 7)")
    w("")
    if new_in_later:
        for l, mh in new_in_later:
            w(f"- FCM `{l}`: `{fmt_hal(mh)}`")
    else:
        w("None.")
    w("")

    text = "\n".join(lines) + "\n"
    if args.out == "-":
        sys.stdout.write(text)
    else:
        with open(args.out, "w") as f:
            f.write(text)
        print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
