/*
 * AshFall RP5 input: desktop-mode gamepad pointer (#131, workstream B).
 *
 * Desktop mode turns the internal pad into a mouse without touching the
 * gamepad device: a *second* uinput device ("AshFall RP5 Pointer") carries
 * EV_REL/REL_X/REL_Y/REL_WHEEL/REL_HWHEEL plus BTN_LEFT/RIGHT/MIDDLE and
 * INPUT_PROP_POINTER.  Games never see relative axes, and in game mode no
 * pointer device exists at all.
 *
 * The mapping logic lives here, free of evdev/uinput, so the host tests cover
 * the curve, the fixed 125 Hz tick, the button OR/hysteresis, the wheel repeat
 * and the L1+R1+Start chord with no hardware.
 *
 * Contract (docs/rp5/input.md, "Desktop mode"):
 *   - persist.ashfall.pad.mode = game (default) | desktop, read live;
 *   - right stick = pointer, 5 % radial deadzone, expo 1.8, acceleration ramp,
 *     ~1200 px/s cap, fractional pixels accumulated across ticks;
 *   - A and R3 = left click, B = right click, R2 = middle click, L2 = drag,
 *     L1/R1 = wheel up/down (repeated while held);
 *   - a fixed 125 Hz emission while desktop mode is active, so a held stick
 *     keeps moving even when the MCU sends no changed frame;
 *   - switching modes (property or chord) releases every held button on both
 *     devices and clears the drag/repeat/fractional state;
 *   - GRAB ON suppresses the pointer exactly like the gamepad: the daemon
 *     skips the tick and releases the pointer buttons on the transition.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_POINTER_H
#define ASHFALL_POINTER_H

#include "session.h"

#include <stdbool.h>
#include <stdint.h>

/* Property the app/QS tile writes; the daemon only reads it. */
#define PROP_PAD_MODE "persist.ashfall.pad.mode"
/* Effective mode, published for Parts/QS (status namespace, not persisted). */
#define ASHFALL_PROP_PAD_MODE "ashfall.status.pad.mode"

#define POINTER_NAME "AshFall RP5 Pointer"
#define POINTER_VID 0x2020
#define POINTER_PID 0x3002

/* Fixed emission rate while desktop mode is active. */
#define POINTER_TICK_MS 8 /* 125 Hz */

/* Longest integration step: after a stalled loop the pointer must not jump. */
#define POINTER_MAX_DT_MS 50

/* Pointer curve: reuse the phase-5 radial deadzone shape at a fixed 5 %. */
#define POINTER_DEADZONE_PCT 5
#define POINTER_EXPO 1.8
#define POINTER_MAX_PX_S 1200.0
/* Reaches the cap from rest in 150 ms; the same rate decelerates to a stop. */
#define POINTER_ACCEL_PX_S2 8000.0

/* Analog triggers: press at >= 50 %, release at <= 37.5 % (hysteresis). */
#define POINTER_TRIGGER_ON 128
#define POINTER_TRIGGER_OFF 96

/* Wheel repeat while a shoulder button is held. */
#define POINTER_REPEAT_DELAY_MS 400
#define POINTER_REPEAT_MS 80

/* Fallback mode chord: L1+R1+Start held for this long. */
#define POINTER_CHORD_MS 1000

/* MCU frames stop for this long -> the held state is no longer trusted. */
#define POINTER_FRAME_TIMEOUT_MS 2000

/* MCU key word bits (same layout as the RAW stream, docs/rp5/input.md). */
#define POINTER_KEY_L1 (1u << 0)
#define POINTER_KEY_R1 (1u << 1)
#define POINTER_KEY_SELECT (1u << 2)
#define POINTER_KEY_START (1u << 3)
#define POINTER_KEY_L3 (1u << 4)
#define POINTER_KEY_R3 (1u << 5)
#define POINTER_KEY_HOME (1u << 6)
#define POINTER_KEY_BACK (1u << 7)
#define POINTER_KEY_UP (1u << 8)
#define POINTER_KEY_DOWN (1u << 9)
#define POINTER_KEY_LEFT (1u << 10)
#define POINTER_KEY_RIGHT (1u << 11)
#define POINTER_KEY_X (1u << 12)
#define POINTER_KEY_Y (1u << 13)
#define POINTER_KEY_A (1u << 14)
#define POINTER_KEY_B (1u << 15)

#define POINTER_CHORD_MASK \
    (POINTER_KEY_L1 | POINTER_KEY_R1 | POINTER_KEY_START)

enum pointer_mode {
    POINTER_MODE_GAME = 0,
    POINTER_MODE_DESKTOP,
};

struct pointer_state {
    enum pointer_mode mode;
    /* Last property text seen: a value is adopted only when the text changes,
     * so the chord can switch the live mode without the 250 ms poll undoing
     * it (the app owns persist.ashfall.pad.mode, docs/phase6.md §9.3). */
    char mode_text[16];

    /* Latest decoded frame.  rx/ry are calibrated but *before* the user
     * deadzone, so the pointer can apply its own fixed 5 %. */
    uint16_t keys;
    int rx, ry;
    int l2, r2; /* calibrated 0..255, trigger deadzone applied */
    int64_t last_frame_ms;
    bool have_frame;

    /* Velocity in px/s and the sub-pixel remainder of the last tick. */
    double vx, vy;
    double frac_x, frac_y;

    bool btn_left, btn_right, btn_middle;
    bool l2_down, r2_down;
    bool l1_held, r1_held;
    int64_t l1_next_ms, r1_next_ms;

    int64_t chord_since_ms;
    bool chord_latched;

    int64_t last_tick_ms;
    int64_t next_tick_ms;
};

/* Device capabilities for uinput_dev_spec (uinput_dev.c). */
extern const uint16_t pointer_keys[];
extern const int pointer_key_count;
extern const uint16_t pointer_rels[];
extern const int pointer_rel_count;
extern const uint16_t pointer_props[];
extern const int pointer_prop_count;

void pointer_defaults(struct pointer_state *ps);

/* "game" / "desktop"; false for anything else. */
bool pointer_mode_parse(const char *text, enum pointer_mode *out);
const char *pointer_mode_name(enum pointer_mode mode);

/* Read persist.ashfall.pad.mode.  Adopts the value only when its text changed
 * (like fusion_settings_read); a malformed value falls back to game and is
 * logged once per distinct text.  Returns true when the *effective* mode
 * changed, i.e. when the caller must release both devices and create or
 * destroy the pointer uinput device. */
bool pointer_mode_read(struct pointer_state *ps);

/* Set the live mode (chord fallback).  `mode_text` is left alone on purpose.
 * Returns true when the mode actually changed. */
bool pointer_set_mode(struct pointer_state *ps, enum pointer_mode mode);

/* Record one decoded status frame.  Called for every frame, including while
 * GRAB is on, so GRAB OFF resumes from the current physical state. */
void pointer_frame(struct pointer_state *ps, uint16_t keys, int rx, int ry,
                   int l2, int r2, int64_t now_ms);

/* Release every held pointer button and clear velocity, fraction, wheel repeat
 * and chord state.  `emit` may be NULL to clear silently (GRAB ON / shutdown
 * after the buttons were already released).  Returns the number of events
 * emitted, not counting the SYN. */
int pointer_release(struct pointer_state *ps, session_emit_fn emit, void *ctx);

/*
 * One fixed-rate tick.  In game mode this only runs the chord detector and
 * returns 0.  In desktop mode it emits the button changes, wheel notches and
 * REL_X/REL_Y deltas for this tick plus one SYN.  *chord (optional) is set true
 * once per completed L1+R1+Start hold; the caller switches the mode and calls
 * pointer_release()/apply.
 *
 * Returns the number of events emitted, not counting the SYN.
 */
int pointer_tick(struct pointer_state *ps, int64_t now_ms,
                 session_emit_fn emit, void *ctx, bool *chord);

/* Milliseconds until the next desktop tick (>= 1), or -1 when the pointer is
 * idle (game mode): the caller keeps its normal poll timeout. */
int pointer_timeout_ms(const struct pointer_state *ps, int64_t now_ms);

#endif /* ASHFALL_POINTER_H */
