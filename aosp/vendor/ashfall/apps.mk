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
# priority 1000 (Launcher3 declares none). See docs/launcher.md.
# ---------------------------------------------------------------------------
PRODUCT_PACKAGES += \
    AshFallLauncher
