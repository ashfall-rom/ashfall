/*
 * Shared state between the MCU/uinput loop and the control socket server.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_INPUT_H
#define ASHFALL_INPUT_H

#include "calib.h"
#include "mapping.h"

#include <stdbool.h>

struct fusion_state;

struct raw_sample {
    int lx, ly, rx, ry;  /* signed stick ADC counts */
    int l2, r2;          /* unsigned trigger ADC counts */
    unsigned int buttons; /* 16-bit MCU key word */
};

#define ASHFALL_PROP_CAL_SOURCE "ashfall.status.pad.cal_source"

struct input_ctx {
    struct calibration cal;
    enum cal_source cal_source;
    bool cal_source_published;
    struct deadzones dz;

    /* GamepadFusion state (#138); NULL when the daemon has not wired it. */
    struct fusion_state *fusion;

    /* Fired after SET CAL / RESET CAL so the caller can log/report. */
    void (*on_calibration_changed)(struct input_ctx *ctx);
};

/* Load user -> factory copy -> built-in defaults into ctx->cal. */
void input_reload_calibration(struct input_ctx *ctx);

/* Publish ashfall.status.pad.cal_source (`user`/`factory`/`default`) when it
 * changed.  #80: the app and the tests can check the source without the
 * control socket, and a late factory copy is visible without a restart. */
void input_publish_cal_source(struct input_ctx *ctx);

#endif /* ASHFALL_INPUT_H */
