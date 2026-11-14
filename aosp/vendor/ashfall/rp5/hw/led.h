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

/*
 * Screen-on wake handling (issue #109).
 *
 * `debug.tracing.screen_state` is not stable during the power/keyguard
 * transition, and the HTR3212 controllers can accept the first colour/enable
 * transfers after screen-on without latching them.  The daemon therefore
 * debounces the screen decision and then rewrites both attributes a few times
 * inside a bounded window, regardless of `led.applied`.
 */
#define LED_WAKE_DEBOUNCE_MS 250
#define LED_WAKE_REAPPLY_COUNT 3
/* Offsets from the debounced screen-on edge: transition, ~0.5 s, ~1.5 s. */
#define LED_WAKE_REAPPLY_OFFSET_MS 500

typedef struct {
    bool stable_on;          /* debounced screen state used by the daemon */
    bool raw_on;             /* last sampled debug.tracing.screen_state */
    int64_t raw_since_ms;    /* when raw_on last changed */
    int64_t wake_start_ms;   /* debounced off->on edge */
    int64_t next_reapply_ms; /* next wake rewrite, 0 = window over */
    int reapply_index;       /* number of wake rewrites already issued */
} led_screen;

/* Start with the current screen state, without scheduling a wake window. */
void led_screen_init(led_screen *screen, bool on, int64_t now_ms);

/* Feed a raw sample.  Returns true when the debounced state changed; *woke is
 * set on a debounced off->on edge (which opens the re-apply window). */
bool led_screen_update(led_screen *screen, bool raw_on, int64_t now_ms,
                       bool *woke);

/* True when a wake rewrite is due now; advances to the next one.  The caller
 * invalidates the cached attributes and re-applies the current frame. */
bool led_screen_reapply_due(led_screen *screen, int64_t now_ms);

/* Next debounce/re-apply deadline for the main sleep, 0 when none. */
int64_t led_screen_next_ms(const led_screen *screen);

/* Debounced screen state (also available as screen->stable_on). */
bool led_screen_on(const led_screen *screen);

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
