/*
 * AshFall RP5 performance override: disable software clock throttling.
 *
 * Contract: docs/phase5.md §6.1.  Device survey and the exact scope of what is
 * disabled: docs/rp5/hw.md §9.
 *
 * The override uses the kernel's cdev -> trip bindings and raises only the
 * trips that drive the CPU cpufreq / GPU devfreq cooling devices:
 *   - CPU clock trips  -> 105 C (below the 110 C CPU-pause trip),
 *   - GPU clock trips  -> 110 C (below the 115 C reset-monitor trip).
 * Critical trips, CPU hotplug, PMIC/DDR/modem cooling and LMh hardware limits
 * stay untouched.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_THROTTLE_H
#define ASHFALL_THROTTLE_H

#include <stdbool.h>

#define THERMAL_ZONE_DIR "/sys/class/thermal"
#define THROTTLE_MAX_TRIPS 32

#define THROTTLE_TARGET_CPU_MC 105000
#define THROTTLE_TARGET_GPU_MC 110000
/* Stock clock-trip temperatures on the RP5: ddr/gpuss 95 C, the secondary GPU
 * zones (cwlan/video/npu/...) 100 C.  Used to recognise an already-raised trip
 * after an init restart of the daemon. */
#define THROTTLE_STOCK_DDR_MC 95000
#define THROTTLE_STOCK_GPU100_MC 100000

typedef struct {
    char path[192];
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
 * Survey the thermal zones and their cooling-device bindings.  No hardware
 * writes; safe to call at start.  Logs the clock trips it found so a boot log
 * shows exactly what the device exposes.
 */
void throttle_scan(throttle_state *state);

/*
 * Enable/disable the override.  Returns true when the requested state is in
 * effect (disable restores what enable changed).  All writes are best effort:
 * failures are logged with errno and reported through *state.
 */
bool throttle_set(throttle_state *state, bool on);

/* Status for ashfall.status.perf.throttle: 1 active, 0 off, -1 unavailable. */
int throttle_status(const throttle_state *state);

#endif /* ASHFALL_THROTTLE_H */
