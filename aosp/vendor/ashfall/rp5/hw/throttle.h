/*
 * AshFall RP5 performance override: disable software clock throttling.
 *
 * Contract: docs/phase5.md §6.1.  Device survey and the exact scope of what is
 * disabled: docs/rp5/hw.md §9.
 *
 * The kernel pushes the CPU/GPU clocks down from thermal trip points that are
 * defined in the device tree (cpufreq at 75 C on the gold cores, GPU devfreq at
 * 95 C).  Those trips are `passive` and their temperature attributes are
 * writable (CONFIG_THERMAL_WRITABLE_TRIPS=y).  The override raises the lowest
 * passive trip of the CPU/GPU tsens zones just below the *next* trip, so the
 * cooling device is never driven in the normal operating range.  Critical
 * trips, CPU-pause/hotplug, the PMIC trips, LMh (hardware DVFS limits) and the
 * thermal HAL are not touched.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_THROTTLE_H
#define ASHFALL_THROTTLE_H

#include <stdbool.h>

#define THERMAL_ZONE_DIR "/sys/class/thermal"
#define THROTTLE_MAX_TRIPS 32
/* The lowest passive CPU/GPU trip is the clock-throttling trip (75 C CPU /
 * 95 C GPU on the RP5).  Values above this are either already raised by this
 * override (recognised on a service restart) or not a clock trip. */
#define THROTTLE_TRIP_STOCK_MAX_C 95
#define THROTTLE_STOCK_CPU_MC 75000
#define THROTTLE_STOCK_GPU_MC 95000
/* Never raise a trip above this: the CPU-pause trip is 110 C and the
 * reset-monitor trip is 115 C, both stay untouched. */
#define THROTTLE_TRIP_CEIL_C 110

typedef struct {
    char path[128];
    int original_mc; /* trip temperature in millidegrees C */
    int target_mc;   /* value written while the override is active */
} throttle_trip;

typedef struct {
    bool active;
    bool supported; /* at least one CPU/GPU clock trip was found */
    int trip_count;
    throttle_trip trip[THROTTLE_MAX_TRIPS];
    /* True when at least one targeted trip could not be written. */
    bool partial;
} throttle_state;

/*
 * Survey the thermal zones and remember the trip temperatures.  No hardware
 * access; safe to call at start.  Logs the CPU/GPU zones it would change so a
 * boot log shows exactly what the device exposes.
 */
void throttle_scan(throttle_state *state);

/*
 * Enable/disable the override.  Returns true when the requested state is in
 * effect (disable always restores what enable changed).  All writes are best
 * effort: failures are logged with errno and reported through *state.
 */
bool throttle_set(throttle_state *state, bool on);

/* Status for ashfall.status.perf.throttle: 1 active, 0 off, -1 failed. */
int throttle_status(const throttle_state *state);

#endif /* ASHFALL_THROTTLE_H */
