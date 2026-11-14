/*
 * AshFall RP5 thermal sensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "thermal.h"
#include "plat.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static bool interesting_type(const char *type)
{
    /* kona tsens types look like cpu0-usr, cpuss-0-usr, gpuss-0-usr; the
     * PMIC zones (pm8150_tz, ...) and the battery are not SoC temperature. */
    static const char *const prefixes[] = {"cpu", "cpuss", "gpu", "gpuss"};

    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {
        size_t n = strlen(prefixes[i]);
        if (!strncmp(type, prefixes[i], n))
            return true;
    }
    return false;
}

static int zone_tenths(const thermal_zone *zone)
{
    int raw;

    if (sysfs_read_int(zone->temp_path, &raw) < 0)
        return -1;
    if (raw <= 0 || raw > 200000)
        return -1;
    return thermal_raw_to_tenths(raw);
}

int thermal_sweep(thermal_set *set)
{
    int tenths[THERMAL_MAX_ZONES];
    thermal_zone picked[THERMAL_MAX_WATCH];
    int picked_tenths[THERMAL_MAX_WATCH];
    int picked_count = 0;
    int hottest = -1;

    /* Read every zone exactly once: each read makes the stock tsens driver
     * print a KERN_FATAL line, so the values are cached for the selection. */
    for (int i = 0; i < set->count; i++) {
        tenths[i] = zone_tenths(&set->zone[i]);
        if (tenths[i] > hottest)
            hottest = tenths[i];
    }

    /* Keep the hottest THERMAL_MAX_WATCH zones, hottest first. */
    for (int i = 0; i < set->count; i++) {
        int pos = picked_count;

        if (tenths[i] < 0)
            continue;
        while (pos > 0 && picked_tenths[pos - 1] < tenths[i])
            pos--;
        if (pos >= THERMAL_MAX_WATCH)
            continue;
        if (picked_count < THERMAL_MAX_WATCH)
            picked_count++;
        for (int j = picked_count - 1; j > pos; j--) {
            picked[j] = picked[j - 1];
            picked_tenths[j] = picked_tenths[j - 1];
        }
        picked[pos] = set->zone[i];
        picked_tenths[pos] = tenths[i];
    }

    if (picked_count != set->watch_count ||
        memcmp(picked, set->watch, sizeof(picked[0]) * (size_t)picked_count) !=
            0) {
        memcpy(set->watch, picked, sizeof(picked[0]) * (size_t)picked_count);
        set->watch_count = picked_count;
        for (int i = 0; i < picked_count; i++)
            ah_logf(AH_LOG_INFO, "thermal: watching %s (%.1f C)",
                    picked[i].type, picked_tenths[i] / 10.0);
    }
    return hottest;
}

void thermal_scan(thermal_set *set)
{
    DIR *dir;
    struct dirent *de;
    char base[PATH_MAX];

    memset(set, 0, sizeof(*set));
    dir = opendir(plat_resolve(THERMAL_BASE, base, sizeof(base)));
    if (!dir) {
        ah_logf(AH_LOG_WARN, "thermal: cannot open %s", THERMAL_BASE);
        return;
    }

    while ((de = readdir(dir)) != NULL && set->count < THERMAL_MAX_ZONES) {
        char path[PATH_MAX];
        char type[THERMAL_TYPE_LEN];
        thermal_zone *zone;

        if (strncmp(de->d_name, "thermal_zone", 12) != 0)
            continue;
        snprintf(path, sizeof(path), THERMAL_BASE "/%s/type", de->d_name);
        if (sysfs_read_str(path, type, sizeof(type)) < 0)
            continue;
        if (!interesting_type(type))
            continue;
        zone = &set->zone[set->count];
        snprintf(zone->type, sizeof(zone->type), "%s", type);
        snprintf(zone->temp_path, sizeof(zone->temp_path),
                 THERMAL_BASE "/%s/temp", de->d_name);
        set->count++;
    }
    closedir(dir);

    if (set->count == 0) {
        ah_logf(AH_LOG_WARN, "thermal: no CPU/GPU zones found in %s",
                THERMAL_BASE);
        return;
    }
    for (int i = 0; i < set->count; i++)
        ah_logf(AH_LOG_INFO, "thermal: zone %d type=%s", i, set->zone[i].type);
    thermal_sweep(set);
}

int thermal_raw_to_tenths(int raw)
{
    if (raw > 1000 || raw < -1000)
        return raw / 100; /* millidegrees C (the usual thermal_zone unit) */
    if (raw > 100 || raw < -100)
        return raw; /* decidegrees C == tenths */
    return raw * 10; /* degrees C */
}

int thermal_hottest_tenths(const thermal_set *set)
{
    int best = -1;

    for (int i = 0; i < set->watch_count; i++) {
        int tenths = zone_tenths(&set->watch[i]);

        if (tenths > best)
            best = tenths;
    }
    return best;
}
