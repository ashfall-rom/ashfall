/*
 * AshFall RP5 performance override: disable software clock throttling.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "throttle.h"
#include "plat.h"

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

#define TRIP_MAX 16

typedef struct {
    int index;
    int temp_mc;
    char type[16];
} trip_info;

static bool zone_is_target(const char *type)
{
    /*
     * The RP5 device tree puts the cpufreq cooling trip on the gold CPU tsens
     * zones (`cpu-1-*`, 75 C) and the GPU devfreq trip on `gpuss-*` (95 C).
     * The silver clusters (`cpu-0-*`) only have the 110 C CPU-pause trip,
     * which is a last-resort hotplug protection and is deliberately left
     * alone.  DDR/modem/camera zones are also left alone: their trips also
     * drive remote processors, not the CPU/GPU clock.
     */
    return !strncmp(type, "cpu-1-", 6) || !strncmp(type, "gpuss", 5);
}

static int read_trips(const char *zone, trip_info trips[TRIP_MAX])
{
    int count = 0;

    for (int i = 0; i < TRIP_MAX; i++) {
        char path[PATH_MAX];
        char type[16];
        int temp_mc;

        snprintf(path, sizeof(path),
                 THERMAL_ZONE_DIR "/%s/trip_point_%d_type", zone, i);
        if (sysfs_read_str(path, type, sizeof(type)) < 0)
            break;
        snprintf(path, sizeof(path),
                 THERMAL_ZONE_DIR "/%s/trip_point_%d_temp", zone, i);
        if (sysfs_read_int(path, &temp_mc) < 0)
            break;
        trips[count].index = i;
        trips[count].temp_mc = temp_mc;
        snprintf(trips[count].type, sizeof(trips[count].type), "%s", type);
        count++;
    }
    return count;
}

void throttle_scan(throttle_state *state)
{
    DIR *dir;
    struct dirent *de;
    char base[PATH_MAX];
    bool overridden = false;

    memset(state, 0, sizeof(*state));
    dir = opendir(plat_resolve(THERMAL_ZONE_DIR, base, sizeof(base)));
    if (!dir) {
        ah_logf(AH_LOG_WARN, "throttle: cannot open %s", THERMAL_ZONE_DIR);
        return;
    }

    while ((de = readdir(dir)) != NULL) {
        char type[48];
        char path[PATH_MAX];
        trip_info trips[TRIP_MAX];
        int trip_count;
        int lowest = -1;
        int next_mc = -1;
        int target_mc;
        int stock_mc;
        throttle_trip *entry;

        if (strncmp(de->d_name, "thermal_zone", 12) != 0)
            continue;
        snprintf(path, sizeof(path), THERMAL_ZONE_DIR "/%s/type", de->d_name);
        if (sysfs_read_str(path, type, sizeof(type)) < 0)
            continue;
        if (!zone_is_target(type))
            continue;
        stock_mc = !strncmp(type, "cpu-1-", 6) ? THROTTLE_STOCK_CPU_MC
                                               : THROTTLE_STOCK_GPU_MC;

        trip_count = read_trips(de->d_name, trips);
        for (int i = 0; i < trip_count; i++) {
            if (strcmp(trips[i].type, "passive") != 0)
                continue;
            if (lowest < 0 || trips[i].temp_mc < trips[lowest].temp_mc)
                lowest = i;
        }
        if (lowest < 0)
            continue;
        /* Only the clock trip is lowered by the firmware; a zone whose lowest
         * passive trip is already at the safety range is not clock-throttled
         * by the kernel.  A value between the stock and the ceiling means a
         * previous instance of this daemon already raised it (init restarts
         * the service on a crash), so remember the stock value to be able to
         * restore it. */
        if (trips[lowest].temp_mc > THROTTLE_TRIP_CEIL_C * 1000)
            continue;
        if (trips[lowest].temp_mc > THROTTLE_TRIP_STOCK_MAX_C * 1000) {
            if (trips[lowest].temp_mc < stock_mc)
                continue;
            ah_logf(AH_LOG_INFO,
                    "throttle: %s already overridden at %d.%03d C; stock is "
                    "%d.%03d C",
                    type, trips[lowest].temp_mc / 1000,
                    trips[lowest].temp_mc % 1000, stock_mc / 1000,
                    stock_mc % 1000);
            trips[lowest].temp_mc = stock_mc;
            overridden = true;
        }
        for (int i = 0; i < trip_count; i++) {
            if (strcmp(trips[i].type, "passive") != 0)
                continue;
            if (trips[i].temp_mc > trips[lowest].temp_mc &&
                (next_mc < 0 || trips[i].temp_mc < next_mc))
                next_mc = trips[i].temp_mc;
        }
        if (next_mc < 0)
            next_mc = (THROTTLE_TRIP_CEIL_C + 5) * 1000;
        target_mc = next_mc - 5000;
        if (target_mc > THROTTLE_TRIP_CEIL_C * 1000)
            target_mc = THROTTLE_TRIP_CEIL_C * 1000;
        if (target_mc <= trips[lowest].temp_mc)
            continue;
        if (state->trip_count >= THROTTLE_MAX_TRIPS)
            continue;

        entry = &state->trip[state->trip_count++];
        snprintf(entry->path, sizeof(entry->path),
                 THERMAL_ZONE_DIR "/%s/trip_point_%d_temp", de->d_name,
                 trips[lowest].index);
        entry->original_mc = trips[lowest].temp_mc;
        entry->target_mc = target_mc;
        ah_logf(AH_LOG_INFO,
                "throttle: %s clock trip %s: %d.%03d C -> %d.%03d C",
                type, entry->path, trips[lowest].temp_mc / 1000,
                trips[lowest].temp_mc % 1000, target_mc / 1000,
                target_mc % 1000);
    }
    closedir(dir);
    state->supported = state->trip_count > 0;
    state->active = overridden;
    if (!state->supported)
        ah_logf(AH_LOG_WARN,
                "throttle: no CPU/GPU clock trip found; override unavailable");
}

bool throttle_set(throttle_state *state, bool on)
{
    int failures = 0;

    if (!state)
        return false;
    if (on && !state->supported) {
        ah_logf(AH_LOG_WARN, "throttle: override requested but unsupported");
        return false;
    }
    /* Deliberately no "on == active" early return: a caller may re-apply the
     * same state to repair a partial write (or after a restart), and writing
     * the same trip temperature is harmless. */

    for (int i = 0; i < state->trip_count; i++) {
        throttle_trip *trip = &state->trip[i];
        int value = on ? trip->target_mc : trip->original_mc;

        if (sysfs_write_int(trip->path, value) != 0) {
            failures++;
            ah_logf(AH_LOG_WARN, "throttle: write %s=%d failed",
                    trip->path, value);
        }
    }
    state->partial = failures > 0;
    state->active = on;
    ah_logf(failures ? AH_LOG_WARN : AH_LOG_INFO,
            "throttle: software clock throttling %s (%d trip(s), %d failed)",
            on ? "disabled" : "restored", state->trip_count, failures);
    return failures == 0;
}

int throttle_status(const throttle_state *state)
{
    if (!state || !state->supported || state->partial)
        return -1;
    return state->active ? 1 : 0;
}
