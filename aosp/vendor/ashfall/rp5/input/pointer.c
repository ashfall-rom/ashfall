/*
 * AshFall RP5 input: desktop-mode gamepad pointer (#131, workstream B).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "pointer.h"
#include "mapping.h"
#include "plat.h"

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

void pointer_defaults(struct pointer_state *ps)
{
    if (!ps)
        return;
    memset(ps, 0, sizeof(*ps));
    ps->mode = POINTER_MODE_GAME;
}

bool pointer_mode_parse(const char *text, enum pointer_mode *out)
{
    if (!text)
        return false;
    if (!strcmp(text, "game")) {
        if (out)
            *out = POINTER_MODE_GAME;
        return true;
    }
    if (!strcmp(text, "desktop")) {
        if (out)
            *out = POINTER_MODE_DESKTOP;
        return true;
    }
    return false;
}

const char *pointer_mode_name(enum pointer_mode mode)
{
    return mode == POINTER_MODE_DESKTOP ? "desktop" : "game";
}

bool pointer_mode_read(struct pointer_state *ps)
{
    char text[16];
    enum pointer_mode mode = POINTER_MODE_GAME;

    if (!ps)
        return false;
    if (prop_get_str(PROP_PAD_MODE, text, sizeof(text)) < 0 || !text[0])
        snprintf(text, sizeof(text), "%s", "game");
    if (!strcmp(text, ps->mode_text))
        return false;
    snprintf(ps->mode_text, sizeof(ps->mode_text), "%s", text);
    if (!pointer_mode_parse(text, &mode)) {
        ah_logf(AH_LOG_WARN,
                "%s=%s is not game|desktop, using game", PROP_PAD_MODE, text);
        mode = POINTER_MODE_GAME;
    }
    if (mode == ps->mode)
        return false;
    ps->mode = mode;
    return true;
}

bool pointer_set_mode(struct pointer_state *ps, enum pointer_mode mode)
{
    if (!ps || ps->mode == mode)
        return false;
    ps->mode = mode;
    return true;
}

void pointer_frame(struct pointer_state *ps, uint16_t keys, int rx, int ry,
                   int l2, int r2, int64_t now_ms)
{
    if (!ps)
        return;
    ps->keys = keys;
    ps->rx = rx;
    ps->ry = ry;
    ps->l2 = l2;
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
    ps->rx = 0;
    ps->ry = 0;
    ps->l2 = 0;
    ps->r2 = 0;
    ps->have_frame = false;
}

static void pointer_chord(struct pointer_state *ps, int64_t now_ms, bool *chord)
{
    bool held = (ps->keys & POINTER_CHORD_MASK) == POINTER_CHORD_MASK;

    if (!held) {
        ps->chord_since_ms = 0;
        ps->chord_latched = false;
        return;
    }
    if (ps->chord_latched)
        return;
    if (ps->chord_since_ms == 0) {
        ps->chord_since_ms = now_ms;
        return;
    }
    if (now_ms - ps->chord_since_ms >= POINTER_CHORD_MS) {
        ps->chord_latched = true;
        if (chord)
            *chord = true;
    }
}

static bool trigger_down(int value, bool was_down)
{
    if (value >= POINTER_TRIGGER_ON)
        return true;
    if (value <= POINTER_TRIGGER_OFF)
        return false;
    return was_down;
}

static void pointer_buttons(struct pointer_state *ps, session_emit_fn emit,
                            void *ctx, int *emitted)
{
    bool left;
    bool right;
    bool middle;

    ps->l2_down = trigger_down(ps->l2, ps->l2_down);
    ps->r2_down = trigger_down(ps->r2, ps->r2_down);

    /* Two buttons and the drag trigger share BTN_LEFT: it is up only when no
     * source is active, so releasing one while the other is held cannot drop
     * a drag (or a click) early. */
    left = ((ps->keys & (POINTER_KEY_A | POINTER_KEY_R3)) != 0) || ps->l2_down;
    right = (ps->keys & POINTER_KEY_B) != 0;
    middle = ps->r2_down;

    if (left != ps->btn_left) {
        ps->btn_left = left;
        emit(ctx, EV_KEY, BTN_LEFT, left);
        (*emitted)++;
    }
    if (right != ps->btn_right) {
        ps->btn_right = right;
        emit(ctx, EV_KEY, BTN_RIGHT, right);
        (*emitted)++;
    }
    if (middle != ps->btn_middle) {
        ps->btn_middle = middle;
        emit(ctx, EV_KEY, BTN_MIDDLE, middle);
        (*emitted)++;
    }
}

static void wheel_repeat(struct pointer_state *ps, bool held, bool *was_held,
                         int64_t *next_ms, int64_t now_ms, int32_t direction,
                         session_emit_fn emit, void *ctx, int *emitted)
{
    if (!held) {
        *was_held = false;
        return;
    }
    if (!*was_held) {
        *was_held = true;
        *next_ms = now_ms + POINTER_REPEAT_DELAY_MS;
        emit(ctx, EV_REL, REL_WHEEL, direction);
        (*emitted)++;
        return;
    }
    if (now_ms < *next_ms)
        return;
    emit(ctx, EV_REL, REL_WHEEL, direction);
    (*emitted)++;
    *next_ms += POINTER_REPEAT_MS;
    /* A long stall must not turn into a burst of notches. */
    if (*next_ms <= now_ms)
        *next_ms = now_ms + POINTER_REPEAT_MS;
}

static void pointer_wheel(struct pointer_state *ps, int64_t now_ms,
                          session_emit_fn emit, void *ctx, int *emitted)
{
    wheel_repeat(ps, (ps->keys & POINTER_KEY_L1) != 0, &ps->l1_held,
                 &ps->l1_next_ms, now_ms, 1, emit, ctx, emitted);
    wheel_repeat(ps, (ps->keys & POINTER_KEY_R1) != 0, &ps->r1_held,
                 &ps->r1_next_ms, now_ms, -1, emit, ctx, emitted);
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

int pointer_release(struct pointer_state *ps, session_emit_fn emit, void *ctx)
{
    int emitted = 0;

    if (!ps)
        return 0;
    if (emit) {
        if (ps->btn_left) {
            emit(ctx, EV_KEY, BTN_LEFT, 0);
            emitted++;
        }
        if (ps->btn_right) {
            emit(ctx, EV_KEY, BTN_RIGHT, 0);
            emitted++;
        }
        if (ps->btn_middle) {
            emit(ctx, EV_KEY, BTN_MIDDLE, 0);
            emitted++;
        }
        if (emitted > 0)
            emit(ctx, EV_SYN, SYN_REPORT, 0);
    }

    ps->btn_left = false;
    ps->btn_right = false;
    ps->btn_middle = false;
    ps->l2_down = false;
    ps->r2_down = false;
    ps->l1_held = false;
    ps->r1_held = false;
    ps->l1_next_ms = 0;
    ps->r1_next_ms = 0;
    ps->vx = 0.0;
    ps->vy = 0.0;
    ps->frac_x = 0.0;
    ps->frac_y = 0.0;
    ps->chord_since_ms = 0;
    ps->chord_latched = false;
    /* Force the next desktop tick to re-establish its dt reference instead of
     * integrating the whole time the pointer was suspended. */
    ps->last_tick_ms = 0;
    ps->next_tick_ms = 0;
    return emitted;
}

int pointer_tick(struct pointer_state *ps, int64_t now_ms,
                 session_emit_fn emit, void *ctx, bool *chord)
{
    int emitted = 0;
    double dt_s;

    if (!ps)
        return 0;
    if (chord)
        *chord = false;

    pointer_age(ps, now_ms);
    pointer_chord(ps, now_ms, chord);

    if (ps->mode != POINTER_MODE_DESKTOP || !emit)
        return 0;
    if (ps->last_tick_ms == 0) {
        /* First tick after mode entry / release: establish the dt reference
         * and emit button state at once (a click must not wait a tick), but
         * integrate no motion yet. */
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

    pointer_buttons(ps, emit, ctx, &emitted);
    pointer_wheel(ps, now_ms, emit, ctx, &emitted);
    pointer_move(ps, dt_s, emit, ctx, &emitted);

    if (emitted > 0)
        emit(ctx, EV_SYN, SYN_REPORT, 0);
    return emitted;
}

int pointer_timeout_ms(const struct pointer_state *ps, int64_t now_ms)
{
    int64_t due;

    if (!ps || ps->mode != POINTER_MODE_DESKTOP)
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
