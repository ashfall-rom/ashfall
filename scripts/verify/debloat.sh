#!/usr/bin/env bash
# AshFall phase 7 debloat audit (docs/phase7.md).
#
# Checks the phase-7 blocklist against a build's installed-files.txt and,
# optionally, against a device's `pm list packages` output. Read-only.
#
# Usage:
#   scripts/verify/debloat.sh <artifact-dir|installed-files.txt>
#   scripts/verify/debloat.sh --packages <pm-list-file>
#   scripts/verify/debloat.sh --device [--serial SERIAL]
#   scripts/verify/debloat.sh --help
#
# The artifact check is the host-side gate: it fails if a blocklisted module,
# APEX or file is still in the image. The device check is the post-flash gate:
# it fails if a blocklisted package is installed. `--device` goes through
# scripts/device/run.sh and therefore needs the RP5 lease (read-only is fine;
# `scripts/device/lock.sh run <owner> -- scripts/verify/debloat.sh --device`).
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config

BLOCKLIST=${ASHFALL_DEBLOCKLIST:-$ASH_ROOT/aosp/vendor/ashfall/debloat/blocklist.txt}
MODE=artifact
TARGET=
PACKAGES_FILE=
SERIAL=

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

die_usage() { printf 'error: %s\n\n' "$1" >&2; usage >&2; exit 2; }

while (($#)); do
  case $1 in
    -h|--help) usage; exit 0 ;;
    --packages) [[ -n ${2:-} ]] || die_usage "--packages needs a file"; MODE=packages; PACKAGES_FILE=$2; shift 2 ;;
    --device) MODE=device; shift ;;
    --serial) [[ -n ${2:-} ]] || die_usage "--serial needs a value"; SERIAL=$2; shift 2 ;;
    -*) die_usage "unknown option: $1" ;;
    *) [[ -z $TARGET ]] || die_usage "only one artifact target"; TARGET=$1; shift ;;
  esac
done

[[ -f $BLOCKLIST ]] || die "blocklist not found: $BLOCKLIST"

# parse_blocklist: writes "kind<TAB>value" lines, comments/blank stripped.
parse_blocklist() {
  awk '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    {
      kind = $1; $1 = "";
      sub(/^[[:space:]]+/, "");
      if (kind == "module" || kind == "path" || kind == "package") print kind "\t" $0;
    }
  ' "$BLOCKLIST"
}

mapfile -t ENTRIES < <(parse_blocklist)
(( ${#ENTRIES[@]} )) || die "blocklist has no entries: $BLOCKLIST"

check_artifact() {
  local file=$1 kind value hits=0
  [[ -f $file ]] || die "installed-files.txt not found: $file"

  for entry in "${ENTRIES[@]}"; do
    kind=${entry%%$'\t'*}
    value=${entry#*$'\t'}
    [[ $kind == package ]] && continue

    if [[ $kind == path ]]; then
      if grep -Fq -- "$value" "$file"; then
        printf 'FAIL  path   %s\n' "$value"
        hits=$((hits + 1))
      fi
      continue
    fi

    # module: /Name/ or /Name.apex or /Name at the end of a line.
    if grep -Eq -- "/${value}(/|\\.apex|$)" "$file"; then
      printf 'FAIL  module %s\n' "$value"
      grep -E -- "/${value}(/|\\.apex|$)" "$file" | sed 's/^/        /' | head -3
      hits=$((hits + 1))
    fi
  done

  printf '\n%s: %d blocklisted module/path hit(s) in %s\n' \
    "$([[ $hits -eq 0 ]] && echo PASS || echo FAIL)" "$hits" "$file"
  (( hits == 0 ))
}

check_packages() {
  local file=$1 kind value hits=0 pkg
  [[ -f $file ]] || die "package list not found: $file"

  # Accept bare names and `package:` lines; strip CR for adb output.
  while IFS= read -r pkg; do
    pkg=${pkg%$'\r'}
    pkg=${pkg#package:}
    for entry in "${ENTRIES[@]}"; do
      kind=${entry%%$'\t'*}
      value=${entry#*$'\t'}
      [[ $kind == package ]] || continue
      if [[ $pkg == "$value" ]]; then
        printf 'FAIL  package %s\n' "$value"
        hits=$((hits + 1))
      fi
    done
  done < "$file"

  printf '\n%s: %d blocklisted package(s) installed\n' \
    "$([[ $hits -eq 0 ]] && echo PASS || echo FAIL)" "$hits"
  (( hits == 0 ))
}

case $MODE in
  artifact)
    [[ -n $TARGET ]] || die_usage "an artifact directory or installed-files.txt is required"
    if [[ -d $TARGET ]]; then TARGET=$TARGET/installed-files.txt; fi
    check_artifact "$TARGET"
    ;;
  packages)
    check_packages "$PACKAGES_FILE"
    ;;
  device)
    need ssh
    mkdir -p /tmp/opencode
    tmp=$(mktemp /tmp/opencode/ashfall-debloat.XXXXXX)
    trap 'rm -f "$tmp"' EXIT
    log "reading the package list from the RP5"
    if [[ -n $SERIAL ]]; then
      "$ASH_ROOT/scripts/device/run.sh" adb -s "$SERIAL" shell pm list packages > "$tmp"
    else
      "$ASH_ROOT/scripts/device/run.sh" adb shell pm list packages > "$tmp"
    fi
    check_packages "$tmp"
    ;;
  *)
    die_usage "unknown mode"
    ;;
esac
