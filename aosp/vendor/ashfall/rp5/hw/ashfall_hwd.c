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
#include "gpu.h"
#include "led.h"
#include "plat.h"
#include "thermal.h"
#include "throttle.h"

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
#define PROP_FAN_KICK "persist.ashfall.fan.kick"
#define PROP_FAN_KICK_PCT "persist.ashfall.fan.kick_pct"
#define PROP_FAN_SCREEN_OFF "persist.ashfall.fan.screen_off"

#define PROP_LED_MODE "persist.ashfall.led.mode"
#define PROP_LED_LEFT "persist.ashfall.led.color_left"
#define PROP_LED_RIGHT "persist.ashfall.led.color_right"
#define PROP_LED_SYNC "persist.ashfall.led.sync"
#define PROP_LED_BRIGHTNESS "persist.ashfall.led.brightness"
#define PROP_LED_SPEED "persist.ashfall.led.speed"

#define PROP_UI_FAN_LIVE "ashfall.ui.fan_live"
#define PROP_UI_FAN_PREVIEW "ashfall.ui.fan_preview_curve"
#define PROP_UI_LED_OVERRIDE "ashfall.ui.led_override"

#define PROP_PERF_OVERRIDE "ashfall.perf.throttle_override"
#define PROP_PERF_PERMANENT "persist.ashfall.perf.throttle_override_permanent"
#define PROP_GPU_MAX_MHZ "persist.ashfall.perf.gpu_max_mhz"

#define STATUS_FAN_TEMP "ashfall.status.fan.temp"
#define STATUS_FAN_PCT "ashfall.status.fan.pct"
#define STATUS_FAN_RPM "ashfall.status.fan.rpm"
#define STATUS_FAN_SAFETY "ashfall.status.fan.safety"
#define STATUS_PERF_THROTTLE "ashfall.status.perf.throttle"
#define STATUS_GPU_MAX_MHZ "ashfall.status.perf.gpu_max_mhz"
#define STATUS_GPU_OC "ashfall.status.perf.gpu_oc"

#define STATUS_INTERVAL_MS 2000
/* ashfall.ui.fan_live=1: a fan page/editor/QS dialog is visible, publish the
 * status (and read the tach) four times as often (docs/phase5.md §6.1). */
#define STATUS_LIVE_MS 500
#define IDLE_WAIT_MS 500
/* Retry period for LED writes that failed (vendor init chmod race, node not
 * there yet): lighting must not stay dark for the whole boot. */
#define LED_RETRY_MS 500
/* Full thermal sweep period: re-picks the hottest zones (a few temp reads,
 * see thermal.h for why the daemon does not read all 16 zones per tick). */
#define THERMAL_SWEEP_MS 300000
/* Retry while the kgsl devfreq node is not up yet (vendor init/class core). */
#define GPU_RETRY_MS 5000

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

struct gpu_status_cache {
    int mhz;
    int oc;
    bool valid;
};

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
    /* Out-of-range values are invalid per docs/phase5.md §2 and fall back to
     * the documented default instead of being clamped to the boundary. */
    next.manual_pct =
        prop_get_int_range(PROP_FAN_MANUAL, defaults.manual_pct, 0, 100);
    next.kick = prop_get_bool(PROP_FAN_KICK, defaults.kick);
    next.kick_pct = prop_get_int_range(PROP_FAN_KICK_PCT, defaults.kick_pct,
                                       FAN_KICK_MIN_PCT, FAN_KICK_MAX_PCT);
    if (prop_get_str(PROP_FAN_SCREEN_OFF, buf, sizeof(buf)) > 0)
        next.off_when_screen_off = strcmp(buf, "run") != 0;
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

/* ashfall.ui.fan_preview_curve: live curve editing.  While it is non-empty
 * and valid, smart mode follows it instead of persist.ashfall.fan.curve.  The
 * app clears the property when the editor closes. */
static void read_fan_preview(fan_state *fan, bool *changed)
{
    fan_curve curve;
    char buf[FAN_CURVE_MAX_LEN + 1];
    bool valid = false;

    memset(&curve, 0, sizeof(curve));
    if (prop_get_str(PROP_UI_FAN_PREVIEW, buf, sizeof(buf)) > 0)
        valid = fan_parse_curve(buf, &curve);

    if (valid != fan->preview_valid ||
        (valid && memcmp(&curve, &fan->preview, sizeof(curve)) != 0)) {
        fan->preview = curve;
        fan->preview_valid = valid;
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
        prop_get_int_range(PROP_LED_BRIGHTNESS, defaults.brightness, 0, 100);
    next.speed = prop_get_int_range(PROP_LED_SPEED, defaults.speed, 1, 10);

    /* ashfall.ui.led_override="RRGGBB:RRGGBB" (left:right, final colours incl.
     * intensity), used by the gamepad tester.  Empty = normal lighting. */
    next.override_valid = false;
    if (prop_get_str(PROP_UI_LED_OVERRIDE, buf, sizeof(buf)) > 0 &&
        strlen(buf) == 13 && buf[6] == ':') {
        char left[7];
        char right[7];
        rgb_t l;
        rgb_t r;

        memcpy(left, buf, 6);
        left[6] = '\0';
        memcpy(right, buf + 7, 6);
        right[6] = '\0';
        if (led_parse_colour(left, &l) && led_parse_colour(right, &r)) {
            next.override[0] = l;
            next.override[1] = r;
            next.override_valid = true;
        }
    }

    if (memcmp(&next, out, sizeof(next)) != 0) {
        *out = next;
        *changed = true;
    }
}

/* ashfall.perf.throttle_override is non-persistent.  Returns true when the
 * property is present; *requested is its value. */
static bool perf_override_requested(bool *requested)
{
    char buf[16];

    if (prop_get_str(PROP_PERF_OVERRIDE, buf, sizeof(buf)) <= 0)
        return false;
    *requested = !strcmp(buf, "1");
    return true;
}

static void publish_gpu_status(const gpu_state *gpu,
                               struct gpu_status_cache *cache)
{
    int mhz = gpu_status_mhz(gpu);
    int oc = gpu_status_oc(gpu);

    if (!cache->valid || cache->mhz != mhz) {
        prop_set_int(STATUS_GPU_MAX_MHZ, mhz);
        cache->mhz = mhz;
    }
    if (!cache->valid || cache->oc != oc) {
        prop_set_int(STATUS_GPU_OC, oc);
        cache->oc = oc;
    }
    cache->valid = true;
}

static void publish_throttle_status(const throttle_state *throttle,
                                    int *cache)
{
    int value = throttle_status(throttle);

    if (value != *cache) {
        prop_set_int(STATUS_PERF_THROTTLE, value);
        *cache = value;
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
    int interval = prop_get_bool(PROP_UI_FAN_LIVE, false) ? STATUS_LIVE_MS
                                                          : STATUS_INTERVAL_MS;

    next.temp = temp_tenths;
    next.pct = fan->out_pct; /* -1 = unknown (sysfs write failed) */
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
    *next_ms = now + interval;
}

int main(void)
{
    struct sigaction sa;
    thermal_set thermal;
    fan_state fan;
    led_state led;
    led_screen screen;
    throttle_state throttle;
    gpu_state gpu;
    struct status_cache status;
    struct gpu_status_cache gpu_status;
    int throttle_status_cache = -2; /* force the first publish */
    int gpu_request = GPU_STOCK_MHZ;
    bool gpu_override = false;
    int64_t next_gpu_ms = 0;
    int64_t now;
    int64_t next_fan_ms;
    int64_t next_led_ms = 0;
    int64_t next_status_ms = 0;
    int64_t next_thermal_ms;
    int last_temp = -1;
    bool force_led = true;
    bool screen_on = screen_is_on();
    bool led_want_on = false;
    bool fan_live = prop_get_bool(PROP_UI_FAN_LIVE, false);

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
    led_screen_init(&screen, screen_on, now);
    thermal_scan(&thermal);
    memset(&status, 0, sizeof(status));
    memset(&gpu_status, 0, sizeof(gpu_status));
    gpu_state_init(&gpu);

    /* Apply the persisted settings once at boot: the daemon is the only
     * writer of the fan/LED nodes, so nothing else restores them. */
    {
        bool changed = false;

        read_fan_settings(&fan.settings, &changed);
        read_fan_preview(&fan, &changed);
        read_led_settings(&led.settings, &changed);
    }
    last_temp = thermal_hottest_tenths(&thermal);
    next_thermal_ms = now + THERMAL_SWEEP_MS;
    fan_control_tick(&fan, last_temp, now, screen_on);
    next_fan_ms = now + FAN_SAMPLE_MS;
    publish_status(&fan, last_temp, &status, &next_status_ms, now);

    /* Performance override: the non-persistent property wins when present (a
     * service restart in the same boot), otherwise the hidden permanent
     * switch decides whether it is applied at this boot. */
    throttle_scan(&throttle);
    {
        bool requested = false;

        if (!perf_override_requested(&requested))
            requested = prop_get_bool(PROP_PERF_PERMANENT, false);
        if (requested || throttle.active)
            throttle_set(&throttle, requested);
        publish_throttle_status(&throttle, &throttle_status_cache);
    }

    /* GPU cap: apply the persisted request once at boot, clamped to the DT
     * table and to 670 MHz while the throttle override is active. */
    gpu_request = prop_get_int_range(PROP_GPU_MAX_MHZ, GPU_STOCK_MHZ,
                                     GPU_MIN_MHZ, GPU_MAX_MHZ);
    gpu_override = throttle.active;
    gpu_apply(&gpu, gpu_request, gpu_override);
    publish_gpu_status(&gpu, &gpu_status);
    next_gpu_ms = now + (gpu.available && gpu.applied ? 0 : GPU_RETRY_MS);

    while (g_running) {
        bool changed = false;
        bool fan_due;
        bool led_due;
        bool have_raw;
        bool want_on;
        bool led_reapply;
        int wait_ms;
        int64_t deadline;
        int64_t screen_ms;

        now = ah_now_ms();
        read_fan_settings(&fan.settings, &changed);
        read_fan_preview(&fan, &changed);
        {
            led_settings before = led.settings;

            read_led_settings(&led.settings, &changed);
            if (memcmp(&before, &led.settings, sizeof(before)) != 0)
                force_led = true;
        }
        if (changed)
            force_led = true;

        /* Screen transitions are property changes, so prop_wait_any() wakes
         * us; both the fan hold and the LED re-apply below follow from it.
         * The decision is debounced: debug.tracing.screen_state can flicker
         * during the power/keyguard transition, and the HTR3212 controllers
         * are not ready the instant the panel comes back (issue #109). */
        {
            bool woke = false;

            if (led_screen_update(&screen, screen_is_on(), now, &woke)) {
                screen_on = led_screen_on(&screen);
                changed = true;
                force_led = true;
                if (woke) {
                    /* The lighting block below rewrites both attributes now,
                     * at +0.5 s and at +1.5 s, regardless of led.applied. */
                    ah_logf(AH_LOG_INFO,
                            "screen on: re-applying LED/fan settings");
                } else {
                    ah_logf(AH_LOG_INFO,
                            "screen off: holding fan and LEDs");
                }
            }
        }

        /* --- performance override ---------------------------------------- */
        {
            bool requested = false;

            if (perf_override_requested(&requested) &&
                requested != throttle.active)
                throttle_set(&throttle, requested);
            publish_throttle_status(&throttle, &throttle_status_cache);
        }

        /* --- GPU cap ------------------------------------------------------
         * Re-evaluated when the property or the throttle override changes,
         * and retried while the kgsl devfreq node is not available yet. */
        {
            int request = prop_get_int_range(PROP_GPU_MAX_MHZ, GPU_STOCK_MHZ,
                                             GPU_MIN_MHZ, GPU_MAX_MHZ);
            bool override = throttle.active;
            bool retry = (!gpu.available || !gpu.applied) && now >= next_gpu_ms;

            if (request != gpu_request || override != gpu_override || retry) {
                gpu_apply(&gpu, request, override);
                gpu_request = request;
                gpu_override = override;
                next_gpu_ms = now + ((gpu.available && gpu.applied)
                                         ? 0 : GPU_RETRY_MS);
                publish_gpu_status(&gpu, &gpu_status);
            }
        }

        /* A settings, curve-preview or screen change must re-evaluate the
         * smart target immediately, even when the temperature sits inside the
         * 3 C hysteresis band; reset the anchor (the EMA is temperature-only
         * and stays). */
        if (changed)
            fan.anchor_c = -1;

        fan_due = now >= next_fan_ms;
        if (fan_due) {
            /* Re-pick the watched zones periodically, and immediately while
             * no zone is readable (they can appear after class core starts). */
            if (last_temp < 0 || now >= next_thermal_ms) {
                last_temp = thermal_sweep(&thermal);
                next_thermal_ms =
                    now + (last_temp < 0 ? 0 : THERMAL_SWEEP_MS);
            } else {
                last_temp = thermal_hottest_tenths(&thermal);
            }
            fan_control_tick(&fan, last_temp, now, screen_on);
            next_fan_ms = now + FAN_SAMPLE_MS;
        } else if (changed) {
            /* A settings or screen change is applied immediately, using the
             * last temperature sample. */
            fan_control_tick(&fan, last_temp, now, screen_on);
        } else if (fan.kick_until_ms > 0 && now >= fan.kick_until_ms) {
            /* The spin-up kick is over: drop to the requested speed. */
            fan_apply_pct(&fan, fan.pending_pct, now);
        }

        /* --- lighting ----------------------------------------------------
         * LEDs are always off while the screen is off and re-applied (both
         * attributes rewritten) on screen-on; persist.ashfall.led.screen_off
         * was removed in phase 5 v2.  No animation runs while off; a failed
         * off/on write is retried every LED_RETRY_MS. */
        have_raw = (led.settings.mode != LED_MODE_OFF ||
                    led.settings.override_valid) &&
                   led.backend != LED_BACKEND_NONE;
        want_on = screen_on && have_raw;
        led_due = want_on && led_is_animated(&led) && now >= next_led_ms;
        led_reapply = led_screen_reapply_due(&screen, now);
        if (led_reapply) {
            /* The controllers may have dropped the first transfers after
             * wake-up: forget what was written and rewrite both attributes
             * instead of trusting applied/shown. */
            led_invalidate(&led);
        }
        if (force_led || !led.applied || led_due || want_on != led_want_on ||
            led_reapply) {
            rgb_t frame[2];

            led_frame(&led, now, frame);
            led_apply(&led, frame, want_on);
            led_want_on = want_on;
            force_led = false;
            if (!led.applied)
                next_led_ms = now + LED_RETRY_MS;
            else if (want_on && led_is_animated(&led))
                next_led_ms = now + LED_FRAME_MS;
            else
                next_led_ms = 0;
        }

        /* --- status ------------------------------------------------------ */
        {
            bool live = prop_get_bool(PROP_UI_FAN_LIVE, false);

            if (live != fan_live) {
                fan_live = live;
                next_status_ms = 0; /* switch 2 s <-> 500 ms immediately */
            }
        }
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
        screen_ms = led_screen_next_ms(&screen);
        if (screen_ms > 0 && screen_ms < deadline)
            deadline = screen_ms;
        if (fan.kick_until_ms > 0 && fan.kick_until_ms < deadline)
            deadline = fan.kick_until_ms;
        wait_ms = (int)(deadline - now);
        if (wait_ms > IDLE_WAIT_MS)
            wait_ms = IDLE_WAIT_MS;
        if (wait_ms < 0)
            wait_ms = 0;
        prop_wait_any(wait_ms);
    }

    ah_logf(AH_LOG_INFO, "stopping");
    led_disable(&led);
    /* Release the performance override: the kernel trips must never stay
     * raised without the daemon watching over them. */
    if (throttle.active)
        throttle_set(&throttle, false);
    /* Leave the fan at its current duty: the safety override only lives as
     * long as this daemon, so stopping it must not also stop cooling.  init
     * restarts the service and re-applies persist.ashfall.fan.*. */
    return 0;
}
