#!/usr/bin/env bash
# AshFall remote job runner.
#
# Long jobs (sync, build) must run detached in tmux inside the build box, with
# state kept on the build host. This script is the local (orchestrator-side)
# front-end: it uploads a tiny runner + the job command and drives tmux.
#
#   scripts/remote/job.sh start <name> <cmd> [--force]
#   scripts/remote/job.sh status [name]
#   scripts/remote/job.sh log <name> [-f|-n N]
#   scripts/remote/job.sh wait <name> [--timeout SECONDS]
#   scripts/remote/job.sh kill <name>
#   scripts/remote/job.sh list
#
# State lives in $REMOTE_ROOT/jobs/<name>/ inside the box:
#   cmd.sh  log  status (running|done|failed|killed)  exit  pid
#   started_at  finished_at
# Exit code of the command is captured in `exit`.
#
# Optional env: ASH_JOB_LOG  absolute path (on the host) for the job log.
# The default is $REMOTE_ROOT/jobs/<name>/log; sync.sh points it at
# $REMOTE_LOGS/sync-<timestamp>.log.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../lib/common.sh
. "$HERE/../lib/common.sh"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
load_config

JOB_PREFIX="ashfall-"
RUNNER_REMOTE="$REMOTE_ROOT/bin/job-runner.sh"

usage() {
  awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"
}

upload_runner() {
  local tmp
  tmp=$(mktemp)
  cat >"$tmp" <<'RUNNER'
#!/usr/bin/env bash
# AshFall remote job runner - uploaded by scripts/remote/job.sh. Do not edit on
# the host; edit scripts/remote/job.sh and re-run any job.sh command.
set -uo pipefail
jobs=${1:?jobs dir}
name=${2:?job name}
logfile=${3:-}
d="$jobs/$name"
mkdir -p "$d"
printf '%s\n' "$$" >"$d/pid"
printf 'running\n' >"$d/status"
date -u +%Y-%m-%dT%H:%M:%SZ >"$d/started_at"
rm -f "$d/exit" "$d/finished_at"
if [ -z "$logfile" ]; then
  logfile="$d/log"
else
  mkdir -p "$(dirname "$logfile")"
  ln -sfn "$logfile" "$d/log"
fi
{
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) job $name starting (pid $$) ==="
  echo "=== cmd: $d/cmd.sh"
} >>"$logfile"
bash "$d/cmd.sh" >>"$logfile" 2>&1
rc=$?
echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) job $name finished rc=$rc ===" >>"$logfile"
printf '%s\n' "$rc" >"$d/exit"
date -u +%Y-%m-%dT%H:%M:%SZ >"$d/finished_at"
if [ "$rc" -eq 0 ]; then printf 'done\n' >"$d/status"; else printf 'failed\n' >"$d/status"; fi
RUNNER
  ash_upload_file "$tmp" "$RUNNER_REMOTE"
  host_sh "chmod 0755 $(printf %q "$RUNNER_REMOTE")"
  rm -f "$tmp"
}

job_status() {
  local name=$1
  box_sh "d=$(printf %q "$REMOTE_ROOT/jobs/$name"); if [ ! -d \"\$d\" ]; then echo 'unknown'; else cat \"\$d/status\" 2>/dev/null || echo unknown; fi" |
    tail -1
}

is_running() { [[ $(job_status "$1") == running ]]; }

require_job() {
  local name=$1
  [[ $(job_status "$name") != unknown ]] || die "no such job: $name"
}

cmd_start() {
  local name=${1:?job name} cmd=${2:?command} force=0
  [[ ${3:-} == --force ]] && force=1
  [[ $name =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid job name: $name (A-Za-z0-9._-)"
  if is_running "$name" && [[ $force -eq 0 ]]; then
    if box_sh "tmux has-session -t $(printf %q "${JOB_PREFIX}${name}") 2>/dev/null" >/dev/null 2>&1; then
      die "job '$name' is already running (use --force to restart)"
    fi
    warn "job '$name' says running but its tmux session is gone (container restarted?) - restarting"
  fi
  upload_runner
  local cmd_file logfile
  cmd_file=$(mktemp)
  printf '#!/usr/bin/env bash\nset -euo pipefail\n%s\n' "$cmd" >"$cmd_file"
  ash_upload_file "$cmd_file" "$REMOTE_ROOT/jobs/$name/cmd.sh"
  rm -f "$cmd_file"
  logfile=${ASH_JOB_LOG:-}
  box_sh "set -e; tmux kill-session -t $(printf %q "${JOB_PREFIX}${name}") 2>/dev/null || true; tmux new-session -d -s $(printf %q "${JOB_PREFIX}${name}") -- bash $(printf %q "$RUNNER_REMOTE") $(printf %q "$REMOTE_ROOT/jobs") $(printf %q "$name") $(printf %q "$logfile")" >/dev/null
  # tmux returns immediately; wait briefly so `status` is meaningful right away.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [[ $(job_status "$name") == running ]] && break
    sleep 1
  done
  local st
  st=$(job_status "$name")
  if [[ $st != 'running' ]] && [[ $st != 'done' ]]; then
    warn "job '$name' $st right after start - log tail:"
    cmd_log "$name" -n 20 >&2 || true
    die "job '$name' already $st - see log above"
  fi
  ok "job '$name' started: $st"
  printf 'log: %s\n' "${logfile:-$REMOTE_ROOT/jobs/$name/log}"
}

cmd_status() {
  local name=${1:-}
  if [[ -z $name ]]; then
    cmd_list
    return
  fi
  require_job "$name"
  local raw
  raw=$(box_sh "d=$(printf %q "$REMOTE_ROOT/jobs/$name"); printf 'ASHJOB:status='; cat \"\$d/status\" 2>/dev/null || echo unknown; printf 'ASHJOB:pid='; cat \"\$d/pid\" 2>/dev/null || echo -; printf 'ASHJOB:started='; cat \"\$d/started_at\" 2>/dev/null || echo -; printf 'ASHJOB:finished='; cat \"\$d/finished_at\" 2>/dev/null || echo -; printf 'ASHJOB:exit='; cat \"\$d/exit\" 2>/dev/null || echo -; l=\$(readlink -f \"\$d/log\" 2>/dev/null || echo \"\$d/log\"); printf 'ASHJOB:log=%s\n' \"\$l\"; printf 'ASHJOB:size='; du -h \"\$l\" 2>/dev/null | cut -f1; printf 'ASHJOB:recent=\n'; tail -n 5 \"\$l\" 2>/dev/null | sed 's/^/  | /'")
  printf '%s\n' "$raw" |
    awk '/^ASHJOB:/{sub(/^ASHJOB:/,""); key=substr($0,1,index($0,"=")-1); val=substr($0,index($0,"=")+1); if(key=="recent"){print "recent:"; p=1} else printf "%-9s %s\n", key":", val; next} p&&/^  \| /{print}' |
    sed "1i job:      $name"
  if [[ $(job_status "$name") == 'running' ]] &&
    ! box_sh "tmux has-session -t $(printf %q "${JOB_PREFIX}${name}") 2>/dev/null" >/dev/null 2>&1; then
    warn "status says running but tmux session '${JOB_PREFIX}${name}' is gone (container restarted?) - re-run start --force"
  fi
}

cmd_list() {
  box_sh "for d in $(printf %q "$REMOTE_ROOT/jobs")/*/; do [ -d \"\$d\" ] || continue; n=\$(basename \"\$d\"); printf '%-20s %-8s exit=%s\n' \"\$n\" \"\$(cat \"\$d/status\" 2>/dev/null || echo unknown)\" \"\$(cat \"\$d/exit\" 2>/dev/null || echo -)\"; done"
}

cmd_log() {
  local name=${1:?job name} mode=${2:-n} n=40
  require_job "$name"
  if [[ $mode == -f ]]; then
    box_sh "exec tail -n 40 -f $(printf %q "$REMOTE_ROOT/jobs/$name/log")"
  else
    [[ $mode == -n ]] && n=${3:-40}
    box_sh "tail -n $(printf %q "$n") $(printf %q "$REMOTE_ROOT/jobs/$name/log")"
  fi
}

cmd_wait() {
  local name=${1:?job name} timeout=${2:-0}
  require_job "$name"
  log "waiting for job '$name' (status: $(job_status "$name")) - Ctrl-C stops waiting, the job keeps running"
  if [[ $timeout -gt 0 ]]; then
    box_sh "end=\$((SECONDS+$(printf %q "$timeout"))); while [ -d $(printf %q "$REMOTE_ROOT/jobs/$name") ] && [ ! -f $(printf %q "$REMOTE_ROOT/jobs/$name/exit") ] && [ \$SECONDS -lt \$end ]; do sleep 3; done; exit 0" >/dev/null
  else
    box_sh "while [ ! -f $(printf %q "$REMOTE_ROOT/jobs/$name/exit") ]; do sleep 3; done"
  fi
  local st rc
  st=$(job_status "$name")
  rc=$(box_sh "cat $(printf %q "$REMOTE_ROOT/jobs/$name/exit") 2>/dev/null || echo -" | tail -1)
  printf 'job:    %s\nstatus: %s\nexit:   %s\n' "$name" "$st" "$rc"
  [[ $st == 'done' ]] && [[ $rc == 0 ]]
}

cmd_kill() {
  local name=${1:?job name}
  require_job "$name"
  box_sh "tmux kill-session -t $(printf %q "${JOB_PREFIX}${name}") 2>/dev/null || true; d=$(printf %q "$REMOTE_ROOT/jobs/$name"); if [ ! -f \"\$d/exit\" ]; then printf '143\n' >\"\$d/exit\"; printf 'killed\n' >\"\$d/status\"; date -u +%Y-%m-%dT%H:%M:%SZ >\"\$d/finished_at\"; fi" >/dev/null
  ok "job '$name' killed"
}

case ${1:-} in
  start) shift; cmd_start "$@" ;;
  status) shift; cmd_status "${1:-}" ;;
  log) shift; cmd_log "$@" ;;
  wait) shift; cmd_wait "$@" ;;
  kill) shift; cmd_kill "$@" ;;
  list) cmd_list ;;
  -h | --help | help | '') usage ;;
  *) die "unknown subcommand: $1 (try --help)" ;;
esac
