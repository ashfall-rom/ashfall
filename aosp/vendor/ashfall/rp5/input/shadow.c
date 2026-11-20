/*
 * AshFall RP5 input: per-pad pass-through "shadow" uinput device (#250.1).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "shadow.h"

#include <string.h>

void shadow_spec(const struct shadow_source *src, struct shadow_spec_buf *buf,
                 struct uinput_dev_spec *spec)
{
    if (!src || !buf || !spec)
        return;
    memset(buf, 0, sizeof(*buf));
    /* The shadow owns every field: no relative axes, no props, never FF. */
    memset(spec, 0, sizeof(*spec));

    for (int code = 1; code <= KEY_MAX; code++) {
        if ((size_t)(code / 8) < src->keybits_len &&
            fusion_bits_test(src->keybits, code))
            buf->keys[buf->key_count++] = (uint16_t)code;
    }
    for (int code = 0; code <= ABS_MAX; code++) {
        int slot = shadow_axis_slot(src, (uint16_t)code);
        struct input_absinfo *out;

        if (!((size_t)(code / 8) < src->absbits_len &&
              fusion_bits_test(src->absbits, code)))
            continue;
        out = &buf->absinfo[buf->axis_count];
        buf->axes[buf->axis_count++] = (uint16_t)code;
        /* A code that feeds a slot carries the canonical range of that slot
         * (the HID convention puts a trigger on ABS_Z; it must not become a
         * right-stick-shaped axis).  Anything else is a faithful pass-through
         * with the pad's own range. */
        if (slot >= 0)
            fusion_slot_axis_range(slot, &out->minimum, &out->maximum);
        else if (src->absinfo && src->absinfo[code].present)
            *out = (struct input_absinfo){
                .minimum = src->absinfo[code].minimum,
                .maximum = src->absinfo[code].maximum,
            };
    }

    spec->name = src->name;
    spec->bustype = src->id.bustype;
    spec->vendor = src->id.vendor;
    spec->product = src->id.product;
    spec->version = src->id.version;
    spec->keys = buf->keys;
    spec->key_count = buf->key_count;
    spec->axes = buf->axes;
    spec->axis_count = buf->axis_count;
    spec->absinfo = buf->absinfo;
    /* Rumble is not relayed through a shadow: no EV_FF, ff_effects_max 0. */
    spec->rumble = false;
}

int shadow_axis_slot(const struct shadow_source *src, uint16_t code)
{
    if (!src || !src->axis_src)
        return -1;
    for (int slot = 0; slot < FUSION_AXIS_COUNT; slot++) {
        if (src->axis_src[slot] == code)
            return slot;
    }
    return -1;
}

int32_t shadow_rescale(const struct shadow_source *src, uint16_t code,
                       int32_t value)
{
    int slot = shadow_axis_slot(src, code);

    if (slot < 0 || code > ABS_MAX || !src || !src->absinfo)
        return value;
    return fusion_pad_rescale(&src->absinfo[code], slot, value);
}

void shadow_state_init(struct shadow_state *ss)
{
    if (!ss)
        return;
    memset(ss, 0, sizeof(*ss));
    /* A fresh uinput device reports every axis at rest. */
    for (int code = 0; code <= ABS_MAX; code++)
        ss->last[code] = 0;
}

int shadow_route_event(const struct shadow_source *src, struct shadow_state *ss,
                       bool consume, uint16_t type, uint16_t code,
                       int32_t value, session_emit_fn emit, void *ctx)
{
    if (!ss || !emit)
        return 0;

    if (type == EV_SYN) {
        /* The pad's own report markers end a batch: forward one only when
         * something was forwarded since the last one. */
        if (code == SYN_REPORT && ss->pending) {
            ss->pending = false;
            emit(ctx, EV_SYN, SYN_REPORT, 0);
            return 1;
        }
        return 0;
    }
    if (type == EV_FF)
        return 0; /* never advertised on a shadow: rumble stays fusion-only */

    if (type == EV_KEY) {
        if (consume && code == FUSION_POINTER_OWNED_KEY) {
            /* R1 belongs to the pointer.  If a gate-off window let a press
             * through, report rest exactly once, then drop. */
            if (ss->tr_last != 0) {
                ss->tr_last = 0;
                ss->pending = true;
                emit(ctx, EV_KEY, code, 0);
                return 1;
            }
            return 0;
        }
        if (code == FUSION_POINTER_OWNED_KEY)
            ss->tr_last = value != 0;
        ss->pending = true;
        emit(ctx, EV_KEY, code, value);
        return 1;
    }

    if (type == EV_ABS) {
        int slot = shadow_axis_slot(src, code);

        if (consume && slot >= 0 && fusion_slot_pointer_owned(slot)) {
            if (code <= ABS_MAX && ss->last[code] != 0) {
                ss->last[code] = 0;
                ss->pending = true;
                emit(ctx, EV_ABS, code, 0);
                return 1;
            }
            return 0;
        }
        {
            int32_t out = shadow_rescale(src, code, value);

            if (code <= ABS_MAX)
                ss->last[code] = out;
            ss->pending = true;
            emit(ctx, EV_ABS, code, out);
            return 1;
        }
    }

    /* EV_MSC, EV_LED and friends: a faithful pass-through, the shadow is
     * the pad as far as Android can tell. */
    ss->pending = true;
    emit(ctx, type, code, value);
    return 1;
}
