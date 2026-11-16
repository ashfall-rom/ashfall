/*
 * AshFall RP5 hardware daemon: status publication policy.
 *
 * The daemon publishes the telemetry in one place, on one timer: the existing
 * status tick.  Its period follows the UI: while a page that shows live data is
 * visible the app sets a liveness property, and the tick drops from
 * STATUS_INTERVAL_MS to STATUS_LIVE_MS.  There is no second timer and no
 * per-page reader (docs/phase6.md §9.3).
 *
 *   ashfall.ui.fan_live=1    Fan page / fan editor / Fan QS dialog visible
 *   ashfall.ui.power_live=1  Power page visible (issue #156 item 2)
 *
 * Either property keeps the fast tick; both empty returns to the background
 * interval.  This header is shared with the host tests so the selection can be
 * asserted without a device.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_HWD_H
#define ASHFALL_HWD_H

#include <stdbool.h>
#include <stdint.h>

#define STATUS_INTERVAL_MS 2000
#define STATUS_LIVE_MS 500

/*
 * Publisher heartbeat for the ashfall.ui.* values the app owns (issue #94,
 * docs/phase5.md §6.1).  The app refreshes the matching *_live property every
 * HWD_UI_HEARTBEAT_MS while it publishes the value; the daemon honours the
 * value only while the heartbeat is at most HWD_UI_HEARTBEAT_TIMEOUT_MS old.
 * A force-stopped app therefore cannot latch a stale LED override or fan
 * preview: the value expires on its own and the configured setting returns.
 *
 * `observed` is true once a write of the heartbeat has been seen (not just
 * that the property exists), so a value left over from a previous publisher
 * is never fresh on its own.  Shared with the host tests like
 * hwd_status_interval_ms() so the expiry rule is assertable without a device.
 */
#define HWD_UI_HEARTBEAT_MS 500
#define HWD_UI_HEARTBEAT_TIMEOUT_MS 1500

static inline bool hwd_ui_heartbeat_fresh(bool observed, int64_t seen_ms,
                                          int64_t now_ms)
{
    return observed && now_ms - seen_ms <= HWD_UI_HEARTBEAT_TIMEOUT_MS;
}

/* Status (and telemetry) period for the current UI liveness. */
static inline int hwd_status_interval_ms(bool fan_live, bool power_live)
{
    return (fan_live || power_live) ? STATUS_LIVE_MS : STATUS_INTERVAL_MS;
}

#endif /* ASHFALL_HWD_H */
