#!/usr/bin/env python3
"""Temporary AshFall controller-tester layout editor server.

Serves a small drag-and-drop editor and writes the layout JSON resource that
the AshFall Parts tester reads. Only the one known JSON file can be written.

Run:
    python3 tools/tester-layout-editor/server.py \
        --repo /root/.t3/worktrees/ashfall/feat-tester-v2-84-... \
        --port 8787 --token <random>

The URL is http://<tailscale-ip>:<port>/?token=<token>.
"""

import argparse
import hmac
import json
import os
import secrets
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

LAYOUT_REL = Path("aosp/vendor/ashfall/apps/AshFallParts/res/raw/tester_layout.json")

CIRCLE_KEYS = {
    "left_stick", "right_stick", "dpad",
    "button_x", "button_y", "button_a", "button_b",
}
RECT_KEYS = {
    "l1", "r1", "l2", "r2",
    "back", "home", "select", "start",
}
TEXT_KEYS = {"status"}
ALL_KEYS = CIRCLE_KEYS | RECT_KEYS | TEXT_KEYS
MIN_SIZE = 0.001


def die(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(2)


def is_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v == v


def validate(data) -> list[str]:
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["top level must be an object"]
    if data.get("version") != 1:
        errors.append("version must be 1")
    controls = data.get("controls")
    if not isinstance(controls, dict):
        return errors + ["controls must be an object"]
    for key in controls:
        if key not in ALL_KEYS:
            errors.append(f"unknown control: {key}")
    for key in sorted(ALL_KEYS):
        c = controls.get(key)
        if not isinstance(c, dict):
            errors.append(f"{key}: missing object")
            continue
        for axis in ("x", "y"):
            v = c.get(axis)
            if not is_number(v) or not (0.0 <= float(v) <= 1.0):
                errors.append(f"{key}.{axis}: must be a number in 0..1")
        if key in CIRCLE_KEYS:
            v = c.get("r")
            if not is_number(v) or not (MIN_SIZE <= float(v) <= 1.0):
                errors.append(f"{key}.r: must be a number in {MIN_SIZE}..1")
        elif key in RECT_KEYS:
            for dim in ("w", "h"):
                v = c.get(dim)
                if not is_number(v) or not (MIN_SIZE <= float(v) <= 1.0):
                    errors.append(f"{key}.{dim}: must be a number in {MIN_SIZE}..1")
    return errors


def clean(data):
    """Keep only the documented keys, in a stable order."""
    controls = {}
    for key in sorted(ALL_KEYS):
        c = data["controls"].get(key)
        if not isinstance(c, dict):
            continue
        out = {"x": round(float(c["x"]), 6), "y": round(float(c["y"]), 6)}
        if key in CIRCLE_KEYS:
            out["r"] = round(float(c["r"]), 6)
        elif key in RECT_KEYS:
            out["w"] = round(float(c["w"]), 6)
            out["h"] = round(float(c["h"]), 6)
        controls[key] = out
    return {"version": 1, "controls": controls}


class Handler(BaseHTTPRequestHandler):
    server_version = "AshFallLayoutEditor/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _token_ok(self, query) -> bool:
        supplied = (query.get("token") or [""])[0] or self.headers.get("X-Token", "")
        return hmac.compare_digest(str(supplied), self.server.token)

    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj) -> None:
        self._send(code, (json.dumps(obj) + "\n").encode(), "application/json")

    def _layout_path(self) -> Path:
        return self.server.repo / LAYOUT_REL

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path in ("/", "/index.html"):
            html = (Path(__file__).with_name("index.html")).read_text(encoding="utf-8")
            html = html.replace("__TOKEN__", self.server.token)
            html = html.replace("__TARGET__", str(LAYOUT_REL))
            self._send(HTTPStatus.OK, html.encode(), "text/html; charset=utf-8")
            return
        if parsed.path == "/health":
            self._json(HTTPStatus.OK, {"ok": True})
            return
        if parsed.path == "/layout":
            if not self._token_ok(query):
                self._json(HTTPStatus.FORBIDDEN, {"ok": False, "error": "bad token"})
                return
            path = self._layout_path()
            try:
                raw = path.read_text(encoding="utf-8")
            except OSError as exc:
                self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": str(exc)})
                return
            try:
                data = json.loads(raw)
            except json.JSONDecodeError as exc:
                self._json(HTTPStatus.INTERNAL_SERVER_ERROR,
                           {"ok": False, "error": f"invalid JSON on disk: {exc}"})
                return
            self._send(HTTPStatus.OK, raw.encode(), "application/json")
            return
        self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path != "/save":
            self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not found"})
            return
        if not self._token_ok(query):
            self._json(HTTPStatus.FORBIDDEN, {"ok": False, "error": "bad token"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > 65536:
            self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": "bad body size"})
            return
        try:
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": f"invalid JSON: {exc}"})
            return
        errors = validate(data)
        if errors:
            self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": "; ".join(errors)})
            return
        path = self._layout_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(clean(data), indent=2) + "\n"
        tmp = path.with_name(path.name + ".tmp")
        try:
            tmp.write_text(payload, encoding="utf-8")
            os.replace(tmp, path)
        except OSError as exc:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": str(exc)})
            return
        print(f"saved {path} ({len(payload)} bytes)", file=sys.stderr, flush=True)
        self._json(HTTPStatus.OK, {"ok": True, "path": str(LAYOUT_REL), "bytes": len(payload)})


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="worktree/repo root containing the ROM sources")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--token", default=secrets.token_urlsafe(18))
    args = ap.parse_args()

    repo = Path(args.repo).expanduser().resolve()
    if not (repo / "aosp/vendor/ashfall/apps/AshFallParts").is_dir():
        die(f"not an AshFall worktree: {repo}")
    if not (repo / LAYOUT_REL).is_file():
        die(f"layout file not found: {repo / LAYOUT_REL}")

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.repo = repo
    httpd.token = args.token
    url = f"http://<tailscale-ip>:{args.port}/?token={args.token}"
    print(f"repo:  {repo}")
    print(f"file:  {repo / LAYOUT_REL}")
    print(f"url:   {url}")
    print("ready", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
