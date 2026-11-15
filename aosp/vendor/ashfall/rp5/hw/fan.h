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
/*
 * Spin-up kick (docs/phase5.md §6.1): after the fan was off (mode off, or the
 * screen-off hold) it starts at persist.ashfall.fan.kick_pct instead of 100 %
 * for a short moment, then drops to the requested speed.  A 4 000 ns start
 * from rest was reliable in the measurement, so the kick is insurance for a
 * cold unit; persist.ashfall.fan.kick=0 disables it.
 */
#define FAN_KICK_MS 500
#define FAN_KICK_DEFAULT_PCT 30
#define FAN_KICK_MIN_PCT 10
#define FAN_KICK_MAX_PCT 60

/* Fixed, undocumented last-resort rule (persist.ashfall.fan.safety_c was
 * removed in phase 5 v2).  It is deliberately not a setting: 100 % at or
 * above 90 C, released at 80 C, in every mode and also with the screen off. */
#define FAN_SAFETY_ON_C 90
#define FAN_SAFETY_OFF_C 80

/* Output limits (docs/phase5.md §7.1): the final percentage resolved by the
 * mode, the smart curve or the screen-off hold is cut to 0 below min_pct and
 * capped at max_pct.  min_pct = 0 disables the cut-off, max_pct = 100 the cap.
 * The fixed 90 C rule and the no-sensor fail-safe are exempt. */
#define FAN_MIN_PCT_DEFAULT 5
#define FAN_MAX_PCT_DEFAULT 100

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
    bool kick;      /* spin-up kick enabled */
    int kick_pct;   /* 10..60, the kick level (never 100 %) */
    bool off_when_screen_off; /* fan off while the screen is off */
    int min_pct;    /* 0..100, below it the fan stops (0 = no cut-off) */
    int max_pct;    /* 0..100, never run above it (100 = no cap) */
} fan_settings;

typedef struct {
    fan_settings settings;

    /* ashfall.ui.fan_preview_curve: while valid and the active mode is
     * `smart`, this curve is used instead of settings.curve (live editing in
     * the app). */
    fan_curve preview;
    bool preview_valid;

    /* last applied output (0..100, -1 = unknown) */
    int out_pct;
    bool safety;       /* safety override latched (or no sensor: fail-safe) */
    bool sensor_fault; /* no readable CPU/GPU thermal zone */
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

/* Sanitise a persist.ashfall.fan.{min_pct,max_pct} pair (docs/phase5.md
 * §7.1): out-of-range values and max < min fall back to the defaults, so both
 * come back 5/100.  Returns false when the pair was invalid (the caller logs
 * that once); the two values are corrected either way. */
bool fan_limits_normalise(int *min_pct, int *max_pct);

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

/* One control tick (every FAN_SAMPLE_MS): applies the mode, the smart curve,
 * the screen-off hold, the output limits (min_pct/max_pct) and the always-on
 * safety override.  `screen_on` false holds the fan at 0 % when
 * settings.off_when_screen_off is set; the safety override still runs.
 * Returns the output percentage that was applied.  The safety override and
 * the no-sensor fail-safe bypass the limits. */
int fan_control_tick(fan_state *state, int temp_tenths, int64_t now_ms,
                     bool screen_on);

/* Apply a percentage now (state/duty + spin-up kick).  Returns the output
 * percentage as actually commanded (100 during a kick). */
int fan_apply_pct(fan_state *state, int pct, int64_t now_ms);

/* True when a kick is still running and the daemon must wake up for it. */
bool fan_kick_pending(const fan_state *state, int64_t now_ms);

/* Read the tach/FG node; -1 when it is unreadable. */
int fan_read_rpm(void);

#endif /* ASHFALL_FAN_H */
