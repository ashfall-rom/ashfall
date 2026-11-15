/*
 * AshFall RP5 GPU frequency cap (Adreno 650).
 *
 * Contract: docs/phase6.md section 3.  Device notes: docs/rp5/gpu.md.
 *
 * The GPU devfreq max_freq node is the only writable interface exposed by the
 * stock msm_kgsl module: writing a frequency from the DT table raises/lowers
 * the software cap the msm-adreno-tz governor may use.  The kernel still
 * enforces the GPU cooling-device trips, so the cap is a ceiling, not a
 * thermal bypass.
 *
 * The nodes are opened through their resolved device paths, not through
 * /sys/class/kgsl: those class symlinks are labelled vendor_sysfs_kgsl and
 * following them needs a vendor-private `lnk_file read` allow that system_ext
 * policy cannot add (issue #115).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "gpu.h"
#include "plat.h"

#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>

#define HZ_PER_MHZ 1000000

static int mhz_from_hz(int hz)
{
    if (hz <= 0)
        return -1;
    return (hz + HZ_PER_MHZ / 2) / HZ_PER_MHZ;
}

/* The resolved device path, with the older /devices/soc layout as fallback.
 * A class symlink (vendor_sysfs_kgsl:lnk_file) must never be traversed. */
static const char *pick_path(const char *primary, const char *legacy)
{
    static bool logged_legacy;

    if (primary && sysfs_present(primary))
        return primary;
    if (legacy && sysfs_present(legacy)) {
        if (!logged_legacy) {
            logged_legacy = true;
            ah_logf(AH_LOG_INFO, "gpu: using fallback DT path %s", legacy);
        }
        return legacy;
    }
    return NULL;
}

static void sort_descending(int *values, int count)
{
    for (int i = 1; i < count; i++) {
        int value = values[i];
        int j = i - 1;

        while (j >= 0 && values[j] < value) {
            values[j + 1] = values[j];
            j--;
        }
        values[j + 1] = value;
    }
}

/* Read the resolved gpu_available_frequencies ("670000000 ..."). */
static void gpu_load_freqs(gpu_state *state)
{
    static bool logged_missing;
    static bool logged_empty;
    char buf[512];
    char *cursor;
    int count = 0;
    int len;

    state->count = 0;
    state->available = false;
    state->freqs_path = pick_path(GPU_AVAILABLE_FREQS, GPU_AVAILABLE_FREQS_LEGACY);
    if (!state->freqs_path) {
        if (!logged_missing) {
            logged_missing = true;
            ah_logf(AH_LOG_WARN, "gpu: %s unavailable; GPU cap stays at the "
                    "boot default", GPU_AVAILABLE_FREQS);
        }
        return;
    }
    len = sysfs_read_str(state->freqs_path, buf, sizeof(buf));
    if (len <= 0) {
        if (!logged_missing) {
            logged_missing = true;
            ah_logf(AH_LOG_WARN, "gpu: %s unavailable; GPU cap stays at the "
                    "boot default", GPU_AVAILABLE_FREQS);
        }
        return;
    }

    cursor = buf;
    while (*cursor && count < GPU_MAX_STEPS) {
        char *end = NULL;
        long hz;

        while (*cursor == ' ' || *cursor == '\t' || *cursor == '\n' ||
               *cursor == '\r')
            cursor++;
        if (!*cursor)
            break;
        errno = 0;
        hz = strtol(cursor, &end, 10);
        if (errno != 0 || end == cursor || hz <= 0 || hz > INT_MAX)
            break;
        state->freqs_hz[count++] = (int)hz;
        cursor = end;
    }
    if (!count) {
        if (!logged_empty) {
            logged_empty = true;
            ah_logf(AH_LOG_WARN, "gpu: %s has no usable frequency",
                    GPU_AVAILABLE_FREQS);
        }
        return;
    }
    sort_descending(state->freqs_hz, count);
    state->count = count;
    state->available = true;
    ah_logf(AH_LOG_INFO, "gpu: %d DT step(s), %d..%d MHz", count,
            mhz_from_hz(state->freqs_hz[count - 1]),
            mhz_from_hz(state->freqs_hz[0]));
}

/* Highest available step <= request_mhz; the lowest step when none matches. */
static int pick_hz(const gpu_state *state, int request_mhz)
{
    int limit = request_mhz * HZ_PER_MHZ;
    int lowest = state->freqs_hz[state->count - 1];

    for (int i = 0; i < state->count; i++) {
        if (state->freqs_hz[i] <= limit)
            return state->freqs_hz[i];
    }
    return lowest;
}

void gpu_state_init(gpu_state *state)
{
    if (!state)
        return;
    memset(state, 0, sizeof(*state));
    state->applied_mhz = -1;
}

int gpu_apply(gpu_state *state, int request_mhz, bool throttle_override)
{
    int request;
    int target_hz;
    int actual_mhz;
    int readback_hz = 0;
    static bool logged_missing_max;
    bool was_applied;
    bool was_clamped;
    int was_mhz;
    int was_request;

    if (!state)
        return -1;
    if (!state->available)
        gpu_load_freqs(state);
    if (!state->available) {
        state->applied = false;
        state->applied_hz = 0;
        state->applied_mhz = -1;
        state->clamped = false;
        return -1;
    }

    was_applied = state->applied;
    was_clamped = state->clamped;
    was_mhz = state->applied_mhz;
    was_request = state->request_mhz;
    state->request_mhz = request_mhz;

    if (!state->max_path) {
        state->max_path = pick_path(GPU_DEVFREQ_MAX, GPU_DEVFREQ_MAX_LEGACY);
        state->min_path = pick_path(GPU_DEVFREQ_MIN, GPU_DEVFREQ_MIN_LEGACY);
    }
    if (!state->max_path) {
        if (!logged_missing_max) {
            logged_missing_max = true;
            ah_logf(AH_LOG_WARN, "gpu: %s unavailable; cap not applied",
                    GPU_DEVFREQ_MAX);
        }
        state->applied = false;
        state->applied_mhz = -1;
        return -1;
    }

    request = request_mhz;
    if (request < GPU_MIN_MHZ)
        request = GPU_MIN_MHZ;
    if (request > GPU_MAX_MHZ)
        request = GPU_MAX_MHZ;

    state->clamped = throttle_override && request > GPU_STOCK_MHZ;
    if (state->clamped)
        request = GPU_STOCK_MHZ;

    target_hz = pick_hz(state, request);
    if (target_hz <= 0)
        return -1;

    if (!state->applied || state->applied_hz != target_hz) {
        if (sysfs_write_int(state->max_path, target_hz) != 0) {
            /* devfreq refuses a max_freq below min_freq; the governor may have
             * raised min_freq above the requested cap.  Lower min_freq first
             * (never raise it), then retry once. */
            int min_hz = 0;

            if (state->min_path &&
                sysfs_read_int(state->min_path, &min_hz) == 0 &&
                min_hz > target_hz) {
                ah_logf(AH_LOG_INFO,
                        "gpu: min_freq %d Hz > cap %d Hz; lowering min_freq",
                        min_hz, target_hz);
                sysfs_write_int(state->min_path, target_hz);
                if (sysfs_write_int(state->max_path, target_hz) != 0)
                    return -1;
            } else {
                return -1;
            }
        }
        state->applied = true;
        state->applied_hz = target_hz;
    }

    actual_mhz = mhz_from_hz(target_hz);
    if (sysfs_read_int(state->max_path, &readback_hz) == 0 && readback_hz > 0)
        actual_mhz = mhz_from_hz(readback_hz);

    /* The kernel clamps to a DT step; report the step, not the request. */
    if (!was_applied || was_mhz != actual_mhz || was_clamped != state->clamped ||
        was_request != request_mhz) {
        if (state->clamped) {
            ah_logf(AH_LOG_INFO,
                    "gpu: throttle override active: cap %d MHz "
                    "(requested %d MHz)", actual_mhz, request_mhz);
        } else if (actual_mhz > GPU_STOCK_MHZ) {
            ah_logf(AH_LOG_INFO, "gpu: cap %d MHz (OC, requested %d MHz)",
                    actual_mhz, request_mhz);
        } else if (request_mhz > GPU_STOCK_MHZ) {
            ah_logf(AH_LOG_INFO,
                    "gpu: requested %d MHz is not in the DT table; cap %d MHz",
                    request_mhz, actual_mhz);
        } else {
            ah_logf(AH_LOG_INFO, "gpu: cap %d MHz", actual_mhz);
        }
    }
    state->applied_mhz = actual_mhz;
    return actual_mhz;
}

int gpu_status_mhz(const gpu_state *state)
{
    if (!state || !state->available || !state->applied)
        return -1;
    return state->applied_mhz;
}

int gpu_status_oc(const gpu_state *state)
{
    int mhz = gpu_status_mhz(state);

    if (mhz < 0)
        return -1;
    return mhz > GPU_STOCK_MHZ ? 1 : 0;
}

int gpu_clock_mhz(void)
{
    const char *path = pick_path(GPU_CLOCK, GPU_CLOCK_LEGACY);
    int hz = 0;

    if (!path)
        return -1;
    if (sysfs_read_int(path, &hz) < 0 || hz < 0)
        return -1;
    if (hz == 0)
        return 0;
    return mhz_from_hz(hz);
}

int gpu_load_pct(void)
{
    const char *path = pick_path(GPU_BUSY, GPU_BUSY_LEGACY);
    int pct = 0;

    if (!path)
        return -1;
    /* gpu_busy_percentage_show() prints "<n> %", which sysfs_read_int()
     * parses (trailing text is ignored). */
    if (sysfs_read_int(path, &pct) < 0 || pct < 0)
        return -1;
    if (pct > 100)
        pct = 100;
    return pct;
}
