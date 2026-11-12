# Building AshFall

This document describes how `scripts/build.sh` and `scripts/patches.sh` turn
this repository plus the shared LineageOS 24.0 (Android 17) tree on the build
host into `system.img`, and how to extend the `ashfall_gsi_arm64` product.

- Build host, container and tree: phase 1, issue #1 (`scripts/sync.sh`,
  `scripts/host/`, `scripts/remote/`).
- Product and pipeline: issue #4 (this document).
- `patches/**`: issue #5 (TrebleDroid ports and RP5 fixes).
- Flashing and device logs: issue #3.

```
orchestrator VM (this repo)                 build host (epickitrolaz@100.66.37.14)
┌──────────────────────────┐   rsync        ┌──────────────────────────────────────┐
│ scripts/build.sh         │ ─────────────► │ $REMOTE_WS/<branch>/                 │
│  ws_push                 │                │   ├── aosp/          (overlay)       │
│  detach job              │                │   ├── patches/                       │
└──────────────────────────┘                │   └── scripts/patches.sh             │
                                            │                                      │
                                            │ distrobox: ashfall-build             │
                                            │  tmux ashfall-<build-id>             │
                                            │   run.sh (generated)                 │
                                            │    flock $REMOTE_TREE_LOCK           │
                                            │    rsync aosp/ -> $REMOTE_SRC        │
                                            │    patches.sh apply                  │
                                            │    lunch + m systemimage             │
                                            │    -> $REMOTE_ARTIFACTS/<build-id>/  │
                                            └──────────────────────────────────────┘
```

## Quick start

```bash
# start a build (returns immediately; everything runs detached on the host)
scripts/build.sh

# follow it and pull the artifacts into out/<build-id>/
scripts/build.sh --fetch <build-id> --wait

# full rebuild (wipes <tree>/out), custom parallelism
scripts/build.sh --clean --jobs 16

# see exactly what would happen, without touching the host
scripts/build.sh --dry-run
```

A build id looks like `20261112-214335-ashfall_gsi_arm64-userdebug-4508355f5a99`
(UTC start, product, variant, AshFall commit; `-dirty` when the checkout had
uncommitted changes).

## What `scripts/build.sh` does

1. **Pushes this checkout** to `$REMOTE_WS/<branch>` (`ws_push`; `.git` and
   `out/` are excluded).
2. **Renders the job script** to `out/.tmp/<build-id>/run.sh` locally, rsyncs it
   to `$REMOTE_LOGS/<build-id>/run.sh`.
3. **Preflight**: SSH, the synced tree, `vendor/lineage/.../lineage_gsi_arm64.mk`,
   the `ashfall-build` distrobox container and `repo` inside it. Missing pieces
   fail here with the issue that owns them.
4. **Starts the job detached** in a tmux session `ashfall-<build-id>` inside the
   container, logging to `$REMOTE_LOGS/<build-id>/build.log` and writing the exit
   code to `$REMOTE_LOGS/<build-id>/status`. (See
   [Detached jobs](#detached-jobs) for why `scripts/remote/job.sh` is not used.)
5. The job, on the host, holds `$REMOTE_TREE_LOCK` for its whole lifetime and:
   1. rsyncs `aosp/` into `$REMOTE_SRC`; **only our own directories**
      (`aosp/vendor/ashfall` today) are mirrored with `--delete`, never the tree
      root;
   2. runs `scripts/patches.sh apply --tree $REMOTE_SRC`, which resets every
      patched project to its manifest revision before applying;
   3. `source build/envsetup.sh` and lunches
      `<product>-<release>-<variant>` (auto-detected, see below);
   4. asserts `BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE == ext4`;
   5. enables ccache (`$REMOTE_CCACHE`, `CCACHE_SIZE`) and runs
      `m -j<jobs> systemimage`, then tries `m vbmetaimage` (optional, absent for
      a GSI);
   6. collects artifacts into `$REMOTE_ARTIFACTS/<build-id>/` and updates the
      `latest` symlink.

The tree is never modified without the lock. Because the lock is taken *inside*
the detached job, no lock is held while `build.sh` itself runs: a second build
(another worker) simply queues.

### Detached jobs

`scripts/remote/job.sh` (issue #1) is not part of this base, so `build.sh`
carries its own launcher: tmux inside the container, a log file, and a status
file. The important detail is that **the job script acquires the tree lock
itself**. Do not wrap `run.sh` in another runner that already holds
`$REMOTE_TREE_LOCK`: a nested `flock` on the same file from a different process
would block forever. When #1's runner merges, it needs a "tree lock already
held by the caller" mode, or this launcher stays.

### Release config and lunch

Android 17 `lunch` takes `<product>-<release>-<variant>`; the release selects
which build flag values apply. On LineageOS 24.0:

- `vendor/lineage/vars/aosp_target_release` contains
  `aosp_target_release=cp2a` - the release LineageOS itself builds with.
- Release configs are defined in `build/release/release_config_map.textproto`
  (AOSP, pinned by the manifest) and `vendor/lineage/release/`
  (`release_configs/cp2a.textproto`, `release_config_map.textproto`).
- `aosp_target_release` defaults to `trunk_staging`, which is *not* what
  LineageOS uses; passing the release explicitly matters.

`build.sh` detects the release in this order and logs which one it used
(`build-info.txt` records `release=`):

1. `LUNCH_RELEASE` from `config/ashfall.conf` (or `config/ashfall.local.conf`);
2. `vendor/lineage/vars/aosp_target_release`;
3. the newest name in `vendor/lineage/release/release_configs/*.textproto`,
   else in `build/release/release_configs/*.textproto`;
4. last resort: two-part `lunch <product>-<variant>` and read `TARGET_RELEASE`
   back (`build.sh` logs a warning in this case).

Each attempt is verified with `get_build_var TARGET_PRODUCT` and
`get_build_var TARGET_RELEASE`; a lunch that silently selects another product or
release is rejected.

### Why the system image must be ext4

The Retroid Pocket 5 ships a 4.19 kernel, which cannot mount EROFS. On
lineage-24.0, `build/make/target/board/BoardConfigGsiCommon.mk` does:

```make
GSI_FILE_SYSTEM_TYPE ?= ext4
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := $(GSI_FILE_SYSTEM_TYPE)
```

`lineage_generic_target.mk` also sets `USE_SOONG_DEFINED_SYSTEM_IMAGE := false`,
so the make-based image path (which uses `BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE`),
not the Soong `android_gsi` image path, produces `system.img`.

`aosp/vendor/ashfall/products/ashfall_gsi_arm64.mk` pins
`GSI_FILE_SYSTEM_TYPE := ext4` (product config is parsed before board config in
`core/envsetup.mk`, so the pin is effective) and `build.sh` fails the build if
the resolved value is anything else. After the build it additionally checks
`file system.img` and refuses to collect an image that is not an ext filesystem.

## The product: `aosp/vendor/ashfall`

Mirrored to `$REMOTE_SRC/vendor/ashfall` before every build (and tracked in this
repo so it is reviewable).

| File | Purpose |
|---|---|
| `AndroidProducts.mk` | registers `products/ashfall_gsi_arm64.mk` and lunch choices |
| `ashfall_common.mk` | props/packages/overlays shared with the future device build |
| `products/ashfall_gsi_arm64.mk` | the GSI: inherits `lineage_gsi_arm64`, ext4, branding |
| `overlay/` | static overlay placeholder (phase 4) |
| `sepolicy/` | device sepolicy placeholder (phase 4) |

The GSI keeps `PRODUCT_DEVICE := generic_arm64` (inherited). Android resolves
the board config through `TARGET_DEVICE`, and the GSI board config is
`build/make/target/board/generic_arm64/BoardConfig.mk`.

Set for this product: `PRODUCT_NAME := ashfall_gsi_arm64`,
`PRODUCT_BRAND`/`PRODUCT_MANUFACTURER := AshFall`,
`PRODUCT_MODEL := AshFall GSI arm64`, and the props

```
ro.ashfall.version   <- ASH_VERSION from config/ashfall.conf
ro.ashfall.build.id  <- build id of the image
```

`build.sh` exports `ASH_VERSION` and `ASH_BUILD_ID` into the build environment,
and make imports environment variables, so the props need no generated file. A
manual `lunch` + `m` without those exports falls back to `0.1.0-dev` / `local`.

To extend the product:

- add packages in `ashfall_common.mk` (`PRODUCT_PACKAGES += ...`) when they are
  useful for both the GSI and the device build;
- uncomment `PRODUCT_PACKAGE_OVERLAYS += vendor/ashfall/overlay` once the
  overlay has content (an empty overlay directory must not be wired up);
- uncomment `BOARD_SEPOLICY_DIRS += vendor/ashfall/sepolicy` in phase 4;
- RP5-specific fixes belong in `patches/**` (issue #5), not in the overlay.

## Patching the tree: `scripts/patches.sh`

```bash
scripts/patches.sh apply  [--tree DIR] [--rev REV] [--no-clean] [--keep-conflicts]
scripts/patches.sh reset  [--tree DIR]
scripts/patches.sh status [--tree DIR]
```

Layout: `patches/<tree-path>/NNNN-*.patch`, applied in filename order with
`git am --3way` to `$REMOTE_SRC/<tree-path>`:

```
patches/frameworks/base/0001-....patch   ->  <tree>/frameworks/base
patches/system/core/0002-....patch       ->  <tree>/system/core
```

Rules:

- **Baseline**: `repo manifest` for the project (the manifest's revision, not
  the current HEAD) in a repo-synced tree; for plain git checkouts (tests) the
  recorded `<project>/.git/ashfall-patches-base`, else `HEAD`. `--rev` pins it
  explicitly.
- **`apply` resets first** (`git am --abort` if needed, `git reset --hard` to
  the baseline, `git clean -fdq` unless `--no-clean`), so it is idempotent and
  every build starts from the same tree. Untracked files in a patched project
  are removed - that is the point.
- **One commit per patch file.** `git am` uses the identity from the script
  (`AshFall <ashfall@localhost>`), so the host needs no git user configured.
  After applying, the number of new commits must equal the number of patch
  files; otherwise the run fails with `PATCH SERIES INCOMPLETE` (a silently
  skipped, empty or already-applied patch would otherwise produce a tree that
  does not match `patches/`).
- **Conflicts** exit non-zero and print `PATCH CONFLICT` with the project, the
  patch list, the conflicting files and the tail of `git am`'s output. By
  default the project is restored to the baseline (`--keep-conflicts` leaves the
  `git am` state for inspection). Nothing is built after a failed apply.
- `patches/` does not exist yet (issue #5): every action is a clean no-op and
  prints a warning. `build.sh` therefore works before the first patch lands.

`status` is a read-only report:

```
$ scripts/patches.sh status --tree /path/to/tree
frameworks/base      2/2 applied  base 1a2b3c4d5e6f  head 9f8e7d6c5b4a
system/core          1/2 applied  base 001122334455  head 66778899aabb
    missing: netd: relax BPF version check for 4.19 kernels
```

## Artifacts

`$REMOTE_ARTIFACTS/<build-id>/` (and `out/<build-id>/` after `--fetch`):

| File | Content |
|---|---|
| `system.img` | the GSI (raw ext4, non-sparse) |
| `system.img.sha256` | `sha256sum` of the image |
| `system.img.xz` | optional, with `--xz` |
| `build.prop` | `/system/build.prop` of the image |
| `installed-files.txt`, `installed-files.json` | installed file lists |
| `system_image_info.txt`, `file_list.txt` | image packing metadata |
| `vbmeta.img` | only if the target produces one |
| `build-info.txt` | product, variant, release, device, fs type, commit, timings, image size/sha256/type, ccache stats, host info |
| `build.log` | copy of `$REMOTE_LOGS/<build-id>/build.log` |

`build-info.txt` is the machine-readable answer to "what exactly is this
image" - quote `release=`, `ashfall_commit=` and `system_img_sha256=` when
reporting a test result on the device.

`--fetch` refuses to report success without `system.img`; if the job failed it
prints the status code and the tail of the remote log instead.

## Testing without waiting for the tree

```bash
# local: run the same job script against any tree (no ssh, no ws_push)
scripts/build.sh --local-tree /tmp/stub-tree --jobs 4

# plan only
scripts/build.sh --dry-run

# patch pipeline against a scratch git repo
scripts/patches.sh apply --tree /tmp/tree --patches /tmp/patches --rev "$(git -C /tmp/tree/proj rev-parse HEAD)"
```

`--local-tree` is meant for pipeline testing (stub `build/envsetup.sh`, fake
`m`); it uses `out/.tmp/<build-id>/` for logs and `out/<build-id>/` for
artifacts, and a local tree lock under `out/`.

## Environment knobs

| Variable / config | Meaning |
|---|---|
| `GSI_PRODUCT`, `GSI_VARIANT` (`config/ashfall.conf`) | defaults for `--product` / `--variant` |
| `LUNCH_RELEASE` (config) | pin the lunch release instead of auto-detecting |
| `BUILD_JOBS` (config) | default `--jobs` (empty = `nproc` in the container) |
| `CCACHE_SIZE`, `REMOTE_CCACHE` (config) | ccache max size and directory |
| `ASH_MIN_FREE_GB` | minimum free space on the tree filesystem (default 60) |
| `ASH_VERSION` (config) | `ro.ashfall.version` in the image |

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `no synced LineageOS tree ... (issue #1)` | tree sync has not started/finished; see `scripts/sync.sh` |
| `is not usable yet: ... lineage_gsi_arm64.mk is missing` | sync still running; only some projects are checked out |
| `distrobox container 'ashfall-build' not found` | issue #1 has not created the container |
| `the shared tree lock is held ... will queue` | a sync or another build is running; the job waits |
| `system image filesystem is 'erofs', expected ext4` | the product/board config changed; do not build, kernel 4.19 cannot mount EROFS |
| `PATCH CONFLICT` | a patch in `patches/` (issue #5) no longer applies; see the report |
| `PATCH SERIES INCOMPLETE` | a patch is empty, already applied, or an mbox with several commits |
| `lunch failed for product` | product makefile missing from the tree (overlay failed) or release detection picked a release the tree rejects; set `LUNCH_RELEASE` |
| `not enough free space` | free space where `$REMOTE_SRC` lives (needs ~200 GiB for the tree and build out); override `ASH_MIN_FREE_GB` only if you know better |
| build job "started" but no log | `ssh $HOST 'distrobox enter ashfall-build -- tmux ls'`; the status file appears even on early failures |
