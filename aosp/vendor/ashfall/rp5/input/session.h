/*
 * AshFall RP5 input: MCU session lifecycle.
 *
 * When the MCU stops talking (its power is cycled on screen off/on) or the
 * UART is lost, Android must not keep seeing a pressed button or a deflected
 * stick.  This module turns "what we last reported" into the release events.
 *
 * Kept free of uinput/sysfs so the host tests can drive it with a fake sink.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_SESSION_H
#define ASHFALL_SESSION_H

#include <stdint.h>

#include <limits.h>

#define SESSION_KEY_BITS 16
#define SESSION_MAX_AXES 8

typedef struct {
    uint16_t keys;              /* key word last reported to Android */
    int axes[SESSION_MAX_AXES]; /* axis values last reported */
    int axis_count;
} session_state;

/* EV_KEY/E EV_ABS/EV_SYN sink; `ctx` is whatever session_release() got. */
typedef void (*session_emit_fn)(void *ctx, uint16_t type, uint16_t code,
                                int32_t value);

/*
 * Release everything Android may still think is held: one key-up per set key
 * bit that has a mapping (0 = no key, e.g. the D-pad bits) and one 0 (rest /
 * centre) for every axis that is not already at 0 or INT_MIN (dirty).  A
 * single SYN follows the batch.  The state is then cleared (keys = 0, every
 * axis INT_MIN) so the next frame re-reports all axes.
 *
 * Returns the number of events emitted, not counting the SYN.
 */
int session_release(session_state *st, const uint16_t *keymap,
                    const uint16_t *axis_codes, session_emit_fn emit,
                    void *ctx);

/*
 * The same release rules for the fusion pad (#138), whose merged key set does
 * not fit the MCU's 16-bit key word: `key_bits` is a little-endian bit array
 * indexed by Linux key code (word * 32 + bit), `key_words` its length.  The
 * axis half is shared with session_release().
 *
 * Returns the number of events emitted, not counting the SYN.
 */
int session_release_bits(uint32_t *key_bits, int key_words, int *axes,
                         const uint16_t *axis_codes, int axis_count,
                         session_emit_fn emit, void *ctx);

#endif /* ASHFALL_SESSION_H */
