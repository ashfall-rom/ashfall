#!/usr/bin/env bash
# Render the AshFall brand rasters used outside the device build: the GitHub
# organisation avatar and the per-repo social preview cards (1280x640).
#
# The SVG pair in this directory is the source of truth; the PNGs are committed
# so an upload never depends on a renderer being installed.
#
# Usage: assets/brand/render.sh [--check]
#   --check   render into a temp dir and diff against the committed files
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=SCRIPTDIR/../../scripts/lib/common.sh
. "$HERE/../../scripts/lib/common.sh"
load_config

BRAND=$HERE
SOCIAL=$BRAND/social
AVATAR=$BRAND/png
ICON=$BRAND/ashfall-icon.svg

SOOT='#0B0C0F'
ASH='#EDE9E4'
EMBER='#FF8A3D'
MUTED='#9AA0A8'

# repo|line 1|line 2
CARDS=(
  'ashfall|LineageOS 24.0 (Android 17) for the|Retroid Pocket 5. Source, contracts, docs.'
  'ashfall-install|One-command installer for the Retroid|Pocket 5. Checksum-pinned, no build tools.'
  'rp5-vendor_boot|The exact vendor_boot we flash: stock|provenance, DTB sources, pinned images.'
)

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; }

CHECK=0
case ${1:-} in
  -h|--help) usage; exit 0 ;;
  --check)   CHECK=1 ;;
  '')        ;;
  *)         die "unknown argument: $1 (try --help)" ;;
esac

need rsvg-convert
if command -v magick >/dev/null 2>&1; then IM=magick; else IM=convert; fi

# render <svg> <out> <width> <height>
render() { rsvg-convert -w "$3" -h "$4" "$1" -o "$2"; }

# card <out> <repo> <line 1> <line 2>
# The card carries the mark, not the tile: on a card already filled with Soot an
# embedded tile would show its edge, and the bloom is drawn once by the card.
card() {
  local out=$1 repo=$2 l1=$3 l2=$4 tmp
  tmp=$(mktemp -d)
  sed "s/currentColor/$ASH/g" "$BRAND/ashfall-mark.svg" > "$tmp/mark.svg"
  cat > "$tmp/card.svg" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="640" viewBox="0 0 1280 640">
  <defs>
    <radialGradient id="bloom" cx="266" cy="330" r="210" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="$EMBER" stop-opacity="0.16"/>
      <stop offset="0.45" stop-color="$EMBER" stop-opacity="0.05"/>
      <stop offset="0.8" stop-color="$EMBER" stop-opacity="0"/>
      <stop offset="1" stop-color="$EMBER" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="1280" height="640" fill="$SOOT"/>
  <rect x="96" y="150" width="340" height="340" rx="170" fill="url(#bloom)"/>
  <text x="520" y="248" font-family="DejaVu Sans" font-weight="bold" font-size="84"
        fill="$ASH">AshFall</text>
  <rect x="520" y="284" width="72" height="5" rx="2.5" fill="$EMBER"/>
  <text x="520" y="360" font-family="DejaVu Sans Mono" font-size="34"
        fill="$EMBER">$repo</text>
  <text x="520" y="428" font-family="DejaVu Sans" font-size="30"
        fill="$MUTED">$l1</text>
  <text x="520" y="472" font-family="DejaVu Sans" font-size="30"
        fill="$MUTED">$l2</text>
</svg>
SVG
  render "$tmp/card.svg" "$tmp/base.png" 1280 640
  # 520 px puts the mark's ink at ~300 px tall; +6+56 centres that ink on the
  # bloom at (266, 320).
  render "$tmp/mark.svg" "$tmp/mark.png" 520 520
  $IM "$tmp/base.png" "$tmp/mark.png" -geometry +6+56 -compose over \
    -composite -strip "$out"
  rm -rf "$tmp"
}

# avatar <out> <size>
avatar() {
  local tmp; tmp=$(mktemp -d)
  render "$ICON" "$tmp/icon.png" "$2" "$2"
  $IM "$tmp/icon.png" -background "$SOOT" -alpha remove -alpha off -strip "$1"
  rm -rf "$tmp"
}

DST=$BRAND
if (( CHECK )); then
  DST=$(mktemp -d)
  mkdir -p "$DST/png" "$DST/social"
fi

mkdir -p "$DST/png" "$DST/social"
log "org avatar (GitHub accepts 512; 1024 kept for print/other surfaces)"
avatar "$DST/png/ashfall-avatar-512.png" 512
avatar "$DST/png/ashfall-avatar-1024.png" 1024

for spec in "${CARDS[@]}"; do
  IFS='|' read -r repo l1 l2 <<< "$spec"
  log "social preview: $repo"
  card "$DST/social/$repo.png" "$repo" "$l1" "$l2"
done

if (( CHECK )); then
  status=0
  while IFS= read -r f; do
    if ! cmp -s "$f" "$BRAND/${f#"$DST"/}"; then
      warn "differs: ${f#"$DST"/}"
      status=1
    fi
  done < <(find "$DST" -name '*.png' -type f | sort)
  rm -rf "$DST"
  exit "$status"
fi

ok "wrote $AVATAR and $SOCIAL"
