/*
 * AshFall RP5 input: external-display gamepad pointer (#131).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "pointer.h"
#include "mapping.h"
#include "plat.h"

#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include <linux/input.h>

const uint16_t pointer_keys[] = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE};
const int pointer_key_count =
    (int)(sizeof(pointer_keys) / sizeof(pointer_keys[0]));

const uint16_t pointer_rels[] = {REL_X, REL_Y, REL_WHEEL, REL_HWHEEL};
const int pointer_rel_count =
    (int)(sizeof(pointer_rels) / sizeof(pointer_rels[0]));

const uint16_t pointer_props[] = {INPUT_PROP_POINTER};
const int pointer_prop_count =
    (int)(sizeof(pointer_props) / sizeof(pointer_props[0]));

static double clampd(double value, double lo, double hi)
{
    return value < lo ? lo : value > hi ? hi : value;
}

static int absi(int value)
{
    return value < 0 ? -value : value;
}

void pointer_defaults(struct pointer_state *ps)
{
    if (!ps)
        return;
    memset(ps, 0, sizeof(*ps));
    ps->on = false;
}

bool pointer_switch_parse(const char *text, bool *on)
{
    if (!text)
        return false;
    if (!strcmp(text, "1")) {
        if (on)
            *on = true;
        return true;
    }
    if (!strcmp(text, "0")) {
        if (on)
            *on = false;
        return true;
    }
    return false;
}

bool pointer_switch_read(struct pointer_state *ps)
{
    char text[8];

    if (!ps)
        return false;
    if (prop_get_str(PROP_PAD_POINTER, text, sizeof(text)) < 0 || !text[0])
        snprintf(text, sizeof(text), "%s", "0");
    return pointer_switch_observe(ps, text);
}

bool pointer_switch_observe(struct pointer_state *ps, const char *text)
{
    bool on = false;

    if (!ps || !text)
        return false;
    if (!strcmp(text, ps->prop_text))
        return false;
    snprintf(ps->prop_text, sizeof(ps->prop_text), "%s", text);

    if (!pointer_switch_parse(text, &on)) {
        /* Never let a typo flip the switch; off is the boot default and the
         * state the switch leaves behind when it is not set. */
        ah_logf(AH_LOG_WARN, "%s=%s is not 0|1, ignored",
                PROP_PAD_POINTER, text);
        return false;
    }
    if (on == ps->on)
        return false;
    ps->on = on;
    return true;
}

bool pointer_set_enabled(struct pointer_state *ps, bool on)
{
    if (!ps || ps->on == on)
        return false;
    ps->on = on;
    return true;
}

void pointer_frame(struct pointer_state *ps, uint16_t keys, int lx, int ly,
                   int rx, int ry, int r2, int64_t now_ms)
{
    if (!ps)
        return;
    ps->keys = keys;
    ps->lx = lx;
    ps->ly = ly;
    ps->rx = rx;
    ps->ry = ry;
    ps->r2 = r2;
    ps->last_frame_ms = now_ms;
    ps->have_frame = true;
}

/* The MCU went quiet (screen cycle, UART loss): a held button must not stay
 * pressed and a held stick must stop moving until fresh frames arrive. */
static void pointer_age(struct pointer_state *ps, int64_t now_ms)
{
    if (!ps->have_frame ||
        now_ms - ps->last_frame_ms <= POINTER_FRAME_TIMEOUT_MS)
        return;
    ps->keys = 0;
    ps->lx = 0;
    ps->ly = 0;
    ps->rx = 0;
    ps->ry = 0;
    ps->r2 = 0;
    ps->have_frame = false;
}

static bool trigger_down(int value, bool was_down)
{
    if (value >= POINTER_TRIGGER_ON)
        return true;
    if (value <= POINTER_TRIGGER_OFF)
        return false;
    return was_down;
}

void pointer_shared_init(struct pointer_shared *sh)
{
    if (sh)
        memset(sh, 0, sizeof(*sh));
}

void pointer_shared_clear(struct pointer_shared *sh)
{
    pointer_shared_init(sh);
}

int pointer_shared_button(struct pointer_shared *sh, bool is_left, bool down,
                          session_emit_fn emit, void *ctx)
{
    int *refs;
    bool *reported;
    uint16_t code;
    int emitted = 0;

    if (!sh)
        return 0;
    refs = is_left ? &sh->left : &sh->right;
    reported = is_left ? &sh->left_on : &sh->right_on;
    code = is_left ? BTN_LEFT : BTN_RIGHT;
    if (down) {
        if (*refs < INT_MAX)
            (*refs)++;
        if (*refs == 1 && !*reported) {
            *reported = true;
            if (emit) {
                emit(ctx, EV_KEY, code, 1);
                emitted++;
            }
        }
    } else {
        if (*refs > 0)
            (*refs)--;
        if (*refs == 0 && *reported) {
            *reported = false;
            if (emit) {
                emit(ctx, EV_KEY, code, 0);
                emitted++;
            }
        }
    }
    return emitted;
}

/* One source's button transitions, either straight to the device or through
 * the shared bus that de-duplicates across sources (#250). */
static void emit_button(bool is_left, bool down, session_emit_fn emit,
                        void *ctx, int *emitted, struct pointer_shared *sh)
{
    uint16_t code = is_left ? BTN_LEFT : BTN_RIGHT;

    if (sh) {
        *emitted += pointer_shared_button(sh, is_left, down, emit, ctx);
        return;
    }
    emit(ctx, EV_KEY, code, down);
    (*emitted)++;
}

static void pointer_buttons(struct pointer_state *ps, session_emit_fn emit,
                            void *ctx, int *emitted,
                            struct pointer_shared *sh)
{
    /* R1 is the left button: holding it while the right stick moves is a drag.
     * R2 is the right button, with the same 50 %/37.5 % trigger hysteresis the
     * rest of the daemon uses. */
    bool left = (ps->keys & POINTER_KEY_R1) != 0;
    bool right;

    ps->r2_down = trigger_down(ps->r2, ps->r2_down);
    right = ps->r2_down;

    if (left != ps->btn_left) {
        ps->btn_left = left;
        emit_button(true, left, emit, ctx, emitted, sh);
    }
    if (right != ps->btn_right) {
        ps->btn_right = right;
        emit_button(false, right, emit, ctx, emitted, sh);
    }
}

/* Direction of one stick axis outside the scroll deadzone, 0 inside it. */
static int scroll_dir(int value)
{
    int dz = (int)(32768.0 * POINTER_SCROLL_DEADZONE_PCT / 100.0);

    if (value > dz)
        return 1;
    if (value < -dz)
        return -1;
    return 0;
}

/* Repeat interval for this deflection: 4 notches/s just outside the deadzone,
 * 16/s at full push.  Re-evaluated every tick, so pushing further accelerates
 * immediately instead of waiting for the next repeat. */
static int scroll_interval_ms(int value)
{
    double dz = 32768.0 * POINTER_SCROLL_DEADZONE_PCT / 100.0;
    double n = ((double)absi(value) - dz) / (32768.0 - dz);
    double span = (double)(POINTER_SCROLL_SLOW_MS - POINTER_SCROLL_FAST_MS);

    n = clampd(n, 0.0, 1.0);
    /* Round instead of truncating: a fully pushed stick must repeat at exactly
     * POINTER_SCROLL_FAST_MS, and the arithmetic above is one notch short. */
    return POINTER_SCROLL_SLOW_MS - (int)(n * span + 0.5);
}

static void scroll_axis(struct pointer_repeat *repeat, int value,
                        uint16_t code, int64_t now_ms, session_emit_fn emit,
                        void *ctx, int *emitted)
{
    int dir = scroll_dir(value);

    if (dir == 0) {
        repeat->held = false;
        repeat->dir = 0;
        return;
    }
    if (!repeat->held || dir != repeat->dir) {
        /* Leaving the deadzone and reversing both click at once: scrolling
         * must never feel delayed at the start. */
        repeat->held = true;
        repeat->dir = dir;
        repeat->next_ms = now_ms + scroll_interval_ms(value);
        emit(ctx, EV_REL, code, dir);
        (*emitted)++;
        return;
    }
    if (now_ms < repeat->next_ms)
        return;
    emit(ctx, EV_REL, code, dir);
    (*emitted)++;
    repeat->next_ms += scroll_interval_ms(value);
    /* A long stall must not turn into a burst of notches. */
    if (repeat->next_ms <= now_ms)
        repeat->next_ms = now_ms + scroll_interval_ms(value);
}

static void pointer_scroll(struct pointer_state *ps, int64_t now_ms,
                           session_emit_fn emit, void *ctx, int *emitted)
{
    /* ABS_Y is negative when the stick is pushed up and REL_WHEEL +1 scrolls
     * up, so the vertical sign is inverted; ABS_X and REL_HWHEEL agree
     * (positive = right). */
    scroll_axis(&ps->scroll_y, -ps->ly, REL_WHEEL, now_ms, emit, ctx, emitted);
    scroll_axis(&ps->scroll_x, ps->lx, REL_HWHEEL, now_ms, emit, ctx, emitted);
}

static void pointer_move(struct pointer_state *ps, double dt_s,
                         session_emit_fn emit, void *ctx, int *emitted)
{
    int ix = ps->rx;
    int iy = ps->ry;
    double magnitude;
    double target;
    double tx = 0.0;
    double ty = 0.0;
    double limit;
    int step_x;
    int step_y;

    /* Fixed 5 % radial deadzone, the same shape as the phase-5 user deadzone.
     * The user deadzone is deliberately not applied a second time: rx/ry reach
     * this module before it. */
    stick_apply_deadzone(&ix, &iy, POINTER_DEADZONE_PCT, 0);
    magnitude = hypot((double)ix / 32768.0, (double)iy / 32768.0);
    magnitude = clampd(magnitude, 0.0, 1.0);

    if (magnitude > 0.0) {
        target = POINTER_MAX_PX_S * pow(magnitude, POINTER_EXPO);
        tx = target * ((double)ix / 32768.0) / magnitude;
        ty = target * ((double)iy / 32768.0) / magnitude;
    }

    /* Acceleration ramp: the speed follows the curve, but catches up at a
     * bounded rate, so small corrections near the deadzone stay precise. */
    limit = POINTER_ACCEL_PX_S2 * dt_s;
    ps->vx += clampd(tx - ps->vx, -limit, limit);
    ps->vy += clampd(ty - ps->vy, -limit, limit);
    magnitude = hypot(ps->vx, ps->vy);
    if (magnitude > POINTER_MAX_PX_S) {
        ps->vx *= POINTER_MAX_PX_S / magnitude;
        ps->vy *= POINTER_MAX_PX_S / magnitude;
    }

    /* Fractional pixels survive across ticks: a slow stick eventually moves
     * exactly as far as its speed says, with no rounding to a standstill. */
    ps->frac_x += ps->vx * dt_s;
    ps->frac_y += ps->vy * dt_s;
    step_x = (int)ps->frac_x;
    step_y = (int)ps->frac_y;
    ps->frac_x -= step_x;
    ps->frac_y -= step_y;

    if (step_x != 0) {
        emit(ctx, EV_REL, REL_X, step_x);
        (*emitted)++;
    }
    if (step_y != 0) {
        emit(ctx, EV_REL, REL_Y, step_y);
        (*emitted)++;
    }
}

int pointer_release(struct pointer_state *ps, session_emit_fn emit, void *ctx,
                    struct pointer_shared *sh)
{
    int emitted = 0;

    if (!ps)
        return 0;
    if (emit || sh) {
        if (ps->btn_left) {
            if (sh) {
                emitted += pointer_shared_button(sh, true, false, emit, ctx);
            } else if (emit) {
                emit(ctx, EV_KEY, BTN_LEFT, 0);
                emitted++;
            }
        }
        if (ps->btn_right) {
            if (sh) {
                emitted += pointer_shared_button(sh, false, false, emit, ctx);
            } else if (emit) {
                emit(ctx, EV_KEY, BTN_RIGHT, 0);
                emitted++;
            }
        }
        if (emitted > 0 && emit)
            emit(ctx, EV_SYN, SYN_REPORT, 0);
    }

    ps->btn_left = false;
    ps->btn_right = false;
    ps->r2_down = false;
    memset(&ps->scroll_x, 0, sizeof(ps->scroll_x));
    memset(&ps->scroll_y, 0, sizeof(ps->scroll_y));
    ps->vx = 0.0;
    ps->vy = 0.0;
    ps->frac_x = 0.0;
    ps->frac_y = 0.0;
    /* Force the next tick to re-establish its dt reference instead of
     * integrating the whole time the pointer was suspended. */
    ps->last_tick_ms = 0;
    ps->next_tick_ms = 0;
    return emitted;
}

int pointer_tick(struct pointer_state *ps, int64_t now_ms,
                 session_emit_fn emit, void *ctx, struct pointer_shared *sh)
{
    int emitted = 0;
    double dt_s;

    if (!ps)
        return 0;

    pointer_age(ps, now_ms);
    if (!ps->on || !emit)
        return 0;
    if (ps->last_tick_ms == 0) {
        /* First tick after the switch turned on / release: establish the dt
         * reference and emit button state at once (a click must not wait a
         * tick), but integrate no motion yet. */
        ps->last_tick_ms = now_ms;
        ps->next_tick_ms = now_ms + POINTER_TICK_MS;
        dt_s = 0.0;
    } else {
        if (now_ms < ps->next_tick_ms)
            return 0;
        dt_s = (double)(now_ms - ps->last_tick_ms) / 1000.0;
        if (dt_s <= 0.0)
            return 0;
        if (dt_s > (double)POINTER_MAX_DT_MS / 1000.0)
            dt_s = (double)POINTER_MAX_DT_MS / 1000.0;
        ps->last_tick_ms = now_ms;
        ps->next_tick_ms = now_ms + POINTER_TICK_MS;
    }

    pointer_buttons(ps, emit, ctx, &emitted, sh);
    pointer_scroll(ps, now_ms, emit, ctx, &emitted);
    pointer_move(ps, dt_s, emit, ctx, &emitted);

    if (emitted > 0)
        emit(ctx, EV_SYN, SYN_REPORT, 0);
    return emitted;
}

int pointer_timeout_ms(const struct pointer_state *ps, int64_t now_ms)
{
    int64_t due;

    if (!ps || !ps->on)
        return -1;
    if (ps->last_tick_ms == 0)
        return 1;
    due = ps->next_tick_ms - now_ms;
    if (due < 1)
        return 1;
    if (due > 1000)
        due = 1000;
    return (int)due;
}
