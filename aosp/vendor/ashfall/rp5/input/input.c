/*
 * AshFall RP5 input: calibration loading helper shared by the daemon and the
 * control server.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "input.h"

void input_reload_calibration(struct input_ctx *ctx)
{
    if (!ctx)
        return;
    ctx->cal_source = calib_load(&ctx->cal);
    if (ctx->on_calibration_changed)
        ctx->on_calibration_changed(ctx);
}
