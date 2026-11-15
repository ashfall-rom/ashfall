/*
 * AshFall RP5 battery/power telemetry (docs/phase6.md §9.3).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "power.h"
#include "plat.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

/* Node values at or above this are the micro unit (uA/uV/uAh). */
#define POWER_MICRO_THRESHOLD 10000

static int magnitude(int raw)
{
    long value = raw;

    if (value < 0)
        value = -value;
    if (value > INT_MAX)
        return -1;
    return (int)value;
}

int power_ua_to_ma(int raw)
{
    int value;

    /* 0 and -1 are the "no reading" sentinels of the power-supply class, and a
     * zero current is never true while the device is awake and sampled. */
    if (raw == 0 || raw == -1)
        return -1;
    value = magnitude(raw);
    if (value < 0)
        return -1;
    if (value >= POWER_MICRO_THRESHOLD)
        value /= 1000;
    if (value <= 0 || value > POWER_MAX_CURRENT_MA)
        return -1;
    return value;
}

int power_uv_to_mv(int raw)
{
    int value = magnitude(raw);

    if (value < 0 || value == 0)
        return -1;
    if (value >= POWER_MICRO_THRESHOLD)
        value /= 1000;
    if (value < POWER_MIN_VOLTAGE_MV || value > POWER_MAX_VOLTAGE_MV)
        return -1;
    return value;
}

int power_mah_from_node(int raw)
{
    int value = magnitude(raw);

    if (value < 0 || value == 0)
        return -1;
    if (value >= POWER_MICRO_THRESHOLD)
        value /= 1000;
    if (value <= 0 || value > POWER_MAX_CHARGE_MAH)
        return -1;
    return value;
}

void power_state_init(power_state *state)
{
    if (!state)
        return;
    memset(state, 0, sizeof(*state));
}

/* One /sys/class/power_supply entry: the class device name and the type. */
typedef struct {
    char name[POWER_NAME_LEN];
    bool battery_type;
} power_device;

static bool read_type(const char *name, char *type, size_t len)
{
    char path[PATH_MAX];

    snprintf(path, sizeof(path), "%s/%s/type", POWER_SUPPLY_DIR, name);
    if (sysfs_read_str(path, type, len) < 0)
        return false;
    return true;
}

/* Candidate order: `battery`, then `bms`, then the other Battery-type devices
 * in readdir order.  Devices whose type is not `Battery` are ignored. */
static int collect_devices(power_device *out)
{
    DIR *dir;
    struct dirent *de;
    power_device found[POWER_MAX_CANDIDATES * 2];
    char base[PATH_MAX];
    int count = 0;
    int picked = 0;

    dir = opendir(plat_resolve(POWER_SUPPLY_DIR, base, sizeof(base)));
    if (!dir)
        return 0;
    while ((de = readdir(dir)) != NULL && count < (int)(sizeof(found) / sizeof(found[0]))) {
        char type[32];

        if (de->d_name[0] == '.')
            continue;
        if (!read_type(de->d_name, type, sizeof(type)))
            continue;
        if (strcasecmp(type, "Battery") != 0)
            continue;
        if (strlen(de->d_name) >= sizeof(found[count].name))
            continue;
        snprintf(found[count].name, sizeof(found[count].name), "%s", de->d_name);
        found[count].battery_type = true;
        count++;
    }
    closedir(dir);

    /* `battery` first, then `bms`, then the rest. */
    for (int pass = 0; pass < 3; pass++) {
        for (int i = 0; i < count && picked < POWER_MAX_CANDIDATES; i++) {
            bool match = pass == 0 ? !strcmp(found[i].name, "battery")
                       : pass == 1 ? !strcmp(found[i].name, "bms")
                                   : true;

            if (!match)
                continue;
            bool seen = false;

            for (int j = 0; j < picked; j++) {
                if (!strcmp(out[j].name, found[i].name))
                    seen = true;
            }
            if (seen)
                continue;
            out[picked++] = found[i];
        }
    }
    return picked;
}

#define POWER_ATTR_FIELD(field, attr)                                          \
    do {                                                                       \
        for (int i = 0; i < count; i++) {                                      \
            char path[PATH_MAX];                                               \
                                                                               \
            snprintf(path, sizeof(path), "%s/%s/" attr, POWER_SUPPLY_DIR,      \
                     devices[i].name);                                         \
            if (!sysfs_present(path))                                          \
                continue;                                                      \
            snprintf(state->field, sizeof(state->field), "%s", path);          \
            break;                                                             \
        }                                                                      \
    } while (0)

static void resolve_paths(power_state *state)
{
    power_device devices[POWER_MAX_CANDIDATES];
    int count;

    state->current_path[0] = '\0';
    state->voltage_path[0] = '\0';
    state->level_path[0] = '\0';
    state->status_path[0] = '\0';
    state->charge_path[0] = '\0';
    state->capacity_path[0] = '\0';
    state->current_dev[0] = '\0';
    state->voltage_dev[0] = '\0';

    count = collect_devices(devices);
    if (count == 0) {
        static bool logged_missing;

        if (!logged_missing) {
            logged_missing = true;
            ah_logf(AH_LOG_WARN,
                    "power: no Battery-type device in %s; telemetry unavailable",
                    POWER_SUPPLY_DIR);
        }
        return;
    }

    POWER_ATTR_FIELD(current_path, "current_now");
    POWER_ATTR_FIELD(voltage_path, "voltage_now");
    POWER_ATTR_FIELD(level_path, "capacity");
    POWER_ATTR_FIELD(status_path, "status");
    POWER_ATTR_FIELD(charge_path, "charge_counter");
    POWER_ATTR_FIELD(capacity_path, "charge_full_design");

    /* The charge/capacity nodes live on the fuel gauge more often than on the
     * `battery` class device: fall back to the other candidates. */
    if (!state->charge_path[0])
        POWER_ATTR_FIELD(charge_path, "charge_now");
    if (!state->capacity_path[0])
        POWER_ATTR_FIELD(capacity_path, "charge_full");

    for (int i = 0; i < count; i++) {
        char prefix[PATH_MAX];

        snprintf(prefix, sizeof(prefix), "%s/%s/", POWER_SUPPLY_DIR,
                 devices[i].name);
        if (!state->current_dev[0] && state->current_path[0] &&
            !strncmp(state->current_path, prefix, strlen(prefix)))
            snprintf(state->current_dev, sizeof(state->current_dev), "%.*s",
                     (int)sizeof(state->current_dev) - 1, devices[i].name);
        if (!state->voltage_dev[0] && state->voltage_path[0] &&
            !strncmp(state->voltage_path, prefix, strlen(prefix)))
            snprintf(state->voltage_dev, sizeof(state->voltage_dev), "%.*s",
                     (int)sizeof(state->voltage_dev) - 1, devices[i].name);
    }

    state->resolved = state->current_path[0] || state->voltage_path[0] ||
                      state->level_path[0] || state->status_path[0];
    if (state->resolved) {
        ah_logf(AH_LOG_INFO,
                "power: battery=%s current=%s voltage=%s charge=%s "
                "capacity=%s",
                state->current_dev[0] ? state->current_dev : devices[0].name,
                state->current_path[0] ? state->current_path : "none",
                state->voltage_path[0] ? state->voltage_path : "none",
                state->charge_path[0] ? state->charge_path : "none",
                state->capacity_path[0] ? state->capacity_path : "none");
    }
}

static int read_int_path(const char *path, int *out)
{
    if (!path || !path[0])
        return -1;
    return sysfs_read_int(path, out);
}

/* `Charging` is the only status that means power is flowing in.  `Full` and
 * `Not charging` are reported as 0 (the pack is not gaining charge) and an
 * unknown status as -1. */
static int read_charging(const power_state *state)
{
    char status[32];

    if (!state->status_path[0])
        return -1;
    if (sysfs_read_str(state->status_path, status, sizeof(status)) < 0)
        return -1;
    if (!strncasecmp(status, "Charging", 8))
        return 1;
    if (!strncasecmp(status, "Discharging", 11) ||
        !strncasecmp(status, "Full", 4) || !strncasecmp(status, "Not charging", 12))
        return 0;
    return -1;
}

int power_sample_read(power_state *state, power_sample *out)
{
    int raw;
    bool any = false;

    if (!state || !out)
        return -1;
    out->current_ma = -1;
    out->voltage_mv = -1;
    out->level = -1;
    out->charging = -1;
    out->charge_mah = -1;
    out->capacity_mah = -1;
    out->power_mw = -1;

    if (!state->resolved)
        resolve_paths(state);
    if (!state->resolved)
        return -1;

    if (read_int_path(state->current_path, &raw) == 0) {
        out->current_ma = power_ua_to_ma(raw);
        any = true;
    }
    if (read_int_path(state->voltage_path, &raw) == 0) {
        out->voltage_mv = power_uv_to_mv(raw);
        any = true;
    }
    if (read_int_path(state->level_path, &raw) == 0) {
        out->level = (raw >= 0 && raw <= 100) ? raw : -1;
        any = true;
    }
    if (read_int_path(state->charge_path, &raw) == 0)
        out->charge_mah = power_mah_from_node(raw);
    if (read_int_path(state->capacity_path, &raw) == 0)
        out->capacity_mah = power_mah_from_node(raw);
    out->charging = read_charging(state);

    /* A battery-side watt number is meaningless while charging (#124). */
    if (out->charging != 1 && out->current_ma > 0 && out->voltage_mv > 0) {
        long mw = (long)out->current_ma * out->voltage_mv / 1000;

        if (mw > 0 && mw <= 100000)
            out->power_mw = (int)mw;
    }

    if (!any && out->charging < 0)
        return -1;
    return 0;
}
