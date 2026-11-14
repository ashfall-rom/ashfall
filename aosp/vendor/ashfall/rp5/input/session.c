/*
 * AshFall RP5 input: MCU session lifecycle.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "session.h"

#include <linux/input.h>

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
    if (axis_codes) {
        for (int i = 0; i < st->axis_count && i < SESSION_MAX_AXES; i++) {
            if (st->axes[i] == INT_MIN || st->axes[i] == 0)
                continue;
            emit(ctx, EV_ABS, axis_codes[i], 0);
            emitted++;
        }
    }
    if (emitted > 0)
        emit(ctx, EV_SYN, SYN_REPORT, 0);

    st->keys = 0;
    for (int i = 0; i < st->axis_count && i < SESSION_MAX_AXES; i++)
        st->axes[i] = INT_MIN;
    return emitted;
}
