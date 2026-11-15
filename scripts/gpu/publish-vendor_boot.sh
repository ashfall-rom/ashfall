#!/usr/bin/env bash
# AshFall: publish a built vendor_boot image as a hash-pinned release in the
# artifact repository ($VENDOR_BOOT_REPO, default ashfall-rom/rp5-vendor_boot).
#
# The ROM repository owns the build (scripts/gpu/build-vendor-boot.sh) and the
# flashing (scripts/device/flash-vendor-boot.sh); this script is the one-way
# bridge from `out/gpu/<id>/` to an immutable, downloadable artifact.  See the
# artifact repo's README/PROVENANCE for the rules.
#
# What it publishes (never the image itself into git):
#   vendor_boot-<tag>.img            content-only image (issue #54)
#   vendor_boot-<tag>.img.sha256
#   manifest-<tag>.json              sizes, hashes, per-FDT verification, tools
#   dts-<tag>.tar.gz                 every board FDT, stock and patched
#   summary-<tag>.txt                the human summary from the build
#   SHA256SUMS-<tag>                 hashes of all of the above
#
# Usage:
#   scripts/gpu/publish-vendor_boot.sh --dir out/gpu/<id> [options]
#
# Options:
#   --dir PATH        artifact directory from build-vendor-boot.sh
#                     (default: out/gpu/<id>, see --id)
#   --id ID           artifact id / directory name under out/gpu
#   --tag TAG         release tag (default: the manifest id, e.g.
#                     vb-oc8-20261115); tags are immutable by rule
#   --repo OWNER/REPO artifact repository (default: $VENDOR_BOOT_REPO)
#   --title TITLE     release title (default: "vendor_boot <tag>")
#   --notes FILE      release notes (default: generated from the manifest)
#   --rom-commit SHA  ROM commit the image was built from (default: HEAD)
#   --force           update an existing release (breaks tag immutability; only
#                     for a tag that was never flashed)
#   --allow-debug     allow a debug-only artifact (--extra-step/--drop-step)
#   --allow-nonreproducible  allow manifest "reproducible: no/skipped"
#   --dry-run         build and verify the release set, print the plan, no gh
#   -h, --help        this help
#
# Exit codes: 0 published, 1 verification/gh failure, 2 usage.
#
# The image is checked against its own manifest before anything is uploaded,
# and the uploaded asset is downloaded back and re-hashed afterwards, so a
# release always matches what the build produced.  Requires `gh` authenticated
# with write access to the artifact repo.
# Markdown backticks inside printf format strings are intentional.
# shellcheck disable=SC2016
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
need gh sha256sum tar python3

DIR=""
ID=""
TAG=""
REPO=${VENDOR_BOOT_REPO:-}
TITLE=""
NOTES=""
ROM_COMMIT=""
FORCE=0
ALLOW_DEBUG=0
ALLOW_REPRO=0
DRY_RUN=0

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }
die_usage() { printf 'err %s\n' "$*" >&2; printf 'try --help\n' >&2; exit 2; }

while (($#)); do
  case $1 in
    --dir) DIR=${2:?--dir needs a path}; shift 2 ;;
    --id) ID=${2:?--id needs a value}; shift 2 ;;
    --tag) TAG=${2:?--tag needs a value}; shift 2 ;;
    --repo) REPO=${2:?--repo needs owner/repo}; shift 2 ;;
    --title) TITLE=${2:?--title needs a value}; shift 2 ;;
    --notes) NOTES=${2:?--notes needs a path}; shift 2 ;;
    --rom-commit) ROM_COMMIT=${2:?--rom-commit needs a sha}; shift 2 ;;
    --force) FORCE=1; shift ;;
    --allow-debug) ALLOW_DEBUG=1; shift ;;
    --allow-nonreproducible) ALLOW_REPRO=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) die_usage "unknown option: $1" ;;
    *) die_usage "unexpected argument: $1" ;;
  esac
done

[[ -n $REPO ]] || [[ $DRY_RUN == 1 ]] || die "VENDOR_BOOT_REPO is not set in config/ashfall.conf (or pass --repo)"
[[ -n $REPO ]] || REPO="ashfall-rom/rp5-vendor_boot"
[[ -n $DIR || -n $ID ]] || die_usage "pass --dir <artifact dir> (or --id <id>)"
[[ -z $DIR || -z $ID ]] || die_usage "--dir and --id are mutually exclusive"
[[ -n $DIR ]] || DIR="$ASH_ROOT/out/gpu/$ID"
[[ -d $DIR ]] || die "artifact directory not found: $DIR"
[[ -f $DIR/manifest.json ]] || die "missing manifest.json in $DIR (rebuild with build-vendor-boot.sh)"
[[ -f $DIR/vendor_boot.img ]] || die "missing vendor_boot.img in $DIR"
[[ -d $DIR/dts ]] || die "missing dts/ in $DIR (the review sources)"
[[ -n ${ROM_COMMIT:-} ]] || ROM_COMMIT=$(git -C "$ASH_ROOT" rev-parse --short HEAD)

# --- read and check the manifest --------------------------------------------
MANIFEST="$DIR/manifest.json"
read -r M_ID M_SHA M_SIZE M_STOCK_SHA M_PADDED_SHA M_REPRO M_DEBUG M_DTB_SHA < <(
  python3 - "$MANIFEST" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
out = m.get("output", {})
print(
    m.get("id", ""),
    out.get("sha256", ""),
    out.get("size", 0),
    m.get("stock", {}).get("sha256", ""),
    m.get("padded", {}).get("sha256", ""),
    m.get("reproducible", ""),
    "yes" if m.get("debug", {}).get("debug_only") else "no",
    m.get("dtb", {}).get("out_sha256", ""),
)
PY
) || die "cannot parse $MANIFEST"

[[ -n $TAG ]] || TAG=$M_ID
[[ $TAG =~ ^vb-[a-z0-9][a-z0-9._-]*$ ]] || die "tag '$TAG' must look like vb-<table>-<yyyymmdd> (lowercase, no spaces)"

if [[ $M_DEBUG == yes && $ALLOW_DEBUG != 1 ]]; then
  die "$DIR is a debug-only artifact (--extra-step/--drop-step); refusing to publish it (pass --allow-debug only for a deliberate test image)"
fi
if [[ $M_REPRO != yes && $ALLOW_REPRO != 1 ]]; then
  die "manifest says reproducible='$M_REPRO'; a published image must come from a reproducible build (--allow-nonreproducible to override)"
fi

ACTUAL_SHA=$(sha256sum "$DIR/vendor_boot.img" | awk '{print $1}')
ACTUAL_SIZE=$(stat -c %s "$DIR/vendor_boot.img")
[[ $ACTUAL_SHA == "$M_SHA" ]] || die "vendor_boot.img sha256 $ACTUAL_SHA does not match the manifest ($M_SHA)"
[[ $ACTUAL_SIZE == "$M_SIZE" ]] || die "vendor_boot.img size $ACTUAL_SIZE does not match the manifest ($M_SIZE)"
((ACTUAL_SIZE < 100663296)) || die "refusing to publish a partition-sized image ($ACTUAL_SIZE B): content-only only (issue #54)"
python3 - "$DIR/vendor_boot.img" <<'PY' || die "vendor_boot.img is not a header-v4 vendor_boot image"
import struct, sys
with open(sys.argv[1], "rb") as fh:
    head = fh.read(12)
magic, version = struct.unpack_from("<8sI", head, 0)
raise SystemExit(0 if magic == b"VNDRBOOT" and version == 4 else 1)
PY

ok "verified $DIR/vendor_boot.img ($(numfmt --to=iec-i --suffix=B "$ACTUAL_SIZE" 2>/dev/null || echo "$ACTUAL_SIZE B"), sha256 $ACTUAL_SHA)"
[[ -n $TITLE ]] || TITLE="vendor_boot $TAG"

# --- staging ----------------------------------------------------------------
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
IMG_ASSET="vendor_boot-$TAG.img"
cp "$DIR/vendor_boot.img" "$STAGE/$IMG_ASSET"
(cd "$STAGE" && sha256sum "$IMG_ASSET" >"$IMG_ASSET.sha256")
cp "$MANIFEST" "$STAGE/manifest-$TAG.json"
[[ -f $DIR/summary.txt ]] && cp "$DIR/summary.txt" "$STAGE/summary-$TAG.txt"
tar -C "$DIR/dts" -czf "$STAGE/dts-$TAG.tar.gz" .
[[ -f $DIR/SHA256SUMS ]] && cp "$DIR/SHA256SUMS" "$STAGE/build-SHA256SUMS-$TAG.txt"
(
  cd "$STAGE"
  # shellcheck disable=SC2035
  sha256sum -- * >"SHA256SUMS-$TAG"
)
# The padded image must never leave the build host (issue #54).
if compgen -G "$STAGE/*padded*" >/dev/null; then
  die "internal: padded image found in the staging set"
fi

# --- notes ------------------------------------------------------------------
if [[ -z $NOTES ]]; then
  NOTES="$STAGE/release-notes.md"
  {
    printf '%s\n\n' "$TITLE"
    printf 'ROM commit `%s`. Built with `scripts/gpu/build-vendor-boot.sh`.\n\n' "$ROM_COMMIT"
    printf '| | |\n|---|---|\n'
    printf '| image | `%s` |\n' "$IMG_ASSET"
    printf '| size | %s B (content-only) |\n' "$ACTUAL_SIZE"
    printf '| sha256 | `%s` |\n' "$ACTUAL_SHA"
    printf '| stock input | `%s` (`.31 lun4_vendor_boot_a.bin`) |\n' "$M_STOCK_SHA"
    printf '| DTB payload | `%s` |\n' "$M_DTB_SHA"
    printf '| padded variant | `%s` — **never flash** (issue #54) |\n' "$M_PADDED_SHA"
    printf '| reproducible | %s |\n\n' "$M_REPRO"
    printf 'Pin it in the ROM repo (`config/ashfall.conf`):\n\n'
    printf '```\nVENDOR_BOOT_REPO="%s"\nVENDOR_BOOT_TAG="%s"\nVENDOR_BOOT_SHA256="%s"\n```\n\n' \
      "$REPO" "$TAG" "$ACTUAL_SHA"
    printf 'Flash it: `scripts/device/flash-vendor-boot.sh --release %s`\n\n' "$TAG"
    printf 'Provenance, rules and the DTB sources: see the repository README and `PROVENANCE.md`.\n'
    if [[ -f $DIR/summary.txt ]]; then
      printf '\n<details><summary>build summary</summary>\n\n```\n'
      cat "$DIR/summary.txt"
      printf '```\n</details>\n'
    fi
  } >"$NOTES"
fi

ok "release set ($TAG):"
find "$STAGE" -maxdepth 1 -type f -printf '    %10s  %f\n' | sort -k2

if ((DRY_RUN)); then
  warn "--dry-run: nothing was uploaded"
  printf '\npin:\n  VENDOR_BOOT_REPO="%s"\n  VENDOR_BOOT_TAG="%s"\n  VENDOR_BOOT_SHA256="%s"\n' \
    "$REPO" "$TAG" "$ACTUAL_SHA"
  exit 0
fi

# --- publish ----------------------------------------------------------------
need gh
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  if ((FORCE)); then
    warn "release $TAG exists; --force given, re-uploading the assets (clobber)"
  else
    die "release $TAG already exists in $REPO; tags are immutable. Bump the tag (e.g. -r2) or pass --force for a tag that was never flashed."
  fi
fi

mapfile -t ASSETS < <(find "$STAGE" -maxdepth 1 -type f ! -name 'release-notes.md' -printf '%f\n' | sort)
((${#ASSETS[@]})) || die "nothing to upload"
mapfile -t ASSET_PATHS < <(for f in "${ASSETS[@]}"; do printf '%s/%s\n' "$STAGE" "$f"; done)

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "${ASSET_PATHS[@]}" --repo "$REPO" --clobber
else
  gh release create "$TAG" "${ASSET_PATHS[@]}" --repo "$REPO" \
    --title "$TITLE" --notes-file "$NOTES" --target main
fi

# --- verify what is actually downloadable ------------------------------------
VERIFY=$(mktemp -d)
trap 'rm -rf "$STAGE" "$VERIFY"' EXIT
gh release download "$TAG" --repo "$REPO" --pattern "$IMG_ASSET" --dir "$VERIFY" >/dev/null ||
  die "cannot download the published image back from $REPO@$TAG"
GOT=$(sha256sum "$VERIFY/$IMG_ASSET" | awk '{print $1}')
[[ $GOT == "$ACTUAL_SHA" ]] || die "published asset hash $GOT differs from the built image $ACTUAL_SHA"
ok "published and re-downloaded: $REPO@$TAG sha256 $GOT"

printf '\nnext:\n'
printf '  1. pin in config/ashfall.conf:\n'
printf '     VENDOR_BOOT_REPO="%s"\n' "$REPO"
printf '     VENDOR_BOOT_TAG="%s"\n' "$TAG"
printf '     VENDOR_BOOT_SHA256="%s"\n' "$ACTUAL_SHA"
printf '  2. flash with: scripts/device/flash-vendor-boot.sh --release %s\n' "$TAG"
printf '  3. add the row to RELEASES/README in %s\n' "$REPO"
