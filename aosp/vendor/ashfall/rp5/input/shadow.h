/*
 * AshFall RP5 input: per-pad pass-through "shadow" uinput device (#250.1).
 *
 * With the desktop pointer on and Gamepad Fusion off, every external pad is
 * EVIOCGRABbed by the daemon - Android would otherwise lose the pad entirely
 * while the daemon reads it for the pointer.  The shadow re-publishes it:
 * the same name, bustype and vendor/product/version identity and the same
 * key/abs capabilities, but the daemon's canonical axis ranges and the fusion
 * rescale helper for events, so there is no second normalisation convention.
 *
 * The shadow carries every event except the pointer-owned inputs: right
 * stick, left stick and the analog R2 are reported at rest, R1 is suppressed.
 * An input only *becomes* pointer-owned mid-session when the pointer device
 * is torn down and recreated, so the routing keeps the last value it wrote
 * per code and reports rest exactly once when consumption returns.
 *
 * Force feedback is not relayed through a shadow, so a shadow never
 * advertises EV_FF - rumble stays a GamepadFusion feature (docs/rp5/input.md,
 * "Sources and shadows").
 *
 * The spec construction and the event routing live here, free of file
 * descriptors, so the host tests drive them through struct uinput_dev_ops
 * and a fake sink (#156 style).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_SHADOW_H
#define ASHFALL_SHADOW_H

#include "fusion.h"
#include "session.h"
#include "uinput_dev.h"

#include <linux/input.h>

#include <stddef.h>
#include <stdint.h>

/* Everything the scan learned about one node, as the shadow needs it. */
struct shadow_source {
    const char *name;
    struct {
        uint16_t bustype;
        uint16_t vendor;
        uint16_t product;
        uint16_t version;
    } id;
    const uint8_t *keybits;               /* EVIOCGBIT(EV_KEY) */
    size_t keybits_len;
    const uint8_t *absbits;               /* EVIOCGBIT(EV_ABS) */
    size_t absbits_len;
    const struct fusion_absinfo *absinfo; /* [FUSION_ABS_MAX], by ABS code */
    const uint16_t *axis_src;             /* [FUSION_AXIS_COUNT], slot -> code */
};

/* Storage for the arrays a uinput_dev_spec points into.  One per pad. */
struct shadow_spec_buf {
    uint16_t keys[KEY_MAX + 1];
    int key_count;
    uint16_t axes[FUSION_ABS_MAX];
    int axis_count;
    /* Parallel to `axes`: the canonical range of the slot each code feeds,
     * or the pad's own range for codes that feed no slot. */
    struct input_absinfo absinfo[FUSION_ABS_MAX];
};

/* Build the shadow's uinput_dev_spec: the pad's identity and key/abs
 * capabilities, canonical ranges per fed slot, no EV_REL, no props and never
 * EV_FF (rumble is not relayed through a shadow). */
void shadow_spec(const struct shadow_source *src, struct shadow_spec_buf *buf,
                 struct uinput_dev_spec *spec);

/* The fusion slot a pad ABS code feeds, or -1. */
int shadow_axis_slot(const struct shadow_source *src, uint16_t code);

/* The event's value in canonical space; identity for codes that feed no
 * slot. */
int32_t shadow_rescale(const struct shadow_source *src, uint16_t code,
                       int32_t value);

/* What the shadow last wrote per ABS code, so an input that becomes
 * pointer-owned reports rest exactly once instead of sticking at a stale
 * value.  Starts at rest, which is what a fresh uinput device reports. */
struct shadow_state {
    int32_t last[FUSION_ABS_MAX];
    int tr_last;  /* BTN_TR state the shadow last reported */
    bool pending; /* events forwarded since the last SYN */
};

void shadow_state_init(struct shadow_state *ss);

/*
 * Route one event from the physical pad into the shadow.  `consume` is the
 * live pointer-device gate: while true, the pointer-owned inputs are
 * reported at rest instead of forwarded.  Returns the number of events
 * written; the caller forwards them and one SYN per forwarded report.
 */
int shadow_route_event(const struct shadow_source *src, struct shadow_state *ss,
                       bool consume, uint16_t type, uint16_t code,
                       int32_t value, session_emit_fn emit, void *ctx);

#endif /* ASHFALL_SHADOW_H */
