/*
 * AshFall RP5 gamepad calibration: stock `joys` format, validation and the
 * user -> factory copy -> built-in default load order.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "calib.h"
#include "plat.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Sane ranges.  The MCU's sticks are ~12-bit ADCs around 0, the triggers are
 * unipolar and rest above their calibrated maximum. */
#define STICK_LIMIT 20000.0
#define STICK_MIN_SPAN 100.0
#define STICK_MAX_SPAN 40000.0
#define TRIGGER_MAX 65535.0
#define TRIGGER_MIN_SPAN 50.0

struct key_def {
    const char *name;
    /* offset into struct calibration; axis keys use axis, trigger keys the
     * two scalars below. */
    int axis; /* 0..3 for lx/ly/rx/ry, -1 for l2/r2 */
    int which; /* 0 = center/min (l2_min), 1 = max */
};

#define AXIS_KEY(n, a, w) {n, a, w}

static const struct key_def key_defs[CALIB_KEYS] = {
    AXIS_KEY("left_x_center", 0, 0),
    AXIS_KEY("left_x_min", 0, -1),
    AXIS_KEY("left_x_max", 0, 1),
    AXIS_KEY("left_y_center", 1, 0),
    AXIS_KEY("left_y_min", 1, -1),
    AXIS_KEY("left_y_max", 1, 1),
    AXIS_KEY("right_x_center", 2, 0),
    AXIS_KEY("right_x_min", 2, -1),
    AXIS_KEY("right_x_max", 2, 1),
    AXIS_KEY("right_y_center", 3, 0),
    AXIS_KEY("right_y_min", 3, -1),
    AXIS_KEY("right_y_max", 3, 1),
    AXIS_KEY("l2_min", -1, 0),
    AXIS_KEY("l2_max", -1, 1),
    AXIS_KEY("r2_min", -2, 0),
    AXIS_KEY("r2_max", -2, 1),
};

const char *cal_source_name(enum cal_source source)
{
    switch (source) {
    case CAL_SOURCE_USER:
        return "user";
    case CAL_SOURCE_FACTORY:
        return "factory";
    case CAL_SOURCE_DEFAULT:
    default:
        return "default";
    }
}

const char *calib_user_path(void)
{
#ifdef ASHFALL_HOST
    static char path[PATH_MAX];
    const char *dir = getenv("ASHFALL_CAL_DIR");

    if (dir && dir[0]) {
        snprintf(path, sizeof(path), "%s/joys", dir);
        return path;
    }
#endif
    return CALIB_USER_PATH;
}

const char *calib_factory_path(void)
{
#ifdef ASHFALL_HOST
    static char path[PATH_MAX];
    const char *dir = getenv("ASHFALL_CAL_DIR");

    if (dir && dir[0]) {
        snprintf(path, sizeof(path), "%s/joys.factory", dir);
        return path;
    }
#endif
    return CALIB_FACTORY_PATH;
}

void calib_defaults(struct calibration *out)
{
    memset(out, 0, sizeof(*out));
    out->lx = (struct calib_axis){125.0, -1134.0, 1196.0};
    out->ly = (struct calib_axis){31.0, -1146.0, 1174.0};
    out->rx = (struct calib_axis){82.0, -1121.0, 1218.0};
    out->ry = (struct calib_axis){-6.0, -1167.0, 1192.0};
    out->l2_min = 92.0;
    out->l2_max = 1266.0;
    out->r2_min = 0.0;
    out->r2_max = 1362.0;
}

static bool validate_axis(const char *name, const struct calib_axis *axis,
                          char *err, size_t errlen)
{
    double span = axis->max - axis->min;

    if (!(axis->min < axis->center && axis->center < axis->max)) {
        if (err && errlen)
            snprintf(err, errlen, "%s: need min < center < max", name);
        return false;
    }
    if (fabs(axis->min) > STICK_LIMIT || fabs(axis->max) > STICK_LIMIT ||
        fabs(axis->center) > STICK_LIMIT) {
        if (err && errlen)
            snprintf(err, errlen, "%s: values out of range", name);
        return false;
    }
    if (span < STICK_MIN_SPAN || span > STICK_MAX_SPAN) {
        if (err && errlen)
            snprintf(err, errlen, "%s: span %.1f out of range", name, span);
        return false;
    }
    return true;
}

bool calib_validate(const struct calibration *cal, char *err, size_t errlen)
{
    if (!validate_axis("left_x", &cal->lx, err, errlen) ||
        !validate_axis("left_y", &cal->ly, err, errlen) ||
        !validate_axis("right_x", &cal->rx, err, errlen) ||
        !validate_axis("right_y", &cal->ry, err, errlen))
        return false;

    if (!(cal->l2_min < cal->l2_max)) {
        if (err && errlen)
            snprintf(err, errlen, "l2: need min < max");
        return false;
    }
    if (!(cal->r2_min < cal->r2_max)) {
        if (err && errlen)
            snprintf(err, errlen, "r2: need min < max");
        return false;
    }
    if (cal->l2_min < 0 || cal->l2_max > TRIGGER_MAX ||
        cal->l2_max - cal->l2_min < TRIGGER_MIN_SPAN) {
        if (err && errlen)
            snprintf(err, errlen, "l2: values out of range");
        return false;
    }
    if (cal->r2_min < 0 || cal->r2_max > TRIGGER_MAX ||
        cal->r2_max - cal->r2_min < TRIGGER_MIN_SPAN) {
        if (err && errlen)
            snprintf(err, errlen, "r2: values out of range");
        return false;
    }
    return true;
}

static struct calib_axis *axis_ptr(struct calibration *cal, int index)
{
    switch (index) {
    case 0:
        return &cal->lx;
    case 1:
        return &cal->ly;
    case 2:
        return &cal->rx;
    default:
        return &cal->ry;
    }
}

static bool set_key(struct calibration *cal, const char *key, double value,
                    char *err, size_t errlen)
{
    for (int i = 0; i < CALIB_KEYS; i++) {
        const struct key_def *def = &key_defs[i];
        struct calib_axis *axis;
        double *slot;

        if (strcmp(key, def->name))
            continue;
        if (def->axis >= 0) {
            axis = axis_ptr(cal, def->axis);
            slot = def->which == 0 ? &axis->center
                                   : def->which < 0 ? &axis->min : &axis->max;
        } else {
            double *base = def->axis == -1 ? &cal->l2_min : &cal->r2_min;

            slot = def->which == 0 ? base : base + 1;
        }
        *slot = value;
        return true;
    }
    if (err && errlen)
        snprintf(err, errlen, "unknown key '%s'", key);
    return false;
}

static bool get_key(const struct calibration *cal, int index, double *value)
{
    const struct key_def *def;
    const struct calib_axis *axis;

    if (index < 0 || index >= CALIB_KEYS)
        return false;
    def = &key_defs[index];
    if (def->axis >= 0) {
        axis = def->axis == 0 ? &cal->lx
               : def->axis == 1 ? &cal->ly
               : def->axis == 2 ? &cal->rx
                                : &cal->ry;
        *value = def->which == 0 ? axis->center
                                 : def->which < 0 ? axis->min : axis->max;
        return true;
    }
    if (def->axis == -1)
        *value = def->which == 0 ? cal->l2_min : cal->l2_max;
    else
        *value = def->which == 0 ? cal->r2_min : cal->r2_max;
    return true;
}

bool calib_load_file(const char *path, struct calibration *out, char *err,
                     size_t errlen)
{
    struct calibration cal;
    bool seen[CALIB_KEYS] = {false};
    char line[160];
    FILE *file;
    int lineno = 0;

    memset(&cal, 0, sizeof(cal));
    file = fopen(path, "r");
    if (!file) {
        if (err && errlen)
            snprintf(err, errlen, "cannot open %s: %s", path, strerror(errno));
        return false;
    }

    while (fgets(line, sizeof(line), file)) {
        char *eq;
        char *end = NULL;
        double value;

        lineno++;
        eq = strchr(line, '=');
        if (!eq)
            continue; /* blank line or comment */
        *eq = '\0';
        errno = 0;
        value = strtod(eq + 1, &end);
        if (errno != 0 || end == eq + 1 || !isfinite(value)) {
            if (err && errlen)
                snprintf(err, errlen, "%s:%d: bad value", path, lineno);
            fclose(file);
            return false;
        }
        /* trim the key */
        {
            char *k = line;
            char *kend;

            while (*k == ' ' || *k == '\t')
                k++;
            kend = k + strlen(k);
            while (kend > k && (kend[-1] == ' ' || kend[-1] == '\t'))
                *--kend = '\0';
            for (int i = 0; i < CALIB_KEYS; i++) {
                if (strcmp(k, key_defs[i].name))
                    continue;
                if (seen[i]) {
                    if (err && errlen)
                        snprintf(err, errlen, "%s:%d: duplicate key '%s'", path,
                                 lineno, k);
                    fclose(file);
                    return false;
                }
                if (!set_key(&cal, k, value, err, errlen)) {
                    fclose(file);
                    return false;
                }
                seen[i] = true;
                break;
            }
        }
    }
    fclose(file);

    for (int i = 0; i < CALIB_KEYS; i++) {
        if (!seen[i]) {
            if (err && errlen)
                snprintf(err, errlen, "%s: missing key '%s'", path,
                         key_defs[i].name);
            return false;
        }
    }
    if (!calib_validate(&cal, err, errlen))
        return false;
    *out = cal;
    return true;
}

/* File form: one key=value per line (the stock `joys` layout). */
static void calib_format_lines(const struct calibration *cal, char *buf,
                               size_t len)
{
    size_t used = 0;

    if (!buf || len == 0)
        return;
    buf[0] = '\0';
    for (int i = 0; i < CALIB_KEYS && used + 1 < len; i++) {
        double value;
        int n;

        if (!get_key(cal, i, &value))
            continue;
        n = snprintf(buf + used, len - used, "%s=%.1f\n", key_defs[i].name,
                     value);
        if (n < 0)
            break;
        used += (size_t)n;
    }
}

bool calib_save_file(const char *path, const struct calibration *cal, char *err,
                     size_t errlen)
{
    char tmp[PATH_MAX];
    char body[1024];
    size_t used = 0;
    int fd;

    if (!calib_validate(cal, err, errlen))
        return false;

    calib_format_lines(cal, body, sizeof(body));
    if (snprintf(tmp, sizeof(tmp), "%s.tmp", path) >= (int)sizeof(tmp)) {
        if (err && errlen)
            snprintf(err, errlen, "path too long");
        return false;
    }

    fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        if (err && errlen)
            snprintf(err, errlen, "cannot create %s: %s", tmp, strerror(errno));
        return false;
    }
    used = strlen(body);
    if (write(fd, body, used) != (ssize_t)used) {
        if (err && errlen)
            snprintf(err, errlen, "cannot write %s: %s", tmp, strerror(errno));
        close(fd);
        unlink(tmp);
        return false;
    }
    if (fsync(fd) != 0) {
        if (err && errlen)
            snprintf(err, errlen, "fsync %s: %s", tmp, strerror(errno));
        close(fd);
        unlink(tmp);
        return false;
    }
    close(fd);

    if (rename(tmp, path) != 0) {
        if (err && errlen)
            snprintf(err, errlen, "cannot replace %s: %s", path,
                     strerror(errno));
        unlink(tmp);
        return false;
    }
    return true;
}

void calib_format(const struct calibration *cal, char *buf, size_t len)
{
    size_t used = 0;

    if (!buf || len == 0)
        return;
    buf[0] = '\0';
    for (int i = 0; i < CALIB_KEYS && used + 1 < len; i++) {
        double value;
        int n;

        if (!get_key(cal, i, &value))
            continue;
        n = snprintf(buf + used, len - used, "%s%s=%.1f", i ? " " : "",
                     key_defs[i].name, value);
        if (n < 0)
            break;
        used += (size_t)n;
    }
}

bool calib_parse_pairs(int count, char *const pairs[], struct calibration *out,
                       char *err, size_t errlen)
{
    struct calibration cal;
    bool seen[CALIB_KEYS] = {false};

    calib_defaults(&cal);
    for (int i = 0; i < count; i++) {
        char key[64];
        const char *eq;
        char *end = NULL;
        double value;
        size_t key_len;
        int matched = -1;

        if (!pairs[i]) {
            if (err && errlen)
                snprintf(err, errlen, "empty argument");
            return false;
        }
        eq = strchr(pairs[i], '=');
        if (!eq) {
            if (err && errlen)
                snprintf(err, errlen, "expected key=value, got '%s'", pairs[i]);
            return false;
        }
        key_len = (size_t)(eq - pairs[i]);
        if (key_len == 0 || key_len >= sizeof(key)) {
            if (err && errlen)
                snprintf(err, errlen, "bad key in '%s'", pairs[i]);
            return false;
        }
        memcpy(key, pairs[i], key_len);
        key[key_len] = '\0';
        errno = 0;
        value = strtod(eq + 1, &end);
        if (errno != 0 || end == eq + 1 || !isfinite(value)) {
            if (err && errlen)
                snprintf(err, errlen, "bad value for '%s'", key);
            return false;
        }
        for (int k = 0; k < CALIB_KEYS; k++) {
            if (!strcmp(key, key_defs[k].name)) {
                matched = k;
                break;
            }
        }
        if (matched < 0) {
            if (err && errlen)
                snprintf(err, errlen, "unknown key '%s'", key);
            return false;
        }
        if (seen[matched]) {
            if (err && errlen)
                snprintf(err, errlen, "duplicate key '%s'", key);
            return false;
        }
        if (!set_key(&cal, key, value, err, errlen))
            return false;
        seen[matched] = true;
    }

    for (int i = 0; i < CALIB_KEYS; i++) {
        if (!seen[i]) {
            if (err && errlen)
                snprintf(err, errlen, "missing key '%s'", key_defs[i].name);
            return false;
        }
    }
    if (!calib_validate(&cal, err, errlen))
        return false;
    *out = cal;
    return true;
}

enum cal_source calib_load(struct calibration *out)
{
    struct calibration cal;
    char err[160];

    if (calib_load_file(calib_user_path(), &cal, err, sizeof(err))) {
        *out = cal;
        ah_logf(AH_LOG_INFO, "calibration: user file (%s)", calib_user_path());
        return CAL_SOURCE_USER;
    }
    if (calib_load_file(calib_factory_path(), &cal, err, sizeof(err))) {
        *out = cal;
        ah_logf(AH_LOG_INFO, "calibration: factory copy (%s)",
                calib_factory_path());
        return CAL_SOURCE_FACTORY;
    }
    calib_defaults(&cal);
    *out = cal;
    ah_logf(AH_LOG_INFO, "calibration: built-in defaults (%s)", err);
    return CAL_SOURCE_DEFAULT;
}
