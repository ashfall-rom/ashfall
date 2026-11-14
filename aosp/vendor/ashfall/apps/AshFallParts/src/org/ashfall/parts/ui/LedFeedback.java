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

package org.ashfall.parts.ui;

import org.ashfall.parts.hw.Led;
import org.ashfall.parts.ipc.Props;

import java.util.Locale;

/**
 * Publishes the gamepad tester's per-side LED reaction through
 * {@code ashfall.ui.led_override} (contract {@code docs/phase5.md} §6.1).
 *
 * <p>Value = {@code RRGGBB:RRGGBB} (left:right, final colours including
 * intensity); empty = normal lighting.  The daemon applies it at up to 30 Hz,
 * so this class rate-limits and only writes when the colour actually changes.
 * {@link #clear()} is always called when the tester leaves the foreground, and
 * the property is non-persistent, so a crash cannot leave the rings stuck.
 */
public final class LedFeedback {

    private static final String OVERRIDE = "ashfall.ui.led_override";
    private static final long MIN_INTERVAL_MS = 34; // ~29 Hz
    private static final float IDLE_GLOW = 0.06f;

    private final int mLeftBase;
    private final int mRightBase;
    private final float mBrightness;
    private long mLastPublish;
    private String mLastValue = "";

    public LedFeedback() {
        mLeftBase = Led.colorToArgb(Led.colorLeft());
        mRightBase = Led.colorToArgb(Led.colorRight());
        mBrightness = Led.brightness() / 100f;
    }

    /**
     * @param left  0..1 activity of the left half (left stick, D-pad, L1/L2/L3)
     * @param right 0..1 activity of the right half (face buttons, right stick, R1/R2/R3)
     */
    public void publish(float left, float right) {
        final long now = System.currentTimeMillis();
        if (now - mLastPublish < MIN_INTERVAL_MS) {
            return;
        }
        mLastPublish = now;
        final String value = scale(mLeftBase, left) + ":" + scale(mRightBase, right);
        if (value.equals(mLastValue)) {
            return;
        }
        mLastValue = value;
        Props.set(OVERRIDE, value);
    }

    /** Restores normal lighting (the daemon re-reads its own settings). */
    public void clear() {
        mLastValue = "";
        Props.set(OVERRIDE, "");
    }

    private String scale(int argb, float activity) {
        final float level = clamp(IDLE_GLOW + (1f - IDLE_GLOW) * clamp(activity)) * mBrightness;
        final int r = Math.round(((argb >> 16) & 0xFF) * level);
        final int g = Math.round(((argb >> 8) & 0xFF) * level);
        final int b = Math.round((argb & 0xFF) * level);
        return String.format(Locale.US, "%02X%02X%02X", r, g, b);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
