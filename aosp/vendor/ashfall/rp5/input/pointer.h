/*
 * AshFall RP5 input: external-display gamepad pointer (#131).
 *
 * A secondary display (USB-C DP / HDMI) has no trackpad and no touch cursor,
 * so the built-in pad can drive the system mouse there.  The pointer is a
 * *second* uinput device ("AshFall RP5 Pointer") carrying
 * EV_REL/REL_X/REL_Y/REL_WHEEL/REL_HWHEEL plus BTN_LEFT/RIGHT/MIDDLE and
 * INPUT_PROP_POINTER.  The gamepad device never grows relative axes or mouse
 * buttons, and with the switch off no pointer device exists at all.
 *
 * It is opt-in and external-only: the AshFall Parts switch in the Fusion QS
 * dialog is disabled unless DisplayManager reports a display with
 * displayId != 0 that is not STATE_OFF, and it is the only writer.
 *
 * The mapping logic lives here, free of evdev/uinput, so the host tests cover
 * the curve, the fixed 125 Hz tick, the two mouse buttons, the left-stick
 * scroll repeat and the release path with no hardware.
 *
 * Contract (docs/rp5/input.md, "External-display gamepad pointer"):
 *   - persist.ashfall.pad.pointer = 1 | 0 (default 0), read live;
 *   - right stick = pointer, 5 % radial deadzone, expo 1.8, acceleration ramp,
 *     ~1200 px/s cap, fractional pixels accumulated across ticks;
 *   - R1 = BTN_LEFT (hold = drag), R2 = BTN_RIGHT (hold);
 *   - left stick = scroll: vertical -> REL_WHEEL, horizontal -> REL_HWHEEL,
 *     with a per-axis deadzone, repeat and deflection-dependent acceleration;
 *   - a fixed 125 Hz emission while the pointer is on, so a held stick keeps
 *     moving even when the MCU sends no changed frame;
 *   - switching it off releases both mouse buttons and clears the
 *     scroll/velocity/fractional state;
 *   - GRAB ON suppresses the pointer exactly like the gamepad: the daemon skips
 *     the tick and releases the pointer buttons on the transition.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_POINTER_H
#define ASHFALL_POINTER_H

#include "session.h"

#include <stdbool.h>
#include <stdint.h>

/* Property the app/QS dialog writes; the daemon only reads it. */
#define PROP_PAD_POINTER "persist.ashfall.pad.pointer"

#define POINTER_NAME "AshFall RP5 Pointer"
#define POINTER_VID 0x2020
#define POINTER_PID 0x3002

/* Fixed emission rate while the pointer is on. */
#define POINTER_TICK_MS 8 /* 125 Hz */

/* Longest integration step: after a stalled loop the pointer must not jump. */
#define POINTER_MAX_DT_MS 50

/* Pointer curve: reuse the phase-5 radial deadzone shape at a fixed 5 %. */
#define POINTER_DEADZONE_PCT 5
#define POINTER_EXPO 1.8
#define POINTER_MAX_PX_S 1200.0
/* Reaches the cap from rest in 150 ms; the same rate decelerates to a stop. */
#define POINTER_ACCEL_PX_S2 8000.0

/* Left stick -> scroll: one notch immediately on leaving the deadzone, then a
 * repeat that gets faster with deflection (16 notches/s fully pushed, 4/s just
 * outside the deadzone). */
#define POINTER_SCROLL_DEADZONE_PCT 5
#define POINTER_SCROLL_SLOW_MS 250
#define POINTER_SCROLL_FAST_MS 60

/* Analog R2: press at >= 50 %, release at <= 37.5 % (hysteresis). */
#define POINTER_TRIGGER_ON 128
#define POINTER_TRIGGER_OFF 96

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

/* Keys the MCU pad gives up while the pointer is on: R1 is the left mouse
 * button, so it must not also reach the gamepad device (a game would see a
 * click).  L1/L2/A/B/X/Y/R3/L3, D-pad, Start/Select and Home/Back pass
 * through; R2, the left stick and the right stick are the axes consumed in
 * ashfall_rp5_input.c. */
#define POINTER_CONSUMED_KEYS (POINTER_KEY_R1)

/* One scroll axis' auto-repeat state. */
struct pointer_repeat {
    bool held;
    int dir;      /* -1 / 0 / +1 */
    int64_t next_ms;
};

/*
 * Shared mouse-button bus for the single pointer device (#250.1): every
 * source writes the one "AshFall RP5 Pointer" device, so a second source
 * pressing a button that is already down must not emit a second press, and a
 * release only reaches the device when the last holding source lets go.
 */
struct pointer_shared {
    int left;     /* sources currently holding BTN_LEFT */
    int right;    /* sources currently holding BTN_RIGHT */
    bool left_on; /* what the device was last told */
    bool right_on;
};

void pointer_shared_init(struct pointer_shared *sh);

/* Route one source's button transition through the bus.  Returns the number
 * of events emitted (0 while another source already holds or still holds the
 * button).  `emit` may be NULL to drop a claim silently. */
int pointer_shared_button(struct pointer_shared *sh, bool is_left, bool down,
                          session_emit_fn emit, void *ctx);

/* Forget every claim without emitting: the pointer device is going away. */
void pointer_shared_clear(struct pointer_shared *sh);

struct pointer_state {
    /* persist.ashfall.pad.pointer, live.  The last text seen is kept so a
     * malformed value is logged once per distinct text, not every poll. */
    bool on;
    char prop_text[8];

    /* Latest decoded frame.  lx/ly/rx/ry are calibrated but *before* the user
     * deadzone, so pointer movement and scrolling apply their own fixed 5 %. */
    uint16_t keys;
    int lx, ly;
    int rx, ry;
    int r2; /* calibrated 0..255, trigger hysteresis applied */
    int64_t last_frame_ms;
    bool have_frame;

    /* Velocity in px/s and the sub-pixel remainder of the last tick. */
    double vx, vy;
    double frac_x, frac_y;

    bool btn_left, btn_right;
    bool r2_down;

    struct pointer_repeat scroll_x; /* left stick horizontal -> REL_HWHEEL */
    struct pointer_repeat scroll_y; /* left stick vertical   -> REL_WHEEL */

    int64_t last_tick_ms;
    int64_t next_tick_ms;
};

/* Device capabilities for uinput_dev_spec (uinput_dev.c).  BTN_MIDDLE is
 * advertised for pointer identity but no input maps to it: R1 is the left and
 * R2 the right button. */
extern const uint16_t pointer_keys[];
extern const int pointer_key_count;
extern const uint16_t pointer_rels[];
extern const int pointer_rel_count;
extern const uint16_t pointer_props[];
extern const int pointer_prop_count;

void pointer_defaults(struct pointer_state *ps);

/* "1" -> true, "0" -> false; false for anything else. */
bool pointer_switch_parse(const char *text, bool *on);

/* Read persist.ashfall.pad.pointer.  The switch follows the property text; a
 * malformed value is ignored (logged once per distinct text) and never flips
 * it.  Returns true when the effective state changed, i.e. when the caller must
 * release the gamepad and the pointer and create or destroy the pointer uinput
 * device. */
bool pointer_switch_read(struct pointer_state *ps);

/* The same decision on an explicit text: the daemon's read path with the
 * property API peeled off, so the host tests need no property service. */
bool pointer_switch_observe(struct pointer_state *ps, const char *text);

/* Set the live state directly (host tests / boot).  Returns true when it
 * actually changed. */
bool pointer_set_enabled(struct pointer_state *ps, bool on);

/* Record one decoded status frame.  Called for every frame, including while
 * GRAB is on, so GRAB OFF resumes from the current physical state. */
void pointer_frame(struct pointer_state *ps, uint16_t keys, int lx, int ly,
                   int rx, int ry, int r2, int64_t now_ms);

/* Release every held pointer button and clear velocity, fraction and scroll
 * repeat state.  `emit` may be NULL to clear silently (GRAB ON / shutdown
 * after the buttons were already released).  `sh` routes the button events
 * through the shared bus (#250); NULL keeps the single-source behaviour.
 * Returns the number of events emitted, not counting the SYN. */
int pointer_release(struct pointer_state *ps, session_emit_fn emit, void *ctx,
                    struct pointer_shared *sh);

/*
 * One fixed-rate tick.  With the pointer off this returns 0.  With it on it
 * emits the button changes, wheel notches and REL_X/REL_Y deltas for this tick
 * plus one SYN.  `sh` routes the button events through the shared bus (#250);
 * NULL keeps the single-source behaviour.
 *
 * Returns the number of events emitted, not counting the SYN.
 */
int pointer_tick(struct pointer_state *ps, int64_t now_ms,
                 session_emit_fn emit, void *ctx, struct pointer_shared *sh);

/* Milliseconds until the next pointer tick (>= 1), or -1 when the pointer is
 * off: the caller keeps its normal poll timeout. */
int pointer_timeout_ms(const struct pointer_state *ps, int64_t now_ms);

#endif /* ASHFALL_POINTER_H */
