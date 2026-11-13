/*
 * AshFall RP5 stick lighting (sn3112l / sn3112r LED controllers).
 *
 * Hardware (.31 vendor, see docs/rp5/hw.md):
 *   /sys/class/sn3112l/led/{enable,brightness}   left stick ring
 *   /sys/class/sn3112r/led/{enable,brightness}   right stick ring
 * `brightness` takes "<mode>-<r>:<g>:<b>" and mode 1 is the static colour
 * path.  The generic /sys/class/leds/{red,green,blue} nodes (pm8150l) are a
 * fallback for units/boots where the sn3112 class is missing.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_LED_H
#define ASHFALL_LED_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define LED_FPS 30
#define LED_FRAME_MS (1000 / LED_FPS)

typedef struct {
    unsigned char r, g, b;
} rgb_t;

typedef enum {
    LED_MODE_OFF,
    LED_MODE_STATIC,
    LED_MODE_BREATHING,
    LED_MODE_RAINBOW,
    LED_MODE_DUO,
} led_mode;

typedef struct {
    led_mode mode;
    rgb_t left;
    rgb_t right;
    bool sync;       /* right follows the left colour */
    int brightness;  /* 0..100 */
    int speed;       /* 1..10, breathing/rainbow/duo */
    bool screen_off_keep; /* keep the LEDs on while the screen is off */
} led_settings;

typedef enum {
    LED_BACKEND_NONE,
    LED_BACKEND_SN3112, /* one controller per side */
    LED_BACKEND_LEDS,   /* /sys/class/leds/{red,green,blue} */
} led_backend;

typedef struct {
    led_settings settings;
    led_backend backend;
    char side_dir[2][64]; /* sn3112 left/right attribute directories */
    bool have_enable[2];
    rgb_t shown[2];
    bool shown_on[2];
    bool shown_valid[2];
    int64_t anim_start_ms;
} led_state;

void led_settings_default(led_settings *out);
bool led_parse_mode(const char *name, led_mode *out);
const char *led_mode_name(led_mode mode);

bool led_parse_colour(const char *value, rgb_t *out);

void led_state_init(led_state *state, int64_t now_ms);

/* True while the current mode needs a per-frame animation timer. */
bool led_is_animated(const led_state *state);

/* Colour of both sticks at time now_ms (brightness already applied). */
void led_frame(const led_state *state, int64_t now_ms, rgb_t out[2]);

/* Push a frame to the hardware.  Only changed attributes are written. */
void led_apply(led_state *state, const rgb_t frame[2], bool enabled);

/* Turn both rings off (screen-off handling / mode off). */
void led_disable(led_state *state);

#endif /* ASHFALL_LED_H */
