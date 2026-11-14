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

package org.ashfall.parts;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import org.ashfall.parts.hw.PadState;
import org.ashfall.parts.ipc.CalClient;
import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.RawSample;
import org.ashfall.parts.ipc.RawStream;
import org.ashfall.parts.ui.ControllerTesterView;
import org.ashfall.parts.ui.LedFeedback;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full-screen controller tester.
 *
 * <p>While the tester is visible it owns the daemon's input: {@code GRAB ON} is
 * sent on the raw-stream socket, so the daemon injects nothing into uinput and
 * every button (A/B/X/Y, shoulders, triggers, Start, Select, Home, Back and the
 * D-pad) is reported only to this app's {@code RAW} frames.  That is the only
 * way Home/Back/Select can be observed, since the system would otherwise
 * consume them first (issue #84).
 *
 * <p>Leaving: hold <b>Start + Select for 2 s</b> or tap the on-screen
 * <b>Close</b> button.  (With the pad grabbed no key event reaches the app, so
 * the hold is detected from the RAW mask.)  B/Back still leaves when the daemon
 * is not reachable and the normal key path is used.
 *
 * <p>The stick rings react to the activity of their own half through
 * {@code ashfall.ui.led_override}; normal lighting is restored in
 * {@code onPause}.
 */
public class TesterActivity extends PartsActivity {

    private static final long SAMPLE_STALE_MS = 400;
    private static final long EXIT_HOLD_MS = 2000;

    private ControllerTesterView mView;
    private TextView mHint;
    private Button mClose;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<RawSample> mLatest = new AtomicReference<>();
    private RawStream mRawStream;
    private LedFeedback mLed;
    private boolean mDrainPosted;
    private long mLastDrain;
    private long mLastSampleAt;
    private long mHoldStart;

    private final Runnable mDrain = this::drain;

    @Override
    protected int getPageTitle() {
        return R.string.tester_title;
    }

    @Override
    protected int getContentLayout() {
        return R.layout.activity_tester;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mView = findViewById(R.id.tester_view);
        mHint = findViewById(R.id.tester_hint);
        mClose = findViewById(R.id.tester_close);
        mView.setFocusable(true);
        mView.requestFocus();
        if (mClose != null) {
            mClose.setOnClickListener(view -> finish());
        }
        mLed = new LedFeedback();
        loadCalibration();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startRaw();
    }

    @Override
    protected void onPause() {
        stopRaw();
        if (mLed != null) {
            mLed.clear();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopRaw();
        if (mLed != null) {
            mLed.clear();
        }
        super.onDestroy();
    }

    private void loadCalibration() {
        new Thread(() -> {
            CalClient client = null;
            CalData data = null;
            try {
                client = CalClient.open();
                data = client.getCal();
            } catch (Exception ignored) {
                // PadState falls back to the documented factory values.
            } finally {
                CalClient.closeQuietly(client);
            }
            final CalData calibration = data;
            mHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    mView.setCalibration(calibration);
                }
            });
        }, "ashfall-tester-cal").start();
    }

    private void startRaw() {
        stopRaw();
        try {
            mRawStream = RawStream.open(new RawStream.Listener() {
                @Override
                public void onSample(RawSample sample) {
                    mLatest.set(sample);
                    scheduleDrain();
                }

                @Override
                public void onClosed(IOException error) {
                    mHandler.post(() -> onStreamLost(error));
                }

                @Override
                public void onGrabFailed(String reason) {
                    mHandler.post(() -> {
                        mView.setGrabActive(false);
                        if (mHint != null) {
                            mHint.setText(R.string.tester_grab_failed);
                        }
                    });
                }
            }, true);
            mView.setGrabActive(true);
            mView.setRawAvailable(true);
            if (mHint != null) {
                mHint.setText(R.string.tester_hint_v2);
            }
        } catch (IOException e) {
            onStreamLost(e);
        }
    }

    private void onStreamLost(IOException error) {
        // Closing the socket also releases the daemon-side grab, in case the
        // reader died while the connection was still half-open.
        stopRaw();
        mView.setGrabActive(false);
        mView.setRawAvailable(false);
        if (mHint != null) {
            mHint.setText(R.string.tester_no_raw_v2);
        }
        if (mLed != null) {
            mLed.clear();
        }
    }

    private void stopRaw() {
        if (mRawStream != null) {
            // Sends GRAB OFF + RAW OFF before closing; the daemon also releases
            // the grab on disconnect.
            mRawStream.close();
            mRawStream = null;
        }
        mHoldStart = 0;
        mHandler.removeCallbacks(mDrain);
        mHandler.removeCallbacks(mHoldWatch);
        mDrainPosted = false;
        mLatest.set(null);
    }

    /** Coalesces the up-to-100 Hz stream into ~30 UI updates per second. */
    private void scheduleDrain() {
        if (mDrainPosted) {
            return;
        }
        mDrainPosted = true;
        final long since = SystemClock.uptimeMillis() - mLastDrain;
        mHandler.postDelayed(mDrain, Math.max(0, 33 - since));
    }

    private void drain() {
        mDrainPosted = false;
        mLastDrain = SystemClock.uptimeMillis();
        final RawSample sample = mLatest.getAndSet(null);
        if (sample == null) {
            return;
        }
        mLastSampleAt = SystemClock.uptimeMillis();
        mView.setRaw(sample);
        updateExitHold();
        updateLeds();
    }

    /**
     * Start + Select must be held for two seconds.  The chord is read from the
     * RAW mask because nothing is injected into uinput while grabbed.
     */
    private void updateExitHold() {
        final boolean held = mView.isPressed(ControllerTesterView.BTN_START)
                && mView.isPressed(ControllerTesterView.BTN_SELECT);
        if (!held) {
            mHoldStart = 0;
            mView.setExitHold(0f);
            mHandler.removeCallbacks(mHoldWatch);
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (mHoldStart == 0) {
            mHoldStart = now;
            mHandler.removeCallbacks(mHoldWatch);
            mHandler.postDelayed(mHoldWatch, 350);
        }
        final long elapsed = now - mHoldStart;
        mView.setExitHold(elapsed / (float) EXIT_HOLD_MS);
        if (elapsed >= EXIT_HOLD_MS) {
            finish();
        }
    }

    /** Stops a hold when the pad stops reporting (screen off, daemon restart). */
    private final Runnable mHoldWatch = () -> {
        if (mHoldStart == 0) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - mLastSampleAt > SAMPLE_STALE_MS) {
            mHoldStart = 0;
            mView.setExitHold(0f);
        } else {
            mHandler.postDelayed(this.mHoldWatch, 350);
        }
    };

    private void updateLeds() {
        if (mLed == null) {
            return;
        }
        final PadState state = mView.getState();
        final int buttons = mView.getButtons();
        final boolean leftStick = (buttons & (1 << ControllerTesterView.BTN_L3)) != 0;
        final boolean rightStick = (buttons & (1 << ControllerTesterView.BTN_R3)) != 0;
        float left = Math.max(state.stickLength(true), state.l2);
        float right = Math.max(state.stickLength(false), state.r2);
        if ((buttons & (1 << ControllerTesterView.BTN_L1)) != 0) {
            left = Math.max(left, 0.85f);
        }
        if ((buttons & (1 << ControllerTesterView.BTN_R1)) != 0) {
            right = Math.max(right, 0.85f);
        }
        if (leftStick) {
            left = Math.max(left, 0.9f);
        }
        if (rightStick) {
            right = Math.max(right, 0.9f);
        }
        final int dpad = (1 << ControllerTesterView.BTN_UP)
                | (1 << ControllerTesterView.BTN_DOWN)
                | (1 << ControllerTesterView.BTN_LEFT)
                | (1 << ControllerTesterView.BTN_RIGHT);
        if ((buttons & dpad) != 0) {
            left = Math.max(left, 0.7f);
        }
        final int face = (1 << ControllerTesterView.BTN_A)
                | (1 << ControllerTesterView.BTN_B)
                | (1 << ControllerTesterView.BTN_X)
                | (1 << ControllerTesterView.BTN_Y);
        if ((buttons & face) != 0) {
            right = Math.max(right, 0.85f);
        }
        // Start/Select/Home/Back are in the middle: light both rings a little.
        final int middle = (1 << ControllerTesterView.BTN_START)
                | (1 << ControllerTesterView.BTN_SELECT)
                | (1 << ControllerTesterView.BTN_HOME)
                | (1 << ControllerTesterView.BTN_BACK);
        if ((buttons & middle) != 0) {
            left = Math.max(left, 0.55f);
            right = Math.max(right, 0.55f);
        }
        mLed.publish(left, right);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            // Fallback only: while grabbed these never reach the activity.
            if (event.getAction() == KeyEvent.ACTION_UP) {
                finish();
            }
            return true;
        }
        if (mView != null && mView.handleKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mView != null && mView.handleMotion(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mLed != null) {
            mLed.clear();
        }
    }
}
