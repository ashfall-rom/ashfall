/*
 * AshFall RP5 stick lighting (sn3112l / sn3112r LED controllers).
 *
 * Hardware (.31 vendor, see docs/rp5/hw.md):
 *   /sys/class/sn3112l/led/{enable,brightness}   left stick ring
 *   /sys/class/sn3112r/led/{enable,brightness}   right stick ring
 * `brightness` takes "<mode>-<r>:<g>:<b>" and mode 1 is the static colour
 * path.  The generic /sys/class/leds/{red,green,blue} nodes (pm8150l) are NOT
 * used: /sys/class/leds is genfs-labelled sysfs_leds and a coredomain may not
 * touch it (private/coredomain.te neverallow), and they are the notification
 * LED, not the stick rings (docs/rp5/hw.md).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_LED_H
#define ASHFALL_LED_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define LED_FPS 50
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
    /* ashfall.ui.led_override: final colours (intensity already applied),
     * left/right; overrides the mode/colour/brightness while valid. */
    bool override_valid;
    rgb_t override[2];
} led_settings;

typedef enum {
    LED_BACKEND_NONE,
    LED_BACKEND_SN3112, /* one sn3112 controller per side */
} led_backend;

typedef struct {
    led_settings settings;
    led_backend backend;
    char side_dir[2][64]; /* sn3112 left/right attribute directories */
    rgb_t shown[2];
    bool shown_valid[2];
    int8_t en_state[2]; /* -1 unknown, 0 off, 1 on (last successful write) */
    bool applied;       /* result of the last led_apply() */
    int64_t anim_start_ms;
} led_state;

void led_settings_default(led_settings *out);
bool led_parse_mode(const char *name, led_mode *out);
const char *led_mode_name(led_mode mode);

bool led_parse_colour(const char *value, rgb_t *out);

void led_state_init(led_state *state, int64_t now_ms);

/* (Re)detect the LED class devices; true when at least one side is present.
 * Safe to call repeatedly: the vendor init chmod and the LED driver probe can
 * both happen after class core has already started the daemon, so presence is
 * an existence check only and the first writes retry until they succeed. */
bool led_probe(led_state *state);

/* True while the current mode needs a per-frame animation timer.  The
 * ashfall.ui.led_override path is driven by property changes, not a timer. */
bool led_is_animated(const led_state *state);

/* Forget what was last written (both attributes are rewritten on the next
 * led_apply()).  Used on screen-on, where the rings were deliberately off and
 * the driver may have been re-initialised. */
void led_invalidate(led_state *state);

/* Colour of both sticks at time now_ms (brightness already applied). */
void led_frame(const led_state *state, int64_t now_ms, rgb_t out[2]);

/* Push a frame to the hardware.  Attributes whose last write succeeded are
 * skipped; failed writes are retried by the caller.  Returns true when every
 * present side was written successfully. */
bool led_apply(led_state *state, const rgb_t frame[2], bool enabled);

/* Turn both rings off (screen-off handling / mode off).  Always attempts the
 * writes, so a ring left on by the bootloader/stock firmware is turned off
 * even after a fresh start. */
bool led_disable(led_state *state);

#endif /* ASHFALL_LED_H */
