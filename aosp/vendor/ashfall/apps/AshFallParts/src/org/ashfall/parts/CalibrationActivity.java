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

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.ashfall.parts.ipc.CalClient;
import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.RawSample;
import org.ashfall.parts.ipc.RawStream;
import org.ashfall.parts.ui.StickPreviewView;

import java.io.IOException;
import java.util.Locale;

/**
 * Calibration wizard: centre the sticks, roll them, pull the triggers, review,
 * save via {@code SET CAL}.  Nothing is written until Save; Cancel leaves the
 * existing calibration untouched.
 *
 * <p>Every capture step runs until the user presses <b>Start</b> (or the
 * on-screen button), so the instructions stay readable for as long as needed
 * (issue #84, item 6).  The wizard never grabs the pad: Start has to reach the
 * activity as a normal key event.
 */
public class CalibrationActivity extends PartsActivity {

    private static final int STEP_RELEASE = 0;
    private static final int STEP_ROTATE = 1;
    private static final int STEP_TRIGGER = 2;
    private static final int STEP_REVIEW = 3;

    /** Enough samples to average the centre without waiting for the user. */
    private static final int MIN_RELEASE_SAMPLES = 8;

    private TextView mTitleView;
    private TextView mBodyView;
    private TextView mProgressView;
    private TextView mErrorView;
    private TextView mReviewView;
    private TextView mStartHint;
    private StickPreviewView mPreview;
    private Button mCancel;
    private Button mNext;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RawStream mStream;
    private CalData mFactory;

    private int mStep = STEP_RELEASE;
    private boolean mCapturing;
    private int mRetryAction;
    private int mRetryStep;
    private boolean mSaving;
    private volatile boolean mResumed;
    private long mLastPreview;

    private int mSamples;
    private final double[] mSums = new double[6];
    private final float[] mMin = new float[6];
    private final float[] mMax = new float[6];
    private final float[] mCenter = new float[6];
    private final float[] mAxisMin = new float[4];
    private final float[] mAxisMax = new float[4];
    private final float[] mTriggerMin = new float[2];
    private final float[] mTriggerMax = new float[2];

    @Override
    protected int getPageTitle() {
        return R.string.calibration_title;
    }

    @Override
    protected int getContentLayout() {
        return R.layout.activity_calibration;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTitleView = findViewById(R.id.cal_title);
        mBodyView = findViewById(R.id.cal_body);
        mProgressView = findViewById(R.id.cal_progress);
        mErrorView = findViewById(R.id.cal_error);
        mReviewView = findViewById(R.id.cal_review);
        mStartHint = findViewById(R.id.cal_start_hint);
        mPreview = findViewById(R.id.cal_preview);
        mCancel = findViewById(R.id.cal_cancel);
        mNext = findViewById(R.id.cal_next);

        mCancel.setOnClickListener(view -> finish());
        mNext.setOnClickListener(view -> onNext());
        mNext.setVisibility(View.GONE);
        mCancel.requestFocus();

        resetRanges();
        connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        if (mStream == null && mStep != STEP_REVIEW && !mSaving) {
            // Reconnect after a pause; the current step restarts from scratch.
            connect();
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        mCapturing = false;
        super.onPause();
        stopStream();
    }

    private void connect() {
        mProgressView.setText("");
        mErrorView.setVisibility(View.GONE);
        mRetryAction = 0;
        new Thread(() -> {
            CalClient client = null;
            CalData factory = null;
            RawStream stream = null;
            try {
                client = CalClient.open();
                try {
                    factory = client.getCal();
                } catch (IOException ignored) {
                    // The RAW stream is enough; the factory values only seed the preview.
                }
                stream = RawStream.open(new RawStream.Listener() {
                    @Override
                    public void onSample(RawSample sample) {
                        onRawSample(sample);
                    }

                    @Override
                    public void onClosed(IOException failure) {
                        mHandler.post(() -> {
                            // Closing our own socket (pause, save, review) is
                            // not an error; only report a stream that died
                            // while we were still capturing.
                            if (!isFinishing() && !isDestroyed() && !mSaving
                                    && mStep != STEP_REVIEW) {
                                fail(R.string.cal_error_socket, 1);
                            }
                        });
                    }
                });
            } catch (IOException e) {
                // Reported below.
            } finally {
                CalClient.closeQuietly(client);
            }
            final CalData factoryData = factory;
            final RawStream rawStream = stream;
            final boolean failed = rawStream == null;
            mHandler.post(() -> {
                if (isFinishing() || !mResumed) {
                    // Paused while connecting: drop the stream instead of
                    // capturing in the background.
                    if (rawStream != null) {
                        rawStream.close();
                    }
                    return;
                }
                if (failed) {
                    fail(R.string.cal_error_socket, 1);
                    return;
                }
                mStream = rawStream;
                mFactory = factoryData;
                mPreview.setCalibration(factoryData);
                startStep(STEP_RELEASE);
            });
        }, "ashfall-calibration").start();
    }

    private void stopStream() {
        if (mStream != null) {
            mStream.close();
            mStream = null;
        }
    }

    private void onRawSample(RawSample sample) {
        synchronized (this) {
            if (!mCapturing) {
                return;
            }
            switch (mStep) {
                case STEP_RELEASE:
                    mSums[0] += sample.leftX;
                    mSums[1] += sample.leftY;
                    mSums[2] += sample.rightX;
                    mSums[3] += sample.rightY;
                    mSamples++;
                    break;
                case STEP_ROTATE:
                    track(0, sample.leftX);
                    track(1, sample.leftY);
                    track(2, sample.rightX);
                    track(3, sample.rightY);
                    break;
                case STEP_TRIGGER:
                    track(4, sample.l2);
                    track(5, sample.r2);
                    break;
                default:
                    break;
            }
        }
        final long now = SystemClock.uptimeMillis();
        if (now - mLastPreview > 50) {
            mLastPreview = now;
            mHandler.post(() -> {
                mPreview.setRaw(sample);
                updateLiveStatus();
            });
        }
    }

    private void track(int channel, float value) {
        mMin[channel] = Math.min(mMin[channel], value);
        mMax[channel] = Math.max(mMax[channel], value);
    }

    private void resetRanges() {
        for (int i = 0; i < mMin.length; i++) {
            mMin[i] = Float.MAX_VALUE;
            mMax[i] = -Float.MAX_VALUE;
        }
        for (int i = 0; i < mAxisMin.length; i++) {
            mAxisMin[i] = Float.MAX_VALUE;
            mAxisMax[i] = -Float.MAX_VALUE;
        }
    }

    private void startStep(int step) {
        synchronized (this) {
            mStep = step;
            mSamples = 0;
            for (int i = 0; i < mSums.length; i++) {
                mSums[i] = 0;
            }
            for (int i = 0; i < mMin.length; i++) {
                mMin[i] = Float.MAX_VALUE;
                mMax[i] = -Float.MAX_VALUE;
            }
        }
        mErrorView.setVisibility(View.GONE);
        mReviewView.setVisibility(View.GONE);
        mRetryAction = 0;
        mNext.setEnabled(true);
        mStartHint.setVisibility(step == STEP_REVIEW ? View.GONE : View.VISIBLE);

        if (step == STEP_REVIEW) {
            showReview();
            return;
        }

        switch (step) {
            case STEP_RELEASE:
                mTitleView.setText(R.string.cal_step_release_title);
                mBodyView.setText(R.string.cal_step_release_body_v2);
                break;
            case STEP_ROTATE:
                mTitleView.setText(R.string.cal_step_rotate_title);
                mBodyView.setText(R.string.cal_step_rotate_body_v2);
                break;
            case STEP_TRIGGER:
            default:
                mTitleView.setText(R.string.cal_step_trigger_title);
                mBodyView.setText(R.string.cal_step_trigger_body_v2);
                break;
        }
        mCapturing = true;
        mProgressView.setText(R.string.cal_wait_samples);
        mNext.setText(R.string.cal_step_start);
        mNext.setVisibility(View.VISIBLE);
        mNext.postDelayed(mNext::requestFocus, 120);
    }

    /** Live, readable progress for the current step (10 Hz). */
    private void updateLiveStatus() {
        if (!mCapturing) {
            return;
        }
        switch (mStep) {
            case STEP_RELEASE:
                mProgressView.setText(getString(R.string.cal_live_release, mSamples));
                break;
            case STEP_ROTATE: {
                final int left = (int) Math.max(0, mMax[0] - mMin[0]);
                final int right = (int) Math.max(0, mMax[2] - mMin[2]);
                mProgressView.setText(getString(R.string.cal_live_rotate, left, right));
                break;
            }
            case STEP_TRIGGER:
            default: {
                final int l2 = (int) Math.max(0, mMax[4] - mMin[4]);
                final int r2 = (int) Math.max(0, mMax[5] - mMin[5]);
                mProgressView.setText(getString(R.string.cal_live_trigger, l2, r2));
                break;
            }
        }
    }

    /** Start (or the button) ends the current step; validation happens here. */
    private void finishStep() {
        mCapturing = false;
        switch (mStep) {
            case STEP_RELEASE: {
                synchronized (this) {
                    if (mSamples < MIN_RELEASE_SAMPLES) {
                        mCapturing = true;
                        mProgressView.setText(R.string.cal_wait_samples);
                        return;
                    }
                    for (int i = 0; i < 4; i++) {
                        mCenter[i] = (float) (mSums[i] / mSamples);
                    }
                    mCenter[4] = mFactory != null ? mFactory.get("l2_min", 0) : 0;
                    mCenter[5] = mFactory != null ? mFactory.get("r2_min", 0) : 0;
                }
                startStep(STEP_ROTATE);
                break;
            }
            case STEP_ROTATE: {
                synchronized (this) {
                    for (int i = 0; i < 4; i++) {
                        if (mMax[i] - mMin[i] < minimumTravel(AXIS_KEYS[i], 300f)) {
                            fail(R.string.cal_error_range, 2);
                            return;
                        }
                        mAxisMin[i] = mMin[i];
                        mAxisMax[i] = mMax[i];
                    }
                }
                startStep(STEP_TRIGGER);
                break;
            }
            case STEP_TRIGGER: {
                synchronized (this) {
                    if (mMax[4] - mMin[4] < minimumTravel("l2", 80f)
                            || mMax[5] - mMin[5] < minimumTravel("r2", 80f)) {
                        fail(R.string.cal_error_trigger, 2);
                        return;
                    }
                    mTriggerMin[0] = mMin[4];
                    mTriggerMax[0] = mMax[4];
                    mTriggerMin[1] = mMin[5];
                    mTriggerMax[1] = mMax[5];
                }
                startStep(STEP_REVIEW);
                break;
            }
            default:
                break;
        }
    }

    private static final String[] AXIS_KEYS = {
            "left_x", "left_y", "right_x", "right_y",
    };

    /**
     * A quarter of the factory travel is the least that can still be called a
     * full roll/full pull.  A user calibration can be different, but the factory
     * copy (or the built-in defaults of the same unit) is a safe reference; the
     * absolute floor keeps the check useful when GET CAL failed.
     */
    private float minimumTravel(String key, float floor) {
        if (mFactory == null || !mFactory.isComplete()) {
            return floor;
        }
        final float min = mFactory.get(key + "_min", 0);
        final float max = mFactory.get(key + "_max", 0);
        final float span = max - min;
        if (span <= 0) {
            return floor;
        }
        return Math.max(floor, span * 0.25f);
    }

    private void fail(int messageRes, int retryAction) {
        mCapturing = false;
        mStartHint.setVisibility(View.GONE);
        mErrorView.setText(messageRes);
        mErrorView.setVisibility(View.VISIBLE);
        mRetryAction = retryAction;
        mRetryStep = mStep;
        mNext.setText(R.string.cal_step_retry);
        mNext.setVisibility(View.VISIBLE);
        mNext.postDelayed(mNext::requestFocus, 120);
    }

    private void onNext() {
        if (mSaving) {
            return;
        }
        if (mRetryAction == 1) {
            connect();
            return;
        }
        if (mRetryAction == 2) {
            startStep(mRetryStep);
            return;
        }
        if (mStep == STEP_REVIEW) {
            save();
            return;
        }
        if (mCapturing) {
            finishStep();
        }
    }

    private void showReview() {
        mCapturing = false;
        stopStream();
        mTitleView.setText(R.string.cal_step_review_title);
        mBodyView.setText(R.string.cal_step_review_body_v2);
        mProgressView.setText("");
        mErrorView.setVisibility(View.GONE);
        mNext.setText(R.string.save);
        mNext.setVisibility(View.VISIBLE);
        mNext.postDelayed(mNext::requestFocus, 120);

        final StringBuilder builder = new StringBuilder();
        final String[] labels = {"Left X", "Left Y", "Right X", "Right Y"};
        for (int i = 0; i < 4; i++) {
            ensureOrder(i);
            builder.append(labels[i]).append("  ")
                    .append(getString(R.string.cal_review_min, number(mAxisMin[i])))
                    .append("  ")
                    .append(getString(R.string.cal_review_center, number(mCenter[i])))
                    .append("  ")
                    .append(getString(R.string.cal_review_max, number(mAxisMax[i])))
                    .append('\n');
        }
        builder.append("L2  ")
                .append(getString(R.string.cal_review_min, number(mTriggerMin[0]))).append("  ")
                .append(getString(R.string.cal_review_max, number(mTriggerMax[0]))).append('\n');
        builder.append("R2  ")
                .append(getString(R.string.cal_review_min, number(mTriggerMin[1]))).append("  ")
                .append(getString(R.string.cal_review_max, number(mTriggerMax[1]))).append('\n');
        mReviewView.setText(builder.toString());
        mReviewView.setVisibility(View.VISIBLE);
        mRetryAction = 0;
    }

    private void ensureOrder(int index) {
        if (mAxisMax[index] <= mAxisMin[index]) {
            mAxisMax[index] = mAxisMin[index] + 2;
        }
        if (mCenter[index] <= mAxisMin[index]) {
            mCenter[index] = mAxisMin[index] + 1;
        }
        if (mCenter[index] >= mAxisMax[index]) {
            mCenter[index] = mAxisMax[index] - 1;
        }
    }

    private static String number(float value) {
        return String.format(Locale.US, "%.0f", value);
    }

    private CalData buildCalibration() {
        final CalData data = CalData.empty();
        final String[] axes = {"left_x", "left_y", "right_x", "right_y"};
        for (int i = 0; i < 4; i++) {
            ensureOrder(i);
            data.put(axes[i] + "_min", mAxisMin[i]);
            data.put(axes[i] + "_center", mCenter[i]);
            data.put(axes[i] + "_max", mAxisMax[i]);
        }
        float l2Min = mTriggerMin[0];
        float l2Max = mTriggerMax[0];
        float r2Min = mTriggerMin[1];
        float r2Max = mTriggerMax[1];
        if (l2Max <= l2Min) {
            l2Max = l2Min + 2;
        }
        if (r2Max <= r2Min) {
            r2Max = r2Min + 2;
        }
        data.put("l2_min", l2Min);
        data.put("l2_max", l2Max);
        data.put("r2_min", r2Min);
        data.put("r2_max", r2Max);
        return data;
    }

    private void save() {
        if (mSaving) {
            return;
        }
        mSaving = true;
        final CalData data = buildCalibration();
        mNext.setEnabled(false);
        new Thread(() -> {
            String error = null;
            CalClient client = null;
            try {
                client = CalClient.open();
                client.setCal(data);
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                CalClient.closeQuietly(client);
            }
            final String failure = error;
            mHandler.post(() -> {
                mSaving = false;
                mNext.setEnabled(true);
                if (isFinishing()) {
                    return;
                }
                if (failure == null) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.cal_saved_title)
                            .setMessage(R.string.cal_saved_body)
                            .setPositiveButton(R.string.done, (dialog, which) -> finish())
                            .setCancelable(false)
                            .show();
                } else {
                    fail(R.string.cal_error_socket, 0);
                    Toast.makeText(this, getString(R.string.controller_cal_error, failure),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "ashfall-cal-save").start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                onNext();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B
                && event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
