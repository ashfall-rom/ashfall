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

# ext4 fallback instead of the default EROFS system image
scripts/build.sh --fs ext4

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
   4. asserts `BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE` equals the requested
      `--fs` (default `erofs`, fallback `ext4`);
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
4. `trunk_staging` (AOSP's default release, always present in `build/release`).

Each candidate is tried as the full `<product>-<release>-<variant>` form and
verified by reading `TARGET_PRODUCT`/`TARGET_RELEASE` back. The legacy two-part
`lunch <product>-<variant>` is **not** used: `lineage-24.0`'s `envsetup.sh`
rejects it with `Invalid lunch combo` (it would parse as
`product-release` with an empty variant), so a fallback that relied on it could
never work. If every candidate is rejected the build dies and asks for
`LUNCH_RELEASE` in `config/ashfall.conf`.

An attempt that selects another product is rejected via `get_build_var
TARGET_PRODUCT`; the release is checked against the `TARGET_RELEASE` that
`lunch` exports (`get_build_var TARGET_RELEASE` is empty on lineage-24.0 because
`core/release_config.mk` consumes and unsets it during the make parse).

### Filesystem: EROFS by default, ext4 as fallback

AshFall's GSI is **EROFS**. The stock Retroid Pocket 5 kernel is a 5.15.148 GKI
kernel with EROFS support (`docs/stock-analysis.md`), and the stock super group
`qti_dynamic_partitions_a` (6.0 GiB) leaves only ~4.2 GiB for a `system.img`
that replaces `system_a`, so the compressed image is what we want. (The 4.19
kernel and `ro.bpf.kver_override` quoted in older notes belong to the community
device build, not to the GSI on stock vendor.)

Verified against lineage-24.0 / AOSP `android-17.0.0_r1`:
`build/make/target/board/BoardConfigGsiCommon.mk` reads

```make
# system.img is ext4/erofs and non-sparsed.
GSI_FILE_SYSTEM_TYPE ?= ext4          # tree default is ext4, not EROFS
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := $(GSI_FILE_SYSTEM_TYPE)
TARGET_USERIMAGES_SPARSE_EXT_DISABLED := true
TARGET_USERIMAGES_SPARSE_EROFS_DISABLED := true
```

So the *tree* default is ext4; AshFall makes EROFS explicit instead of relying
on it. `lineage_generic_target.mk` sets
`USE_SOONG_DEFINED_SYSTEM_IMAGE := false`, so the make-based image path (which
uses `BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE`), not the Soong `android_gsi` image
path, produces `system.img`.

The product (`aosp/vendor/ashfall/products/ashfall_gsi_arm64.mk`) does:

```make
ASHFALL_SYSTEM_FS ?= $(if $(filter ext4 erofs,$(strip $(ASH_SYSTEM_FS))),$(strip $(ASH_SYSTEM_FS)),erofs)
GSI_FILE_SYSTEM_TYPE := $(ASHFALL_SYSTEM_FS)
```

Product config is parsed before board config (`core/envsetup.mk`:
`product_config.mk` before `board_config.mk`), so the value is effective.
`scripts/build.sh --fs erofs|ext4` (default `erofs`) exports `ASH_SYSTEM_FS`;
a manual lunch without it gets EROFS.

Four independent checks guard the result:

1. after lunch, `get_build_var BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE` must equal
   the requested filesystem;
2. after the build, the image's **superblock magic** is read directly
   (EROFS `0xE0F5E1E2` at offset 1024, ext4 `0xEF53` at offset 1080 - no
   dependence on `file(1)`'s magic database) and must match;
3. the size budget below;
4. `build-info.txt` records `system_filesystem_requested`,
   `system_filesystem_detected`, `system_img_gib` and `image_size_check`.

### system.img size budget

`super` is 6.0 GiB and the GSI replaces `system_a`; deleting the stock
`product_a`/`system_ext_a` (a GSI bundles both) leaves ~4.2 GiB for
`system.img`. `build.sh` therefore:

- **warns** above `ASH_IMAGE_WARN_GIB` (default 3.8 GiB),
- **fails** above `ASH_IMAGE_MAX_GIB` (default 4.2 GiB) after collecting the
  image, so an oversized build is never reported as success,
- always records the size (`system_img_bytes`, `system_img_gib`) in
  `build-info.txt`.

Both limits are GiB (decimal values allowed) and must satisfy
`ASH_IMAGE_WARN_GIB <= ASH_IMAGE_MAX_GIB`.

## The product: `aosp/vendor/ashfall`

Mirrored to `$REMOTE_SRC/vendor/ashfall` before every build (and tracked in this
repo so it is reviewable).

| File | Purpose |
|---|---|
| `AndroidProducts.mk` | registers `products/ashfall_gsi_arm64.mk` and lunch choices |
| `ashfall_common.mk` | props/packages/overlays shared with the future device build |
| `products/ashfall_gsi_arm64.mk` | the GSI: inherits `lineage_gsi_arm64`, EROFS, branding |
| `overlay/` | static overlay placeholder (phase 4) |
| `sepolicy/` | device sepolicy placeholder (phase 4) |

The GSI keeps `PRODUCT_DEVICE := generic_arm64` (inherited). Android resolves
the board config through `TARGET_DEVICE`, and the GSI board config is
`build/make/target/board/generic_arm64/BoardConfig.mk`.

Set for this product: `PRODUCT_NAME := ashfall_gsi_arm64`,
`PRODUCT_BRAND`/`PRODUCT_MANUFACTURER := AshFall`,
`PRODUCT_MODEL := RP5 AshFall`, and the props

```
ro.ashfall.version   <- ASH_VERSION from config/ashfall.conf
ro.ashfall.build.id  <- build id of the image
```

`build.sh` exports `ASH_VERSION` and `ASH_BUILD_ID` into the generated job
environment (the same names the product reads) and make imports environment
variables, so the props need no generated file. A manual `lunch` + `m` without
those exports falls back to `0.1.0-dev` / `local`. `build-info.txt` records
`ashfall_version=` from the same variable, so it always agrees with
`getprop ro.ashfall.version` in the image.

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

- **Path prefixes**: every diff path must be relative to the project root, e.g.
  `a/packages/SystemUI/...` inside `patches/frameworks/base/`. A patch copied
  from a whole-tree `git format-patch` keeps the `a/frameworks/base/` prefix,
  which plain `git am` rejects (and `--3way` only survives when the index blobs
  exist upstream); `scripts/patches-lint.sh` reports those without needing a
  tree (issue #221).
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
- **Shared tree lock**: `apply`/`reset` check whether `--tree` resolves to
  `$REMOTE_SRC`; if it does they take a blocking `flock` on `$REMOTE_TREE_LOCK`
  (`status` is read-only and never locks, scratch trees are never locked). The
  build job already holds that lock, so `build.sh` exports
  `ASH_TREE_LOCK_HELD=1` and `patches.sh` skips the nested lock (a nested flock
  on the same file from another process would deadlock). `--no-lock` bypasses
  the check for scratch trees or when you hold the lock yourself; never use it
  on the shared tree.
- **Subjects**: `status` derives each patch's commit subject with
  `git mailinfo` (the same machinery `git am` uses), so RFC 2822 folded
  subjects are unfolded exactly like the commit git am creates. A first-line-only
  read would report long subjects as missing even when the patch is applied.
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
    missing: vendor.qti: fix gatekeeper HAL for stock kona vendor
```

## Artifacts

`$REMOTE_ARTIFACTS/<build-id>/` (and `out/<build-id>/` after `--fetch`):

| File | Content |
|---|---|
| `system.img` | the GSI (raw EROFS, non-sparse; ext4 with `--fs ext4`) |
| `system.img.sha256` | `sha256sum` of the image |
| `system.img.xz` | optional, with `--xz` |
| `build.prop` | `/system/build.prop` of the image |
| `installed-files.txt`, `installed-files.json` | installed file lists |
| `system_image_info.txt`, `file_list.txt` | image packing metadata |
| `vbmeta.img` | only if the target produces one |
| `build-info.txt` | product, variant, release, device, filesystem requested/detected, image size (bytes + GiB) and budget verdict, commit, timings, sha256, ccache stats, host info |
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
| `ASH_ALLOW_UNSYNCED=1` | allow a build while `repo sync` is running (normally refused) |
| `ASH_IMAGE_WARN_GIB` | warn above this `system.img` size (default 3.8) |
| `ASH_IMAGE_MAX_GIB` | fail above this `system.img` size (default 4.2) |
| `ASH_SYSTEM_FS` | `erofs`/`ext4`; normally set by `--fs`, read by the product mk |
| `ASH_TREE_LOCK_HELD=1` | tell `patches.sh` the caller already holds `$REMOTE_TREE_LOCK` (set by `build.sh`) |
| `ASH_VERSION` (config) | `ro.ashfall.version` in the image |

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `no synced LineageOS tree ... (issue #1)` | tree sync has not started/finished; see `scripts/sync.sh` |
| `a 'repo sync' is running ... refusing to build` | the tree is still being written; wait for the sync (override only with `ASH_ALLOW_UNSYNCED=1`) |
| `is not usable yet: ... lineage_gsi_arm64.mk is missing` | only some projects are checked out (sync still running) |
| `distrobox container 'ashfall-build' not found` | issue #1 has not created the container |
| `the shared tree lock is held ... will queue` | another build is running; the job waits |
| `system image filesystem is 'X', expected 'Y'` | the tree did not honour `--fs`; check the product overlay and `GSI_FILE_SYSTEM_TYPE` |
| `system.img is 'unknown', expected 'erofs'` | the image is sparse or truncated, or the wrong target was built |
| `system.img is N GiB, over the 4.2 GiB budget` | the image does not fit the stock super group; shrink the build or raise `ASH_IMAGE_MAX_GIB` deliberately |
| `PATCH CONFLICT` | a patch in `patches/` (issue #5) no longer applies; see the report |
| `PATCH SERIES INCOMPLETE` | a patch is empty, already applied, or an mbox with several commits |
| `cannot lunch ... no usable release config` | the tree rejected every candidate release (and `trunk_staging`); set `LUNCH_RELEASE` in `config/ashfall.conf` |
| `lunch failed for product` | the product makefile is missing from the tree (overlay failed) |
| `shared tree lock is busy ... waiting` | a sync or build holds the lock; `patches.sh` waits for it (that is correct, do not kill it) |
| `not enough free space` | free space where `$REMOTE_SRC` lives (needs ~200 GiB for the tree and build out); override `ASH_MIN_FREE_GB` only if you know better |
| build job "started" but no log | `ssh $HOST 'distrobox enter ashfall-build -- tmux ls'`; the status file appears even on early failures |
