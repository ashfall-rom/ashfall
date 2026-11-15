# AGENTS.md

Repository-wide instructions for agents working on AshFall.

## Device flashing: always follow issue #54

The RP5 bootloader (ABL) wedges when a new `fastboot` process opens between
transfers or after a burst of `getvar`s. Two stock restores hung on `Sending
'boot_a'` / `Sending 'init_boot_a'` and needed a 15 s power-off. **Always**
write bootloader-side operations this way:

1. **One fastboot process per bootloader-side operation.** Build a single
   command line, e.g.
   `fastboot flash boot_a boot.img flash init_boot_a init_boot.img erase userdata set_active a`.
   Never loop `fastboot` per partition and never insert a getvar between writes.
2. **Run it detached on the build host with a log + an `exit` file** (same
   shape as `scripts/remote/job.sh`):
   `setsid nohup distrobox enter --name ashfall-dev --no-tty -- ...`.
   An SSH drop must not kill the flash.
3. **Do getvars only once, before the batch.** Prefer the documented static
   partition sizes (`docs/stock/device-live/README.md`, guide §8.2) over
   `partition-size:*` queries. Never `fastboot getvar all`.
4. **Use `distrobox enter` as the user, not `podman exec`/root** -- root cannot
   see the USB device after re-enumeration.
5. **Kill a hung fastboot with `pkill -x fastboot`**, never `pkill -f`: the
   latter matches and kills your own ssh/distrobox command line.

The shared helper is `dev_fastboot_batch` in
`scripts/device/lib/device.sh`; use it instead of ad-hoc fastboot loops.
Userdata erase takes ~351 s after a physical super flash and ~0.09 s otherwise.
See `docs/flashing.md` §8 and `docs/HANDOFF.md` §2 for the full rationale.

## Device access: one owner at a time

The RP5 and the emulator are single-tenant. Two workers on the RP5 at once has
already cost this project a build (#50/#51 vs #52, `docs/HANDOFF.md` §0), and a
second `fastboot` session can wedge the ABL (issue #54). Before any device work,
take the lease:

```bash
scripts/device/lock.sh status                 # who holds the RP5?
scripts/device/lock.sh run worker-124 --ttl 3600 -- scripts/device/run.sh adb devices
scripts/device/lock.sh run emulator --local -- adb install -r app-debug.apk
```

- **RP5** (build host, `distrobox ... adb`): lease name `device`, stored on the
  build host in `$REMOTE_ROOT/locks/device.lease`.
- **Emulator** (this VM): `--local`, lease name `emulator`, stored under
  `$ASH_LOCAL_LOCK_DIR` (`/tmp/opencode/locks` by default). One AVD instance at
  a time; never start a second emulator while one runs.
- Every adb/fastboot call that goes through `scripts/device/lib/device.sh`
  refuses when another owner holds the lease, and `scripts/device/run.sh` goes
  through it. Raw `ssh ... adb` cannot be intercepted, so the rule is: **take the
  lease first**, and report `DEVICE FREE` when you release it.
- Long jobs (flash batches, userdata erase ~351 s) need `--ttl` above their
  worst case; renew or re-acquire rather than letting a lease expire mid-flash.
- `steal` exists for stale leases only. Read-only triage may use
  `ASH_DEVICE_LOCK_BYPASS=1`, never a write path.

## General

- Scripts are `bash`, `set -euo pipefail`, source `scripts/lib/common.sh` +
  `load_config`, support `--help`, and are idempotent.
- Never flash anything on the recovery guide's NEVER-flash list (guide §13).
- Keep edits consistent with the surrounding style; run `shellcheck` on shell
  changes when available.
