# AshFall sepolicy

Placeholder. Device-specific policy (`*.te`, `file_contexts`, ...) goes here.

Wire it up in `vendor/ashfall/ashfall_common.mk` once there is content:

```makefile
BOARD_SEPOLICY_DIRS += vendor/ashfall/sepolicy
```

The GSI builds against the AOSP/LineageOS policy only; SM8250 / 5.15 GKI
quirks are phase 4 (#5).
