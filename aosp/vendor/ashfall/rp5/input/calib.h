/*
 * AshFall RP5 gamepad calibration files.
 *
 * The stock factory file /mnt/vendor/persist/retrostation/joys is never
 * written by AshFall; init copies it to /data/misc/ashfall/joys.factory at
 * post-fs-data because a coredomain daemon may not read /mnt/vendor.
 * Calibration load order: user file -> factory copy -> built-in defaults.
 * Format is the stock one: 16 `key=float` lines.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_CALIB_H
#define ASHFALL_CALIB_H

#include <stdbool.h>
#include <stddef.h>

#define CALIB_USER_PATH "/data/misc/ashfall/joys"
#define CALIB_FACTORY_PATH "/data/misc/ashfall/joys.factory"
#define CALIB_DIR "/data/misc/ashfall"
#define CALIB_KEYS 16

struct calib_axis {
    double center;
    double min;
    double max;
};

struct calibration {
    struct calib_axis lx, ly, rx, ry;
    double l2_min, l2_max;
    double r2_min, r2_max;
};

enum cal_source {
    CAL_SOURCE_DEFAULT = 0,
    CAL_SOURCE_FACTORY,
    CAL_SOURCE_USER,
};

const char *cal_source_name(enum cal_source source);

/* Paths are overridable in the host test build through $ASHFALL_CAL_DIR. */
const char *calib_user_path(void);
const char *calib_factory_path(void);

/* Built-in defaults: identical to the factory values read from a stock RP5. */
void calib_defaults(struct calibration *out);

bool calib_validate(const struct calibration *cal, char *err, size_t errlen);

bool calib_load_file(const char *path, struct calibration *out, char *err,
                     size_t errlen);
bool calib_save_file(const char *path, const struct calibration *cal, char *err,
                     size_t errlen);

/* "left_x_center=125.0 ... r2_max=1362.0" without a trailing newline. */
void calib_format(const struct calibration *cal, char *buf, size_t len);

/* Parse "k=v" pairs (a whole SET CAL payload).  Every one of the 16 keys must
 * be present exactly once and the result must validate. */
bool calib_parse_pairs(int count, char *const pairs[], struct calibration *out,
                       char *err, size_t errlen);

/* user -> factory -> default.  Never fails: falls back to the defaults. */
enum cal_source calib_load(struct calibration *out);

#endif /* ASHFALL_CALIB_H */
