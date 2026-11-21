#!/usr/bin/env python3
"""Summarise an idle-drain run captured by idle-drain.sh (issue #233).

The device history is the source of truth: `+running` / `-running` transitions
give the time the AP was actually awake (CLOCK_MONOTONIC, so suspend time is
excluded), `+screen` / `-screen` give the panel state and `wake_reason=0:"..."`
lines name whatever aborted each suspend attempt.  Wall-clock samples add the
charge counter and the battery level.

Usage:
  scripts/bench/drain-summary.py <run-dir>
  scripts/bench/drain-summary.py --compare <baseline-dir> <experimental-dir>
"""
import json
import os
import re
import statistics
import sys
import time

LINE_RE = re.compile(r"^\s*(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(\d+)\s(.*)$")
WAKE_RE = re.compile(r'wake_reason=\d+:"([^"]*)"')
SAMPLE_RE = re.compile(r"^@@(\w+)=(.*)$")


def load_samples(run_dir):
    samples = []
    for name in sorted(os.listdir(run_dir)):
        if not re.match(r"^sample-\d+\.txt$", name):
            continue
        values = {}
        with open(os.path.join(run_dir, name), encoding="utf-8", errors="replace") as handle:
            for line in handle:
                match = SAMPLE_RE.match(line.rstrip("\n"))
                if match:
                    values[match.group(1)] = match.group(2).strip()
        if "wall" not in values:
            continue
        samples.append(values)
    if not samples:
        raise SystemExit("error: no @@wall samples in %s" % run_dir)
    return samples


def clock_offset(sample):
    """Device epoch minus host-tz epoch of the device's own local wall clock.

    mktime() applies the *host* timezone, but it is used for both sides of the
    subtraction, so the timezone cancels; only a DST change inside one run can
    skew it by an hour.
    """
    naive = time.strptime(sample["local"], "%Y-%m-%d %H:%M:%S")
    return int(sample["wall"]) - int(time.mktime(naive)), naive.tm_year


def parse_history(path, year, offset):
    """(epoch, tokens) per history line, oldest first."""
    lines = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            match = LINE_RE.match(raw)
            if not match:
                continue
            month, day, hour, minute, second, _ms, _level, rest = match.groups()
            try:
                stamp = time.mktime((year, int(month), int(day),
                                     int(hour), int(minute), int(second), 0, 0, -1))
            except (ValueError, OverflowError):
                continue
            lines.append((stamp + offset, rest))
    return lines


def apply_tokens(state, tokens):
    for token in tokens.split():
        if token == "+running":
            state["running"] = True
        elif token == "-running":
            state["running"] = False
        elif token == "+screen":
            state["screen"] = True
        elif token == "-screen":
            state["screen"] = False


def summarise(run_dir):
    samples = load_samples(run_dir)
    start, end = samples[0], samples[-1]
    offset, year = clock_offset(end)
    t0, t1 = int(start["wall"]), int(end["wall"])
    if t1 <= t0:
        raise SystemExit("error: run is too short to measure (%d s)" % (t1 - t0))

    state = {"running": False, "screen": False}
    for stamp, tokens in parse_history(os.path.join(run_dir, "history.txt"), year, offset):
        if stamp <= t0:
            apply_tokens(state, tokens)

    awake = screen_on = 0.0
    episodes = []
    episode_start = None
    reasons = {}
    last = t0
    for stamp, tokens in parse_history(os.path.join(run_dir, "history.txt"), year, offset):
        if stamp <= t0:
            continue
        if stamp > t1:
            break
        chunk = stamp - last
        if state["running"]:
            awake += chunk
        if state["screen"]:
            screen_on += chunk
        if "+running" in tokens.split() and not state["running"]:
            episode_start = stamp
        if "-running" in tokens.split() and state["running"] and episode_start is not None:
            episodes.append(stamp - episode_start)
            episode_start = None
        for reason in WAKE_RE.findall(tokens):
            reasons[reason] = reasons.get(reason, 0) + 1
        apply_tokens(state, tokens)
        last = stamp
    tail = t1 - last
    if state["running"]:
        awake += tail
    if state["screen"]:
        screen_on += tail
    if state["running"] and episode_start is not None:
        episodes.append(t1 - episode_start)

    realtime = t1 - t0
    hours = realtime / 3600.0
    screen_off = realtime - screen_on

    def number(values, key):
        try:
            return float(values[key])
        except (KeyError, ValueError):
            return None

    counter0 = number(start, "counter")
    counter1 = number(end, "counter")
    drain_mah = drain_ma = None
    if counter0 is not None and counter1 is not None:
        drain_mah = (counter1 - counter0) / 1000.0
        drain_ma = (counter1 - counter0) / hours / 1000.0

    level0 = number(start, "level")
    level1 = number(end, "level")
    pct_per_h = (level1 - level0) / hours if level0 is not None and level1 is not None else None

    return {
        "label": open(os.path.join(run_dir, "label.txt"), encoding="utf-8").read().strip()
        if os.path.exists(os.path.join(run_dir, "label.txt")) else os.path.basename(run_dir.rstrip("/")),
        "dir": os.path.abspath(run_dir),
        "realtime_s": round(realtime, 1),
        "screen_off_s": round(screen_off, 1),
        "awake_s": round(awake, 1),
        "suspend_s": round(screen_off - awake, 1),
        "suspend_pct": round(100.0 * (screen_off - awake) / screen_off, 1) if screen_off > 0 else None,
        "awake_episodes": len(episodes),
        "awake_median_s": round(statistics.median(episodes), 1) if episodes else None,
        "awake_p90_s": round(sorted(episodes)[int(len(episodes) * 0.9)], 1) if episodes else None,
        "charge_start_uah": counter0,
        "charge_end_uah": counter1,
        "drain_mah": round(drain_mah, 1) if drain_mah is not None else None,
        "drain_ma": round(drain_ma, 1) if drain_ma is not None else None,
        "level_start": level0,
        "level_end": level1,
        "pct_per_h": round(pct_per_h, 2) if pct_per_h is not None else None,
        "wake_reasons": dict(sorted(reasons.items(), key=lambda kv: -kv[1])),
    }


def hms(seconds):
    seconds = int(round(seconds))
    return "%dm %02ds" % (seconds // 60, seconds % 60)


def report(summary, top=10):
    print("AshFall idle drain — %s" % summary["label"])
    print("  dir             %s" % summary["dir"])
    print("  realtime        %s" % hms(summary["realtime_s"]))
    screen_off = summary["screen_off_s"]
    if screen_off > 0:
        print("  screen off      %s (%.1f%%)" % (hms(screen_off),
                                                 100.0 * screen_off / summary["realtime_s"]))
        print("  awake           %s (%.1f%% of screen off), %d episodes, median %s, p90 %s"
              % (hms(summary["awake_s"]), 100.0 * summary["awake_s"] / screen_off,
                 summary["awake_episodes"],
                 hms(summary["awake_median_s"] or 0), hms(summary["awake_p90_s"] or 0)))
        print("  suspended       %s (%.1f%% of screen off)"
              % (hms(summary["suspend_s"]), summary["suspend_pct"] or 0.0))
    if summary["charge_start_uah"] is not None:
        print("  charge          %.1f -> %.1f mAh  (%.1f mAh, %.1f mA average)"
              % (summary["charge_start_uah"] / 1000.0, summary["charge_end_uah"] / 1000.0,
                 summary["drain_mah"], summary["drain_ma"]))
    if summary["pct_per_h"] is not None:
        print("  battery level   %d%% -> %d%%  (%.2f %%/h)"
              % (summary["level_start"], summary["level_end"], summary["pct_per_h"]))
    if summary["wake_reasons"]:
        print("  wake reasons:")
        for reason, count in list(summary["wake_reasons"].items())[:top]:
            print("    %5dx  %s" % (count, reason))
        print("    %5dx  (other)" % sum(list(summary["wake_reasons"].values())[top:])) \
            if len(summary["wake_reasons"]) > top else None


def compare(base, exp):
    rows = [
        ("suspend % of screen off", "suspend_pct", "%.1f%%"),
        ("awake time", "awake_s", "%.0f s"),
        ("awake episodes", "awake_episodes", "%d"),
        ("median awake burst", "awake_median_s", "%.0f s"),
        ("average current", "drain_ma", "%.1f mA"),
        ("drain", "pct_per_h", "%.2f %%/h"),
    ]
    print("AshFall idle drain — %s vs %s" % (base["label"], exp["label"]))
    print("  %-24s %12s %12s %8s" % ("metric", "baseline", "experimental", "change"))
    for title, key, fmt in rows:
        if base.get(key) is None or exp.get(key) is None:
            continue
        delta = exp[key] - base[key]
        if key in ("suspend_pct", "awake_s", "awake_episodes", "awake_median_s"):
            delta = -delta  # lower is better for awake time
            sign = "better" if delta > 0 else ("worse" if delta < 0 else "same")
        else:
            sign = "better" if delta < 0 else ("worse" if delta > 0 else "same")
        print("  %-24s %12s %12s %8s  %s"
              % (title, fmt % base[key], fmt % exp[key], "%+.1f" % delta, sign))
    base_reasons = set(base["wake_reasons"])
    new_reasons = [r for r in exp["wake_reasons"] if r not in base_reasons]
    if new_reasons:
        print("  new wake reasons: %s" % "; ".join(new_reasons[:5]))


def main(argv):
    if len(argv) >= 2 and argv[1] == "--compare":
        if len(argv) != 4:
            raise SystemExit("usage: drain-summary.py --compare <baseline-dir> <experimental-dir>")
        compare(summarise(argv[2]), summarise(argv[3]))
        return 0
    if len(argv) != 2:
        raise SystemExit("usage: drain-summary.py <run-dir> | --compare <a> <b>")
    summary = summarise(argv[1])
    report(summary)
    with open(os.path.join(argv[1], "summary.json"), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
