#!/usr/bin/env bash
# AshFall device runner: run adb/fastboot on the build host (where the RP5 is
# USB-connected) from this VM.
#
# The tools live in the dedicated distrobox container `ashfall-dev`; this
# wrapper creates/installs it on demand and forwards commands into it.
#
# Usage:
#   scripts/device/run.sh adb devices -l
#   scripts/device/run.sh fastboot getvar product
#   scripts/device/run.sh status          # container + device overview
#   scripts/device/run.sh ensure          # create/repair the container
#   scripts/device/run.sh shell           # interactive bash inside the box
#   scripts/device/run.sh recreate        # destroy + recreate (fixes a broken box)
#
# Options:
#   --no-ensure    do not create/install the container (fail fast instead)
#   --tty          allocate a TTY (interactive adb shell / fastboot)
#   -h, --help     this help
#
# The container is `ashfall-dev` (issue #3, USB/adb/fastboot). Do not reuse
# `ashfall-build` (#1) or `ashfall-fw` (#2).
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config
# shellcheck source=SCRIPTDIR/lib/device.sh
. "$HERE/lib/device.sh"

ensure=1
use_tty=0

usage() { awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; }

log_cmd() { printf '+ %s\n' "$*" >&2; }

main() {
  local -a passthrough=()
  while (($#)); do
    case $1 in
      -h|--help) usage; exit 0 ;;
      --no-ensure) ensure=0 ;;
      --tty) use_tty=1 ;;
      --) shift; passthrough+=("$@"); break ;;
      *) passthrough+=("$1") ;;
    esac
    shift
  done
  ((${#passthrough[@]})) || { usage; exit 2; }

  local sub=${passthrough[0]}
  case $sub in
    ensure)
      dev_ensure
      ok "container '$DEV_BOX' ready on $BUILD_HOST"
      dev_sh 'adb version | head -1; fastboot --version | head -1'
      return 0
      ;;
    recreate)
      dev_recreate
      ok "container '$DEV_BOX' recreated"
      return 0
      ;;
    status)
      ((ensure)) && dev_ensure
      printf 'host:      %s\n' "$BUILD_HOST"
      printf 'container: %s (%s)\n' "$DEV_BOX" "$(host_sh "podman container exists $(_dq "$DEV_BOX") && echo present || echo missing")"
      printf 'tools:     %s\n' "$(dev_sh 'adb version 2>/dev/null | head -1; fastboot --version 2>/dev/null | head -1' | paste -sd ' / ' -)"
      printf 'usb:       \n'
      dev_usb_ids | sed 's/^/           /'
      printf 'adb:       \n'
      dev_sh 'timeout 20 adb devices -l' | sed 's/^/           /'
      printf 'fastboot:  \n'
      dev_sh 'timeout 20 fastboot devices -l' | sed 's/^/           /'
      printf 'mode:      %s\n' "$(dev_mode)"
      return 0
      ;;
    shell)
      ((ensure)) && dev_ensure
      dev_interactive_sh 'exec bash -i'
      return 0
      ;;
  esac

  ((ensure)) && dev_ensure
  local cmd; cmd=$(_cmd "${passthrough[@]}")
  log_cmd "distrobox enter $DEV_BOX -- $cmd"
  if ((use_tty)); then
    dev_interactive_sh "$cmd"
  else
    dev_sh "$cmd"
  fi
}

main "$@"
