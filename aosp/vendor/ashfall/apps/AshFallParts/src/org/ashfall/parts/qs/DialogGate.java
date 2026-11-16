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

package org.ashfall.parts.qs;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * One in-flight guard per tile, so a burst of taps opens one dialog (#166).
 *
 * <p>{@code TileService.showDialog()} is asynchronous: SystemUI expands the
 * panel, binds the tile service and only then attaches the dialog.  Right after
 * a boot that first bind is slow (cold process, class load, theme), and every
 * tap in that window used to build and queue another dialog — they all appeared
 * at once, stacked on top of each other.  The tile services now
 * {@link #begin() the gate} on the tap and stay closed until the card is
 * actually on screen:
 *
 * <ul>
 *   <li>{@link #track(Dialog)} reopens the gate when the dialog is shown and,
 *       as a belt and braces, when it is dismissed;</li>
 *   <li>a backstop reopens it after {@link #GIVE_UP_MS}, so a
 *       {@code showDialog()} SystemUI drops can never wedge the tile.  The
 *       backstop is generous on purpose: it must not expire while a cold first
 *       bind is still on its way, or the taps it is meant to swallow would
 *       queue a second dialog again;</li>
 *   <li>taps while the gate is closed are dropped, not turned into a
 *       dismissal: dismissing would fight the panel and lose the dialog the
 *       first tap is still opening.</li>
 * </ul>
 *
 * <p>The gate is per service instance, not static: each tile has its own
 * dialog, and tapping Fusion while Power is still opening must not swallow the
 * Fusion tap.
 *
 * <p>{@link #begin()} records the tap time and {@link #track(Dialog)} logs how
 * long the dialog took to appear ({@code adb logcat -s AshFallParts}), which is
 * how the startup latency is measured (docs/parts.md §3).
 */
final class DialogGate {

    private static final String TAG = "AshFallParts";

    /** How long a tap may wait for its dialog before the gate gives up. */
    private static final long GIVE_UP_MS = 8000;

    private final String mName;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mGiveUp;

    private boolean mBusy;
    private long mTapMs;

    /** @param name the tile's short name, used in the log lines. */
    DialogGate(String name) {
        mName = name;
        mGiveUp = () -> {
            Log.w(TAG, mName + ": no dialog after " + GIVE_UP_MS + " ms, tile released");
            release();
        };
    }

    /**
     * Claims the gate for this tap.
     *
     * @return {@code true} when the caller should open the dialog,
     *         {@code false} when one is already opening and this tap is a no-op.
     */
    boolean begin() {
        if (mBusy) {
            Log.i(TAG, mName + ": tap ignored, the dialog is still opening");
            return false;
        }
        mBusy = true;
        mTapMs = SystemClock.elapsedRealtime();
        mHandler.removeCallbacks(mGiveUp);
        mHandler.postDelayed(mGiveUp, GIVE_UP_MS);
        return true;
    }

    /**
     * Attaches the gate to a dialog that is about to be handed to
     * {@code showDialog()}.
     *
     * @return the same dialog, for chaining.
     */
    Dialog track(Dialog dialog) {
        dialog.setOnShowListener(shown -> {
            Log.i(TAG, mName + ": dialog shown "
                    + (SystemClock.elapsedRealtime() - mTapMs) + " ms after the tap");
            release();
        });
        dialog.setOnDismissListener(dismissed -> release());
        return dialog;
    }

    /** Reopens the gate; safe to call more than once. */
    void release() {
        mBusy = false;
        mHandler.removeCallbacks(mGiveUp);
    }
}
