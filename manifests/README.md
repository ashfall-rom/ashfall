# Local manifests

`*.xml` files here are mirrored verbatim to the build host and copied into
`$REMOTE_SRC/.repo/local_manifests/` by `scripts/sync.sh` before every sync.
Use them to add device/vendor/kernel projects (e.g. the RP5 community trees
under `Ayn8250Dev`) without editing the LineageOS manifest.

Rules:

- Keep files small and named `<purpose>.xml` (e.g. `rp5.xml`).
- Only `*.xml` is mirrored; everything else in this directory is ignored.
- Because they are copied into the shared tree, they affect every build on the
  host - discuss additions in the issue/PR first.
- Currently empty on purpose: Phase 1 only needs a working `lineage-24.0` tree.

Example (not active):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <project name="Ayn8250Dev/android_device_retroidpocket_RP5"
           path="device/retroidpocket/RP5" revision="lineage-24.0" />
</manifest>
```
