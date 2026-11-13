#!/usr/bin/env bash
# inspect.sh - turn the extracted stock firmware into the text artefacts that
# docs/stock-analysis.md is based on.
#
# Produces (and pulls into docs/stock/):
#   super-lpdump.txt, props-*.txt, props-of-interest.txt,
#   vintf/**, fstab-vendor-ramdisk.txt, init-*-services.txt,
#   boot-headers.txt, bootconfig.txt, dtbo-table.txt, vbmeta.txt,
#   gpt-headers.txt, kernel-version.txt, kernel-config.txt, kernel-modules.txt,
#   vendor-apps.txt / system-apps.txt, mcu-fan-rgb.txt,
#   fan-control.txt (real fan interface), touch-idc.txt,
#   rpsettings-sysfs-strings.txt (hints only),
#   community/** (A17 device tree's key files + FETCH-STATUS.txt),
#   fcm-report.md (vendor manifest vs Android 17 matrices).
#
# Usage: scripts/firmware/inspect.sh [--workdir DIR] [--out DIR]
#                                    [--no-community] [--no-fcm]
#
# --workdir selects the extracted-firmware root (default: $REMOTE_FIRMWARE), so
# a second firmware staged under a different directory can be inspected without
# replacing the .31 tree. Runs remotely (build host) and rsyncs the result back
# to DIR (default docs/stock). NO binary images are written into the repo.
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

Collect the stock-firmware facts needed by docs/stock-analysis.md.

Options:
  --workdir DIR    extracted-firmware root (default: $REMOTE_FIRMWARE)
  --out DIR        local output directory (default: $ASH_ROOT/docs/stock)
  --no-community   skip fetching the community A17 device tree files
  --no-fcm         skip the Android 17 compatibility-matrix comparison
  -h, --help       show this help

Prerequsite: scripts/firmware/extract.sh has run on the build host.
EOF
}

FW_REPORT=$REMOTE_FIRMWARE/report
FW_SLOT=${FW_SLOT:-_a}

# --------------------------------------------------------------------------
# Local phase: push, run remotely, pull the report.
# --------------------------------------------------------------------------
if [[ ${ASH_FW_REMOTE:-0} != 1 ]]; then
  OUT=$ASH_ROOT/docs/stock
  WORKDIR=$REMOTE_FIRMWARE
  DO_COMMUNITY=1
  DO_FCM=1
  while [[ $# -gt 0 ]]; do
    case $1 in
      --workdir) WORKDIR=$2; shift 2 ;;
      --out) OUT=$2; shift 2 ;;
      --no-community) DO_COMMUNITY=0; shift ;;
      --no-fcm) DO_FCM=0; shift ;;
      -h | --help) usage; exit 0 ;;
      *) die "unknown argument: $1 (try --help)" ;;
    esac
  done
  FW_REPORT=$WORKDIR/report
  need rsync
  ws=$(ws_push) || die "could not push checkout to the build host"
  log "collecting stock facts on $BUILD_HOST ($ws)"
  remote="--remote --slot $(printf %q "$FW_SLOT") --workdir $(printf %q "$WORKDIR")"
  [[ $DO_COMMUNITY == 1 ]] && remote+=" --community"
  [[ $DO_FCM == 1 ]] && remote+=" --fcm"
  # shellcheck disable=SC2029  # intentional: expanded locally
  host_sh "ASH_ROOT=$(printf %q "$ws") ASH_FW_REMOTE=1 bash $(printf %q "$ws/scripts/firmware/inspect.sh") $remote"
  mkdir -p "$OUT"
  rsync -a --delete -e "ssh ${ASH_SSH_OPTS[*]}" "$BUILD_HOST:$FW_REPORT/" "$OUT/" >&2
  ok "stock facts -> $OUT"
  exit 0
fi

# --------------------------------------------------------------------------
# Remote phase (arguments are built by the local phase above).
# --------------------------------------------------------------------------
SLOT=_a
DO_COMMUNITY=0
DO_FCM=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --remote) shift ;;
    --slot) SLOT=$2; shift 2 ;;
    --workdir) REMOTE_FIRMWARE=$2; shift 2 ;;
    --community) DO_COMMUNITY=1; shift ;;
    --fcm) DO_FCM=1; shift ;;
    *) die "unknown remote argument: $1" ;;
  esac
done
FW_IMG=$REMOTE_FIRMWARE/img
FW_SUPER=$FW_IMG/super
FW_RAMDISK=$FW_IMG/vendor_ramdisk
FW_VENDOR_DUMP=$FW_IMG/vendordump
FW_LOG=$REMOTE_FIRMWARE/logs
FW_REPORT=$REMOTE_FIRMWARE/report
FW_SLOT=$SLOT

[[ -f $FW_SUPER/vendor${FW_SLOT}.img ]] || die "run scripts/firmware/extract.sh first"
[[ -d $FW_VENDOR_DUMP/vendor ]] || die "vendor text dump missing; run scripts/firmware/extract.sh"

rm -rf "$FW_REPORT"
mkdir -p "$FW_REPORT/vintf" "$FW_REPORT/community"

cp "$FW_SUPER/lpdump.txt" "$FW_REPORT/super-lpdump.txt"

# --- properties ----------------------------------------------------------
for part in vendor odm system system_ext product; do
  src=$FW_VENDOR_DUMP/$part/build.prop
  [[ -f $src ]] || continue
  cp "$src" "$FW_REPORT/props-$part.txt"
done
{
  for part in vendor odm system system_ext product; do
    f=$FW_REPORT/props-$part.txt
    [[ -f $f ]] || continue
    echo "## $part"
    grep -hE '^(ro\.(vendor\.build|vendor\.api|vendor\.vndk|board\.|product\.|boot\.|hardware\.|sf\.|surface_flinger\.|opengles\.|gfx\.|soc\.|crypto\.|bpf\.|treble\.)|persist\.(sys\.|vendor\.|demo\.))' "$f" || true
    echo
  done
} >"$FW_REPORT/props-of-interest.txt"

# --- VINTF ---------------------------------------------------------------
vintf=$FW_VENDOR_DUMP/vendor/vintf
if [[ -d $vintf ]]; then
  cp -a "$vintf/." "$FW_REPORT/vintf/"
  (cd "$FW_REPORT/vintf" && find . -type f | sort) >"$FW_REPORT/vintf-files.txt"
  {
    echo "# vendor manifest"
    cat "$FW_REPORT/vintf/manifest.xml" 2>/dev/null || echo "(no manifest.xml)"
    for f in "$FW_REPORT"/vintf/*.xml; do
      [[ $(basename "$f") == manifest.xml ]] && continue
      case $f in *manifest*) echo; echo "# $(basename "$f")"; cat "$f" ;; esac
    done
  } >"$FW_REPORT/vendor-vintf-merged.txt"
else
  warn "no /vendor/etc/vintf in the dump"
fi

# --- fstab / first stage mount ------------------------------------------
{
  found=0
  while IFS= read -r f; do
    found=1
    echo "## ${f#"$FW_RAMDISK"/}"
    cat "$f"
    echo
  done < <(find "$FW_RAMDISK" -type f -name 'fstab*' | sort)
  [[ $found == 1 ]] || echo "(no fstab in vendor_boot ramdisk)"
} >"$FW_REPORT/fstab-vendor-ramdisk.txt"
grep -hE 'first_stage_mount' "$FW_REPORT/fstab-vendor-ramdisk.txt" >"$FW_REPORT/fstab-first-stage-mount.txt" || true

# --- init: services and MCU/fan/RGB hooks -------------------------------
collect_services() { # <init-root> <outfile>
  local root=$1 out=$2
  [[ -d $root ]] || {
    echo "(no init rc in $root)" >"$out"
    return
  }
  find "$root" -type f -name '*.rc' | sort | while IFS= read -r f; do
    awk -v file="${f#"$root"/}" '
      /^[[:space:]]*service[[:space:]]/ { printf "%s\t%s\t%s\n", file, $2, $3; next }
    ' "$f"
  done >"$out"
}
collect_services "$FW_VENDOR_DUMP/vendor/init" "$FW_REPORT/init-vendor-services.txt"
collect_services "$FW_VENDOR_DUMP/system/init" "$FW_REPORT/init-system-services.txt"
find "$FW_VENDOR_DUMP/vendor/init" -type f -name '*.rc' 2>/dev/null | sed "s|$FW_VENDOR_DUMP/||" | sort >"$FW_REPORT/init-vendor-rc-files.txt" || true

{
  echo "# vendor init lines mentioning MCU / fan / RGB / input / rotation"
  grep -rniE 'mcu|fan|rgb|led|gamepad|joystick|rotation|hwrotation' "$FW_VENDOR_DUMP/vendor/init" 2>/dev/null | sed "s|$FW_VENDOR_DUMP/||" || true
  echo
  echo "# vendor apps"
  find "$FW_VENDOR_DUMP/vendor/app" "$FW_VENDOR_DUMP/vendor/priv-app" -maxdepth 2 -name '*.apk' 2>/dev/null | sed "s|$FW_VENDOR_DUMP/||" | sort || true
  echo
  echo "# vendor hw services"
  fw_sh "debugfs -R $(printf %q "ls -l /bin/hw") $(printf %q "$FW_SUPER/vendor${FW_SLOT}.img")" 2>/dev/null | sed '/^debugfs /d' || true
} >"$FW_REPORT/mcu-fan-rgb.txt"

# --- RPSettings sysfs strings (not proof that the nodes exist) ----------
{
  echo "# Sysfs paths/keys found as strings in the stock RPSettings.apk."
  echo "# WARNING: this APK carries a generic multi-platform table (sprd/tegra/omap/"
  echo "# s5p paths appear too). Treat these as hints only; see fan-control.txt for"
  echo "# the interfaces that are actually backed by the stock kernel/DT/init."
  fw_sh "rm -rf $(printf %q "$FW_IMG/.appscan") && mkdir -p $(printf %q "$FW_IMG/.appscan") && \
    debugfs -R $(printf %q "dump /system/app/RPSettings/RPSettings.apk $FW_IMG/.appscan/RPSettings.apk") $(printf %q "$FW_SUPER/system${FW_SLOT}.img") >/dev/null 2>&1 && \
    cd $(printf %q "$FW_IMG/.appscan") && 7z x -y RPSettings.apk >/dev/null && \
    grep -aoE '/sys/(class|devices)/[A-Za-z0-9_./-]+' classes*.dex | sort -u; \
    rm -rf $(printf %q "$FW_IMG/.appscan")" 2>/dev/null || true
} >"$FW_REPORT/rpsettings-sysfs-strings.txt"

# --- community A17 device tree ------------------------------------------
if [[ $DO_COMMUNITY == 1 ]]; then
  base=https://raw.githubusercontent.com/Ayn8250Dev
  Fetched=()
  Failed=()
  fetch() { # <url> <dest>
    local url=$1 dest=$2 tmp
    tmp=$(mktemp "$FW_REPORT/community/.fetch.XXXXXX")
    if curl -fsSL "$url" -o "$tmp"; then
      mv "$tmp" "$dest"
      Fetched+=("$url")
    else
      rm -f "$tmp"
      Failed+=("$url")
      warn "community fetch failed: $url"
    fi
  }
  fetch "$base/android_device_ayn_sxr2130p-common/lineage-24.0/BoardConfigCommon.mk" "$FW_REPORT/community/BoardConfigCommon.mk"
  fetch "$base/android_device_ayn_sxr2130p-common/lineage-24.0/common.mk" "$FW_REPORT/community/common.mk"
  fetch "$base/android_device_ayn_sxr2130p-common/lineage-24.0/vintf/manifest.xml" "$FW_REPORT/community/vintf-manifest.xml"
  fetch "$base/android_device_ayn_sxr2130p-common/lineage-24.0/init/fstab.qcom" "$FW_REPORT/community/fstab.qcom"
  for p in system product vendor odm; do
    fetch "$base/android_device_ayn_sxr2130p-common/lineage-24.0/properties/$p.prop" "$FW_REPORT/community/$p.prop"
  done
  fetch "$base/android_device_retroidpocket_RP5/lineage-24.0/properties/vendor.prop" "$FW_REPORT/community/rp5-vendor.prop"
  fetch "$base/android_device_retroidpocket_RP5/lineage-24.0/device.mk" "$FW_REPORT/community/device.mk"
  fetch "$base/android_device_retroidpocket_RP5/lineage-24.0/configs/idc/fts_ts.idc" "$FW_REPORT/community/fts_ts.idc"
  fetch "$base/android_kernel_ayn_sxr2130p/lineage-24.0/arch/arm64/configs/vendor/kona-perf_defconfig" "$FW_REPORT/community/kona-perf_defconfig"
  fetch "$base/android_kernel_ayn_sxr2130p/lineage-24.0/arch/arm64/configs/vendor/moorechip/kona_perf.config" "$FW_REPORT/community/kona_perf.config"
  {
    echo "# Community fetch status"
    echo
    echo "OK (${#Fetched[@]}):"
    printf '  %s\n' "${Fetched[@]}"
    echo
    echo "FAILED (${#Failed[@]}):"
    if ((${#Failed[@]})); then printf '  %s\n' "${Failed[@]}"; else echo "  (none)"; fi
  } >"$FW_REPORT/community/FETCH-STATUS.txt"
  grep -hE 'CONFIG_(EROFS|F2FS|UNICODE|BPF|CGROUP_BPF|ANDROID_BINDER|BINDERFS|CGROUPS|MEMCG|PSI|IO_URING|USERFAULTFD|DM_DEFAULT_KEY|SELINUX|INCREMENTAL_FS|OVERLAY_FS|ZRAM|LZ4|FS_ENCRYPTION|DM_VERITY)' \
    "$FW_REPORT/community/kona-perf_defconfig" "$FW_REPORT/community/kona_perf.config" 2>/dev/null |
    sort -u >"$FW_REPORT/community/kernel-config-of-interest.txt" || true
fi

# --- fan control evidence (kernel module + DT + init) -------------------
{
  echo "# Fan control interfaces actually present in the stock firmware"
  echo
  echo "## Device-tree node (vendor_boot dtb)"
  fw_sh "dtc -I dtb -O dts $(printf %q "$FW_IMG/vendor_boot/dtb") 2>/dev/null | grep -B3 -A14 'gpio5-pwm'" || true
  echo
  echo "## Vendor init permissions"
  grep -rn 'gpio5_pwm' "$FW_VENDOR_DUMP/vendor/init" 2>/dev/null | sed "s|$FW_VENDOR_DUMP/||" || true
  echo
  echo "## gpio5_pwm.ko (vendor_dlkm) match/attribute symbols"
  fw_sh "rm -rf $(printf %q "$FW_IMG/.fan") && mkdir -p $(printf %q "$FW_IMG/.fan") && \
    debugfs -R $(printf %q "dump /lib/modules/gpio5_pwm.ko $FW_IMG/.fan/gpio5_pwm.ko") $(printf %q "$FW_SUPER/vendor_dlkm${FW_SLOT}.img") >/dev/null 2>&1 && \
    strings -a $(printf %q "$FW_IMG/.fan/gpio5_pwm.ko") | grep -iE 'gpio5-pwm|fan_|gpio5_pwm2|class_attr_(state|duty|speed)' | sort -u; \
    rm -rf $(printf %q "$FW_IMG/.fan")" 2>/dev/null || true
  echo
  echo "## NOT found anywhere in the stock firmware: fan_info / pwm_fan / PWM_FAN"
  echo "(the only fan_info string is the generic RPSettings table, see rpsettings-sysfs-strings.txt)"
} >"$FW_REPORT/fan-control.txt"

# --- touch input config evidence ----------------------------------------
{
  echo "# Touch/input configuration shipped by the stock firmware"
  echo
  echo "## /system/usr/idc (stock system image)"
  fw_sh "debugfs -R $(printf %q "ls -l /system/usr/idc") $(printf %q "$FW_SUPER/system${FW_SLOT}.img") 2>/dev/null | sed '/^debugfs /d'" || true
  echo
  echo "## /vendor/usr/idc (stock vendor image)"
  fw_sh "debugfs -R $(printf %q "ls -l /usr/idc") $(printf %q "$FW_SUPER/vendor${FW_SLOT}.img") 2>&1 | sed '/^debugfs /d'" || true
  echo
  echo "## touch driver module in vendor_dlkm"
  fw_sh "debugfs -R $(printf %q "ls /lib/modules") $(printf %q "$FW_SUPER/vendor_dlkm${FW_SLOT}.img") 2>/dev/null | tr ' ' '\\n' | grep -iE 'fts|touch' || true"
  echo
  echo "## community A17 tree addition (not in the stock firmware)"
  echo "##   android_device_retroidpocket_RP5/lineage-24.0/device.mk"
  grep -n 'idc' "$FW_REPORT/community/device.mk" 2>/dev/null || true
  echo "##   configs/idc/fts_ts.idc"
  sed 's/^/    /' "$FW_REPORT/community/fts_ts.idc" 2>/dev/null || true
} >"$FW_REPORT/touch-idc.txt"

# --- boot / dtbo / vbmeta / gpt -----------------------------------------
cp "$FW_LOG/boot-header.txt" "$FW_LOG/init_boot-header.txt" "$FW_LOG/vendor_boot-header.txt" "$FW_REPORT/" 2>/dev/null || true
{
  cat "$FW_LOG/boot-header.txt" "$FW_LOG/init_boot-header.txt" "$FW_LOG/vendor_boot-header.txt" 2>/dev/null
} >"$FW_REPORT/boot-headers.txt"
cp "$FW_IMG/vendor_boot/bootconfig.txt" "$FW_REPORT/bootconfig.txt" 2>/dev/null || true
cp "$FW_LOG/dtbo-table.txt" "$FW_LOG/vbmeta.txt" "$FW_LOG/gpt-headers.txt" "$FW_REPORT/" 2>/dev/null || true

# --- apps (what a GSI loses) --------------------------------------------
cp "$FW_LOG/vendor-apps.txt" "$FW_REPORT/vendor-apps.txt" 2>/dev/null || true
cp "$FW_LOG/system-apps.txt" "$FW_REPORT/system-apps.txt" 2>/dev/null || true
cp "$FW_LOG/system_ext-apps.txt" "$FW_REPORT/system_ext-apps.txt" 2>/dev/null || true

# --- kernel --------------------------------------------------------------
fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/ikconfig.py") --version $(printf %q "$FW_IMG/boot/kernel")" >"$FW_REPORT/kernel-version.txt" 2>/dev/null || true
[[ -s $FW_REPORT/kernel-version.txt ]] || echo "(kernel version not found)" >"$FW_REPORT/kernel-version.txt"

if ! fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/ikconfig.py") $(printf %q "$FW_IMG/boot/kernel")" >"$FW_REPORT/kernel-config.txt" 2>/dev/null; then
  echo "# IKCONFIG not embedded in the stock boot Image." >"$FW_REPORT/kernel-config.txt"
fi

{
  echo "# .ko modules shipped in the vendor_boot ramdisk"
  find "$FW_RAMDISK/lib/modules" -type f -name '*.ko' 2>/dev/null | sed "s|$FW_RAMDISK/||" | sort || true
  echo
  echo "# vermagic (first module per line)"
  find "$FW_RAMDISK/lib/modules" -type f -name '*.ko' 2>/dev/null | sort | while IFS= read -r ko; do
    printf '%s: ' "$(basename "$ko")"
    grep -ao 'vermagic=[^ ]*' "$ko" | sed -n '1p' || true
  done
} >"$FW_REPORT/kernel-modules.txt"

# --- Android 17 compatibility matrices ----------------------------------
if [[ $DO_FCM == 1 ]]; then
  mkdir -p "$FW_REPORT/fcm"
  for l in 7 8 202404 202504 202604 202704; do
    curl -fsSL "https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android17-release/compatibility_matrices/compatibility_matrix.$l.xml?format=TEXT" |
      base64 -d >"$FW_REPORT/fcm/compatibility_matrix.$l.xml" || warn "matrix $l download failed"
  done
  manifests=()
  while IFS= read -r f; do manifests+=(--manifest "$f"); done < <(
    {
      [[ -f $FW_REPORT/vintf/manifest.xml ]] && printf '%s\n' "$FW_REPORT/vintf/manifest.xml"
      find "$FW_REPORT/vintf/manifest" -maxdepth 1 -name '*.xml' 2>/dev/null | sort
    }
  )
  if ((${#manifests[@]})); then
    fw_sh "python3 $(printf %q "$ASH_ROOT/scripts/firmware/fcm_report.py") --matrices $(printf %q "$FW_REPORT/fcm") $(printf '%q ' "${manifests[@]}") --out $(printf %q "$FW_REPORT/fcm-report.md")" >&2 || warn "fcm report failed"
  else
    warn "no vendor manifest found; skipping FCM report"
  fi
fi

# Keep generated text diff-clean (debugfs output has trailing blanks).
find "$FW_REPORT" -type f -name '*.txt' -exec sed -i 's/[[:space:]]\+$//' {} +

# --- index ---------------------------------------------------------------
{
  echo "# Stock RP5 firmware artefacts"
  echo
  echo "Generated by \`scripts/firmware/inspect.sh\` from the stock Android 13"
  echo "firmware (\`STOCK_FW_URL\`), slot suffix \`$FW_SLOT\`. No binary images."
  echo
  echo "| file | contents |"
  echo "|---|---|"
  for f in "$FW_REPORT"/*.txt "$FW_REPORT"/*.md; do
    [[ -f $f ]] || continue
    echo "| \`$(basename "$f")\` | $(sed -n '1p' "$f" | cut -c1-100) |"
  done
  echo "| \`vintf/\` | stock /vendor/etc/vintf manifest + fragments |"
  echo "| \`community/\` | A17 device-tree files used for comparison (fetch status in \`community/FETCH-STATUS.txt\`) |"
  if [[ $DO_COMMUNITY == 1 ]]; then
    echo
    echo "Community fetches: ${#Fetched[@]} ok, ${#Failed[@]} failed - see \`community/FETCH-STATUS.txt\`."
  fi
} >"$FW_REPORT/README.md"

ok "report ready: $FW_REPORT"
