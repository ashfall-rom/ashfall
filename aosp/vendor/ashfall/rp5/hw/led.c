/*
 * AshFall RP5 stick lighting: effects and the sn3112 sysfs interface.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "led.h"
#include "plat.h"

#include <ctype.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#define SN3112_BRIGHTNESS_FMT "%s/brightness"
#define SN3112_ENABLE_FMT "%s/enable"

#define AH_PI 3.14159265358979323846
/* Breathing is interpolated in perceptual brightness: the raw PWM value is
 * gamma-encoded so the fade looks even and has no visible steps at speed 5
 * (docs/phase5.md §6.1). */
#define LED_BREATH_GAMMA 2.2
#define LED_BREATH_MIN 0.02

static const char *const side_dirs[2] = {
    "/sys/class/sn3112l/led",
    "/sys/class/sn3112r/led",
};

void led_settings_default(led_settings *out)
{
    memset(out, 0, sizeof(*out));
    out->mode = LED_MODE_STATIC;
    out->left = (rgb_t){0xFF, 0x20, 0x20};
    out->right = (rgb_t){0xFF, 0x20, 0x20};
    out->sync = true;
    out->brightness = 60;
    out->speed = 5;
}

static const struct {
    const char *name;
    led_mode mode;
} led_mode_names[] = {
    {"off", LED_MODE_OFF},
    {"static", LED_MODE_STATIC},
    {"breathing", LED_MODE_BREATHING},
    {"rainbow", LED_MODE_RAINBOW},
    {"duo", LED_MODE_DUO},
};

bool led_parse_mode(const char *name, led_mode *out)
{
    if (!name)
        return false;
    for (size_t i = 0; i < sizeof(led_mode_names) / sizeof(led_mode_names[0]);
         i++) {
        if (!strcmp(name, led_mode_names[i].name)) {
            *out = led_mode_names[i].mode;
            return true;
        }
    }
    return false;
}

const char *led_mode_name(led_mode mode)
{
    for (size_t i = 0; i < sizeof(led_mode_names) / sizeof(led_mode_names[0]);
         i++) {
        if (led_mode_names[i].mode == mode)
            return led_mode_names[i].name;
    }
    return "static";
}

bool led_parse_colour(const char *value, rgb_t *out)
{
    unsigned int r, g, b;
    const char *p = value;

    if (!value)
        return false;
    if (*p == '#')
        p++;
    if (strlen(p) != 6)
        return false;
    for (int i = 0; i < 6; i++) {
        if (!isxdigit((unsigned char)p[i]))
            return false;
    }
    if (sscanf(p, "%2x%2x%2x", &r, &g, &b) != 3)
        return false;
    out->r = (unsigned char)r;
    out->g = (unsigned char)g;
    out->b = (unsigned char)b;
    return true;
}

bool led_probe(led_state *state)
{
    char path[128];

    state->backend = LED_BACKEND_NONE;
    for (int i = 0; i < 2; i++) {
        state->side_dir[i][0] = '\0';
        /* The attributes are write-only (reading returns EIO), so presence is
         * an existence check and never a read or a DAC check: vendor init
         * may not have chmod'ed them yet. */
        snprintf(path, sizeof(path), SN3112_BRIGHTNESS_FMT, side_dirs[i]);
        if (!sysfs_present(path))
            continue;
        snprintf(state->side_dir[i], sizeof(state->side_dir[i]), "%s",
                 side_dirs[i]);
        state->backend = LED_BACKEND_SN3112;
    }
    return state->backend != LED_BACKEND_NONE;
}

void led_state_init(led_state *state, int64_t now_ms)
{
    memset(state, 0, sizeof(*state));
    led_settings_default(&state->settings);
    state->anim_start_ms = now_ms;
    state->backend = LED_BACKEND_NONE;
    state->en_state[0] = state->en_state[1] = -1;

    if (led_probe(state)) {
        ah_logf(AH_LOG_INFO, "led: sn3112 backend (left=%s right=%s)",
                state->side_dir[0][0] ? "yes" : "no",
                state->side_dir[1][0] ? "yes" : "no");
        return;
    }
    ah_logf(AH_LOG_WARN, "led: no LED nodes found (yet); will retry");
}

bool led_is_animated(const led_state *state)
{
    if (state->settings.override_valid)
        return false;
    switch (state->settings.mode) {
    case LED_MODE_BREATHING:
    case LED_MODE_RAINBOW:
    case LED_MODE_DUO:
        return true;
    default:
        return false;
    }
}

void led_invalidate(led_state *state)
{
    for (int i = 0; i < 2; i++) {
        state->shown_valid[i] = false;
        state->en_state[i] = -1;
    }
}

static rgb_t scale(rgb_t c, double factor)
{
    rgb_t out;

    if (factor < 0)
        factor = 0;
    if (factor > 1)
        factor = 1;
    out.r = (unsigned char)lround(c.r * factor);
    out.g = (unsigned char)lround(c.g * factor);
    out.b = (unsigned char)lround(c.b * factor);
    return out;
}

static rgb_t lerp(rgb_t a, rgb_t b, double t)
{
    rgb_t out;

    out.r = (unsigned char)lround(a.r + (b.r - a.r) * t);
    out.g = (unsigned char)lround(a.g + (b.g - a.g) * t);
    out.b = (unsigned char)lround(a.b + (b.b - a.b) * t);
    return out;
}

/* h in [0,1) */
static rgb_t hsv_to_rgb(double h, double s, double v)
{
    double c = v * s;
    double hp = h * 6.0;
    double x = c * (1.0 - fabs(fmod(hp, 2.0) - 1.0));
    double r = 0, g = 0, b = 0;

    if (hp < 1) {
        r = c;
        g = x;
    } else if (hp < 2) {
        r = x;
        g = c;
    } else if (hp < 3) {
        g = c;
        b = x;
    } else if (hp < 4) {
        g = x;
        b = c;
    } else if (hp < 5) {
        r = x;
        b = c;
    } else {
        r = c;
        b = x;
    }
    return (rgb_t){(unsigned char)lround(r * 255.0),
                   (unsigned char)lround(g * 255.0),
                   (unsigned char)lround(b * 255.0)};
}

void led_frame(const led_state *state, int64_t now_ms, rgb_t out[2])
{
    const led_settings *set = &state->settings;
    int speed = set->speed < 1 ? 1 : set->speed > 10 ? 10 : set->speed;
    double elapsed = (double)(now_ms - state->anim_start_ms);
    double level = 1.0;
    rgb_t left = set->left;
    rgb_t right = set->right;
    double brightness;

    if (set->sync)
        right = left;

    /* Gamepad tester override: final colours, no brightness/mode processing. */
    if (set->override_valid) {
        out[0] = set->override[0];
        out[1] = set->override[1];
        return;
    }

    switch (set->mode) {
    case LED_MODE_OFF:
        out[0] = out[1] = (rgb_t){0, 0, 0};
        return;
    case LED_MODE_STATIC:
        break;
    case LED_MODE_BREATHING: {
        double period = 4000.0 / speed;
        double phase = fmod(elapsed, period) / period;
        double perceptual = 0.5 - 0.5 * cos(2.0 * AH_PI * phase);

        level = LED_BREATH_MIN +
                (1.0 - LED_BREATH_MIN) * pow(perceptual, LED_BREATH_GAMMA);
        break;
    }
    case LED_MODE_DUO: {
        double period = 5000.0 / speed;
        double phase = fmod(elapsed, period) / period;
        double t = 0.5 - 0.5 * cos(2.0 * AH_PI * phase);

        left = lerp(set->left, set->right, t);
        right = left; /* both sticks fade between the two colours */
        break;
    }
    case LED_MODE_RAINBOW: {
        /* Base speed is one third of the phase 5 v1 value: a full hue turn
         * takes 18 000 / speed ms (was 6 000 / speed). */
        double period = 18000.0 / speed;
        double hue = fmod(elapsed, period) / period;

        left = hsv_to_rgb(hue, 1.0, 1.0);
        right = set->sync ? left : hsv_to_rgb(fmod(hue + 1.0 / 3.0, 1.0), 1.0,
                                              1.0);
        break;
    }
    }

    brightness = (set->brightness < 0 ? 0 : set->brightness > 100
                                                   ? 100
                                                   : set->brightness) /
                 100.0;
    out[0] = scale(left, brightness * level);
    out[1] = scale(right, brightness * level);
}

static bool write_side(led_state *state, int side, rgb_t colour, bool enabled)
{
    char path[128];
    char value[32];
    bool ok = true;

    if (side < 0 || side > 1 || !state->side_dir[side][0])
        return true; /* side not present: nothing to do */

    if (!enabled) {
        if (state->en_state[side] != 0 ||
            (state->shown_valid[side] &&
             (state->shown[side].r || state->shown[side].g ||
              state->shown[side].b))) {
            snprintf(path, sizeof(path), SN3112_BRIGHTNESS_FMT,
                     state->side_dir[side]);
            if (sysfs_write_str(path, "0-0:0:0") != 0)
                ok = false;
            snprintf(path, sizeof(path), SN3112_ENABLE_FMT,
                     state->side_dir[side]);
            if (sysfs_write_str(path, "0") != 0)
                ok = false;
        }
        if (ok) {
            state->shown[side] = (rgb_t){0, 0, 0};
            state->shown_valid[side] = true;
            state->en_state[side] = 0;
        } else {
            state->en_state[side] = -1;
        }
        return ok;
    }

    if (!state->shown_valid[side] ||
        memcmp(&state->shown[side], &colour, sizeof(colour)) != 0) {
        snprintf(path, sizeof(path), SN3112_BRIGHTNESS_FMT,
                 state->side_dir[side]);
        snprintf(value, sizeof(value), "1-%u:%u:%u", colour.r, colour.g,
                 colour.b);
        if (sysfs_write_str(path, value) != 0) {
            state->shown_valid[side] = false;
            state->en_state[side] = -1;
            return false;
        }
        state->shown[side] = colour;
        state->shown_valid[side] = true;
    }
    if (state->en_state[side] != 1) {
        snprintf(path, sizeof(path), SN3112_ENABLE_FMT,
                 state->side_dir[side]);
        if (sysfs_write_str(path, "1") != 0) {
            state->en_state[side] = -1;
            return false;
        }
        state->en_state[side] = 1;
    }
    return true;
}

bool led_disable(led_state *state)
{
    bool ok0, ok1;

    if (!led_probe(state))
        return false;
    ok0 = write_side(state, 0, state->shown[0], false);
    ok1 = write_side(state, 1, state->shown[1], false);
    state->applied = ok0 && ok1;
    return state->applied;
}

bool led_apply(led_state *state, const rgb_t frame[2], bool enabled)
{
    bool ok0, ok1;

    if (!enabled)
        return led_disable(state);
    if (!led_probe(state))
        return false;
    ok0 = write_side(state, 0, frame[0], true);
    ok1 = write_side(state, 1, frame[1], true);
    state->applied = ok0 && ok1;
    return state->applied;
}
