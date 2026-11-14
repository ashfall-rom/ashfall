/*
 * Copyright (C) 2026 The AshFall Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ashfall.parts.hw;

import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.RawSample;

/**
 * Calibrated, deadzoned controller state in Android units.
 *
 * <p>The tester normally has no {@code MotionEvent} path at all: while
 * {@code GRAB ON} is active the daemon injects nothing into uinput (contract
 * {@code docs/phase5.md} §6.2), so the only input is the raw MCU stream.  The
 * display therefore re-applies the same calibration -> deadzone pipeline the
 * daemon applies ({@code docs/rp5/input.md}), which also lets the raw MCU counts
 * be shown next to the values Android would have received.
 *
 * <p>Stick axes are -1..1, triggers 0..1.  The MCU's ADC direction is opposite
 * to Android's, so {@code axis = center - raw} (the stock converter multiplies
 * by -1 as well).
 */
public final class PadState {

    /** Factory values of the live unit (docs/rp5/input.md); used until GET CAL works. */
    private static final float[] DEFAULT_AXES = {
            // min, center, max: left_x, left_y, right_x, right_y
            -1134f, 125f, 1196f,
            -1146f, 31f, 1174f,
            -1121f, 82f, 1218f,
            -1167f, -6f, 1192f,
    };
    private static final float[] DEFAULT_TRIGGERS = {
            // min, max: l2, r2
            92f, 1266f,
            0f, 1362f,
    };

    private static final String[] AXIS_KEYS = {
            "left_x", "left_y", "right_x", "right_y",
    };
    private static final String[] TRIGGER_KEYS = {"l2", "r2"};

    public float leftX;
    public float leftY;
    public float rightX;
    public float rightY;
    public float l2;
    public float r2;

    public static PadState rest() {
        return new PadState();
    }

    /** Converts one raw MCU sample into Android units (calibration + deadzones). */
    public static PadState from(RawSample sample, CalData calibration) {
        final PadState state = new PadState();
        if (sample == null) {
            return state;
        }
        final float[] axes = new float[4];
        final float[] values = {
                sample.leftX, sample.leftY, sample.rightX, sample.rightY,
        };
        for (int i = 0; i < 4; i++) {
            final float min = calibration != null && calibration.has(AXIS_KEYS[i] + "_min")
                    ? calibration.get(AXIS_KEYS[i] + "_min", 0) : DEFAULT_AXES[i * 3];
            final float center = calibration != null && calibration.has(AXIS_KEYS[i] + "_center")
                    ? calibration.get(AXIS_KEYS[i] + "_center", 0) : DEFAULT_AXES[i * 3 + 1];
            final float max = calibration != null && calibration.has(AXIS_KEYS[i] + "_max")
                    ? calibration.get(AXIS_KEYS[i] + "_max", 0) : DEFAULT_AXES[i * 3 + 2];
            axes[i] = axis(values[i], min, center, max);
        }
        final float leftLength = (float) Math.hypot(axes[0], axes[1]);
        final float rightLength = (float) Math.hypot(axes[2], axes[3]);
        final float[] left = deadzone(axes[0], axes[1], leftLength);
        final float[] right = deadzone(axes[2], axes[3], rightLength);
        state.leftX = left[0];
        state.leftY = left[1];
        state.rightX = right[0];
        state.rightY = right[1];
        state.l2 = trigger(sample.l2, triggerRange(calibration, 0));
        state.r2 = trigger(sample.r2, triggerRange(calibration, 1));
        return state;
    }

    /** Values that already arrived calibrated (the MotionEvent fallback). */
    public static PadState ofAxes(float leftX, float leftY, float rightX, float rightY,
            float l2, float r2) {
        final PadState state = new PadState();
        state.leftX = clamp(leftX, -1f, 1f);
        state.leftY = clamp(leftY, -1f, 1f);
        state.rightX = clamp(rightX, -1f, 1f);
        state.rightY = clamp(rightY, -1f, 1f);
        state.l2 = clamp(l2, 0f, 1f);
        state.r2 = clamp(r2, 0f, 1f);
        return state;
    }

    public float stickLength(boolean left) {
        return (float) Math.hypot(left ? leftX : rightX, left ? leftY : rightY);
    }

    private static float[] triggerRange(CalData calibration, int index) {
        final String key = TRIGGER_KEYS[index];
        final float min = calibration != null && calibration.has(key + "_min")
                ? calibration.get(key + "_min", 0) : DEFAULT_TRIGGERS[index * 2];
        final float max = calibration != null && calibration.has(key + "_max")
                ? calibration.get(key + "_max", 0) : DEFAULT_TRIGGERS[index * 2 + 1];
        return new float[] {min, max};
    }

    /** {@code (center - raw)} scaled by the travel to each side. */
    private static float axis(float raw, float min, float center, float max) {
        if (raw <= center) {
            final float span = center - min;
            if (span <= 1f) {
                return 0f;
            }
            return clamp((center - raw) / span, -1f, 1f);
        }
        final float span = max - center;
        if (span <= 1f) {
            return 0f;
        }
        return clamp(-(raw - center) / span, -1f, 1f);
    }

    /** Radial inner deadzone with the remaining travel rescaled, then the outer cut. */
    private static float[] deadzone(float x, float y, float length) {
        final float inner = Pad.inner() / 100f;
        final float outer = Pad.outer() / 100f;
        if (length <= inner || length <= 0.0001f) {
            return new float[] {0f, 0f};
        }
        float scaled = (length - inner) / Math.max(0.0001f, 1f - inner);
        final float full = Math.max(0.01f, 1f - outer);
        scaled = Math.min(1f, scaled / full);
        return new float[] {x / length * scaled, y / length * scaled};
    }

    /** At rest the raw trigger counts are above max; pulling lowers them towards min. */
    private static float trigger(float raw, float[] range) {
        final float min = range[0];
        final float max = range[1];
        final float span = max - min;
        if (span <= 1f) {
            return 0f;
        }
        final float value = clamp((max - raw) / span, 0f, 1f);
        final float deadzone = Pad.trigger() / 100f;
        if (value <= deadzone) {
            return 0f;
        }
        return clamp((value - deadzone) / Math.max(0.0001f, 1f - deadzone), 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
