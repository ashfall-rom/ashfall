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
 * Calibration wizard: release the sticks, roll them, pull the triggers, review,
 * save via {@code SET CAL}.  Nothing is written until Save; Cancel leaves the
 * existing calibration untouched.
 */
public class CalibrationActivity extends PartsActivity {

    private static final int STEP_RELEASE = 0;
    private static final int STEP_ROTATE = 1;
    private static final int STEP_TRIGGER = 2;
    private static final int STEP_REVIEW = 3;

    private static final long DURATION_RELEASE = 2000;
    private static final long DURATION_ROTATE = 8000;
    private static final long DURATION_TRIGGER = 4000;

    private TextView mTitleView;
    private TextView mBodyView;
    private TextView mProgressView;
    private TextView mErrorView;
    private TextView mReviewView;
    private StickPreviewView mPreview;
    private Button mCancel;
    private Button mNext;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RawStream mStream;
    private CalData mFactory;

    private int mStep = STEP_RELEASE;
    private boolean mCapturing;
    private long mStepStart;
    private int mRetryAction;
    private boolean mSaving;
    private long mLastPreview;

    private int mSamples;
    private final double[] mSums = new double[6];
    private final float[] mMin = new float[6];
    private final float[] mMax = new float[6];
    private final float[] mCenter = new float[6];
    private float[] mAxisMin = new float[4];
    private float[] mAxisMax = new float[4];
    private final float[] mTriggerMin = new float[2];
    private final float[] mTriggerMax = new float[2];

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mCapturing) {
                return;
            }
            final long duration = durationFor(mStep);
            final long elapsed = SystemClock.uptimeMillis() - mStepStart;
            final long remaining = Math.max(0, duration - elapsed);
            mProgressView.setText(getString(R.string.cal_step_progress,
                    (remaining + 999) / 1000));
            if (elapsed >= duration) {
                finishStep();
            } else {
                mHandler.postDelayed(this, 100);
            }
        }
    };

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
        mPreview = findViewById(R.id.cal_preview);
        mCancel = findViewById(R.id.cal_cancel);
        mNext = findViewById(R.id.cal_next);

        mCancel.setOnClickListener(view -> finish());
        mNext.setOnClickListener(view -> onNext());
        mNext.setVisibility(View.GONE);
        mCancel.requestFocus();

        for (int i = 0; i < mMin.length; i++) {
            mMin[i] = Float.MAX_VALUE;
            mMax[i] = -Float.MAX_VALUE;
        }
        for (int i = 0; i < mAxisMin.length; i++) {
            mAxisMin[i] = Float.MAX_VALUE;
            mAxisMax[i] = -Float.MAX_VALUE;
        }
        connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mStream == null && mStep != STEP_REVIEW && !mSaving) {
            // Reconnect after a pause; the current step restarts from scratch.
            connect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCapturing = false;
        mHandler.removeCallbacks(mTick);
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
            String error = null;
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
                error = e.getMessage();
            } finally {
                CalClient.closeQuietly(client);
            }
            final CalData factoryData = factory;
            final RawStream rawStream = stream;
            final boolean failed = rawStream == null;
            mHandler.post(() -> {
                if (isFinishing()) {
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
            mHandler.post(() -> mPreview.setRaw(sample));
        }
    }

    private void track(int channel, float value) {
        mMin[channel] = Math.min(mMin[channel], value);
        mMax[channel] = Math.max(mMax[channel], value);
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
        mNext.setVisibility(View.GONE);
        mRetryAction = 0;

        if (step == STEP_REVIEW) {
            showReview();
            return;
        }

        switch (step) {
            case STEP_RELEASE:
                mTitleView.setText(R.string.cal_step_release_title);
                mBodyView.setText(R.string.cal_step_release_body);
                break;
            case STEP_ROTATE:
                mTitleView.setText(R.string.cal_step_rotate_title);
                mBodyView.setText(R.string.cal_step_rotate_body);
                break;
            case STEP_TRIGGER:
            default:
                mTitleView.setText(R.string.cal_step_trigger_title);
                mBodyView.setText(R.string.cal_step_trigger_body);
                break;
        }
        mCapturing = true;
        mStepStart = SystemClock.uptimeMillis();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    private void finishStep() {
        mCapturing = false;
        mHandler.removeCallbacks(mTick);
        switch (mStep) {
            case STEP_RELEASE: {
                synchronized (this) {
                    if (mSamples == 0) {
                        fail(R.string.cal_error_no_input, 2);
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
                        if (mMax[i] - mMin[i] <= 8f) {
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
                    if (mMax[4] - mMin[4] <= 8f || mMax[5] - mMin[5] <= 8f) {
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

    private void fail(int messageRes, int retryAction) {
        mCapturing = false;
        mHandler.removeCallbacks(mTick);
        mErrorView.setText(messageRes);
        mErrorView.setVisibility(View.VISIBLE);
        mRetryAction = retryAction;
        mRetryStep = mStep;
        mNext.setText(R.string.cal_step_retry);
        mNext.setVisibility(View.VISIBLE);
        mNext.postDelayed(mNext::requestFocus, 120);
    }

    private int mRetryStep;

    private void onNext() {
        if (mStep == STEP_REVIEW && mRetryAction == 0) {
            save();
            return;
        }
        if (mRetryAction == 1) {
            connect();
        } else if (mRetryAction == 2) {
            startStep(mRetryStep);
        }
    }

    private void showReview() {
        mCapturing = false;
        mHandler.removeCallbacks(mTick);
        mTitleView.setText(R.string.cal_step_review_title);
        mBodyView.setText(R.string.cal_step_review_body);
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private long durationFor(int step) {
        switch (step) {
            case STEP_RELEASE:
                return DURATION_RELEASE;
            case STEP_ROTATE:
                return DURATION_ROTATE;
            case STEP_TRIGGER:
                return DURATION_TRIGGER;
            default:
                return 0;
        }
    }
}
