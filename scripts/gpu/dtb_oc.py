#!/usr/bin/env python3
"""Phase 6 GPU overclock: Adreno 650 pwrlevel tables in the vendor_boot DTB.

Contract: docs/phase6.md section 2.  The stock ``vendor_boot`` ``dtb`` payload
is **not** a single FDT: it is a concatenation of one FDT per board variant
(7 on the RP5 stock image, selected by the bootloader from ``qcom,msm-id`` /
``qcom,pmic-id``).  Six of them carry ``qcom,gpu-pwrlevel-bins``; one variant
only has the legacy ``qcom,gpu-pwrlevels`` node and is left byte-identical.

The OC table is added to every speed-bin table whose stock top is 670 MHz
(``-0``, ``-1``, ``-3`` and ``-4`` on this image); the 525 MHz bin (``-2``)
stays stock.  The transformation is a deterministic DTS text rewrite; this
module also parses FDT blobs so the result can be verified structurally
(all non-GPU nodes/properties unchanged) without trusting the text round-trip.

Subcommands::

    apply       rewrite one decompiled DTS (one FDT) with the OC table
    split-blob  split a concatenated FDT payload into fdt-NN.dtb files
    join-blob   concatenate fdt-NN.dtb files back into one payload
    info        print the FDT/board/bin layout of a payload (JSON)
    verify      prove an output payload against the stock one

SPDX-License-Identifier: Apache-2.0
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --- OC table (docs/phase6.md section 2) -------------------------------------

OC_STEPS = (
    # (MHz, qcom,level)
    (1000, 0x1A0),  # TURBO_L1
    (940, 0x180),   # TURBO
    (850, 0x180),   # TURBO
    (765, 0x160),   # NOM_L3
)
OC_STEPS_HZ = tuple((mhz * 1_000_000, level) for mhz, level in OC_STEPS)
OC_ACD_LEVEL = 0x802B5FFD
OC_BUS_FREQ = 0x0B
STOCK_TOP_HZ = 670_000_000
STOCK_SLOW_TOP_HZ = 525_000_000
# The exact stock 670-top table (docs/phase6.md section 2), used by the debug
# --extra-step/--drop-step path to build and verify a replacement table.
STOCK_TOP_FREQS_HZ = (
    670_000_000, 587_000_000, 525_000_000, 490_000_000,
    441_600_000, 400_000_000, 305_000_000, 0,
)


BINS_NODE = "qcom,gpu-pwrlevel-bins"
KGSL_PREFIX = "qcom,kgsl-3d0@"

FDT_MAGIC = 0xD00DFEED
FDT_BEGIN_NODE = 1
FDT_END_NODE = 2
FDT_PROP = 3
FDT_NOP = 4
FDT_END = 9


class DtbError(Exception):
    """A layout or value this tooling refuses to guess about."""


def parse_step(spec: str) -> tuple[int, int]:
    """Parse ``MHz:LEVEL`` (level may be decimal or 0x-prefixed)."""
    try:
        mhz, level = spec.split(":", 1)
        hz = int(round(float(mhz) * 1_000_000))
        qcom_level = int(level, 0)
    except (AttributeError, TypeError, ValueError) as exc:
        raise DtbError(f"bad --extra-step {spec!r} (want MHz:LEVEL, e.g. 940:384)") from exc
    if hz <= 0:
        raise DtbError(f"--extra-step {spec!r}: frequency must be positive")
    if not 0 <= qcom_level <= 0xFFFF:
        raise DtbError(f"--extra-step {spec!r}: level out of range")
    return hz, qcom_level


def parse_drop(spec: str) -> int:
    """Parse a stock frequency to drop (MHz, decimals allowed: 441.6)."""
    try:
        hz = int(round(float(spec) * 1_000_000))
    except (TypeError, ValueError) as exc:
        raise DtbError(f"bad --drop-step {spec!r} (want MHz, e.g. 400)") from exc
    if hz <= 0:
        raise DtbError(f"--drop-step {spec!r}: frequency must be positive")
    return hz


# --- helpers -----------------------------------------------------------------


def fmt_cell(value: int) -> str:
    """Format a DT cell the way dtc does (0x00 .. 0x0b .. 0x27ef6380)."""
    if value < 0:
        raise DtbError(f"negative cell {value}")
    return f"0x{value:02x}" if value < 0x100 else f"0x{value:x}"


def align4(n: int) -> int:
    return (n + 3) & ~3


def _brace_delta(line: str) -> int:
    depth = 0
    in_str = False
    escape = False
    for ch in line:
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
    return depth


NODE_OPEN_RE = re.compile(r"^(\t*)([^\s][^{]*?)\s*\{\s*$")
PROP_RE = re.compile(r"^(\s*)([A-Za-z0-9_,.\-+@#]+)\s*=\s*<([^>]*)>;\s*$")


@dataclass
class Entry:
    """One ``qcom,gpu-pwrlevel@N`` node of a speed-bin table."""

    open_idx: int
    close_idx: int
    indent: str
    props: list[tuple[str, list[int]]]

    def get(self, name: str) -> list[int] | None:
        for prop, values in self.props:
            if prop == name:
                return values
        return None

    def value(self, name: str) -> int | None:
        values = self.get(name)
        if not values:
            return None
        out = 0
        for cell in values:
            out = (out << 32) | cell
        return out

    @property
    def freq(self) -> int:
        freq = self.value("qcom,gpu-freq")
        if freq is None:
            raise DtbError(f"pwrlevel node at line {self.open_idx + 1} has no qcom,gpu-freq")
        return freq

    @property
    def reg(self) -> int:
        reg = self.value("reg")
        if reg is None:
            raise DtbError(f"pwrlevel node at line {self.open_idx + 1} has no reg")
        return reg


def block_end(lines: list[str], open_idx: int) -> int:
    depth = 0
    for idx in range(open_idx, len(lines)):
        depth += _brace_delta(lines[idx])
        if depth == 0:
            return idx
        if depth < 0:
            break
    raise DtbError(f"unbalanced braces starting at line {open_idx + 1}")


def direct_children(lines: list[str], open_idx: int, close_idx: int, indent: str) -> list[tuple[str, int, int, str]]:
    """Return (name, open, close, indent) for the direct child nodes."""
    out: list[tuple[str, int, int, str]] = []
    idx = open_idx + 1
    while idx < close_idx:
        match = NODE_OPEN_RE.match(lines[idx])
        if match and match.group(1) == indent + "\t":
            end = block_end(lines, idx)
            out.append((match.group(2), idx, end, match.group(1)))
            idx = end + 1
        else:
            idx += 1
    return out


def parse_entry(lines: list[str], open_idx: int, close_idx: int, indent: str) -> Entry:
    props: list[tuple[str, list[int]]] = []
    for line in lines[open_idx + 1:close_idx]:
        match = PROP_RE.match(line)
        if not match:
            continue
        raw = match.group(3).strip()
        values = [int(token, 0) for token in raw.split()] if raw else []
        props.append((match.group(2), values))
    return Entry(open_idx, close_idx, indent, props)


def find_node(lines: list[str], name: str) -> tuple[int, int, str] | None:
    for idx, line in enumerate(lines):
        match = NODE_OPEN_RE.match(line)
        if match and match.group(2) == name:
            return idx, block_end(lines, idx), match.group(1)
    return None


def set_cell(lines: list[str], name: str, value: int) -> None:
    pattern = re.compile(rf"^(\s*){re.escape(name)} = <[^>]*>;\s*$")
    for idx, line in enumerate(lines):
        match = pattern.match(line)
        if match:
            lines[idx] = f"{match.group(1)}{name} = <{fmt_cell(value)}>;"
            return
    raise DtbError(f"property {name} not found")


def get_cell(lines: list[str], name: str) -> int | None:
    pattern = re.compile(rf"^(\s*){re.escape(name)} = <([^>]*)>;\s*$")
    for line in lines:
        match = pattern.match(line)
        if match:
            return int(match.group(2).strip(), 0)
    return None


# --- DTS transformation ------------------------------------------------------


@dataclass
class BinReport:
    suffix: str
    speed_bin: int
    top_hz: int
    entries_before: int
    entries_after: int
    initial_before: int | None = None
    initial_after: int | None = None
    throttle_before: int | None = None
    throttle_after: int | None = None
    oc_added: bool = False
    already_applied: bool = False
    untouched: bool = False


@dataclass
class DtsReport:
    has_bins: bool = False
    bins: list[BinReport] = field(default_factory=list)
    changed: bool = False
    custom: bool = False
    steps_hz: list[int] = field(default_factory=list)
    drop_hz: list[int] = field(default_factory=list)

    def as_dict(self) -> dict:
        payload = {
            "has_bins": self.has_bins,
            "changed": self.changed,
            "bins": [bin.__dict__ for bin in self.bins],
        }
        if self.custom:
            payload["custom"] = {"steps_hz": self.steps_hz,
                                 "drop_hz": self.drop_hz}
        return payload


def _renumber_entry(lines: list[str], entry: Entry, new_idx: int) -> list[str]:
    block = list(lines[entry.open_idx:entry.close_idx + 1])
    match = NODE_OPEN_RE.match(block[0])
    assert match is not None
    block[0] = f"{match.group(1)}qcom,gpu-pwrlevel@{new_idx} {{"
    reg_pattern = re.compile(r"^(\s*)reg = <[^>]*>;\s*$")
    for pos, line in enumerate(block):
        reg_match = reg_pattern.match(line)
        if reg_match:
            block[pos] = f"{reg_match.group(1)}reg = <{fmt_cell(new_idx)}>;"
            break
    return block


def _oc_entry(indent: str, index: int, freq_hz: int, level: int,
              template: list[tuple[str, list[int]]]) -> list[str]:
    """Build one OC pwrlevel node using the stock top entry's property order."""
    out = [f"{indent}qcom,gpu-pwrlevel@{index} {{"]
    for name, values in template:
        if name == "reg":
            cells = [index]
        elif name == "qcom,acd-level":
            cells = [OC_ACD_LEVEL]
        elif name == "qcom,level":
            cells = [level]
        elif name == "qcom,gpu-freq":
            cells = [freq_hz]
        elif name in ("qcom,bus-freq", "qcom,bus-min", "qcom,bus-max"):
            cells = [OC_BUS_FREQ]
        else:
            # Keep anything else (future DT additions) from the stock top entry.
            cells = values
        joined = " ".join(fmt_cell(cell) for cell in cells)
        out.append(f"{indent}\t{name} = <{joined}>;")
    out.append(f"{indent}}};")
    return out


def _check_entry_table(entries: list[Entry], where: str) -> None:
    if not entries:
        raise DtbError(f"{where}: no qcom,gpu-pwrlevel entries")
    freqs = [entry.freq for entry in entries]
    for prev, cur in zip(freqs, freqs[1:]):
        if cur >= prev:
            raise DtbError(f"{where}: frequencies not strictly descending: {prev} -> {cur}")
    if freqs[-1] != 0:
        raise DtbError(f"{where}: last entry is not the 0 MHz off entry")
    regs = [entry.reg for entry in entries]
    if regs != list(range(len(entries))):
        raise DtbError(f"{where}: reg is not contiguous from 0: {regs}")


def _check_oc_table(entries: list[Entry], where: str) -> None:
    """Validate an already-overclocked table (idempotent re-apply)."""
    _check_entry_table(entries, where)
    expected = [freq * 1_000_000 for freq, _ in OC_STEPS]
    got = [entry.freq for entry in entries[:len(expected)]]
    if got != expected:
        raise DtbError(f"{where}: OC steps are {got}, expected {expected}")
    for entry, (freq, level) in zip(entries, OC_STEPS):
        if entry.value("qcom,level") != level:
            raise DtbError(f"{where}: {freq // 1_000_000} MHz level "
                           f"{entry.value('qcom,level')} != {level}")
        if entry.value("qcom,acd-level") != OC_ACD_LEVEL:
            raise DtbError(f"{where}: {freq // 1_000_000} MHz acd-level mismatch")
        for bus in ("qcom,bus-freq", "qcom,bus-min", "qcom,bus-max"):
            if entry.value(bus) != OC_BUS_FREQ:
                raise DtbError(f"{where}: {freq // 1_000_000} MHz {bus} mismatch")


def _transform_bin(lines: list[str], name: str, open_idx: int, close_idx: int,
                   indent: str) -> tuple[list[str], BinReport]:
    children = direct_children(lines, open_idx, close_idx, indent)
    entries = [parse_entry(lines, child[1], child[2], child[3]) for child in children]
    suffix = name.rsplit("-", 1)[-1]
    speed_bin = get_cell(lines[open_idx + 1:close_idx], "qcom,speed-bin")
    initial = get_cell(lines[open_idx + 1:close_idx], "qcom,initial-pwrlevel")
    throttle = get_cell(lines[open_idx + 1:close_idx], "qcom,throttle-pwrlevel")
    report = BinReport(
        suffix=suffix,
        speed_bin=-1 if speed_bin is None else speed_bin,
        top_hz=entries[0].freq if entries else -1,
        entries_before=len(entries),
        entries_after=len(entries),
        initial_before=initial,
        initial_after=initial,
        throttle_before=throttle,
        throttle_after=throttle,
    )

    if not entries:
        raise DtbError(f"speed bin {suffix}: empty table")
    _check_entry_table(entries, f"speed bin {suffix}")

    oc_freqs = [freq * 1_000_000 for freq, _ in OC_STEPS]
    present = [entry.freq for entry in entries if entry.freq in set(oc_freqs)]
    if present:
        if present != oc_freqs:
            raise DtbError(f"speed bin {suffix}: partial OC table ({present}); "
                           f"refusing to guess")
        # Re-applying to an already-overclocked table must be a no-op.
        _check_oc_table(entries, f"speed bin {suffix}")
        if initial != len(entries) - 2:
            raise DtbError(
                f"speed bin {suffix}: already OC but initial-pwrlevel {initial} "
                f"!= {len(entries) - 2} (boot level must be 305 MHz)")
        if throttle is None:
            raise DtbError(f"speed bin {suffix}: already OC but no throttle-pwrlevel")
        report.already_applied = True
        report.oc_added = True
        return lines[open_idx:close_idx + 1], report

    if entries[0].freq == STOCK_SLOW_TOP_HZ:
        report.untouched = True
        return lines[open_idx:close_idx + 1], report

    if entries[0].freq != STOCK_TOP_HZ:
        raise DtbError(
            f"speed bin {suffix}: unexpected stock top {entries[0].freq} Hz "
            f"(want {STOCK_TOP_HZ} or {STOCK_SLOW_TOP_HZ}); refusing to guess")


    if initial is None:
        raise DtbError(f"speed bin {suffix}: no qcom,initial-pwrlevel")
    if throttle is None:
        raise DtbError(f"speed bin {suffix}: no qcom,throttle-pwrlevel")
    # The 305 MHz boot entry is the second-to-last one (0 MHz off entry last).
    if initial != len(entries) - 2:
        raise DtbError(
            f"speed bin {suffix}: initial-pwrlevel {initial} does not point at "
            f"the 305 MHz entry ({len(entries) - 2})")

    template = entries[0].props
    new_blocks: list[list[str]] = []
    for index, (freq_mhz, level) in enumerate(OC_STEPS):
        new_blocks.append(_oc_entry(indent + "\t", index, freq_mhz * 1_000_000,
                                    level, template))
    shift = len(OC_STEPS)
    for index, entry in enumerate(entries):
        new_blocks.append(_renumber_entry(lines, entry, index + shift))

    header = list(lines[open_idx + 1:children[0][1]])
    while header and not header[-1].strip():
        header.pop()
    set_cell(header, "qcom,initial-pwrlevel", initial + shift)
    set_cell(header, "qcom,throttle-pwrlevel", throttle + shift)

    body: list[str] = list(header) + [""]
    for block in new_blocks:
        body.extend(block)
        body.append("")

    new_block = [lines[open_idx]] + body[:-1] + [lines[close_idx]]
    report.entries_after = len(entries) + shift
    report.initial_after = initial + shift
    report.throttle_after = throttle + shift
    report.oc_added = True
    return new_block, report


def _check_step_entry(entry: Entry, hz: int, level: int, where: str) -> None:
    mhz = hz // 1_000_000
    if entry.freq != hz:
        raise DtbError(f"{where}: {mhz} MHz entry has freq {entry.freq}")
    if entry.value("qcom,level") != level:
        raise DtbError(f"{where}: {mhz} MHz level "
                       f"{entry.value('qcom,level')} != {level}")
    if entry.value("qcom,acd-level") != OC_ACD_LEVEL:
        raise DtbError(f"{where}: {mhz} MHz acd-level mismatch")
    for bus in ("qcom,bus-freq", "qcom,bus-min", "qcom,bus-max"):
        if entry.value(bus) != OC_BUS_FREQ:
            raise DtbError(f"{where}: {mhz} MHz {bus} mismatch")


def _custom_target(where: str, steps: tuple[tuple[int, int], ...],
                   drop_hz: tuple[int, ...]) -> list[int]:
    """Return the requested descending table (0 MHz last) and sanity-check it."""
    step_freqs = [hz for hz, _ in steps]
    if len(set(step_freqs)) != len(step_freqs):
        raise DtbError(f"{where}: duplicate --extra-step frequency")
    if 0 in step_freqs:
        raise DtbError(f"{where}: --extra-step cannot add a 0 MHz entry")
    if 305_000_000 in drop_hz or 0 in drop_hz:
        raise DtbError(f"{where}: refusing to drop the 305 MHz boot level or the "
                       f"0 MHz off entry")
    unknown = [hz for hz in drop_hz if hz not in STOCK_TOP_FREQS_HZ]
    if unknown:
        raise DtbError(f"{where}: --drop-step {unknown} is not a stock frequency")
    overlap = set(step_freqs) & set(STOCK_TOP_FREQS_HZ)
    if overlap:
        raise DtbError(f"{where}: --extra-step duplicates stock frequency {overlap}")
    target = sorted(step_freqs + [hz for hz in STOCK_TOP_FREQS_HZ
                                  if hz not in drop_hz], reverse=True)
    if target[-1] != 0:
        raise DtbError(f"{where}: target table does not end at 0 MHz")
    if len(target) < 3:
        raise DtbError(f"{where}: target table is too short ({len(target)} entries)")
    return target


def _check_custom_table(entries: list[Entry], where: str,
                        steps: tuple[tuple[int, int], ...],
                        target_freq: list[int]) -> None:
    _check_entry_table(entries, where)
    freqs = [entry.freq for entry in entries]
    if freqs != target_freq:
        raise DtbError(f"{where}: table is {freqs}, expected {target_freq}")
    levels = dict(steps)
    for entry in entries:
        if entry.freq in levels:
            _check_step_entry(entry, entry.freq, levels[entry.freq], where)


def _transform_bin_custom(lines: list[str], name: str, open_idx: int, close_idx: int,
                          indent: str, steps: tuple[tuple[int, int], ...],
                          drop_hz: tuple[int, ...]) -> tuple[list[str], BinReport]:
    """Debug path: add/replace steps while staying inside the RPMh/KGSL limits."""
    children = direct_children(lines, open_idx, close_idx, indent)
    entries = [parse_entry(lines, child[1], child[2], child[3]) for child in children]
    suffix = name.rsplit("-", 1)[-1]
    speed_bin = get_cell(lines[open_idx + 1:close_idx], "qcom,speed-bin")
    initial = get_cell(lines[open_idx + 1:close_idx], "qcom,initial-pwrlevel")
    throttle = get_cell(lines[open_idx + 1:close_idx], "qcom,throttle-pwrlevel")
    where = f"speed bin {suffix}"
    report = BinReport(
        suffix=suffix,
        speed_bin=-1 if speed_bin is None else speed_bin,
        top_hz=entries[0].freq if entries else -1,
        entries_before=len(entries),
        entries_after=len(entries),
        initial_before=initial,
        initial_after=initial,
        throttle_before=throttle,
        throttle_after=throttle,
    )
    if not entries:
        raise DtbError(f"{where}: empty table")
    _check_entry_table(entries, where)

    # The 525 MHz speed-bin table stays stock even in debug mode.
    if entries[0].freq == STOCK_SLOW_TOP_HZ:
        report.untouched = True
        return lines[open_idx:close_idx + 1], report

    steps_sorted = tuple(sorted(steps, key=lambda step: step[0], reverse=True))
    target_freq = _custom_target(where, steps_sorted, drop_hz)
    freqs = [entry.freq for entry in entries]

    if any(hz in set(hz for hz, _ in steps_sorted) for hz in freqs):
        _check_custom_table(entries, where, steps_sorted, target_freq)
        report.already_applied = True
        report.oc_added = True
        report.entries_after = len(entries)
        report.initial_after = initial
        report.throttle_after = throttle
        return lines[open_idx:close_idx + 1], report

    if entries[0].freq != STOCK_TOP_HZ:
        raise DtbError(
            f"{where}: unexpected stock top {entries[0].freq} Hz "
            f"(want {STOCK_TOP_HZ} or {STOCK_SLOW_TOP_HZ}); refusing to guess")
    if freqs != list(STOCK_TOP_FREQS_HZ):
        raise DtbError(f"{where}: unexpected stock table {freqs}; refusing to guess")
    if initial is None:
        raise DtbError(f"{where}: no qcom,initial-pwrlevel")
    if throttle is None:
        raise DtbError(f"{where}: no qcom,throttle-pwrlevel")

    template = entries[0].props
    new_blocks: list[list[str]] = []
    for hz, level in steps_sorted:
        new_blocks.append(_oc_entry(indent + "\t", len(new_blocks), hz, level, template))
    kept = [entry for entry in entries if entry.freq not in drop_hz]
    for entry in kept:
        new_blocks.append(_renumber_entry(lines, entry, len(new_blocks)))
    new_freqs = [hz for hz, _ in steps_sorted] + [entry.freq for entry in kept]
    if new_freqs != target_freq:
        raise DtbError(f"{where}: built {new_freqs}, expected {target_freq}")

    # Preserve the mask's bottom entries: only drop entries the mask covered.
    covered = entries[-(throttle + 1):]
    dropped_bottom = sum(1 for entry in covered if entry.freq in drop_hz)
    new_throttle = max(0, throttle - dropped_bottom)
    new_initial = target_freq.index(305_000_000)

    header = list(lines[open_idx + 1:children[0][1]])
    while header and not header[-1].strip():
        header.pop()
    set_cell(header, "qcom,initial-pwrlevel", new_initial)
    set_cell(header, "qcom,throttle-pwrlevel", new_throttle)

    body: list[str] = list(header) + [""]
    for block in new_blocks:
        body.extend(block)
        body.append("")

    new_block = [lines[open_idx]] + body[:-1] + [lines[close_idx]]
    report.entries_after = len(target_freq)
    report.initial_after = new_initial
    report.throttle_after = new_throttle
    report.oc_added = True
    return new_block, report


def transform_dts(text: str, steps: tuple[tuple[int, int], ...] = OC_STEPS_HZ,
                  drop_hz: tuple[int, ...] = ()) -> tuple[str, DtsReport]:
    """Apply the OC table to every 670-MHz-top speed-bin table in one DTS."""
    lines = text.split("\n")
    report = DtsReport()
    custom = bool(drop_hz) or tuple(steps) != OC_STEPS_HZ
    if custom:
        report.custom = True
        report.steps_hz = [hz for hz, _ in steps]
        report.drop_hz = list(drop_hz)
    found = find_node(lines, BINS_NODE)
    if found is None:
        return text, report
    report.has_bins = True
    open_idx, close_idx, indent = found

    bins = direct_children(lines, open_idx, close_idx, indent)
    if not bins:
        raise DtbError(f"{BINS_NODE} has no speed-bin tables")

    replacements: list[tuple[int, int, list[str], BinReport]] = []
    for name, bin_open, bin_close, bin_indent in bins:
        if not re.fullmatch(r"qcom,gpu-pwrlevels-\d+", name):
            raise DtbError(f"unexpected child {name!r} of {BINS_NODE}")
        if custom:
            new_block, bin_report = _transform_bin_custom(
                lines, name, bin_open, bin_close, bin_indent, steps, drop_hz)
        else:
            new_block, bin_report = _transform_bin(
                lines, name, bin_open, bin_close, bin_indent)
        replacements.append((bin_open, bin_close, new_block, bin_report))
        report.bins.append(bin_report)

    if not any(bin_report.oc_added for bin_report in report.bins):
        raise DtbError(f"{BINS_NODE}: no 670 MHz speed-bin table found")

    # Splice from the bottom so the earlier line indexes stay valid.
    for bin_open, bin_close, new_block, _ in reversed(replacements):
        lines[bin_open:bin_close + 1] = new_block

    report.changed = any(bin.oc_added and not bin.already_applied for bin in report.bins)
    return "\n".join(lines), report


# --- FDT parsing -------------------------------------------------------------


@dataclass
class FdtNode:
    name: str
    props: list[tuple[str, bytes]] = field(default_factory=list)
    children: list["FdtNode"] = field(default_factory=list)

    def get(self, name: str) -> bytes | None:
        for prop, value in self.props:
            if prop == name:
                return value
        return None

    def child(self, name: str) -> "FdtNode | None":
        for node in self.children:
            if node.name == name:
                return node
        return None

    def find(self, predicate) -> "FdtNode | None":
        if predicate(self):
            return self
        for node in self.children:
            found = node.find(predicate)
            if found is not None:
                return found
        return None


@dataclass
class Fdt:
    blob: bytes
    version: int
    last_comp_version: int
    boot_cpuid: int
    memreserve: list[tuple[int, int]]
    root: FdtNode
    totalsize: int


def _cstring(data: bytes, offset: int) -> str:
    end = data.find(b"\0", offset)
    if end < 0:
        raise DtbError("unterminated string in FDT strings block")
    return data[offset:end].decode("latin-1")


def parse_fdt(blob: bytes) -> Fdt:
    if len(blob) < 40:
        raise DtbError("truncated FDT header")
    (magic, totalsize, off_struct, off_strings, off_rsvmap,
     version, last_comp, boot_cpuid, size_strings, size_struct) = struct.unpack_from(">10I", blob, 0)
    if magic != FDT_MAGIC:
        raise DtbError(f"bad FDT magic {magic:#x}")
    if totalsize > len(blob):
        raise DtbError(f"FDT totalsize {totalsize} exceeds the slice ({len(blob)})")
    strings = blob[off_strings:off_strings + size_strings]

    memreserve: list[tuple[int, int]] = []
    pos = off_rsvmap
    while True:
        if pos + 16 > len(blob):
            raise DtbError("truncated FDT memory reserve map")
        address, size = struct.unpack_from(">QQ", blob, pos)
        pos += 16
        if address == 0 and size == 0:
            break
        memreserve.append((address, size))

    root: FdtNode | None = None
    stack: list[FdtNode] = []
    pos = off_struct
    end = off_struct + size_struct
    while pos + 4 <= end:
        token = struct.unpack_from(">I", blob, pos)[0]
        pos += 4
        if token == FDT_BEGIN_NODE:
            name_end = blob.find(b"\0", pos)
            if name_end < 0:
                raise DtbError("unterminated node name")
            node = FdtNode(blob[pos:name_end].decode("latin-1"))
            pos = off_struct + align4(name_end + 1 - off_struct)
            if stack:
                stack[-1].children.append(node)
            elif root is None:
                root = node
            else:
                raise DtbError("multiple FDT root nodes")
            stack.append(node)
        elif token == FDT_END_NODE:
            if not stack:
                raise DtbError("unbalanced FDT_END_NODE")
            stack.pop()
        elif token == FDT_PROP:
            if not stack:
                raise DtbError("FDT_PROP outside a node")
            if pos + 8 > end:
                raise DtbError("truncated FDT_PROP")
            length, name_offset = struct.unpack_from(">II", blob, pos)
            pos += 8
            if pos + length > end:
                raise DtbError("FDT_PROP value out of bounds")
            value = blob[pos:pos + length]
            pos = off_struct + align4(pos + length - off_struct)
            stack[-1].props.append((_cstring(strings, name_offset), value))
        elif token == FDT_NOP:
            continue
        elif token == FDT_END:
            break
        else:
            raise DtbError(f"unknown FDT token {token} at offset {pos - 4}")
    if stack:
        raise DtbError("unbalanced FDT node tree")
    if root is None:
        raise DtbError("FDT has no root node")
    return Fdt(blob=blob[:totalsize], version=version, last_comp_version=last_comp,
               boot_cpuid=boot_cpuid, memreserve=memreserve, root=root,
               totalsize=totalsize)


def split_blob(blob: bytes) -> list[bytes]:
    """Split a vendor_boot dtb payload into the FDTs it concatenates."""
    parts: list[bytes] = []
    pos = 0
    while pos < len(blob):
        if len(blob) - pos < 40:
            raise DtbError(f"{len(blob) - pos} trailing byte(s) after the last FDT")
        magic, totalsize = struct.unpack_from(">II", blob, pos)
        if magic != FDT_MAGIC:
            raise DtbError(f"no FDT magic at payload offset {pos}")
        if totalsize < 40 or pos + totalsize > len(blob):
            raise DtbError(f"bad FDT totalsize {totalsize} at payload offset {pos}")
        parts.append(blob[pos:pos + totalsize])
        pos += totalsize
    return parts


def cells(value: bytes | None) -> list[int]:
    if value is None:
        return []
    if len(value) % 4:
        raise DtbError(f"property length {len(value)} is not a multiple of 4")
    return [int.from_bytes(value[i:i + 4], "big") for i in range(0, len(value), 4)]


def cell_value(value: bytes | None) -> int | None:
    parts = cells(value)
    if not parts:
        return None
    out = 0
    for part in parts:
        out = (out << 32) | part
    return out


def gpu_bins(fdt: Fdt) -> FdtNode | None:
    kgsl = fdt.root.find(lambda node: node.name.startswith(KGSL_PREFIX))
    if kgsl is None:
        return None
    return kgsl.child(BINS_NODE)


def bin_entries(node: FdtNode) -> list[FdtNode]:
    return [child for child in node.children
            if child.name.startswith("qcom,gpu-pwrlevel@")]


def bin_top_hz(node: FdtNode) -> int:
    entries = bin_entries(node)
    if not entries:
        raise DtbError(f"{node.name}: no entries")
    return cell_value(entries[0].get("qcom,gpu-freq")) or 0


def masked_signature(node: FdtNode, mask: bool = True):
    if mask and node.name == BINS_NODE:
        return ("<gpu-pwrlevel-bins>",)
    return (
        node.name,
        tuple((name, bytes(value)) for name, value in node.props),
        tuple(masked_signature(child, mask) for child in node.children),
    )


def serialize_fdt(fdt: Fdt) -> bytes:
    """Serialise a parsed FDT (property values are carried as raw bytes).

    The OC pipeline never round-trips the whole tree through ``dtc``: the
    decompiled DTS is the review artifact, but the shipped blob takes every
    non-GPU property byte-for-byte from the stock FDT.  (dtc's NUL escaping is
    ambiguous for byte arrays followed by digits -- e.g. ``pins =
    "gpio12\\013..."`` in the stock pinctrl node, which dtc re-reads as octal
    013 -- so a full round-trip would silently change that property.)
    """
    strings = bytearray()
    string_offsets: dict[str, int] = {}

    def string_offset(name: str) -> int:
        offset = string_offsets.get(name)
        if offset is None:
            offset = len(strings)
            if offset > 0xFFFFFFFF:
                raise DtbError("FDT strings block overflow")
            string_offsets[name] = offset
            strings.extend(name.encode("latin-1") + b"\0")
        return offset

    struct_block = bytearray()

    def pad4() -> None:
        while len(struct_block) % 4:
            struct_block.append(0)

    def emit_node(node: FdtNode) -> None:
        struct_block.extend(struct.pack(">I", FDT_BEGIN_NODE))
        struct_block.extend(node.name.encode("latin-1") + b"\0")
        pad4()
        for name, value in node.props:
            struct_block.extend(struct.pack(">III", FDT_PROP, len(value),
                                            string_offset(name)))
            struct_block.extend(value)
            pad4()
        for child in node.children:
            emit_node(child)
        struct_block.extend(struct.pack(">I", FDT_END_NODE))

    emit_node(fdt.root)
    struct_block.extend(struct.pack(">I", FDT_END))
    pad4()

    off_rsvmap = 40
    reserved = b"".join(struct.pack(">QQ", address, size)
                        for address, size in fdt.memreserve) + b"\0" * 16
    off_struct = align4(off_rsvmap + len(reserved))
    off_strings = align4(off_struct + len(struct_block))
    totalsize = off_strings + len(strings)
    header = struct.pack(
        ">10I", FDT_MAGIC, totalsize, off_struct, off_strings, off_rsvmap,
        fdt.version, fdt.last_comp_version, fdt.boot_cpuid,
        len(strings), len(struct_block))
    return (header + reserved + b"\0" * (off_struct - off_rsvmap - len(reserved))
            + bytes(struct_block) + b"\0" * (off_strings - off_struct - len(struct_block))
            + bytes(strings))


def replace_gpu_bins(stock: Fdt, oc: Fdt) -> Fdt:
    """Return the stock FDT with only its qcom,gpu-pwrlevel-bins subtree replaced."""
    stock_bins = gpu_bins(stock)
    oc_bins = gpu_bins(oc)
    if stock_bins is None or oc_bins is None:
        raise DtbError("splice: the stock or the transformed FDT has no GPU bins")
    parent = stock.root.find(lambda node: any(child.name == BINS_NODE
                                              for child in node.children))
    if parent is None:
        raise DtbError("splice: cannot find the GPU bins parent node")
    parent.children = [oc_bins if child.name == BINS_NODE else child
                       for child in parent.children]
    return stock



# --- verification ------------------------------------------------------------


@dataclass
class FdtVerify:
    index: int
    stock_size: int
    oc_size: int
    msm_id: list[int]
    pmic_id: list[int]
    has_bins: bool
    bins_oc: int = 0
    bins_slow: int = 0
    byte_identical: bool = False
    checks: list[str] = field(default_factory=list)


def _board_ids(fdt: Fdt) -> tuple[list[int], list[int]]:
    return (cells(fdt.root.get("qcom,msm-id")),
            cells(fdt.root.get("qcom,pmic-id")))


def verify_fdt_pair(index: int, stock_blob: bytes, oc_blob: bytes) -> FdtVerify:
    stock = parse_fdt(stock_blob)
    oc = parse_fdt(oc_blob)
    msm_id, pmic_id = _board_ids(stock)
    result = FdtVerify(index=index, stock_size=stock.totalsize, oc_size=oc.totalsize,
                       msm_id=msm_id, pmic_id=pmic_id,
                       has_bins=gpu_bins(stock) is not None)

    if stock.version != oc.version or stock.last_comp_version != oc.last_comp_version \
            or stock.boot_cpuid != oc.boot_cpuid:
        raise DtbError(f"FDT {index}: header fields changed")
    if stock.memreserve != oc.memreserve:
        raise DtbError(f"FDT {index}: memreserve map changed: "
                       f"{stock.memreserve} -> {oc.memreserve}")
    if masked_signature(stock.root) != masked_signature(oc.root):
        raise DtbError(f"FDT {index}: a non-GPU node/property changed")

    stock_bins = gpu_bins(stock)
    oc_bins = gpu_bins(oc)
    if stock_bins is None:
        if oc_bins is not None:
            raise DtbError(f"FDT {index}: GPU bins appeared where stock had none")
        if stock.blob != oc.blob:
            raise DtbError(f"FDT {index}: variant without GPU bins is not byte-identical")
        result.byte_identical = True
        result.checks.append("no qcom,gpu-pwrlevel-bins; payload kept byte-identical")
        return result

    if oc_bins is None:
        raise DtbError(f"FDT {index}: GPU bins disappeared")
    if [node.name for node in stock_bins.children] != \
            [node.name for node in oc_bins.children]:
        raise DtbError(f"FDT {index}: speed-bin tables were added/removed/reordered")

    for stock_bin, oc_bin in zip(stock_bins.children, oc_bins.children):
        top = bin_top_hz(stock_bin)
        stock_entries = bin_entries(stock_bin)
        oc_entries = bin_entries(oc_bin)
        where = f"FDT {index} {oc_bin.name}"
        if top == STOCK_SLOW_TOP_HZ:
            if masked_signature(stock_bin, mask=False) != masked_signature(oc_bin, mask=False):
                raise DtbError(f"{where}: 525 MHz table was modified")
            result.bins_slow += 1
            continue
        if top != STOCK_TOP_HZ:
            raise DtbError(f"{where}: unexpected stock top {top} Hz")

        if len(oc_entries) != len(stock_entries) + len(OC_STEPS):
            raise DtbError(f"{where}: {len(oc_entries)} entries, expected "
                           f"{len(stock_entries) + len(OC_STEPS)}")

        freqs = [cell_value(node.get("qcom,gpu-freq")) or 0 for node in oc_entries]
        if freqs != sorted(freqs, reverse=True):
            raise DtbError(f"{where}: frequencies not descending")
        if freqs[-1] != 0:
            raise DtbError(f"{where}: 0 MHz off entry is not last")
        regs = [cell_value(node.get("reg")) for node in oc_entries]
        if regs != list(range(len(oc_entries))):
            raise DtbError(f"{where}: reg is not contiguous: {regs}")

        for node, (freq_mhz, level) in zip(oc_entries, OC_STEPS):
            if cell_value(node.get("qcom,gpu-freq")) != freq_mhz * 1_000_000:
                raise DtbError(f"{where}: wrong OC frequency in {node.name}")
            if cell_value(node.get("qcom,level")) != level:
                raise DtbError(f"{where}: {node.name} level "
                               f"{cell_value(node.get('qcom,level'))} != {level}")
            if cell_value(node.get("qcom,acd-level")) != OC_ACD_LEVEL:
                raise DtbError(f"{where}: {node.name} acd-level mismatch")
            for bus in ("qcom,bus-freq", "qcom,bus-min", "qcom,bus-max"):
                if cell_value(node.get(bus)) != OC_BUS_FREQ:
                    raise DtbError(f"{where}: {node.name} {bus} mismatch")

        for offset, (stock_entry, oc_entry) in enumerate(
                zip(stock_entries, oc_entries[len(OC_STEPS):])):
            stock_props = tuple((name, bytes(value)) for name, value in stock_entry.props)
            oc_props = tuple((name, bytes(value)) for name, value in oc_entry.props
                             if name != "reg")
            stock_props_wo_reg = tuple((name, value) for name, value in stock_props
                                       if name != "reg")
            if stock_props_wo_reg != oc_props:
                raise DtbError(f"{where}: stock entry {offset} was modified")
            if cell_value(oc_entry.get("reg")) != offset + len(OC_STEPS):
                raise DtbError(f"{where}: stock entry {offset} has the wrong reg")

        initial = cell_value(oc_bin.get("qcom,initial-pwrlevel"))
        if initial != (cell_value(stock_bin.get("qcom,initial-pwrlevel")) or 0) + len(OC_STEPS):
            raise DtbError(f"{where}: initial-pwrlevel {initial} is not stock+4")
        stock_throttle = cell_value(stock_bin.get("qcom,throttle-pwrlevel"))
        throttle = cell_value(oc_bin.get("qcom,throttle-pwrlevel"))
        if stock_throttle is not None and throttle != stock_throttle + len(OC_STEPS):
            raise DtbError(f"{where}: throttle-pwrlevel {throttle} is not stock+4")
        if initial != len(oc_entries) - 2:
            raise DtbError(f"{where}: initial-pwrlevel does not point at the 305 MHz entry")
        result.bins_oc += 1

    result.checks.append(f"{result.bins_oc} 670 MHz bin(s) overclocked, "
                         f"{result.bins_slow} 525 MHz bin(s) untouched")
    return result


def verify_fdt_pair_custom(index: int, stock_blob: bytes, oc_blob: bytes,
                           steps: tuple[tuple[int, int], ...],
                           drop_hz: tuple[int, ...]) -> FdtVerify:
    """Verify a debug table (added/replaced steps) against the stock FDT."""
    stock = parse_fdt(stock_blob)
    oc = parse_fdt(oc_blob)
    msm_id, pmic_id = _board_ids(stock)
    result = FdtVerify(index=index, stock_size=stock.totalsize, oc_size=oc.totalsize,
                       msm_id=msm_id, pmic_id=pmic_id,
                       has_bins=gpu_bins(stock) is not None)

    if stock.version != oc.version or stock.last_comp_version != oc.last_comp_version \
            or stock.boot_cpuid != oc.boot_cpuid:
        raise DtbError(f"FDT {index}: header fields changed")
    if stock.memreserve != oc.memreserve:
        raise DtbError(f"FDT {index}: memreserve map changed")
    if masked_signature(stock.root) != masked_signature(oc.root):
        raise DtbError(f"FDT {index}: a non-GPU node/property changed")

    stock_bins = gpu_bins(stock)
    oc_bins = gpu_bins(oc)
    if stock_bins is None:
        if oc_bins is not None:
            raise DtbError(f"FDT {index}: GPU bins appeared where stock had none")
        if stock.blob != oc.blob:
            raise DtbError(f"FDT {index}: variant without GPU bins is not byte-identical")
        result.byte_identical = True
        result.checks.append("no qcom,gpu-pwrlevel-bins; payload kept byte-identical")
        return result
    if oc_bins is None:
        raise DtbError(f"FDT {index}: GPU bins disappeared")
    if [node.name for node in stock_bins.children] != \
            [node.name for node in oc_bins.children]:
        raise DtbError(f"FDT {index}: speed-bin tables were added/removed/reordered")

    drop = set(drop_hz)
    levels = dict(steps)
    target: list[int] = []
    for stock_bin, oc_bin in zip(stock_bins.children, oc_bins.children):
        top = bin_top_hz(stock_bin)
        where = f"FDT {index} {oc_bin.name}"
        if top == STOCK_SLOW_TOP_HZ:
            if masked_signature(stock_bin, mask=False) != \
                    masked_signature(oc_bin, mask=False):
                raise DtbError(f"{where}: 525 MHz table was modified")
            result.bins_slow += 1
            continue
        if top != STOCK_TOP_HZ:
            raise DtbError(f"{where}: unexpected stock top {top} Hz")

        target = _custom_target(where, steps, drop_hz)
        oc_entries = bin_entries(oc_bin)
        steps_sorted = sorted(steps, key=lambda step: step[0], reverse=True)
        freqs = [cell_value(node.get("qcom,gpu-freq")) or 0 for node in oc_entries]
        if freqs != target:
            raise DtbError(f"{where}: table is {freqs}, expected {target}")
        regs = [cell_value(node.get("reg")) for node in oc_entries]
        if regs != list(range(len(target))):
            raise DtbError(f"{where}: reg is not contiguous: {regs}")
        for node, (hz, level) in zip(oc_entries, steps_sorted):
            if (cell_value(node.get("qcom,gpu-freq")) or 0) != hz:
                raise DtbError(f"{where}: {node.name} has the wrong frequency")
            if cell_value(node.get("qcom,level")) != level:
                raise DtbError(f"{where}: {node.name} level mismatch")
            if cell_value(node.get("qcom,acd-level")) != OC_ACD_LEVEL:
                raise DtbError(f"{where}: {node.name} acd-level mismatch")
            for bus in ("qcom,bus-freq", "qcom,bus-min", "qcom,bus-max"):
                if cell_value(node.get(bus)) != OC_BUS_FREQ:
                    raise DtbError(f"{where}: {node.name} {bus} mismatch")

        stock_by_freq = {cell_value(node.get("qcom,gpu-freq")) or 0: node
                         for node in bin_entries(stock_bin)}
        for oc_entry in oc_entries:
            freq = cell_value(oc_entry.get("qcom,gpu-freq")) or 0
            if freq in levels:
                continue
            stock_entry = stock_by_freq.get(freq)
            if stock_entry is None:
                raise DtbError(f"{where}: unexpected entry {freq} Hz")
            stock_props = tuple((name, bytes(value))
                                for name, value in stock_entry.props if name != "reg")
            oc_props = tuple((name, bytes(value))
                             for name, value in oc_entry.props if name != "reg")
            if stock_props != oc_props:
                raise DtbError(f"{where}: stock {freq // 1_000_000} MHz entry modified")

        initial = cell_value(oc_bin.get("qcom,initial-pwrlevel"))
        expected_initial = target.index(305_000_000)
        if initial != expected_initial:
            raise DtbError(f"{where}: initial-pwrlevel {initial} != {expected_initial}")

        stock_throttle = cell_value(stock_bin.get("qcom,throttle-pwrlevel"))
        if stock_throttle is not None:
            covered = bin_entries(stock_bin)[-(stock_throttle + 1):]
            dropped_bottom = sum(
                1 for node in covered
                if (cell_value(node.get("qcom,gpu-freq")) or 0) in drop)
            expected_throttle = max(0, stock_throttle - dropped_bottom)
            throttle = cell_value(oc_bin.get("qcom,throttle-pwrlevel"))
            if throttle != expected_throttle:
                raise DtbError(f"{where}: throttle-pwrlevel {throttle} != "
                               f"{expected_throttle}")
        result.bins_oc += 1

    result.checks.append(
        f"{result.bins_oc} debug table(s) applied "
        f"({len(target)} entries, {len(steps)} step(s) added, "
        f"{len(drop_hz)} stock step(s) dropped); "
        f"{result.bins_slow} 525 MHz bin(s) untouched")
    return result


def verify_blob(stock_blob: bytes, oc_blob: bytes,
                steps: tuple[tuple[int, int], ...] = OC_STEPS_HZ,
                drop_hz: tuple[int, ...] = ()) -> list[FdtVerify]:
    stock_parts = split_blob(stock_blob)
    oc_parts = split_blob(oc_blob)
    if len(stock_parts) != len(oc_parts):
        raise DtbError(f"payload has {len(oc_parts)} FDTs, stock has {len(stock_parts)}")
    custom = bool(drop_hz) or tuple(steps) != OC_STEPS_HZ
    if custom:
        return [verify_fdt_pair_custom(index, stock, oc, steps, drop_hz)
                for index, (stock, oc) in enumerate(zip(stock_parts, oc_parts))]
    return [verify_fdt_pair(index, stock, oc)
            for index, (stock, oc) in enumerate(zip(stock_parts, oc_parts))]


# --- CLI ---------------------------------------------------------------------


def _steps_from_args(args) -> tuple[tuple[int, int], ...]:
    """Debug steps: --extra-step wins; a drop-only variant adds no step."""
    if args.extra_step:
        return tuple(parse_step(spec) for spec in args.extra_step)
    if args.drop_step:
        return ()
    return OC_STEPS_HZ


def _drop_from_args(args) -> tuple[int, ...]:
    return tuple(parse_drop(spec) for spec in args.drop_step)


def _cmd_apply(args) -> int:
    text = Path(args.src).read_text()
    steps = _steps_from_args(args)
    drop = _drop_from_args(args)
    if args.extra_step or args.drop_step:
        new_text, report = transform_dts(text, steps=steps, drop_hz=drop)
    else:
        new_text, report = transform_dts(text)
    Path(args.out).write_text(new_text)
    if args.summary:
        Path(args.summary).write_text(json.dumps(report.as_dict(), indent=2) + "\n")
    print(json.dumps({"changed": report.changed, "has_bins": report.has_bins,
                      "bins": len(report.bins)}))
    return 0


def _cmd_split(args) -> int:
    parts = split_blob(Path(args.blob).read_bytes())
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    names = []
    for index, part in enumerate(parts):
        name = outdir / f"fdt-{index:02d}.dtb"
        name.write_bytes(part)
        names.append({"index": index, "file": name.name, "size": len(part)})
    payload = {"count": len(parts), "parts": names}
    if args.json:
        Path(args.json).write_text(json.dumps(payload, indent=2) + "\n")
    print(json.dumps(payload))
    return 0


def _cmd_join(args) -> int:
    indir = Path(args.indir)
    parts = sorted(indir.glob("fdt-*.dtb"))
    if args.count is not None and len(parts) != args.count:
        raise DtbError(f"found {len(parts)} FDT file(s) in {indir}, expected {args.count}")
    if not parts:
        raise DtbError(f"no fdt-*.dtb files in {indir}")
    data = b"".join(part.read_bytes() for part in parts)
    Path(args.out).write_bytes(data)
    print(json.dumps({"count": len(parts), "size": len(data)}))
    return 0


def _cmd_splice(args) -> int:
    stock_blob = Path(args.stock_fdt).read_bytes()
    stock = parse_fdt(stock_blob)
    if gpu_bins(stock) is None:
        Path(args.out).write_bytes(stock.blob)
        print(json.dumps({"spliced": False, "size": stock.totalsize}))
        return 0
    oc = parse_fdt(Path(args.oc_fdt).read_bytes())
    data = serialize_fdt(replace_gpu_bins(stock, oc))
    Path(args.out).write_bytes(data)
    print(json.dumps({"spliced": True, "size": len(data)}))
    return 0


def _cmd_info(args) -> int:
    parts = split_blob(Path(args.blob).read_bytes())
    out = []
    for index, part in enumerate(parts):
        fdt = parse_fdt(part)
        msm_id, pmic_id = _board_ids(fdt)
        bins = gpu_bins(fdt)
        item = {
            "index": index,
            "size": fdt.totalsize,
            "version": fdt.version,
            "msm_id": [hex(value) for value in msm_id],
            "pmic_id": [hex(value) for value in pmic_id],
            "has_bins": bins is not None,
            "bins": [],
        }
        if bins is not None:
            for node in bins.children:
                entries = bin_entries(node)
                item["bins"].append({
                    "name": node.name,
                    "speed_bin": cell_value(node.get("qcom,speed-bin")),
                    "top_hz": bin_top_hz(node),
                    "entries": len(entries),
                    "initial": cell_value(node.get("qcom,initial-pwrlevel")),
                    "throttle": cell_value(node.get("qcom,throttle-pwrlevel")),
                })
        out.append(item)
    print(json.dumps(out, indent=2))
    return 0


def _cmd_verify(args) -> int:
    steps = _steps_from_args(args)
    drop = _drop_from_args(args)
    if args.extra_step or args.drop_step:
        report = verify_blob(Path(args.stock).read_bytes(), Path(args.oc).read_bytes(),
                             steps=steps, drop_hz=drop)
    else:
        report = verify_blob(Path(args.stock).read_bytes(), Path(args.oc).read_bytes())
    payload = [item.__dict__ for item in report]
    if args.json:
        Path(args.json).write_text(json.dumps(payload, indent=2) + "\n")
    print(json.dumps(payload, indent=2))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)

    apply_parser = sub.add_parser("apply", help="apply the OC table to one DTS")
    apply_parser.add_argument("--in", dest="src", required=True)
    apply_parser.add_argument("--out", required=True)
    apply_parser.add_argument("--summary", default=None)
    apply_parser.add_argument(
        "--extra-step", action="append", default=[], metavar="MHZ:LEVEL",
        help="debug: add exactly these steps (repeatable) instead of the full OC "
             "table, e.g. --extra-step 940:384")
    apply_parser.add_argument(
        "--drop-step", action="append", default=[], metavar="MHZ",
        help="debug: drop these stock steps (repeatable), e.g. --drop-step 400")
    apply_parser.set_defaults(func=_cmd_apply)

    split_parser = sub.add_parser("split-blob", help="split a dtb payload into FDTs")
    split_parser.add_argument("--blob", required=True)
    split_parser.add_argument("--outdir", required=True)
    split_parser.add_argument("--json", default=None)
    split_parser.set_defaults(func=_cmd_split)

    join_parser = sub.add_parser("join-blob", help="concatenate FDTs into a payload")
    join_parser.add_argument("--indir", required=True)
    join_parser.add_argument("--out", required=True)
    join_parser.add_argument("--count", type=int, default=None)
    join_parser.set_defaults(func=_cmd_join)

    info_parser = sub.add_parser("info", help="print the FDT/board/bin layout")
    info_parser.add_argument("--blob", required=True)
    info_parser.set_defaults(func=_cmd_info)

    splice_parser = sub.add_parser(
        "splice", help="put the transformed GPU bins into the stock FDT")
    splice_parser.add_argument("--stock-fdt", required=True)
    splice_parser.add_argument("--oc-fdt", required=True)
    splice_parser.add_argument("--out", required=True)
    splice_parser.set_defaults(func=_cmd_splice)

    verify_parser = sub.add_parser("verify", help="verify an OC payload vs stock")
    verify_parser.add_argument("--stock", required=True)
    verify_parser.add_argument("--oc", required=True)
    verify_parser.add_argument("--json", default=None)
    verify_parser.add_argument(
        "--extra-step", action="append", default=[], metavar="MHZ:LEVEL",
        help="debug: expected added steps (repeatable), matching apply")
    verify_parser.add_argument(
        "--drop-step", action="append", default=[], metavar="MHZ",
        help="debug: expected dropped stock steps (repeatable), matching apply")
    verify_parser.set_defaults(func=_cmd_verify)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except DtbError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
