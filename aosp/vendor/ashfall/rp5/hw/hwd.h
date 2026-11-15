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

#define STATUS_INTERVAL_MS 2000
#define STATUS_LIVE_MS 500

/* Status (and telemetry) period for the current UI liveness. */
static inline int hwd_status_interval_ms(bool fan_live, bool power_live)
{
    return (fan_live || power_live) ? STATUS_LIVE_MS : STATUS_INTERVAL_MS;
}

#endif /* ASHFALL_HWD_H */
