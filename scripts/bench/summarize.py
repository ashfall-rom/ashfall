#!/usr/bin/env python3
"""Summarise ashfall-bench runs for docs/perf-tuning.md (issue #130).

Reads the per-run JSON files written by the harness (one file per run, e.g.
`control-1.json`, `tuned-1.json`, `pelt2-1.json`) and prints medians per
configuration plus the ratio against the `control` group.

Usage: scripts/bench/summarize.py <results-dir>
"""
import glob
import json
import os
import statistics
import sys

METRICS = [
    ("cpu_int_st", "CPU int 1T (Mops/s)", True),
    ("cpu_int_mt", "CPU int 8T (Mops/s)", True),
    ("cpu_fp_st", "CPU fp 1T (Mops/s)", True),
    ("cpu_fp_mt", "CPU fp 8T (Mops/s)", True),
    ("mem_read_gbs", "RAM read (GB/s)", True),
    ("mem_write_gbs", "RAM write (GB/s)", True),
    ("mem_copy_gbs", "RAM copy (GB/s)", True),
    ("mem_lat_ns", "RAM latency (ns)", False),
    ("gpu_alu_mpix", "GPU ALU (Mpix/s)", True),
    ("gpu_blend_mpix", "GPU blend (Mpix/s)", True),
    ("gpu_vert_mps", "GPU vertex (Mvert/s)", True),
    ("startup_p50_ms", "App start p50 (ms)", False),
]


def group_of(name):
    parts = name.split("-")
    return "-".join(parts[:-1]) if parts[-1].isdigit() else name


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    groups = {}
    for path in sorted(glob.glob(os.path.join(root, "*.json"))):
        group = group_of(os.path.basename(path)[:-5])
        with open(path) as handle:
            groups.setdefault(group, []).append(json.load(handle))

    medians = {}
    for group, rows in groups.items():
        medians[group] = {
            key: statistics.median(row[key] for row in rows if key in row)
            for key, _, _ in METRICS
            if any(key in row for row in rows)
        }

    base = medians.get("control", {})
    labels = sorted(medians)
    print("metric".ljust(24) + "".join(label.rjust(16) for label in labels))
    for key, label, higher in METRICS:
        line = label.ljust(24)
        for group in labels:
            value = medians[group].get(key)
            if value is None:
                line += "-".rjust(16)
                continue
            cell = f"{value:.1f}"
            if base.get(key) and group != "control":
                ratio = value / base[key] if higher else base[key] / value
                cell += f" ({ratio:.2f}x)"
            line += cell.rjust(16)
        print(line)


if __name__ == "__main__":
    main()
