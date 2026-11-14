/*
 * AshFall RP5 gamepad mapping and deadzones.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "mapping.h"
#include "plat.h"

#include <math.h>
#include <string.h>

void deadzones_default(struct deadzones *dz)
{
    dz->inner_pct = 5;
    dz->outer_pct = 3;
    dz->trigger_pct = 3;
}

void deadzones_read(struct deadzones *dz)
{
    struct deadzones next = *dz;

    next.inner_pct = prop_get_int_range(PROP_DZ_INNER, 5, 0, 30);
    next.outer_pct = prop_get_int_range(PROP_DZ_OUTER, 3, 0, 20);
    next.trigger_pct = prop_get_int_range(PROP_DZ_TRIGGER, 3, 0, 30);
    if (memcmp(&next, dz, sizeof(next)) != 0) {
        *dz = next;
        ah_logf(AH_LOG_INFO, "deadzones: inner=%d%% outer=%d%% trigger=%d%%",
                dz->inner_pct, dz->outer_pct, dz->trigger_pct);
    }
}

static double clampd(double v, double lo, double hi)
{
    return v < lo ? lo : v > hi ? hi : v;
}

int stick_map(int raw, double center, double min, double max)
{
    double v;

    if (raw >= center) {
        if (max <= center)
            return 0;
        v = (center - raw) * 32768.0 / (max - center);
    } else {
        if (center <= min)
            return 0;
        v = (center - raw) * 32767.0 / (center - min);
    }
    if (v < -32768.0)
        v = -32768.0;
    if (v > 32767.0)
        v = 32767.0;
    return (int)lround(v);
}

int trigger_map(int raw, double min, double max)
{
    double v;

    if (max <= min)
        return 0;
    v = (max - raw) * 255.0 / (max - min);
    v = clampd(v, 0.0, 255.0);
    return (int)lround(v);
}

void stick_apply_deadzone(int *x, int *y, int inner_pct, int outer_pct)
{
    double fx, fy, magnitude, inner, outer, scaled, factor;

    if (!x || !y)
        return;
    fx = (double)*x / 32768.0;
    fy = (double)*y / 32768.0;
    magnitude = hypot(fx, fy);
    if (magnitude <= 0.0) {
        *x = 0;
        *y = 0;
        return;
    }

    inner = clampd(inner_pct, 0, 90) / 100.0;
    outer = clampd(outer_pct, 0, 90) / 100.0;

    if (magnitude <= inner) {
        *x = 0;
        *y = 0;
        return;
    }
    /* Remaining travel is re-scaled to the full axis so the stick still
     * reaches the rim; the outer cut makes the last `outer` fraction of the
     * travel map to full scale. */
    scaled = (magnitude - inner) / (1.0 - inner);
    if (outer > 0.0)
        scaled /= (1.0 - outer);
    scaled = clampd(scaled, 0.0, 1.0);

    factor = scaled / magnitude;
    fx *= factor;
    fy *= factor;
    fx = clampd(fx, -1.0, 1.0);
    fy = clampd(fy, -1.0, 1.0);
    *x = (int)lround(fx * 32767.0);
    *y = (int)lround(fy * 32767.0);
}

int trigger_apply_deadzone(int value, int trigger_pct)
{
    double v = value / 255.0;
    double dz = clampd(trigger_pct, 0, 90) / 100.0;

    if (v <= dz)
        return 0;
    v = (v - dz) / (1.0 - dz);
    if (v > 1.0)
        v = 1.0;
    return (int)lround(v * 255.0);
}
