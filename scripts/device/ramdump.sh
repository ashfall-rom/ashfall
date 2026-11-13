#!/usr/bin/env bash
# AshFall: recover the kernel log from an RP5 stuck in Qualcomm Sahara
# "memory debug" / crash-dump mode (USB 05c6:900e).
#
# The RP5 panics with `reboot=panic_warm`; the PBL then re-enumerates as 900e
# and keeps RAM (battery-backed) intact. Sahara memory-debug mode lets us read
# those regions over USB *without* programming anything, so this is a pure
# read: no EDL writes, no erase, no partition reads that need firehose, no
# reset. The device is left in 900e for the next step.
#
# Pipeline
#   1. preflight   05c6:900e present, no qcserial/option/usb_wwan bound,
#                  /dev/bus/usb/BBB/DDD writable by this user
#   2. container   dedicated rootless podman box `ashfall-edl` (Ubuntu 24.04,
#                  bkerler/edl in /opt/edlenv) - never the build boxes
#   3. dump        `edl memorydump` (Sahara memory-debug, normal verbosity) to
#                  $REMOTE_ROOT/crash/<timestamp>/memory/
#   4. extract     scripts/device/printk_extract.py walks the printk record
#                  chain, writes log_all.txt + anchors.txt + summary.txt
#
# Host prerequisite (one-time, approved change, needs root once)
#   The device node is root:root 0664 and podman here is rootless, so the
#   container cannot open usbfs. Install this udev rule on the build host:
#
#     /etc/udev/rules.d/51-ashfall-qcom.rules
#     SUBSYSTEM=="usb", ATTRS{idVendor}=="05c6", ATTRS{idProduct}=="9008", MODE="0666", TAG+="uaccess"
#     SUBSYSTEM=="usb", ATTRS{idVendor}=="05c6", ATTRS{idProduct}=="900e", MODE="0666", TAG+="uaccess"
#
#     sudo udevadm control --reload-rules && sudo udevadm trigger --subsystem-match=usb
#
#   `chmod 666 /dev/bus/usb/001/0NN` also works but only until the next
#   re-enumeration.
#
# Gotchas (docs/reference/rp5-recovery-guide.md §7.3, §12)
#   * `qcserial`/`option`/`usb_wwan` steal the one-shot Sahara hello. Unload
#     them (`sudo modprobe -r qcserial usb_wwan`) *and* block udev reload.
#   * Do NOT use `edl --debugmode` here: it enables DEBUG logging and grows a
#     ~17 GB log while dumping. Plain `edl memorydump` prints the same memory
#     table at INFO level.
#   * Sahara sends its hello once per 900e entry. If something consumes it,
#     the device must be unplugged/re-plugged (RAM survives: battery-backed).
#   * `edl` ends its built-in memory-debug path with `cmd_reset()`. We patch
#     that out in the container so a partial/failed dump never resets the
#     device before the log is saved.
#
# Usage
#   scripts/device/ramdump.sh [--wait SECONDS] [--extract-only DIR] [--out DIR]
#                             [--keep-device] [--help]
#
#   --wait SECONDS    if the node is not writable yet, poll up to SECONDS and
#                     print the exact command the user has to run (default 0)
#   --extract-only D  skip dump, re-run the extractor on an existing memory dir
#   --out DIR         crash root (default: $REMOTE_ROOT/crash)
#   --keep-device     explicit no-op: the patched edl never resets the device
#
# Exit codes: 0 recovered (or dump taken), 1 preflight/setup failure,
#             2 usage error.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$HERE/../lib/common.sh"
load_config

BOX=${ASH_EDL_BOX:-ashfall-edl}
BOX_IMAGE=${ASH_EDL_IMAGE:-docker.io/library/ubuntu:24.04}
EXTRACTOR=$HERE/printk_extract.py
WAIT=0
OUT=""
ONLY=""

while (($#)); do
  case $1 in
    --wait) WAIT=${2:?--wait needs seconds}; shift 2 ;;
    --extract-only) ONLY=${2:?--extract-only needs a dir}; shift 2 ;;
    --out) OUT=${2:?--out needs a dir}; shift 2 ;;
    --keep-device) shift ;;
    -h|--help) awk 'NR==1{next} /^set -euo/{exit} {sub(/^# ?/,""); print}' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
CRASH_ROOT=${OUT:-$REMOTE_ROOT/crash}
need ssh scp

# host <cmd>: run a command on the build host (see lib/common.sh host_sh)
host() { host_sh "$*"; }
# host_py: feed a local file to python3 inside the container
host_py() { ssh "${ASH_SSH_OPTS[@]}" "$BUILD_HOST" "podman exec -i $BOX python3 -" <"$1"; }

# ---------------------------------------------------------------- preflight --
preflight() {
  local line bus dev node waited=0

  line=$(host 'lsusb -d 05c6:900e') || line=
  [[ -n $line ]] || die "no 05c6:900e on $BUILD_HOST (device not in crash-dump mode)"
  ok "device: $line"

  if host 'lsmod | grep -qE "^(qcserial|option|usb_wwan) "' >/dev/null 2>&1; then
    warn "qcserial/option/usb_wwan is loaded and eats the one-shot Sahara hello."
    warn "Run on $BUILD_HOST: sudo modprobe -r qcserial usb_wwan option"
    die "not touching the device until those drivers are unloaded"
  fi
  ok "no qcserial/option/usb_wwan loaded"

  bus=$(awk '{print $2}' <<<"$line")
  dev=$(awk '{print $4}' <<<"$line" | tr -d ':')
  node=/dev/bus/usb/$bus/$dev

  while ! host "test -w $node" >/dev/null 2>&1; do
    if (( waited == 0 )); then
      warn "$node is not writable by ${BUILD_HOST#*@} (rootless podman cannot open usbfs)"
      cat >&2 <<EOF

Run ONE of these on $BUILD_HOST, then start this script again:

  sudo chmod 666 $node
  # or, permanent (approved host change):
  printf 'SUBSYSTEM=="usb", ATTRS{idVendor}=="05c6", ATTRS{idProduct}=="900e", MODE="0666", TAG+="uaccess"\n' \\
    | sudo tee /etc/udev/rules.d/51-ashfall-qcom.rules
  sudo udevadm control --reload-rules && sudo udevadm trigger --subsystem-match=usb

EOF
      (( WAIT > 0 )) || die "node not writable"
    fi
    (( waited >= WAIT )) && die "timed out waiting for $node"
    sleep 2
    waited=$((waited + 2))
  done
  ok "usbfs node writable: $node"
}

# ----------------------------------------------------------------- container --
setup_box() {
  if ! host "podman container exists $BOX" >/dev/null 2>&1; then
    log "creating container $BOX"
    host "podman run -d --name $BOX --privileged \
      -v /dev/bus/usb:/dev/bus/usb -v $(printf %q "$CRASH_ROOT"):/work/crash \
      -w /work/crash $BOX_IMAGE sleep infinity" >/dev/null
  fi
  host "podman start $BOX" >/dev/null 2>&1 || true

  if ! host "podman exec $BOX test -x /opt/edlenv/bin/edl" >/dev/null 2>&1; then
    log "installing edl inside $BOX (Ubuntu 24.04 venv, bkerler/edl)"
    host "podman exec $BOX bash -lc '
      set -e
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -qq
      apt-get install -y -qq --no-install-recommends usbutils python3 python3-pip \
        python3-venv python3-dev git build-essential libusb-1.0-0 ca-certificates >/tmp/apt.log 2>&1
      python3 -m venv /opt/edlenv
      git clone -q https://github.com/bkerler/edl /opt/edl
      cd /opt/edl && git submodule update --init -q
      /opt/edlenv/bin/pip install -q . >/tmp/pip.log 2>&1
    '"
  fi

  # Container-local patches (never touch the device; both only relax the host
  # side of the tool):
  #  1. 900e may not expose a serial string descriptor -> pyusb raises
  #     ValueError while enumerating and edl aborts before the hello.
  #  2. keep the device in 900e: edl's memory-debug path ends with cmd_reset().
  local patch
  patch=$(mktemp)
  cat >"$patch" <<'PY'
import sys

p = '/opt/edlenv/lib/python3.12/site-packages/edlclient/Library/Connection/usblib.py'
s = open(p).read()
old = '''                if dev.idProduct == usbid[1] and dev.idVendor == usbid[0]:
                    if self.serial_number is not None:
                        if dev.serial_number != self.serial_number:
                            continue
                    self.device = dev
                    self.vid = dev.idVendor
                    self.pid = dev.idProduct
                    self.serial_number = dev.serial_number
'''
new = '''                if dev.idProduct == usbid[1] and dev.idVendor == usbid[0]:
                    try:
                        _sn = dev.serial_number
                    except Exception:
                        _sn = None
                    if self.serial_number is not None and _sn is not None:
                        if _sn != self.serial_number:
                            continue
                    self.device = dev
                    self.vid = dev.idVendor
                    self.pid = dev.idProduct
                    self.serial_number = _sn
'''
if new not in s and old in s:
    open(p, 'w').write(s.replace(old, new))
    print('patched usblib: tolerate missing serial descriptor')

q = '/opt/edlenv/lib/python3.12/site-packages/edlclient/Library/sahara.py'
t = open(q).read()
if 'AshFall: keep device in 900e' not in t and '        self.cmd_reset()\n        return True' in t:
    t = t.replace('        self.cmd_reset()\n        return True',
                  '        # AshFall: keep device in 900e (no reset before log is saved)\n'
                  '        return True', 1)
    open(q, 'w').write(t)
    print('patched sahara: no reset after dump')
PY
  host_py "$patch"
  rm -f "$patch"
  ok "container ready: $BOX"
}

# ---------------------------------------------------------------------- dump --
run_dump() {
  local ts dir
  ts=$(date -u +%Y%m%d-%H%M%S)
  dir=$CRASH_ROOT/$ts
  host "mkdir -p $(printf %q "$dir")"
  log "dumping RAM to $dir/memory (read-only; 8 GiB over USB 2.0 takes ~5 min)" >&2
  # $dir only exists on the build host: redirect the dump log there, never here.
  host "podman exec -w /work/crash/$ts $BOX bash -lc '
        mkdir -p logs
        timeout 3600 /opt/edlenv/bin/edl memorydump
      ' > $(printf %q "$dir/dump.log") 2>&1" \
    || warn "edl exited non-zero - dump may be partial"
  host "tail -n 15 $(printf %q "$dir/dump.log")" >&2 || true
  host "test -d $(printf %q "$dir/memory")" \
    || die "no memory/ directory produced - see $dir/dump.log on $BUILD_HOST"
  printf '%s\n' "$dir"
}

# ------------------------------------------------------------------- extract --
run_extract() {
  local dir=$1
  [[ -f $EXTRACTOR ]] || die "missing $EXTRACTOR"
  scp -q "${ASH_SSH_OPTS[@]}" "$EXTRACTOR" "$BUILD_HOST:/tmp/printk_extract.py"
  log "extracting printk records from $dir/memory"
  host "python3 /tmp/printk_extract.py --memory-dir $(printf %q "$dir/memory") \
    --out-dir $(printf %q "$dir/memory")"
  ok "log:     $dir/memory/log_all.txt"
  ok "anchors: $dir/memory/anchors.txt"
  ok "summary: $dir/memory/summary.txt"
  ok "device left in 900e (patched edl does not reset)"
}

main() {
  if [[ -n $ONLY ]]; then
    # path is on the build host
    host "test -d $(printf %q "$ONLY")" >/dev/null 2>&1 || die "not a directory on $BUILD_HOST: $ONLY"
    run_extract "$(host "readlink -f $(printf %q "$ONLY")")"
    return 0
  fi
  preflight
  setup_box
  local dir
  dir=$(run_dump)
  run_extract "$dir"
}

main "$@"
