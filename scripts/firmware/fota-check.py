#!/usr/bin/env python3
"""Read-only client for the Retroid/ADUPS "abupdate" FOTA check-version API.

This script performs ONLY the two calls the stock FOTA app needs before it can
show an update:

  1. POST /register/<productId>                  (device registration)
  2. POST /product/<productId>/<deviceId>/ota/checkVersion

It never calls download/upgrade/error/behaviour reporting endpoints and never
downloads a package.  Response JSON is printed to stdout.

The protocol was recovered from the RP5 stock FOTA app (com.rp.fota,
ADUPS/abupdate iot SDK 2.1.8).  See docs/firmware/fota.md for the write-up.

Dependencies: Python 3 standard library only.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import ssl
import sys
import time
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "https://iotapi.abupdate.com"

# Retail RP5 project (recovered from the official 1.0.0.34 full image).
# The pre-release 1.0.0.31 image carries a different, now-dead project:
#   productId=1723789976, productSecret=8b88566349aa461b866f5bab93ea9e7f
# and ro.fota.device=RP5 instead of RP5_A133.
DEFAULT_PRODUCT_ID = "1731375894"
DEFAULT_PRODUCT_SECRET = "ed5fd6153a174b9cbcfbbc1f22da7076"
DEFAULT_OEM = "Moorechip"
DEFAULT_MODEL = "RP5_A133"
DEFAULT_PLATFORM = "SM8250"
DEFAULT_DEVICE_TYPE = "pad_phone"
DEFAULT_SDK_VERSION = "2.1.8"
DEFAULT_APP_VERSION = "1.0.0-20241024_205810"
DEFAULT_MID = "2f184581"
DEFAULT_NETWORK_TYPE = "WIFI"


def hmac_md5_hex(message: str, key: str) -> str:
    return hmac.new(key.encode("utf-8"), message.encode("ascii"), hashlib.md5).hexdigest()


def sign_register(mid: str, product_id: str, timestamp: int, product_secret: str) -> str:
    # com.abupdate.iot_libs.engine.e.c.a(): HMAC-MD5(mid + productId + ts, productSecret)
    return hmac_md5_hex(mid + product_id + str(timestamp), product_secret)


def sign_request(device_id: str, product_id: str, timestamp: int, device_secret: str) -> str:
    # com.abupdate.iot_libs.engine.e.c.a(): HMAC-MD5(deviceId + productId + ts, deviceSecret)
    return hmac_md5_hex(device_id + product_id + str(timestamp), device_secret)


def post_json(url: str, payload: dict, timeout: float, insecure: bool) -> dict:
    data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    context = None
    if insecure:
        context = ssl._create_unverified_context()
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
            body = response.read().decode("utf-8", "replace")
            status = response.status
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        status = exc.code
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        raise SystemExit(f"HTTP {status}: non-JSON response: {body[:500]}")
    if status != 200:
        raise SystemExit(f"HTTP {status}: {parsed}")
    return parsed


def maybe_json(value):
    """The API returns nested objects as JSON-encoded strings."""
    if isinstance(value, str) and value and value[0] in "{[":
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value


def register(args: argparse.Namespace) -> tuple[str, str]:
    timestamp = int(time.time())
    payload = {
        "mid": args.mid,
        "oem": args.oem,
        "models": args.model,
        "platform": args.platform,
        "deviceType": args.device_type,
        "sdkversion": args.sdk_version,
        "appversion": args.app_version,
        "version": args.version,
        "networkType": args.network_type,
        "timestamp": timestamp,
        "sign": sign_register(args.mid, args.product_id, timestamp, args.product_secret),
    }
    response = post_json(
        f"{args.base_url.rstrip('/')}/register/{args.product_id}",
        payload,
        args.timeout,
        args.insecure,
    )
    if response.get("status") != 1000:
        raise SystemExit(f"registration failed: {response}")
    data = response.get("data") or {}
    device_id = data.get("deviceId")
    device_secret = data.get("deviceSecret")
    if not device_id or not device_secret:
        raise SystemExit(f"registration response missing deviceId/deviceSecret: {response}")
    return device_id, device_secret


def check_version(args: argparse.Namespace, device_id: str, device_secret: str) -> dict:
    timestamp = int(time.time())
    payload = {
        "mid": args.mid,
        "version": args.version,
        "networkType": args.network_type,
        "timestamp": timestamp,
        "sign": sign_request(device_id, args.product_id, timestamp, device_secret),
    }
    response = post_json(
        f"{args.base_url.rstrip('/')}/product/{args.product_id}/{device_id}/ota/checkVersion",
        payload,
        args.timeout,
        args.insecure,
    )
    return response


def normalise(response: dict, args: argparse.Namespace, device_id: str) -> dict:
    out = {
        "productId": args.product_id,
        "deviceId": device_id,
        "requestedVersion": args.version,
        "status": response.get("status"),
        "msg": response.get("msg"),
        "update": False,
        "package": None,
    }
    data = response.get("data")
    if not data:
        return out

    version = maybe_json(data.get("version"))
    release_notes = maybe_json(data.get("releaseNotes"))
    policy = maybe_json(data.get("policy"))
    safe = maybe_json(data.get("safe"))

    package = None
    if isinstance(version, dict):
        package = dict(version)
        # The API has no explicit full/delta flag.  For the RP5 the full OTA is
        # ~2.4 GB while every delta is < 32 MB, so size is a reliable hint.
        # Confirm a package by unzipping it: a full OTA's
        # META-INF/com/android/metadata has no pre-build line.
        package["kind"] = "full" if int(package.get("fileSize") or 0) >= 1_000_000_000 else "delta"
        if "segmentMd5" in package:
            package["segmentMd5"] = maybe_json(package["segmentMd5"])
        if isinstance(release_notes, dict):
            package["publishDate"] = release_notes.get("publishDate")
            package["releaseNotes"] = maybe_json(release_notes.get("content")) or release_notes.get("content")
            package["targetVersion"] = release_notes.get("version")
        if isinstance(policy, dict):
            package["policy"] = policy
        if safe is not None:
            package["safe"] = safe
        out["update"] = True
    elif version is not None:
        # A few deployments return the version object directly.
        package = {"rawVersion": version}

    out["package"] = package
    return out


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Read-only ADUPS/abupdate FOTA check-version client (RP5 retail profile by default).",
    )
    parser.add_argument("--version", required=True, help="source firmware version, e.g. 1.0.0.169")
    parser.add_argument("--mid", default=DEFAULT_MID, help="device serial number used as mid (default: %(default)s)")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="API base URL (default: %(default)s)")
    parser.add_argument("--product-id", default=DEFAULT_PRODUCT_ID, help="FOTA productId (default: retail RP5)")
    parser.add_argument("--product-secret", default=DEFAULT_PRODUCT_SECRET, help="FOTA productSecret")
    parser.add_argument("--oem", default=DEFAULT_OEM, help="registration oem (default: %(default)s)")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="registration models (default: %(default)s)")
    parser.add_argument("--platform", default=DEFAULT_PLATFORM, help="registration platform (default: %(default)s)")
    parser.add_argument("--device-type", default=DEFAULT_DEVICE_TYPE, help="registration deviceType (default: %(default)s)")
    parser.add_argument("--sdk-version", default=DEFAULT_SDK_VERSION, help="registration sdkversion (default: %(default)s)")
    parser.add_argument("--app-version", default=DEFAULT_APP_VERSION, help="registration appversion (default: %(default)s)")
    parser.add_argument("--network-type", default=DEFAULT_NETWORK_TYPE, help="networkType (default: %(default)s)")
    parser.add_argument("--timeout", type=float, default=30.0, help="HTTP timeout in seconds (default: %(default)s)")
    parser.add_argument("--insecure", action="store_true", help="skip TLS verification (needed for the legacy iotapi.adups.com cert)")
    parser.add_argument("--device-id", help="reuse a previous registration instead of registering again")
    parser.add_argument("--device-secret", help="secret for --device-id")
    parser.add_argument("--indent", type=int, default=2, help="JSON output indentation (default: %(default)s)")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if bool(args.device_id) != bool(args.device_secret):
        raise SystemExit("--device-id and --device-secret must be given together")

    if args.device_id:
        device_id, device_secret = args.device_id, args.device_secret
    else:
        device_id, device_secret = register(args)

    response = check_version(args, device_id, device_secret)
    result = normalise(response, args, device_id)
    print(json.dumps(result, indent=args.indent, ensure_ascii=False, sort_keys=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
