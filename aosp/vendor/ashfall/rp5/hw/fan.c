/*
 * AshFall RP5 fan control: modes, smart curve, smoothing, hysteresis and the
 * always-on safety override.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "fan.h"
#include "plat.h"

#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DEFAULT_CURVE "45:0,55:30,65:55,75:80,82:100"

void fan_settings_default(fan_settings *out)
{
    memset(out, 0, sizeof(*out));
    out->mode = FAN_MODE_SMART;
    out->manual_pct = 50;
    out->kick = true;
    out->kick_pct = FAN_KICK_DEFAULT_PCT;
    out->off_when_screen_off = true;
    if (!fan_parse_curve(DEFAULT_CURVE, &out->curve)) {
        /* Cannot happen: the literal above is valid. */
        out->curve.count = 2;
        out->curve.point[0] = (fan_point){45, 0};
        out->curve.point[1] = (fan_point){82, 100};
    }
}

static const struct {
    const char *name;
    fan_mode mode;
} mode_names[] = {
    {"off", FAN_MODE_OFF},
    {"quiet", FAN_MODE_QUIET},
    {"balanced", FAN_MODE_BALANCED},
    {"max", FAN_MODE_MAX},
    {"manual", FAN_MODE_MANUAL},
    {"smart", FAN_MODE_SMART},
};

bool fan_parse_mode(const char *name, fan_mode *out)
{
    if (!name)
        return false;
    for (size_t i = 0; i < sizeof(mode_names) / sizeof(mode_names[0]); i++) {
        if (!strcmp(name, mode_names[i].name)) {
            *out = mode_names[i].mode;
            return true;
        }
    }
    return false;
}

const char *fan_mode_name(fan_mode mode)
{
    for (size_t i = 0; i < sizeof(mode_names) / sizeof(mode_names[0]); i++) {
        if (mode_names[i].mode == mode)
            return mode_names[i].name;
    }
    return "smart";
}

bool fan_parse_curve(const char *value, fan_curve *out)
{
    fan_curve parsed;
    char buf[128];
    char *save = NULL;
    char *tok;

    memset(&parsed, 0, sizeof(parsed));
    if (!value || !out)
        return false;
    if (strlen(value) >= sizeof(buf))
        return false;
    snprintf(buf, sizeof(buf), "%s", value);

    for (tok = strtok_r(buf, ",", &save); tok;
         tok = strtok_r(NULL, ",", &save)) {
        char *colon;
        char *end = NULL;
        long temp;
        long pct;

        if (parsed.count >= FAN_MAX_POINTS)
            return false;
        colon = strchr(tok, ':');
        if (!colon)
            return false;
        *colon = '\0';
        errno = 0;
        temp = strtol(tok, &end, 10);
        if (errno || end == tok || *end)
            return false;
        errno = 0;
        pct = strtol(colon + 1, &end, 10);
        if (errno || end == colon + 1 || *end)
            return false;
        if (temp < 0 || temp > 120 || pct < 0 || pct > 100)
            return false;
        if (parsed.count > 0 &&
            temp <= parsed.point[parsed.count - 1].temp_c)
            return false; /* temperatures must be strictly ascending */
        parsed.point[parsed.count].temp_c = (int)temp;
        parsed.point[parsed.count].pct = (int)pct;
        parsed.count++;
    }

    if (parsed.count < 2)
        return false;
    *out = parsed;
    return true;
}

void fan_curve_format(const fan_curve *curve, char *buf, size_t len)
{
    size_t used = 0;

    if (!buf || len == 0)
        return;
    buf[0] = '\0';
    for (int i = 0; i < curve->count && used + 1 < len; i++) {
        int n = snprintf(buf + used, len - used, "%s%d:%d",
                         i ? "," : "", curve->point[i].temp_c,
                         curve->point[i].pct);
        if (n < 0)
            break;
        used += (size_t)n;
    }
}

int fan_curve_pct(const fan_curve *curve, double temp_c)
{
    int i;

    if (curve->count == 0)
        return 0;
    if (temp_c <= curve->point[0].temp_c)
        return curve->point[0].pct;
    if (temp_c >= curve->point[curve->count - 1].temp_c)
        return curve->point[curve->count - 1].pct;

    for (i = 1; i < curve->count; i++) {
        const fan_point *lo = &curve->point[i - 1];
        const fan_point *hi = &curve->point[i];
        double span;
        double pct;

        if (temp_c > hi->temp_c)
            continue;
        span = (double)(hi->temp_c - lo->temp_c);
        if (span <= 0)
            return hi->pct;
        pct = lo->pct + (temp_c - lo->temp_c) * (hi->pct - lo->pct) / span;
        return (int)lround(pct);
    }
    return curve->point[curve->count - 1].pct;
}

int fan_pct_to_duty(int pct)
{
    if (pct <= 0)
        return 0;
    if (pct > 100)
        pct = 100;
    return FAN_DUTY_MIN_NS +
           (FAN_DUTY_MAX_NS - FAN_DUTY_MIN_NS) * pct / 100;
}

int fan_duty_to_pct(int duty_ns)
{
    if (duty_ns <= 0)
        return 0;
    if (duty_ns <= FAN_DUTY_MIN_NS)
        return 1;
    if (duty_ns >= FAN_DUTY_MAX_NS)
        return 100;
    return 1 + (duty_ns - FAN_DUTY_MIN_NS) * 99 /
                   (FAN_DUTY_MAX_NS - FAN_DUTY_MIN_NS);
}

void fan_state_init(fan_state *state)
{
    memset(state, 0, sizeof(*state));
    fan_settings_default(&state->settings);
    state->out_pct = -1;
    state->anchor_c = -1;
    state->rpm = -1;
}

static int preset_pct(fan_mode mode)
{
    switch (mode) {
    case FAN_MODE_QUIET:
        return FAN_PRESET_QUIET_PCT;
    case FAN_MODE_BALANCED:
        return FAN_PRESET_BALANCED_PCT;
    case FAN_MODE_MAX:
        return FAN_PRESET_MAX_PCT;
    default:
        return 0;
    }
}

int fan_apply_pct(fan_state *state, int pct, int64_t now_ms)
{
    int duty;
    int out;

    if (pct < 0)
        pct = 0;
    if (pct > 100)
        pct = 100;

    if (pct == 0) {
        if (state->out_pct != 0) {
            int state_rc, duty_rc;

            /* state=0 is the safe half and the two attributes are
             * independent: stop first, then zero the duty, and attempt both
             * even when the other fails. */
            state_rc = sysfs_write_int(FAN_STATE_NODE, 0);
            duty_rc = sysfs_write_int(FAN_DUTY_NODE, 0);
            if (state_rc != 0 || duty_rc != 0) {
                /* Unknown state: try again on the next tick. */
                state->out_pct = -1;
                return 0;
            }
            state->out_pct = 0;
        }
        state->kick_until_ms = 0;
        return 0;
    }

    /* Spin the fan up for a moment: the motor does not start reliably at a
     * low duty.  Only a 0 -> nonzero transition needs it.  The kick level is
     * persist.ashfall.fan.kick_pct (default 30 %), never 100 %, and
     * persist.ashfall.fan.kick=0 disables it entirely. */
    if (state->out_pct <= 0) {
        int kick_pct = pct;

        if (state->settings.kick) {
            int configured = state->settings.kick_pct;

            if (configured < FAN_KICK_MIN_PCT)
                configured = FAN_KICK_MIN_PCT;
            if (configured > FAN_KICK_MAX_PCT)
                configured = FAN_KICK_MAX_PCT;
            /* The safety override must reach 100 % at once; a reduced kick
             * would delay cooling by up to FAN_KICK_MS. */
            if (!state->safety && configured > kick_pct)
                kick_pct = configured;
        }
        /* The target itself is never clamped: 61..100 % must run at their
         * real duty (safety/max).  Only the *kick* level is capped at 60. */
        if (sysfs_write_int(FAN_STATE_NODE, 1) != 0 ||
            sysfs_write_int(FAN_DUTY_NODE, fan_pct_to_duty(kick_pct)) != 0) {
            state->out_pct = -1;
            return 0;
        }
        state->pending_pct = pct;
        if (kick_pct > pct) {
            state->kick_until_ms = now_ms + FAN_KICK_MS;
            state->out_pct = kick_pct;
            ah_logf(AH_LOG_INFO, "fan: spin-up kick to %d%% (target %d%%)",
                    kick_pct, pct);
            return kick_pct;
        }
        state->kick_until_ms = 0;
        state->out_pct = pct;
        return pct;
    }

    if (state->kick_until_ms > 0) {
        state->pending_pct = pct;
        if (now_ms < state->kick_until_ms) {
            /* The hardware is still at the kick level; report what is
             * actually applied, not the requested target. */
            return state->out_pct;
        }
        state->kick_until_ms = 0;
    }

    duty = fan_pct_to_duty(pct);
    out = pct;
    if (state->out_pct != out) {
        if (sysfs_write_int(FAN_STATE_NODE, 1) != 0 ||
            sysfs_write_int(FAN_DUTY_NODE, duty) != 0) {
            state->out_pct = -1;
            return 0;
        }
        state->out_pct = out;
    }
    return out;
}

bool fan_kick_pending(const fan_state *state, int64_t now_ms)
{
    return state->kick_until_ms > now_ms;
}

int fan_control_tick(fan_state *state, int temp_tenths, int64_t now_ms,
                     bool screen_on)
{
    fan_settings *set = &state->settings;
    int pct = 0;

    if (temp_tenths < 0) {
        /* No usable CPU/GPU sensor.  Failing safe means cooling, never off:
         * without a temperature there is no way to know whether the SoC is
         * overheating, so latch the safety state and keep the balanced preset
         * running until a sensor is readable again.  The caller re-scans the
         * zones (they can appear after class core starts). */
        if (!state->sensor_fault)
            ah_logf(AH_LOG_WARN,
                    "fan: no thermal sensor data; failing safe at %d%%",
                    FAN_PRESET_BALANCED_PCT);
        state->sensor_fault = true;
        state->safety = true;
        return fan_apply_pct(state, FAN_PRESET_BALANCED_PCT, now_ms);
    }
    if (state->sensor_fault) {
        ah_logf(AH_LOG_INFO, "fan: thermal sensor data available again");
        state->sensor_fault = false;
    }

    /* Fixed last-resort rule (persist.ashfall.fan.safety_c was removed in
     * phase 5 v2): 100 % at >= 90 C, released at <= 80 C, in every mode and
     * also while the screen is off.  Not settable by the UI. */
    if (temp_tenths >= FAN_SAFETY_ON_C * 10) {
        if (!state->safety)
            ah_logf(AH_LOG_WARN, "fan: safety override at %.1f C",
                    temp_tenths / 10.0);
        state->safety = true;
    } else if (state->safety && temp_tenths <= FAN_SAFETY_OFF_C * 10) {
        ah_logf(AH_LOG_INFO, "fan: safety override released at %.1f C",
                temp_tenths / 10.0);
        state->safety = false;
    }

    if (state->safety) {
        pct = 100;
    } else if (!screen_on && set->off_when_screen_off) {
        /* persist.ashfall.fan.screen_off=off: the fan stops with the screen.
         * The next nonzero apply after screen-on runs the spin-up kick. */
        pct = 0;
    } else {
        switch (set->mode) {
        case FAN_MODE_OFF:
            pct = 0;
            break;
        case FAN_MODE_QUIET:
        case FAN_MODE_BALANCED:
        case FAN_MODE_MAX:
            pct = preset_pct(set->mode);
            break;
        case FAN_MODE_MANUAL:
            pct = set->manual_pct;
            break;
        case FAN_MODE_SMART:
        default:
            {
                double temp_c = temp_tenths / 10.0;

                if (!state->have_ema) {
                    state->ema_c = temp_c;
                    state->have_ema = true;
                } else {
                    state->ema_c +=
                        (temp_c - state->ema_c) * FAN_EMA_ALPHA;
                }
                /* Hysteresis on the way down: only lower the target once the
                 * smoothed temperature is a full 3 C below the anchor. */
                if (state->anchor_c < 0 ||
                    state->ema_c >= (double)state->anchor_c ||
                    state->ema_c <= (double)state->anchor_c - FAN_HYSTERESIS_C) {
                    const fan_curve *curve =
                        state->preview_valid ? &state->preview : &set->curve;

                    state->anchor_c = (int)lround(state->ema_c);
                    state->target_pct = fan_curve_pct(curve, state->ema_c);
                }
                pct = state->target_pct;
            }
            break;
        }
    }

    if (pct < 0)
        pct = 0;
    if (pct > 100)
        pct = 100;
    return fan_apply_pct(state, pct, now_ms);
}

int fan_read_rpm(void)
{
    int state;
    int value;

    /*
     * Never touch the tach while the fan is disabled: the stock gpio5_pwm
     * driver's speed_show() calls enable_irq(), and with state=0 the kernel
     * warns "Unbalanced enable for IRQ ..." (seen on the RP5).  A stopped fan
     * reports 0 RPM, so return that without opening the node at all.
     */
    if (sysfs_read_int(FAN_STATE_NODE, &state) < 0)
        return -1; /* enable state unknown: do not risk the WARN */
    if (state == 0)
        return 0;
    if (sysfs_read_int(FAN_SPEED_NODE, &value) < 0)
        return -1;
    if (value < 0)
        return -1;
    return value;
}
