/*
 * AshFall RP5 gamepad mapping: raw MCU counts -> Android axis values, plus the
 * user deadzones that apply after calibration to every app and emulator.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_MAPPING_H
#define ASHFALL_MAPPING_H

struct deadzones {
    int inner_pct;   /* 0..30 of stick travel, radial */
    int outer_pct;   /* 0..20 cut at the rim, above maps to full */
    int trigger_pct; /* 0..30 of trigger travel */
};

#define PROP_DZ_INNER "persist.ashfall.pad.deadzone_inner"
#define PROP_DZ_OUTER "persist.ashfall.pad.deadzone_outer"
#define PROP_DZ_TRIGGER "persist.ashfall.pad.trigger_deadzone"

void deadzones_default(struct deadzones *dz);

/* Read persist.ashfall.pad.* into *dz.  Missing or out-of-range values use
 * the documented defaults (5/3/3, docs/phase5.md §2.3) instead of being
 * clamped, and are logged once. */
void deadzones_read(struct deadzones *dz);

/* Calibrated stick axis value in -32768..32767.  The MCU ADC direction is
 * opposite to Android's, so the raw value is mirrored around the centre (same
 * sign convention as the stock librsinput.so converter). */
int stick_map(int raw, double center, double min, double max);

/* Calibrated trigger value 0..255.  Triggers rest above their calibrated
 * maximum and pulling lowers the raw value. */
int trigger_map(int raw, double min, double max);

/* Radial inner deadzone + outer cut, applied to the calibrated x/y pair. */
void stick_apply_deadzone(int *x, int *y, int inner_pct, int outer_pct);

/* Trigger deadzone on a 0..255 value. */
int trigger_apply_deadzone(int value, int trigger_pct);

#endif /* ASHFALL_MAPPING_H */
