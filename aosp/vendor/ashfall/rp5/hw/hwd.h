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
#include <stdio.h>
#include <string.h>

#define STATUS_INTERVAL_MS 2000
#define STATUS_LIVE_MS 500

/*
 * Publisher heartbeat for the ashfall.ui.* values the app owns (issue #94,
 * docs/phase5.md §6.1).  While it owns a value the app refreshes a companion
 * liveness property every HWD_UI_HEARTBEAT_MS with a token that differs from
 * the previous one (issue #180; SystemClock.elapsedRealtime() is the usual
 * choice).  The token has no meaning of its own: the daemon treats *any*
 * changed, non-empty value as a heartbeat and honours the value only while
 * the last change is at most HWD_UI_HEARTBEAT_TIMEOUT_MS old.  A force-stopped
 * app therefore cannot latch a stale LED override or fan preview.
 *
 * Baking the change into the value (instead of relying on bionic's property
 * serial, which is an implementation detail for identical writes) keeps the
 * mechanism visible in `getprop` and testable without a device.  The struct
 * and helpers are shared with the host tests like hwd_status_interval_ms().
 */
#define HWD_UI_HEARTBEAT_MS 500
#define HWD_UI_HEARTBEAT_TIMEOUT_MS 1500
#define HWD_UI_HEARTBEAT_TOKEN_MAX 32

struct hwd_ui_heartbeat {
    char token[HWD_UI_HEARTBEAT_TOKEN_MAX];
    int64_t seen_ms;
    bool observed; /* a changed token has been seen; only then is it fresh */
};

/* Record a token sample; true when it is a new (non-empty, different) beat. */
static inline bool hwd_ui_heartbeat_update(struct hwd_ui_heartbeat *beat,
                                           const char *token, int64_t now_ms)
{
    if (!token || !token[0] || !strcmp(token, beat->token))
        return false;
    snprintf(beat->token, sizeof(beat->token), "%s", token);
    beat->seen_ms = now_ms;
    beat->observed = true;
    return true;
}

/* Record the token that is already present without counting it as a beat
 * (startup: a value left behind by a dead publisher must not look fresh).
 * The first genuine change is then observed normally. */
static inline void hwd_ui_heartbeat_seed(struct hwd_ui_heartbeat *beat,
                                         const char *token)
{
    beat->seen_ms = 0;
    beat->observed = false;
    beat->token[0] = '\0';
    if (token && token[0])
        snprintf(beat->token, sizeof(beat->token), "%s", token);
}

static inline bool hwd_ui_heartbeat_fresh(const struct hwd_ui_heartbeat *beat,
                                          int64_t now_ms)
{
    return beat->observed &&
           now_ms - beat->seen_ms <= HWD_UI_HEARTBEAT_TIMEOUT_MS;
}

/* Status (and telemetry) period for the current UI liveness. */
static inline int hwd_status_interval_ms(bool fan_live, bool power_live)
{
    return (fan_live || power_live) ? STATUS_LIVE_MS : STATUS_INTERVAL_MS;
}

#endif /* ASHFALL_HWD_H */
