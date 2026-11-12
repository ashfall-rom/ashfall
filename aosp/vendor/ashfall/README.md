# vendor/ashfall

AshFall's own product tree. `scripts/build.sh` mirrors this directory
(`aosp/vendor/ashfall/`) verbatim into `$REMOTE_SRC/vendor/ashfall` before every
build, and it is the only overlay directory that is rsynced with `--delete`.

```
AndroidProducts.mk              product registration (PRODUCT_MAKEFILES, lunch choices)
ashfall_common.mk               props/packages/overlays shared by GSI + future device build
products/ashfall_gsi_arm64.mk   the GSI: inherits lineage_gsi_arm64, ext4, branding
overlay/                        static framework/package overlays (placeholder, phase 4)
sepolicy/                       device sepolicy (placeholder, phase 4)
```

Notes:

- The product intentionally keeps `PRODUCT_DEVICE := generic_arm64` (inherited
  from `lineage_gsi_arm64`). Android looks up the board config through
  `TARGET_DEVICE`, and the GSI board config is
  `build/make/target/board/generic_arm64/BoardConfig.mk`.
- `GSI_FILE_SYSTEM_TYPE := ext4` is not cosmetic: the RP5's 4.19 kernel cannot
  mount EROFS. `scripts/build.sh` fails the build if the resolved
  `BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE` is not `ext4`.
- `ro.ashfall.version` comes from `ASH_VERSION` in `config/ashfall.conf`;
  `scripts/build.sh` exports it into the build environment.
