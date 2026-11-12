#!/usr/bin/env bash
# AshFall build-host bootstrap: distrobox box + AOSP/LineageOS build deps.
#
#   scripts/host/bootstrap.sh            # create/update box 'ashfall-build'
#   scripts/host/bootstrap.sh --check    # report what would change, change nothing
#
# Idempotent: the box is only created when missing; apt only installs packages
# that are not installed; repo/ccache/git are configured only when needed.
# The Fedora Silverblue host is never modified (nothing is installed outside
# the container; only files under $REMOTE_ROOT and /usr/local/bin inside the box).
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../lib/common.sh
. "$HERE/../lib/common.sh"
# shellcheck source=../remote/lib.sh
. "$HERE/../remote/lib.sh"
load_config

MODE=apply
case ${1:-} in
  --check | --dry-run) MODE=check ;;
  -h | --help)
    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"
    exit 0
    ;;
  '') ;;
  *) die "unknown argument: $1 (try --help)" ;;
esac

need ssh base64
log "build host: $BUILD_HOST  box: $BUILD_BOX  image: $BUILD_BOX_IMAGE  mode: $MODE"

host_sh 'command -v distrobox >/dev/null && command -v podman >/dev/null' >/dev/null ||
  die "distrobox/podman missing on $BUILD_HOST (Silverblue should ship them)"
host_sh "mkdir -p $(printf %q "$REMOTE_ROOT")/{src,ccache,ws,artifacts,logs,firmware}" >/dev/null
ok "remote layout under $REMOTE_ROOT ready"

if host_sh "distrobox list 2>/dev/null | cut -d'|' -f2 | tr -d ' ' | grep -qx $(printf %q "$BUILD_BOX")"; then
  ok "box '$BUILD_BOX' exists"
else
  if [[ $MODE == check ]]; then
    warn "box '$BUILD_BOX' missing (would: distrobox create --yes --name $BUILD_BOX --image $BUILD_BOX_IMAGE)"
  else
    log "creating box '$BUILD_BOX' from $BUILD_BOX_IMAGE"
    host_sh "distrobox create --yes --name $(printf %q "$BUILD_BOX") --image $(printf %q "$BUILD_BOX_IMAGE") --additional-flags $(printf %q "--hostname $BUILD_BOX")" >&2
    ok "box '$BUILD_BOX' created"
  fi
fi

# The in-box setup script. Kept inline so bootstrap.sh is a single file that
# works from any checkout; uploaded to $REMOTE_ROOT/tmp and executed in the box.
BOX_SCRIPT=$(
  cat <<'REMOTE'
#!/usr/bin/env bash
set -euo pipefail
mode=${1:?mode} ccache_dir=${2:?ccache dir} ccache_size=${3:?ccache size}
export DEBIAN_FRONTEND=noninteractive

pkgs="bc bison build-essential ccache curl flex g++-multilib gcc-multilib git
git-lfs gnupg gperf imagemagick lib32readline-dev lib32z1-dev libelf-dev
liblz4-tool lz4 libsdl1.2-dev libssl-dev libxml2 libxml2-utils lzop pngcrush
rsync schedtool squashfs-tools xsltproc zip zlib1g-dev python3
python-is-python3 tmux"
pkgs=$(printf '%s\n' $pkgs | sort -u | tr '\n' ' ')

missing=()
for p in $pkgs; do
  dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'install ok installed' || missing+=("$p")
done
if ((${#missing[@]})); then
  echo "==> missing apt packages: ${missing[*]}"
  if [[ $mode == check ]]; then
    echo "==> would run: apt-get update && apt-get install -y ${missing[*]}"
  else
    sudo -n apt-get update -qq
    # shellcheck disable=SC2086
    sudo -n apt-get install -y -qq ${missing[*]}
    echo "==> apt packages installed"
  fi
else
  echo "==> all apt packages present"
fi

# AOSP build tools not packaged by apt: Google's repo launcher.
if command -v repo >/dev/null; then
  echo "==> repo present: $(repo --version 2>/dev/null | awk 'NR==2{print $NF; exit}')"
elif [[ $mode == check ]]; then
  echo "==> repo missing (would install to /usr/local/bin/repo)"
else
  tmp=$(mktemp)
  curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o "$tmp"
  sudo -n install -m 0755 "$tmp" /usr/local/bin/repo
  rm -f "$tmp"
  echo "==> repo installed"
fi

# git identity (the box shares $HOME with the host, so this is host-global;
# set only when missing to avoid touching the user's existing identity).
if ! git config --global user.name >/dev/null 2>&1; then
  if [[ $mode == check ]]; then
    echo "==> git user.name missing (would set AshFall Builder)"
  else
    git config --global user.name "AshFall Builder"
    echo "==> git user.name set to AshFall Builder"
  fi
else
  echo "==> git identity: $(git config --global user.name) <$(git config --global user.email 2>/dev/null || echo -)>"
fi
if ! git config --global user.email >/dev/null 2>&1; then
  [[ $mode == check ]] || git config --global user.email "build@ashfall.local"
fi

# ccache: size + compression, config stored in $ccache_dir/ccache.conf.
mkdir -p "$ccache_dir"
if [[ $mode != check ]]; then
  CCACHE_DIR=$ccache_dir ccache --set-config=max_size="$ccache_size" >/dev/null
  CCACHE_DIR=$ccache_dir ccache --set-config=compression=true >/dev/null
fi
echo "==> ccache: $ccache_dir"
CCACHE_DIR=$ccache_dir ccache -s | sed 's/^/    /'

# Build-host sanity: 64-bit multilib compiler + python.
gcc -m32 -x c -c /dev/null -o /dev/null && echo "==> gcc -m32 ok"
python3 -c 'import sys; print("==> python", sys.version.split()[0])'
REMOTE
)

REMOTE_TMP="$REMOTE_ROOT/tmp/bootstrap-box.sh"
ash_upload_text "$BOX_SCRIPT" "$REMOTE_TMP"
log "running in-box setup (apt/ccache/repo)"
if [[ $MODE == check ]]; then
  box_sh "bash $(printf %q "$REMOTE_TMP") check $(printf %q "$REMOTE_CCACHE") $(printf %q "$CCACHE_SIZE")" >&2
else
  box_sh "bash $(printf %q "$REMOTE_TMP") apply $(printf %q "$REMOTE_CCACHE") $(printf %q "$CCACHE_SIZE")" >&2
fi
ok "bootstrap complete: box '$BUILD_BOX' ready"
printf 'next: scripts/host/doctor.sh   then   scripts/sync.sh\n'
