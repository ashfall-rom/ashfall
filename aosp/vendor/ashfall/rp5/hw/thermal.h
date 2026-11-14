/*
 * AshFall RP5 thermal sensors: the hottest CPU/GPU tsens zone, selected by the
 * zone `type` string (never by index - the zone order is a kernel detail and
 * stock "Smart" fan read thermal_zone0, a PMIC sensor, which is useless).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_THERMAL_H
#define ASHFALL_THERMAL_H

#include <limits.h>
#include <stdbool.h>

#define THERMAL_BASE "/sys/class/thermal"
#define THERMAL_MAX_ZONES 64
#define THERMAL_TYPE_LEN 48

/*
 * Only a few zones are sampled every tick: each `temp` read makes the stock
 * kona tsens driver print at KERN_FATAL ("temp_show ..."), which floods
 * dmesg/logd (2 688 lines shortly after boot with all 16 zones at 2 s).  The
 * fan follows the hottest few zones, re-picked by a full sweep every few
 * minutes, which is 4-5x fewer reads for the same safety behaviour.
 */
#define THERMAL_MAX_WATCH 3

typedef struct {
    char type[THERMAL_TYPE_LEN];
    char temp_path[PATH_MAX];
} thermal_zone;

typedef struct {
    thermal_zone zone[THERMAL_MAX_ZONES]; /* every CPU/GPU zone found */
    int count;
    thermal_zone watch[THERMAL_MAX_WATCH]; /* the zones sampled every tick */
    int watch_count;
} thermal_set;

/* Scan /sys/class/thermal, keep the CPU/GPU zones and pick the watch set.
 * Logs the selection at info level so a device log shows exactly which
 * sensors the fan follows. */
void thermal_scan(thermal_set *set);

/* Read every discovered zone once, re-pick the hottest THERMAL_MAX_WATCH of
 * them and return the hottest temperature in tenths of Celsius (-1 when no
 * zone is readable).  This is the only function that reads all zones. */
int thermal_sweep(thermal_set *set);

/* Hottest of the *watch* zones in tenths of a degree Celsius, -1 when no
 * watch zone is readable.  This is the per-tick read: at most
 * THERMAL_MAX_WATCH `temp` reads. */
int thermal_hottest_tenths(const thermal_set *set);

/* Raw -> tenths of Celsius for the usual kernel units (milli-/deci-degrees). */
int thermal_raw_to_tenths(int raw);

#endif /* ASHFALL_THERMAL_H */
