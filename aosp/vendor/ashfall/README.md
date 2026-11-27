# vendor/ashfall

AshFall's own product tree. `scripts/build.sh` mirrors this directory
(`aosp/vendor/ashfall/`) verbatim into `$REMOTE_SRC/vendor/ashfall` before every
build, and it is the only overlay directory that is rsynced with `--delete`.

```
AndroidProducts.mk              product registration (PRODUCT_MAKEFILES, lunch choices)
ashfall_common.mk               props/packages/overlays shared by GSI + future device build
products/ashfall_gsi_arm64.mk   the GSI: inherits lineage_gsi_arm64, EROFS, branding
debloat.mk                      phase 7 package/APEX filters (docs/phase7.md)
debloat/blocklist.txt           the debloat audit's module/package blocklist
overlay/                        static framework/package overlays (phase 4, phase 7 branding)
sepolicy/                       device sepolicy (placeholder, phase 4)
```

Notes:

- The product intentionally keeps `PRODUCT_DEVICE := generic_arm64` (inherited
  from `lineage_gsi_arm64`). Android looks up the board config through
  `TARGET_DEVICE`, and the GSI board config is
  `build/make/target/board/generic_arm64/BoardConfig.mk`.
- `GSI_FILE_SYSTEM_TYPE` is pinned to **erofs** (the stock RP5 kernel is 5.15
  GKI with EROFS support, and super only leaves ~4.2 GiB for `system.img`).
  `scripts/build.sh --fs ext4` switches to the ext4 fallback. The build fails if
  the resolved `BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE` does not match the request,
  and the finished image is checked by superblock magic plus a 4.2 GiB budget.
- `ro.ashfall.version` comes from `ASH_VERSION` in `config/ashfall.conf`;
  `scripts/build.sh` exports it into the build environment.
