/*
 * AshFall RP5 battery/power telemetry.
 *
 * One sampler for #124 and #137 (docs/phase6.md §9.3): the daemon reads the
 * battery nodes once per status tick and publishes
 *   ashfall.status.power.{current_ma,voltage_mv,level,charging,charge_mah,
 *                          capacity_mah}
 *   ashfall.status.perf.power_mw
 * so the SystemUI PlayTime estimator and the Power page read properties, never
 * /sys/class/power_supply themselves.
 *
 * The stock RP5 exposes more than one battery-ish power-supply device (the
 * `battery` class device and the `bms` fuel gauge), and which one carries
 * which attribute is not fixed.  The paths are therefore resolved at runtime
 * by walking /sys/class/power_supply: a device whose `type` is `Battery` wins,
 * `battery` before `bms` before anything else, and every attribute is taken
 * from the first candidate that has it.
 *
 * Units: the kernel power-supply class documents current_now in uA and
 * voltage_now in uV, but some fuel gauges report mA/mV.  A value >= 10000 is
 * treated as the micro unit (10 000 mA would be a 10 A draw, implausible in
 * mA on this unit; 10 000 uA is 10 mA, an ordinary idle reading).  Every implausible value is published as -1,
 * never as a fabricated number (0/-1/too large current, out-of-range voltage).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_POWER_H
#define ASHFALL_POWER_H

#include <limits.h>
#include <stdbool.h>

#define POWER_SUPPLY_DIR "/sys/class/power_supply"

/* Battery devices to consider, in preference order after a `Battery` type. */
#define POWER_MAX_CANDIDATES 4
#define POWER_NAME_LEN 64

/* A handheld draw above 20 A or a pack outside 2.0..6.0 V is not a reading. */
#define POWER_MAX_CURRENT_MA 20000
#define POWER_MIN_VOLTAGE_MV 2000
#define POWER_MAX_VOLTAGE_MV 6000
#define POWER_MAX_CHARGE_MAH 100000

typedef struct {
    int current_ma;   /* rectified magnitude, -1 unknown */
    int voltage_mv;   /* -1 unknown */
    int level;        /* 0..100, -1 unknown */
    int charging;     /* 1 charging, 0 not charging, -1 unknown */
    int charge_mah;   /* remaining charge, -1 when the node is missing */
    int capacity_mah; /* design capacity, -1 when the node is missing */
    int power_mw;     /* current_ma * voltage_mv / 1000; -1 while charging */
} power_sample;

typedef struct {
    bool resolved;
    /* Attribute paths ("" when no candidate has the attribute). */
    char current_path[PATH_MAX];
    char voltage_path[PATH_MAX];
    char level_path[PATH_MAX];
    char status_path[PATH_MAX];
    char charge_path[PATH_MAX];
    char capacity_path[PATH_MAX];
    /* The device every attribute came from, for the log line. */
    char current_dev[POWER_NAME_LEN];
    char voltage_dev[POWER_NAME_LEN];
} power_state;

/* Reset all state.  Safe before /sys/class/power_supply exists (early boot). */
void power_state_init(power_state *state);

/*
 * Read one sample.  Fills every field, using -1 for a missing/unreadable/
 * implausible value, and derives power_mw from the *same* read (never a
 * second open).  Returns 0 when at least one battery node was readable, -1
 * when /sys/class/power_supply has no usable battery at all.
 */
int power_sample_read(power_state *state, power_sample *out);

/* Unit/plausibility helpers, exported for the host tests. */
int power_ua_to_ma(int raw);
int power_uv_to_mv(int raw);
int power_mah_from_node(int raw);

#endif /* ASHFALL_POWER_H */
