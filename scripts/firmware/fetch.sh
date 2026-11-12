#!/usr/bin/env bash
# fetch.sh - download the stock Retroid Pocket 5 firmware archive on the build
# host, resumably, and verify it with 7z. Idempotent: existing volumes are
# compared against the server's Content-Length and only re-downloaded if short.
#
# Usage: scripts/firmware/fetch.sh [--dir DIR] [--force] [--check]
#
# Env (from config/ashfall.conf): STOCK_FW_URL, REMOTE_FIRMWARE.
#
# The script runs its real work on the build host: it rsyncs the checkout and
# re-execs itself there (nothing is installed on the immutable Fedora host).
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../lib/common.sh
. "$SCRIPT_DIR/../lib/common.sh"
load_config
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Download and verify the stock RP5 firmware archive (STOCK_FW_URL).

Options:
  --dir DIR   download directory (default: $REMOTE_FIRMWARE/dl)
  --force     delete existing volumes and download again
  --check     only verify the archive that is already present
  -h, --help  show this help

The upstream release is a split 7z (fw.7z.001..005, ~3 GiB). Downloads resume
with curl -C -, so an interrupted run can simply be started again.
EOF
}

FORCE=0
CHECK=0
PASSTHRU=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --dir) FW_DL=${2:?--dir needs a value}; PASSTHRU+=(--dir "$2"); shift 2 ;;
    --force) FORCE=1; PASSTHRU+=(--force); shift ;;
    --check) CHECK=1; PASSTHRU+=(--check); shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

if ((${#PASSTHRU[@]})); then
  fw_remote_reentry "$(basename "$0")" "${PASSTHRU[@]}"
else
  fw_remote_reentry "$(basename "$0")"
fi

# --------------------------------------------------------------------------
# Remote phase (runs on the build host).
# --------------------------------------------------------------------------
need curl stat awk
mkdir -p "$FW_DL"
ensure_fw_box

content_length() { # <url> -> bytes on stdout (empty if unknown)
  curl -fsIL --max-time 30 "$1" 2>/dev/null |
    awk 'tolower($1) == "content-length:" { gsub(/\r/, "", $2); n = $2 } END { if (n) print n }'
}

# Discover volumes: .7z.001..N if the release is split, otherwise the single URL.
urls=()
if curl -fsIL --max-time 30 "$STOCK_FW_URL.001" >/dev/null 2>&1; then
  for i in $(seq 1 99); do
    n=$(printf '%03d' "$i")
    if curl -fsIL --max-time 30 "$STOCK_FW_URL.$n" >/dev/null 2>&1; then
      urls+=("$STOCK_FW_URL.$n|$FW_DL/fw.7z.$n")
    else
      break
    fi
  done
else
  urls+=("$STOCK_FW_URL|$FW_DL/$(basename "$STOCK_FW_URL")")
fi
[[ ${#urls[@]} -gt 0 ]] || die "no archive volumes found at $STOCK_FW_URL"

log "archive: ${#urls[@]} volume(s) -> $FW_DL"
for entry in "${urls[@]}"; do
  url=${entry%%|*}
  dest=${entry##*|}
  [[ $FORCE == 1 ]] && rm -f "$dest"
  want=$(content_length "$url")
  have=0
  [[ -f $dest ]] && have=$(stat -c %s "$dest")
  if [[ -n $want && $have == "$want" ]]; then
    ok "$(basename "$dest") complete ($have bytes)"
    continue
  fi
  [[ $CHECK == 1 ]] && die "$(basename "$dest"): incomplete ($have/${want:-?} bytes)"
  log "downloading $(basename "$dest") (${have}/${want:-?} bytes)"
  curl -fL -C - --retry 5 --retry-delay 5 --progress-bar -o "$dest" "$url"
  have=$(stat -c %s "$dest")
  if [[ -n $want && $have != "$want" ]]; then
    die "$(basename "$dest"): size $have != server $want"
  fi
done

first=${urls[0]##*|}
log "verifying archive with 7z"
if ! fw_sh "7z t $(printf %q "$first") | tail -2"; then
  die "7z integrity test failed (delete the volumes and rerun with --force)"
fi
ok "firmware archive verified: $FW_DL ($(du -sh "$FW_DL" | cut -f1))"
