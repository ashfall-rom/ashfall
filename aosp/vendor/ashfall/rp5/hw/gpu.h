/*
 * AshFall RP5 GPU frequency cap (Adreno 650).
 *
 * Contract: docs/phase6.md section 3.  The overclock itself lives in the
 * vendor_boot DTB (scripts/gpu/build-vendor-boot.sh); this module only selects
 * the devfreq max_freq cap requested by the AshFall Parts app:
 *
 *   persist.ashfall.perf.gpu_max_mhz   305..1000, default 670 (stock)
 *
 * The cap is clamped to the highest step the GPU actually exposes (the stock
 * DTB keeps 670 as the top, so a 1000 MHz request is a logged no-op there) and
 * to 670 MHz while the phase-5 thermal throttle override is active.
 *
 * The devfreq nodes are labelled vendor_sysfs_kgsl by the stock vendor policy;
 * our system_ext policy relabels exactly the three attributes we use with
 * ashfall_gpu_sysfs (file_contexts exact paths + genfscon), see
 * aosp/vendor/ashfall/sepolicy/ashfall_hwd.te.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_GPU_H
#define ASHFALL_GPU_H

#include <stdbool.h>

#define GPU_DEVFREQ_MAX "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
#define GPU_DEVFREQ_MIN "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
#define GPU_AVAILABLE_FREQS "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies"

#define GPU_MIN_MHZ 305
#define GPU_STOCK_MHZ 670
#define GPU_MAX_MHZ 1000
#define GPU_MAX_STEPS 32

typedef struct {
    /* Frequencies exposed by the running DT, in Hz, descending. */
    int freqs_hz[GPU_MAX_STEPS];
    int count;
    bool available;   /* gpu_available_frequencies readable, >= 1 step */
    bool applied;     /* the last max_freq write succeeded */
    int applied_hz;   /* last successfully written cap (for the no-op check) */
    int applied_mhz;  /* actual cap as read back, -1 = unknown */
    int request_mhz;  /* last evaluated request (for change logging) */
    bool clamped;     /* the throttle override forced <= 670 MHz */
} gpu_state;

/* Reset all state.  Safe before the sysfs nodes exist (early boot). */
void gpu_state_init(gpu_state *state);

/*
 * Apply request_mhz (clamped to GPU_MIN_MHZ..GPU_MAX_MHZ and to the highest
 * available step <= the request).  While throttle_override is true the cap is
 * clamped to GPU_STOCK_MHZ.  Reads the frequency table on first use and keeps
 * retrying while the node is missing.  Returns the actual cap in MHz, or -1
 * when the sysfs interface is unavailable (logged, never fatal).
 */
int gpu_apply(gpu_state *state, int request_mhz, bool throttle_override);

/* ashfall.status.perf.gpu_max_mhz: actual cap in MHz, -1 when unavailable. */
int gpu_status_mhz(const gpu_state *state);

/* ashfall.status.perf.gpu_oc: 1 = above stock, 0 = stock or lower, -1 = n/a. */
int gpu_status_oc(const gpu_state *state);

#endif /* ASHFALL_GPU_H */
