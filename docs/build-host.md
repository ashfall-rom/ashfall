# Build host

The build host is the user's desktop PC, reached over SSH. It is a **Fedora
Silverblue 45** immutable system: nothing is installed on the host itself.
Every Android tool lives inside a distrobox container, so `rpm-ostree` stays
clean and the box can be thrown away and recreated at any time.

| | |
|---|---|
| Host | `epickitrolaz@100.66.37.14` (Tailscale) |
| OS | Fedora Silverblue 45, kernel 7.2.x |
| CPU / RAM | Ryzen 7 7800X3D, 16 threads / 62 GiB |
| Disk | ~1.9 TB on `/var/home` (~880 GiB free after the sync) |
| Box | `ashfall-build`, image `docker.io/library/ubuntu:24.04` |
| Tree | `/var/home/epickitrolaz/ashfall/src/lineage` (`$REMOTE_SRC`) |

The box is shared between workers: `ashfall-build` (this issue, env + sync),
`ashfall-fw` (firmware, issue #2) and `ashfall-dev` (adb/fastboot, issue #3)
are separate containers. Only `ashfall-build` owns the LineageOS tree; anything
that mutates it must hold `$REMOTE_TREE_LOCK`.

## Remote layout

`$HOME` is shared between the host and the box, so all state is plain files
under `/var/home/epickitrolaz/ashfall` (= `$REMOTE_ROOT`):

| Path | Purpose |
|---|---|
| `src/lineage` | the one LineageOS 24.0 tree (`$REMOTE_SRC`) |
| `ccache` | ccache dir, configured to `$CCACHE_SIZE` (80G) + compression |
| `jobs/<name>` | job state: `cmd.sh`, `log` symlink, `status`, `exit`, `pid` |
| `logs/sync-<UTC>.log` | detached sync logs |
| `bin/job-runner.sh` | tiny runner uploaded by `scripts/remote/job.sh` |
| `manifests` | mirror of this repo's `manifests/` |
| `ws/<branch>` | rsynced copies of this repo (used by `ws_push`) |
| `artifacts`, `firmware` | build outputs / stock firmware (other issues) |
| `.tree.lock` | `flock` file guarding `$REMOTE_SRC` |

## Bootstrap (`scripts/host/bootstrap.sh`)

Idempotent. Run from the orchestrator/repo machine; it only acts over SSH.

```bash
scripts/host/bootstrap.sh --check   # report only; creates nothing
scripts/host/bootstrap.sh           # create box, install deps, configure ccache/repo
```

`--check` makes no permanent change. If the box is missing it stops after
reporting what it would do; if the box exists it uploads a temporary probe
script, runs it, and that is the only write.

What it does:

1. Creates `ashfall-build` from Ubuntu 24.04 **only if missing**
   (`distrobox create --yes ... --additional-flags "--hostname ashfall-build"`).
2. Installs the LineageOS "Build for X" Ubuntu package list, adapted to 24.04:
   `bc bison build-essential ccache curl flex g++-multilib gcc-multilib git
   git-lfs gnupg gperf imagemagick lib32readline-dev lib32z1-dev libelf-dev
   liblz4-tool lz4 libsdl1.2-dev libssl-dev libxml2 libxml2-utils lzop
   pngcrush rsync schedtool squashfs-tools xsltproc zip zlib1g-dev python3
   python-is-python3 tmux`.
   Only packages that are actually missing are installed (`dpkg-query` probe),
   so re-running is a no-op.
3. Installs Google's `repo` launcher to `/usr/local/bin/repo` inside the box.
4. Sets a git identity **only if missing**. The box shares `$HOME` with the
   host, so when it does set one it lands in the host's `~/.gitconfig`; an
   existing identity is never overwritten.
5. Creates `$REMOTE_CCACHE` with `max_size=$CCACHE_SIZE` and
   `compression=true`.
6. Creates the remote layout directories and sanity-checks `gcc -m32` and
   `python3`.

## Sync (`scripts/sync.sh`)

`repo init` + `repo sync` of LineageOS 24.0, **detached in tmux** inside the
box, under `$REMOTE_TREE_LOCK`, logging to `$REMOTE_LOGS/sync-<UTC>.log`.

```bash
scripts/sync.sh                # start (or resume) the sync, returns immediately
scripts/sync.sh --status       # job state + GB/projects/ETA numbers
scripts/sync.sh --log          # tail -f the log
scripts/sync.sh --wait         # block until the job ends (safe to Ctrl-C)
scripts/sync.sh --jobs 16      # parallelism (default: nproc on the host)
scripts/sync.sh --retries 5    # git fetch retries on transient errors
scripts/sync.sh --force        # restart even if the job says running
scripts/sync.sh --init-only    # only repo init, no sync
```

Details:

- `repo init -u <manifest> -b lineage-24.0 --git-lfs --depth=1`.
  `--depth=1` was chosen over `--partial-clone --clone-filter=blob:limit=10M`
  because it is simpler, proven for Lineage builds, and gives the same win
  (no history); this is a throwaway build tree, no history is ever needed.
  Switching modes requires deleting `.repo` and re-running `--init-only`.
- `repo sync -c -j<n> --retry-fetches=<n> --no-tags --no-clone-bundle
  --optimized-fetch --prune`. googlesource throttles bursts of fetches with
  HTTP 429; `--retry-fetches` retries inline and a re-run fetches the repos
  that still failed. On a heavily throttled link, lower `--jobs`.
- The job script starts with `exec 9>$REMOTE_TREE_LOCK; flock 9`, so the lock is
  held for the whole sync. Builds/patches that use `box_sh_locked` simply block
  until it finishes. `doctor.sh` probes the lock **non-blockingly**.
- Re-running is resumable: an interrupted sync continues where it stopped.
  `scripts/sync.sh` refuses to start a second job while one is running
  (`--force` overrides) and `--wait` still works on a running job.
- `manifests/*.xml` from this repo is rsynced to `$REMOTE_ROOT/manifests` and
  copied into `$REMOTE_SRC/.repo/local_manifests/` before every sync.
- The job name is `sync`; `scripts/remote/job.sh status sync` reports it.

## Remote jobs (`scripts/remote/job.sh`)

Every long remote command runs detached in tmux inside the box, never in an
SSH session:

```bash
scripts/remote/job.sh start build 'cd ~/ashfall/src/lineage && lunch ...'
scripts/remote/job.sh status build
scripts/remote/job.sh log build -n 100     # or -f to follow
scripts/remote/job.sh wait build --timeout 3600   # exit 2 on timeout
scripts/remote/job.sh kill build
scripts/remote/job.sh list
```

State under `$REMOTE_ROOT/jobs/<name>/`:

| File | Meaning |
|---|---|
| `cmd.sh` | uploaded command (`bash`, `set -euo pipefail` prepended) |
| `status` | `running` -> `done` / `failed` / `killed` |
| `exit` | exit code of the command |
| `pid`, `started_at`, `finished_at` | bookkeeping |
| `log` | symlink (default: the job's own log; sync: `$REMOTE_LOGS/sync-*.log`) |

Exit codes are captured, so `job.sh wait` can gate a build pipeline. Jobs
survive SSH disconnects, but **not** a container restart (`podman` stop/reboot
kills the tmux server): re-run the job; sync resumes, builds restart.

## Doctor (`scripts/host/doctor.sh`)

Read-only health check the orchestrator can run any time:

```bash
scripts/host/doctor.sh
```

Checks SSH reachability, CPU/RAM/disk, distrobox/podman, box reachability,
all apt deps + tools (`repo`, `tmux`, `flock`, `ccache`, `git-lfs`, `rsync`,
`schedtool`, `lzop`, `pngcrush`, `python3`), git identity, ccache config,
tree presence/size/project count, manifest branch, tree-lock state and the
`sync` job status. Exit code 1 means a real failure; warnings do not fail.

## Observed timings

Measured on 2026-11-12 (Tailscale, host on a ~1 Gbit/s connection):

| Time (UTC) | Tree size | Projects | Note |
|---|---|---|---|
| 21:35:07 | 22 MB | 12 | `repo init` done, `repo sync` started |
| 21:41:43 | 45 GB | 991 | |
| 21:43:54 | 59 GB | 1040 | |
| 21:46:58 | 68 GB | 1077 | |
| 21:50:06 | 89 GB | 1239 | all projects fetched; checkout still running |
| 22:00:02 | 131 GB | 1239 | first pass ended rc=1: HTTP 429 on 30 repos |
| 22:0x | 131 GB | 1239 | resumed with `--retries`; see PR for final numbers |

Rough numbers: 3-8 GB/min while the network is the bottleneck. A cold sync of a
depth-1 LineageOS 24.0 tree lands around 130 GB on disk including `.repo/`
(the checkout itself is ~40 GB). The full build needs another ~150 GB for
`out/` plus ccache (capped at 80 GB).

Disk budget on `/var/home` (~1.9 TB): tree ~80 GB + `out/` ~150 GB + ccache
80 GB + artifacts -> comfortable; keep at least 200 GB free before a build.
