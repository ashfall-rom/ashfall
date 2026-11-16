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

import android.os.Handler;
import android.os.Looper;

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
 *
 * <p>A property alone cannot be crash-safe: a force-stop never runs
 * {@link #clear()}, so the daemon would keep applying the last colour forever.
 * While the tester is active this class therefore also refreshes
 * {@code ashfall.ui.led_override_live=1} every 500 ms, and the daemon drops the
 * override once that heartbeat is 1.5 s old (issue #94).  The heartbeat stops
 * when the publisher stops: {@link #clear()} stops it and clears both
 * properties, and a killed process stops writing it, so the rings return to
 * the configured lighting on their own.
 */
public final class LedFeedback {

    private static final String OVERRIDE = "ashfall.ui.led_override";
    private static final String OVERRIDE_LIVE = "ashfall.ui.led_override_live";
    private static final long MIN_INTERVAL_MS = 34; // ~29 Hz
    private static final long HEARTBEAT_MS = 500;
    private static final float IDLE_GLOW = 0.06f;

    private final int mLeftBase;
    private final int mRightBase;
    private final float mBrightness;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastPublish;
    private String mLastValue = "";
    private boolean mHeartbeat;

    private final Runnable mBeat = new Runnable() {
        @Override
        public void run() {
            if (!mHeartbeat) {
                return;
            }
            // Always write, even when the value is unchanged: the daemon
            // watches the property's write serial, not its value.
            Props.set(OVERRIDE_LIVE, "1");
            mHandler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    public LedFeedback() {
        mLeftBase = Led.colorToArgb(Led.colorLeft());
        mRightBase = Led.colorToArgb(Led.colorRight());
        mBrightness = Led.brightness() / 100f;
    }

    /**
     * Starts the override heartbeat.  Called when the tester becomes active
     * (it must run even while the sticks are idle and the colour is
     * unchanged); {@link #clear()} stops it again.
     */
    public void start() {
        if (mHeartbeat) {
            return;
        }
        mHeartbeat = true;
        mHandler.removeCallbacks(mBeat);
        mHandler.post(mBeat);
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

    /**
     * Restores normal lighting (the daemon re-reads its own settings) and
     * stops the heartbeat.  Safe to call more than once.
     */
    public void clear() {
        mHeartbeat = false;
        mHandler.removeCallbacks(mBeat);
        mLastValue = "";
        Props.set(OVERRIDE, "");
        Props.set(OVERRIDE_LIVE, "");
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
