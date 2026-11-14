/*
 * AshFall RP5 input: calibration loading helper shared by the daemon and the
 * control server.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "input.h"
#include "plat.h"

#include <string.h>

void input_publish_cal_source(struct input_ctx *ctx)
{
    static int last_published = -1;

    if (!ctx)
        return;
    if (ctx->cal_source_published &&
        last_published == (int)ctx->cal_source)
        return;
    last_published = (int)ctx->cal_source;
    ctx->cal_source_published = true;
    prop_set_str(ASHFALL_PROP_CAL_SOURCE, cal_source_name(ctx->cal_source));
}

void input_reload_calibration(struct input_ctx *ctx)
{
    struct calibration previous;
    enum cal_source previous_source;
    bool changed;

    if (!ctx)
        return;
    previous = ctx->cal;
    previous_source = ctx->cal_source;
    ctx->cal_source = calib_load(&ctx->cal);
    changed = ctx->cal_source != previous_source ||
              memcmp(&previous, &ctx->cal, sizeof(previous)) != 0;

    /* #80: the calibration source is also a status property, so a lost boot
     * log or an init copy race is diagnosable from the UI and from tests. */
    input_publish_cal_source(ctx);

    if (changed && ctx->on_calibration_changed)
        ctx->on_calibration_changed(ctx);
}
