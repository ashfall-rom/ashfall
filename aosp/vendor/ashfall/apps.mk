# AshFall apps (launcher, later: AshFall settings, sidebar). Owned by the
# 'apps' worker(s). Sources go in vendor/ashfall/apps/<App>/.

# ---------------------------------------------------------------------------
# AshFallLauncher: lightweight landscape HOME app, controller-first.
#
# Launcher3QuickStep is intentionally NOT overridden or removed: it stays
# installed and keeps providing the recents/quickstep surface, exactly like the
# stock firmware's rp3launcher + Launcher3QuickStepEX pair. AshFall is made the
# home app through RoleManager's fallback holder, which picks the HOME activity
# with the highest intent-filter priority; the launcher manifest declares
# priority 1000 (Launcher3 declares none). A Launcher3 patch removes its HOME
# category, so it is not a second home app in the chooser (#81). The launcher
# also owns the background: solid/gradient/image are installed through
# WallpaperManager and the animated gradient is a live wallpaper in this
# package (docs/launcher.md, docs/ui.md 7).
# ---------------------------------------------------------------------------
PRODUCT_PACKAGES += \
    AshFallLauncher

# ---------------------------------------------------------------------------
# AshFallParts: the "Handheld" Settings pages (fan, lighting, controller) and
# the Fan/Lighting Quick Settings tiles.  See docs/parts.md.
# ---------------------------------------------------------------------------
PRODUCT_PACKAGES += \
    AshFallParts
