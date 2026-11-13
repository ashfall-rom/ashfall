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

struct raw_sample {
    int lx, ly, rx, ry;  /* signed stick ADC counts */
    int l2, r2;          /* unsigned trigger ADC counts */
    unsigned int buttons; /* 16-bit MCU key word */
};

struct input_ctx {
    struct calibration cal;
    enum cal_source cal_source;
    struct deadzones dz;

    /* Fired after SET CAL / RESET CAL so the caller can log/report. */
    void (*on_calibration_changed)(struct input_ctx *ctx);
};

/* Load user -> factory copy -> built-in defaults into ctx->cal. */
void input_reload_calibration(struct input_ctx *ctx);

#endif /* ASHFALL_INPUT_H */
