# shellcheck shell=bash
# AshFall remote helpers (shared by scripts/remote/job.sh, scripts/host/*).
# Requires scripts/lib/common.sh to be sourced first (host_sh, die, ...).
#
# The build host shares $HOME with the box, so plain host-side writes are
# visible inside the container; only `distrobox enter` is needed to run tools.

[[ -n ${ASH_REMOTE_LIB:-} ]] && return 0
ASH_REMOTE_LIB=1

# ash_upload_file <local-file> <remote-absolute-path>
# Atomic: decodes next to the destination and renames, so a running reader never
# sees a truncated file (bash reads scripts incrementally).
ash_upload_file() {
  local src=${1:?local file} dst=${2:?remote path} b64
  [[ -f $src ]] || die "ash_upload_file: no such local file: $src"
  b64=$(base64 -w0 "$src")
  host_sh "mkdir -p $(printf %q "$(dirname "$dst")") && printf %s $(printf %q "$b64") | base64 -d > $(printf %q "$dst.tmp.$$") && chmod 0755 $(printf %q "$dst.tmp.$$") && mv -f $(printf %q "$dst.tmp.$$") $(printf %q "$dst")"
}

# ash_upload_text <text> <remote-absolute-path>
ash_upload_text() {
  local tmp
  tmp=$(mktemp)
  printf '%s' "$1" >"$tmp"
  ash_upload_file "$tmp" "$2"
  rm -f "$tmp"
}

# ash_box_has <cmd>... -> true when every command exists inside the box
ash_box_has() {
  local cmd
  for cmd in "$@"; do
    box_sh "command -v $(printf %q "$cmd") >/dev/null" >/dev/null 2>&1 || return 1
  done
}
