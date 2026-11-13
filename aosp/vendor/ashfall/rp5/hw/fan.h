/*
 * AshFall RP5 fan control (fan + smart curve + safety override).
 *
 * Hardware: /sys/class/gpio5_pwm2/{state,duty,speed}.  `duty` is the PWM high
 * time in nanoseconds, the period is 50 000 ns (20 kHz), `state` enables the
 * fan and `speed` is the fan's tach/FG feedback.  See docs/rp5/hw.md for the
 * measured duty -> RPM range and for the stock values this replaces.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_FAN_H
#define ASHFALL_FAN_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define FAN_SYSFS_DIR "/sys/class/gpio5_pwm2"
#define FAN_STATE_NODE FAN_SYSFS_DIR "/state"
#define FAN_DUTY_NODE FAN_SYSFS_DIR "/duty"
#define FAN_SPEED_NODE FAN_SYSFS_DIR "/speed"

#define FAN_PERIOD_NS 50000

/*
 * Measured useful duty range of the RP5 fan (docs/rp5/hw.md §2, measured on
 * the 2026-11-13 build): the smallest duty at which the fan spins reliably
 * (4 000 ns, 3/3 from rest; 3 700 ns and below stall) and the duty above
 * which the RPM does not rise any more (none below the 50 000 ns period, so
 * the whole PWM range is useful).  Percentages map linearly onto this range,
 * 1 % is the slowest usable speed and 100 % the fastest; 0 % means off.
 */
#define FAN_DUTY_MIN_NS 4000
#define FAN_DUTY_MAX_NS 50000

/* Built-in preset percentages (quiet/balanced/max), from the measurement. */
#define FAN_PRESET_QUIET_PCT 2     /* ~4 920 ns / ~1 800 RPM (stock Quiet 5 000) */
#define FAN_PRESET_BALANCED_PCT 46 /* ~25 160 ns / ~8 100 RPM (stock Sport 25 000) */
#define FAN_PRESET_MAX_PCT 100     /* 50 000 ns / ~15 000 RPM */

/* Smart mode tuning (see docs/phase5.md §2.1). */
#define FAN_SAMPLE_MS 2000
#define FAN_EMA_ALPHA 0.35
#define FAN_HYSTERESIS_C 3
/* A 4 000 ns start from rest was reliable in the measurement (3/3 at ~35 C);
 * the short kick is kept as insurance for a cold unit.  Set to 0 to disable. */
#define FAN_MIN_KICK_MS 500

#define FAN_MAX_POINTS 8
#define FAN_CURVE_MAX_LEN 92 /* persist.* property value limit */

typedef struct {
    int temp_c;
    int pct;
} fan_point;

typedef struct {
    fan_point point[FAN_MAX_POINTS];
    int count;
} fan_curve;

typedef enum {
    FAN_MODE_OFF,
    FAN_MODE_QUIET,
    FAN_MODE_BALANCED,
    FAN_MODE_MAX,
    FAN_MODE_MANUAL,
    FAN_MODE_SMART,
} fan_mode;

typedef struct {
    fan_mode mode;
    int manual_pct; /* 0..100 */
    fan_curve curve;
    int safety_c; /* 70..95 */
} fan_settings;

typedef struct {
    fan_settings settings;

    /* last applied output (0..100, -1 = unknown) */
    int out_pct;
    bool safety;      /* safety override latched */
    double ema_c;     /* smoothed temperature */
    bool have_ema;
    int anchor_c;     /* hysteresis anchor (-1 = none) */
    int target_pct;   /* last smart target */

    /* spin-up kick */
    int pending_pct;
    int64_t kick_until_ms;

    int rpm;
} fan_state;

void fan_settings_default(fan_settings *out);

bool fan_parse_mode(const char *name, fan_mode *out);
const char *fan_mode_name(fan_mode mode);

/* "45:0,55:30,..." -> curve.  Returns false (and leaves *out untouched) when
 * the value is malformed: 2..8 pairs, ascending temperatures, sane ranges. */
bool fan_parse_curve(const char *value, fan_curve *out);
void fan_curve_format(const fan_curve *curve, char *buf, size_t len);
int fan_curve_pct(const fan_curve *curve, double temp_c);

/* Percent -> PWM duty in ns; 0 means "off" (state=0). */
int fan_pct_to_duty(int pct);
int fan_duty_to_pct(int duty_ns);

void fan_state_init(fan_state *state);

/* One control tick (every FAN_SAMPLE_MS): applies the mode, the smart curve
 * and the always-on safety override.  Returns the output percentage that was
 * applied. */
int fan_control_tick(fan_state *state, int temp_tenths, int64_t now_ms);

/* Apply a percentage now (state/duty + spin-up kick).  Returns the output
 * percentage as actually commanded (100 during a kick). */
int fan_apply_pct(fan_state *state, int pct, int64_t now_ms);

/* True when a kick is still running and the daemon must wake up for it. */
bool fan_kick_pending(const fan_state *state, int64_t now_ms);

/* Read the tach/FG node; -1 when it is unreadable. */
int fan_read_rpm(void);

#endif /* ASHFALL_FAN_H */
