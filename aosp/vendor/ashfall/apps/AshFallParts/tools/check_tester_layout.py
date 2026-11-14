#!/usr/bin/env python3
# Copyright (C) 2026 The AshFall Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Host check for the AshFall Parts tester layout (schema v1, issue #103).

The temporary web layout editor writes
res/raw/tester_layout.json directly; this script is the gate the orchestrator
runs before building.  It asserts every required key and the 0..1 ranges the
Java loader enforces:

  * x/y are fractions of the view width/height (0..1, inclusive),
  * r is a fraction of the view height (0 < r <= 1),
  * w/h are fractions of the view width/height (0 < w,h <= 1).

The app itself never crashes on a bad file: it falls back to its built-in
defaults per value.  This check is stricter on purpose - it makes a broken save
visible before flashing instead of silently falling back.

Usage:
  tools/check_tester_layout.py [path/to/tester_layout.json]

Exit code 0 = valid, 1 = invalid or unreadable.
"""

import json
import math
import sys
from pathlib import Path

CIRCLES = (
    "left_stick",
    "right_stick",
    "dpad",
    "button_x",
    "button_y",
    "button_a",
    "button_b",
)
RECTS = ("l1", "r1", "l2", "r2", "back", "home", "select", "start")
POINTS = ("status",)
CONTROLS = CIRCLES + RECTS + POINTS

DEFAULT_PATH = Path(__file__).resolve().parent.parent / "res" / "raw" / "tester_layout.json"


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def fraction(errors, name, key, value, positive=False):
    where = f"controls.{name}.{key}"
    if not is_number(value):
        errors.append(f"{where}: expected a number, got {value!r}")
        return
    if not math.isfinite(value):
        errors.append(f"{where}: not a finite number")
        return
    if positive:
        if not (0 < value <= 1):
            errors.append(f"{where}: {value} out of range (0 < value <= 1)")
    elif not (0 <= value <= 1):
        errors.append(f"{where}: {value} out of range (0 <= value <= 1)")


def main(argv):
    path = Path(argv[1]) if len(argv) > 1 else DEFAULT_PATH
    errors = []
    warnings = []

    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"error: cannot read {path}: {exc}", file=sys.stderr)
        return 1

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"error: {path}: invalid JSON: {exc}", file=sys.stderr)
        return 1

    if not isinstance(data, dict):
        print(f"error: {path}: top level must be an object", file=sys.stderr)
        return 1

    version = data.get("version")
    if not is_number(version) or version != 1:
        errors.append(f"version: expected 1, got {version!r}")

    controls = data.get("controls")
    if not isinstance(controls, dict):
        print(f"error: {path}: 'controls' must be an object", file=sys.stderr)
        return 1

    for name in CONTROLS:
        control = controls.get(name)
        if not isinstance(control, dict):
            errors.append(f"controls.{name}: missing or not an object")
            continue
        if name in CIRCLES:
            for key in ("x", "y"):
                fraction(errors, name, key, control.get(key))
            fraction(errors, name, "r", control.get("r"), positive=True)
        elif name in RECTS:
            for key in ("x", "y"):
                fraction(errors, name, key, control.get(key))
            for key in ("w", "h"):
                fraction(errors, name, key, control.get(key), positive=True)
        else:
            for key in ("x", "y"):
                fraction(errors, name, key, control.get(key))
        for key in sorted(set(control) - {"x", "y", "r", "w", "h"}):
            warnings.append(f"controls.{name}.{key}: unknown key (ignored by the app)")

    for key in sorted(set(controls) - set(CONTROLS)):
        warnings.append(f"controls.{key}: unknown control (ignored by the app)")
    for key in sorted(set(data) - {"version", "controls"}):
        warnings.append(f"{key}: unknown top-level key (ignored by the app)")

    for warning in warnings:
        print(f"warning: {path}: {warning}", file=sys.stderr)

    if errors:
        for error in errors:
            print(f"error: {path}: {error}", file=sys.stderr)
        print(f"{path}: INVALID ({len(errors)} error(s))", file=sys.stderr)
        return 1

    print(f"{path}: ok (schema v1, {len(CONTROLS)} controls)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
