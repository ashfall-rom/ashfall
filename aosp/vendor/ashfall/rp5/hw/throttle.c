/*
 * AshFall RP5 performance override: disable software clock throttling.
 *
 * Contract: docs/phase5.md §6.1.  Device survey: docs/rp5/hw.md §9.
 *
 * The kernel binds the CPU/GPU clock cooling devices to passive thermal trips:
 * on the RP5 (build 20261114-021714, live sysfs) the cpufreq-cpu4/cpu7 and the
 * GPU devfreq device share the `ddr` trip at 95 C, the GPU devfreq device is
 * also bound to `gpuss-0/1` at 95 C and to cwlan/video/npu/... at 100 C.  The
 * `cpu-1-*` 75 C trip present in the device tree has no cooling device bound
 * to it on this kernel build (it is only reported), so it does not throttle.
 *
 * The override reads the kernel's own cdev -> trip bindings
 * (`thermal_zoneN/cdevM_trip_point`) and raises the clock trips to just below
 * the protective trips:
 *   - trips that drive a cpufreq/cpu-cluster cdev -> 105 C (below the 110 C
 *     CPU-pause/hotplug trip),
 *   - trips that only drive a GPU devfreq cdev         -> 110 C (below the
 *     115 C reset-monitor trip).
 * Critical trips, CPU-pause/hotplug, the 115/125 C trips, PMIC/DDR/modem
 * cooling and LMh hardware limits are not touched.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "throttle.h"
#include "plat.h"

#include <dirent.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

#define ZONE_TRIPS_MAX 32

static bool is_clock_cdev(const char *type, bool *is_cpu)
{
    if (!type)
        return false;
    if (!strncmp(type, "cpufreq", 7) || !strncmp(type, "cpu-cluster", 11)) {
        *is_cpu = true;
        return true;
    }
    if (!strncmp(type, "devfreq", 7) || !strcmp(type, "gpu")) {
        *is_cpu = false;
        return true;
    }
    return false;
}

/* Stock temperature of a clock trip on this device, used to restore a trip
 * that a previous daemon instance already raised. */
static int stock_hint_mc(const char *zone_type)
{
    if (!strcmp(zone_type, "ddr") || !strncmp(zone_type, "gpuss", 5))
        return THROTTLE_STOCK_DDR_MC;
    return THROTTLE_STOCK_GPU100_MC;
}

static void add_trip(throttle_state *state, const char *zone_path,
                     const char *zone_type, int trip_index, int target_mc,
                     bool is_cpu)
{
    char path[192];
    int current_mc;
    int original_mc;
    throttle_trip *trip;

    snprintf(path, sizeof(path), "%s/trip_point_%d_temp", zone_path,
             trip_index);
    if (sysfs_read_int(path, &current_mc) < 0)
        return;

    if (current_mc == target_mc) {
        /* A previous instance of this daemon already raised it (init restarts
         * the service on a crash).  Restore the stock value on OFF. */
        original_mc = stock_hint_mc(zone_type);
        state->active = true;
        ah_logf(AH_LOG_INFO,
                "throttle: %s trip%d already overridden at %d.%03d C; stock "
                "is %d.%03d C",
                zone_type, trip_index, current_mc / 1000, current_mc % 1000,
                original_mc / 1000, original_mc % 1000);
    } else if (current_mc == THROTTLE_STOCK_DDR_MC ||
               current_mc == THROTTLE_STOCK_GPU100_MC) {
        original_mc = current_mc;
    } else {
        /* Unknown value (someone else changed it): do not touch it. */
        ah_logf(AH_LOG_INFO,
                "throttle: %s trip%d is %d.%03d C (not a known clock trip); "
                "leaving it",
                zone_type, trip_index, current_mc / 1000, current_mc % 1000);
        return;
    }
    if (state->trip_count >= THROTTLE_MAX_TRIPS)
        return;

    trip = &state->trip[state->trip_count++];
    snprintf(trip->path, sizeof(trip->path), "%s", path);
    trip->original_mc = original_mc;
    trip->target_mc = target_mc;
    ah_logf(AH_LOG_INFO,
            "throttle: %s trip%d %d.%03d C -> %d.%03d C (%s clock)",
            zone_type, trip_index, original_mc / 1000, original_mc % 1000,
            target_mc / 1000, target_mc % 1000, is_cpu ? "CPU" : "GPU");
}

static void scan_zone(throttle_state *state, const char *name)
{
    char zone_path[128];
    char resolved[PATH_MAX];
    char type[48];
    char path[192];
    struct {
        int index;
        bool cpu;
    } trips[ZONE_TRIPS_MAX];
    int trip_count = 0;
    DIR *dir;
    struct dirent *de;

    snprintf(zone_path, sizeof(zone_path), THERMAL_ZONE_DIR "/%.64s", name);
    snprintf(path, sizeof(path), "%s/type", zone_path);
    if (sysfs_read_str(path, type, sizeof(type)) < 0)
        return;

    dir = opendir(plat_resolve(zone_path, resolved, sizeof(resolved)));
    if (!dir)
        return;
    while ((de = readdir(dir)) != NULL) {
        char cdev_path[256];
        char cdev_type[48];
        char trip_path[256];
        int trip_index;
        bool is_cpu;
        int i;

        if (strncmp(de->d_name, "cdev", 4) != 0)
            continue;
        /* cdev0 is a directory; cdev0_trip_point / cdev0_weight are files. */
        snprintf(cdev_path, sizeof(cdev_path), "%s/%.64s/type", zone_path,
                 de->d_name);
        if (sysfs_read_str(cdev_path, cdev_type, sizeof(cdev_type)) < 0)
            continue;
        if (!is_clock_cdev(cdev_type, &is_cpu))
            continue;

        snprintf(trip_path, sizeof(trip_path), "%s/%.64s_trip_point", zone_path,
                 de->d_name);
        if (sysfs_read_int(trip_path, &trip_index) < 0)
            continue;
        if (trip_index < 0 || trip_index >= 32)
            continue;
        for (i = 0; i < trip_count; i++) {
            if (trips[i].index == trip_index) {
                trips[i].cpu = trips[i].cpu || is_cpu;
                break;
            }
        }
        if (i == trip_count && trip_count < ZONE_TRIPS_MAX) {
            trips[trip_count].index = trip_index;
            trips[trip_count].cpu = is_cpu;
            trip_count++;
        }
    }
    closedir(dir);

    for (int i = 0; i < trip_count; i++) {
        int target = trips[i].cpu ? THROTTLE_TARGET_CPU_MC
                                  : THROTTLE_TARGET_GPU_MC;

        add_trip(state, zone_path, type, trips[i].index, target,
                 trips[i].cpu);
    }
}

void throttle_scan(throttle_state *state)
{
    DIR *dir;
    struct dirent *de;
    char base[PATH_MAX];

    memset(state, 0, sizeof(*state));
    dir = opendir(plat_resolve(THERMAL_ZONE_DIR, base, sizeof(base)));
    if (!dir) {
        ah_logf(AH_LOG_WARN, "throttle: cannot open %s", THERMAL_ZONE_DIR);
        return;
    }
    while ((de = readdir(dir)) != NULL) {
        if (strncmp(de->d_name, "thermal_zone", 12) != 0)
            continue;
        scan_zone(state, de->d_name);
    }
    closedir(dir);
    state->supported = state->trip_count > 0;
    if (!state->supported) {
        ah_logf(AH_LOG_WARN,
                "throttle: no CPU/GPU clock trip found; override unavailable");
        return;
    }
    ah_logf(AH_LOG_INFO,
            "throttle: %d clock trip(s)%s", state->trip_count,
            state->active ? " (override already active)" : "");
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
