/*
 * ashfall-hwd - AshFall RP5 hardware daemon: fan and stick lighting.
 *
 * The AshFall Parts app only writes persist.ashfall.* properties; this daemon
 * is the single writer of the hardware.  It applies settings at boot and at
 * runtime, keeps the fan inside the measured useful duty range, runs the
 * always-on overheat safety override and publishes ashfall.status.* values.
 *
 * Contract: docs/phase5.md §2.  Device notes: docs/rp5/hw.md.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "fan.h"
#include "led.h"
#include "plat.h"
#include "thermal.h"

#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define PROP_FAN_MODE "persist.ashfall.fan.mode"
#define PROP_FAN_MANUAL "persist.ashfall.fan.manual_pct"
#define PROP_FAN_CURVE "persist.ashfall.fan.curve"
#define PROP_FAN_SAFETY "persist.ashfall.fan.safety_c"

#define PROP_LED_MODE "persist.ashfall.led.mode"
#define PROP_LED_LEFT "persist.ashfall.led.color_left"
#define PROP_LED_RIGHT "persist.ashfall.led.color_right"
#define PROP_LED_SYNC "persist.ashfall.led.sync"
#define PROP_LED_BRIGHTNESS "persist.ashfall.led.brightness"
#define PROP_LED_SPEED "persist.ashfall.led.speed"
#define PROP_LED_SCREEN_OFF "persist.ashfall.led.screen_off"

#define STATUS_FAN_TEMP "ashfall.status.fan.temp"
#define STATUS_FAN_PCT "ashfall.status.fan.pct"
#define STATUS_FAN_RPM "ashfall.status.fan.rpm"
#define STATUS_FAN_SAFETY "ashfall.status.fan.safety"

#define STATUS_INTERVAL_MS 2000
#define IDLE_WAIT_MS 500

static volatile sig_atomic_t g_running = 1;

static void on_signal(int sig)
{
    (void)sig;
    g_running = 0;
}

struct status_cache {
    int temp;
    int pct;
    int rpm;
    int safety;
    bool valid;
};

static int clamp_int(int value, int lo, int hi)
{
    return value < lo ? lo : value > hi ? hi : value;
}

static void read_fan_settings(fan_settings *out, bool *changed)
{
    fan_settings next;
    fan_settings defaults;
    char buf[FAN_CURVE_MAX_LEN + 1];

    fan_settings_default(&defaults);
    next = defaults;

    if (prop_get_str(PROP_FAN_MODE, buf, sizeof(buf)) > 0) {
        fan_mode mode;

        if (fan_parse_mode(buf, &mode))
            next.mode = mode;
    }
    next.manual_pct =
        clamp_int(prop_get_int(PROP_FAN_MANUAL, defaults.manual_pct), 0, 100);
    next.safety_c =
        clamp_int(prop_get_int(PROP_FAN_SAFETY, defaults.safety_c), 70, 95);
    if (prop_get_str(PROP_FAN_CURVE, buf, sizeof(buf)) > 0) {
        fan_curve curve;

        if (fan_parse_curve(buf, &curve))
            next.curve = curve;
    }

    if (memcmp(&next, out, sizeof(next)) != 0) {
        *out = next;
        *changed = true;
    }
}

static void read_led_settings(led_settings *out, bool *changed)
{
    led_settings next;
    led_settings defaults;
    char buf[64];
    rgb_t colour;

    led_settings_default(&defaults);
    next = defaults;

    if (prop_get_str(PROP_LED_MODE, buf, sizeof(buf)) > 0) {
        led_mode mode;

        if (led_parse_mode(buf, &mode))
            next.mode = mode;
    }
    if (prop_get_str(PROP_LED_LEFT, buf, sizeof(buf)) > 0 &&
        led_parse_colour(buf, &colour))
        next.left = colour;
    if (prop_get_str(PROP_LED_RIGHT, buf, sizeof(buf)) > 0 &&
        led_parse_colour(buf, &colour))
        next.right = colour;
    next.sync = prop_get_bool(PROP_LED_SYNC, defaults.sync);
    next.brightness =
        clamp_int(prop_get_int(PROP_LED_BRIGHTNESS, defaults.brightness), 0,
                  100);
    next.speed = clamp_int(prop_get_int(PROP_LED_SPEED, defaults.speed), 1, 10);
    if (prop_get_str(PROP_LED_SCREEN_OFF, buf, sizeof(buf)) > 0)
        next.screen_off_keep = !strcmp(buf, "keep");

    if (memcmp(&next, out, sizeof(next)) != 0) {
        *out = next;
        *changed = true;
    }
}

/* Display state published by framework DisplayPowerController: 1 = off,
 * 2 = on, 3/4 = doze, 5 = VR, 6 = on (suspended).  Anything else (or a
 * missing property, e.g. very early boot) is treated as "screen on". */
static bool screen_is_on(void)
{
    char buf[16];
    int state;

    if (prop_get_str("debug.tracing.screen_state", buf, sizeof(buf)) < 0)
        return true;
    state = atoi(buf);
    return state != 1;
}

static void publish_status(const fan_state *fan, int temp_tenths,
                           struct status_cache *cache, int64_t *next_ms,
                           int64_t now)
{
    struct status_cache next;

    next.temp = temp_tenths;
    next.pct = fan->out_pct < 0 ? 0 : fan->out_pct;
    next.rpm = fan->rpm;
    next.safety = fan->safety ? 1 : 0;
    next.valid = true;

    if (!cache->valid || cache->temp != next.temp)
        prop_set_int(STATUS_FAN_TEMP, next.temp);
    if (!cache->valid || cache->pct != next.pct)
        prop_set_int(STATUS_FAN_PCT, next.pct);
    if (!cache->valid || cache->rpm != next.rpm)
        prop_set_int(STATUS_FAN_RPM, next.rpm);
    if (!cache->valid || cache->safety != next.safety)
        prop_set_int(STATUS_FAN_SAFETY, next.safety);

    *cache = next;
    *next_ms = now + STATUS_INTERVAL_MS;
}

int main(void)
{
    struct sigaction sa;
    thermal_set thermal;
    fan_state fan;
    led_state led;
    struct status_cache status;
    int64_t now;
    int64_t next_fan_ms;
    int64_t next_led_ms = 0;
    int64_t next_status_ms = 0;
    int last_temp = -1;
    bool led_on = false;
    bool force_led = true;

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal; /* no SA_RESTART: futex waits are interrupted */
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    ah_log_set_tag("ashfall-hwd");
    ah_logf(AH_LOG_INFO, "starting (fan=%s led=%s)", FAN_SYSFS_DIR,
            "/sys/class/sn3112[lr]/led");

    fan_state_init(&fan);
    now = ah_now_ms();
    led_state_init(&led, now);
    thermal_scan(&thermal);
    memset(&status, 0, sizeof(status));

    /* Apply the persisted settings once at boot: the daemon is the only
     * writer of the fan/LED nodes, so nothing else restores them. */
    {
        bool changed = false;

        read_fan_settings(&fan.settings, &changed);
        read_led_settings(&led.settings, &changed);
    }
    last_temp = thermal_hottest_tenths(&thermal);
    fan_control_tick(&fan, last_temp, now);
    next_fan_ms = now + FAN_SAMPLE_MS;
    publish_status(&fan, last_temp, &status, &next_status_ms, now);

    while (g_running) {
        bool changed = false;
        bool fan_due;
        bool led_due;
        bool have_raw = false;
        int wait_ms;
        int64_t deadline;

        now = ah_now_ms();
        read_fan_settings(&fan.settings, &changed);
        {
            led_settings before = led.settings;

            read_led_settings(&led.settings, &changed);
            if (memcmp(&before, &led.settings, sizeof(before)) != 0)
                force_led = true;
        }
        if (changed)
            force_led = true;

        fan_due = now >= next_fan_ms;
        if (fan_due) {
            last_temp = thermal_hottest_tenths(&thermal);
            fan_control_tick(&fan, last_temp, now);
            next_fan_ms = now + FAN_SAMPLE_MS;
        } else if (changed) {
            /* A settings change is applied immediately, using the last
             * temperature sample. */
            fan_control_tick(&fan, last_temp, now);
        } else if (fan.kick_until_ms > 0 && now >= fan.kick_until_ms) {
            /* The spin-up kick is over: drop to the requested speed. */
            fan_apply_pct(&fan, fan.pending_pct, now);
        }

        /* --- lighting ---------------------------------------------------- */
        have_raw = led.settings.mode != LED_MODE_OFF &&
                   led.backend != LED_BACKEND_NONE;
        led_due = have_raw && led_is_animated(&led) && now >= next_led_ms;
        {
            bool want_on = have_raw && (screen_is_on() ||
                                        led.settings.screen_off_keep);

            if (force_led || led_due) {
                rgb_t frame[2];

                led_frame(&led, now, frame);
                led_apply(&led, frame, want_on);
                led_on = want_on;
                force_led = false;
                if (have_raw && led_is_animated(&led) && want_on)
                    next_led_ms = now + LED_FRAME_MS;
                else
                    next_led_ms = 0;
            } else if (want_on != led_on) {
                rgb_t frame[2];

                led_frame(&led, now, frame);
                led_apply(&led, frame, want_on);
                led_on = want_on;
            }
        }

        /* --- status ------------------------------------------------------ */
        if (now >= next_status_ms) {
            fan.rpm = fan_read_rpm();
            publish_status(&fan, last_temp, &status, &next_status_ms, now);
        }

        /* --- sleep until something happens ------------------------------- */
        deadline = next_fan_ms;
        if (next_status_ms > 0 && next_status_ms < deadline)
            deadline = next_status_ms;
        if (next_led_ms > 0 && next_led_ms < deadline)
            deadline = next_led_ms;
        if (fan.kick_until_ms > 0 && fan.kick_until_ms < deadline)
            deadline = fan.kick_until_ms;
        if (!have_raw || !led_is_animated(&led))
            ; /* still sleep: property changes and the fan tick wake us */
        wait_ms = (int)(deadline - now);
        if (wait_ms > IDLE_WAIT_MS)
            wait_ms = IDLE_WAIT_MS;
        if (wait_ms < 0)
            wait_ms = 0;
        prop_wait_any(wait_ms);
    }

    ah_logf(AH_LOG_INFO, "stopping");
    led_disable(&led);
    /* Leave the fan in a defined state; the safety override lives only as
     * long as this daemon. 100 % would be the safe choice, but a stopped
     * daemon plus a spinning fan at 100 % is worse than the stock default. */
    fan_apply_pct(&fan, 0, ah_now_ms());
    return 0;
}
