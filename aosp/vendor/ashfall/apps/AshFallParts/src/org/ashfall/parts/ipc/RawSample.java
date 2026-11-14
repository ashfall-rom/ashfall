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

package org.ashfall.parts.ipc;

/**
 * One {@code RAW lx ly rx ry l2 r2 buttons} packet (raw, uncalibrated values).
 *
 * <p>The daemon sends the values in its own raw units (the same units as the
 * stock {@code joys} calibration), so consumers must treat them as opaque
 * numbers: the calibration wizard stores min/center/max in these units, and the
 * tester shows them verbatim next to the calibrated {@code MotionEvent} values.
 */
public final class RawSample {

    public final float leftX;
    public final float leftY;
    public final float rightX;
    public final float rightY;
    public final float l2;
    public final float r2;
    public final int buttons;

    public RawSample(float leftX, float leftY, float rightX, float rightY, float l2, float r2,
            int buttons) {
        this.leftX = leftX;
        this.leftY = leftY;
        this.rightX = rightX;
        this.rightY = rightY;
        this.l2 = l2;
        this.r2 = r2;
        this.buttons = buttons;
    }

    public static RawSample parse(String line) {
        if (line == null) {
            return null;
        }
        final String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 8 || !"RAW".equals(tokens[0])) {
            return null;
        }
        try {
            return new RawSample(Float.parseFloat(tokens[1]), Float.parseFloat(tokens[2]),
                    Float.parseFloat(tokens[3]), Float.parseFloat(tokens[4]),
                    Float.parseFloat(tokens[5]), Float.parseFloat(tokens[6]),
                    Integer.parseInt(tokens[7].replace("0x", ""), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
