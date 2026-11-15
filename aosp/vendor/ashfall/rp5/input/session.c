/*
 * AshFall RP5 input: MCU session lifecycle.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "session.h"

#include <string.h>

#include <linux/input.h>

/* Shared tail of both release paths: a 0 (rest) for every axis that is not
 * already at rest or dirty, then the state is marked dirty so the next frame
 * re-reports every axis. */
static int release_axes(int *axes, int axis_count, const uint16_t *axis_codes,
                        session_emit_fn emit, void *ctx)
{
    int emitted = 0;

    if (!axes || !axis_codes)
        return 0;
    for (int i = 0; i < axis_count && i < SESSION_MAX_AXES; i++) {
        if (axes[i] == INT_MIN || axes[i] == 0)
            continue;
        emit(ctx, EV_ABS, axis_codes[i], 0);
        emitted++;
    }
    return emitted;
}

static void mark_axes_dirty(int *axes, int axis_count)
{
    if (!axes)
        return;
    for (int i = 0; i < axis_count && i < SESSION_MAX_AXES; i++)
        axes[i] = INT_MIN;
}

int session_release(session_state *st, const uint16_t *keymap,
                    const uint16_t *axis_codes, session_emit_fn emit,
                    void *ctx)
{
    int emitted = 0;

    if (!st || !emit)
        return 0;

    if (keymap) {
        for (int i = 0; i < SESSION_KEY_BITS; i++) {
            if (!(st->keys & (1u << i)) || keymap[i] == 0)
                continue;
            emit(ctx, EV_KEY, keymap[i], 0);
            emitted++;
        }
    }
    emitted += release_axes(st->axes, st->axis_count, axis_codes, emit, ctx);
    if (emitted > 0)
        emit(ctx, EV_SYN, SYN_REPORT, 0);

    st->keys = 0;
    mark_axes_dirty(st->axes, st->axis_count);
    return emitted;
}

int session_release_bits(uint32_t *key_bits, int key_words, int *axes,
                         const uint16_t *axis_codes, int axis_count,
                         session_emit_fn emit, void *ctx)
{
    int emitted = 0;

    if (!emit)
        return 0;

    if (key_bits) {
        for (int w = 0; w < key_words; w++) {
            uint32_t bits = key_bits[w];

            while (bits) {
                int bit = __builtin_ctz(bits);

                bits &= bits - 1;
                emit(ctx, EV_KEY, (uint16_t)(w * 32 + bit), 0);
                emitted++;
            }
            key_bits[w] = 0;
        }
    }
    emitted += release_axes(axes, axis_count, axis_codes, emit, ctx);
    if (emitted > 0)
        emit(ctx, EV_SYN, SYN_REPORT, 0);

    mark_axes_dirty(axes, axis_count);
    return emitted;
}
