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

typedef struct {
    char type[THERMAL_TYPE_LEN];
    char temp_path[PATH_MAX];
} thermal_zone;

typedef struct {
    thermal_zone zone[THERMAL_MAX_ZONES];
    int count;
} thermal_set;

/* Scan /sys/class/thermal and keep the CPU/GPU zones.  Logs the selection at
 * info level so a device log shows exactly which sensors the fan follows. */
void thermal_scan(thermal_set *set);

/* Hottest zone in tenths of a degree Celsius, -1 when no sensor is readable.
 * Accepts a cached set (sensors do not appear/disappear at runtime). */
int thermal_hottest_tenths(const thermal_set *set);

/* Raw -> tenths of Celsius for the usual kernel units (milli-/deci-degrees). */
int thermal_raw_to_tenths(int raw);

#endif /* ASHFALL_THERMAL_H */
