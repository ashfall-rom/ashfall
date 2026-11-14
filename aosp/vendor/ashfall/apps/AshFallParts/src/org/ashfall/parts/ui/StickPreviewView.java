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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.RawSample;

import java.util.Locale;

/**
 * Compact live stick + trigger preview used by the calibration wizard.
 *
 * <p>Normalisation uses the factory calibration when it is available (the same
 * units as the raw stream); otherwise it falls back to the usual +-32767
 * joystick range.
 */
public class StickPreviewView extends View {

    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private RawSample mRaw;
    private CalData mCalibration;
    private int mAccent;
    private int mSecondary;
    private float mDensity;

    public StickPreviewView(Context context) {
        this(context, null);
    }

    public StickPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mAccent = themeColor(android.R.attr.colorAccent, 0xFFFF2020);
        mSecondary = themeColor(android.R.attr.textColorSecondary, 0xFF888888);
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(1.5f * mDensity);
        mRing.setColor(Ui.fade(mSecondary, 0.6f));
        mDot.setStyle(Paint.Style.FILL);
        mDot.setColor(mAccent);
        mText.setColor(mSecondary);
        mText.setTextSize(12 * mDensity);
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(Ui.fade(mAccent, 0.55f));
        setFocusable(false);
    }

    public void setCalibration(CalData calibration) {
        mCalibration = calibration;
        invalidate();
    }

    public void setRaw(RawSample raw) {
        mRaw = raw;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final float radius = Math.min(w * 0.16f, h * 0.30f);
        final float cy = h * 0.48f;
        drawStick(canvas, w * 0.28f, cy, radius, "left_x", "left_y", true);
        drawStick(canvas, w * 0.72f, cy, radius, "right_x", "right_y", false);

        // Triggers along the bottom.
        final float barWidth = w * 0.30f;
        drawTrigger(canvas, w * 0.30f, h * 0.88f, barWidth, "L2", "l2",
                mRaw != null ? mRaw.l2 : 0);
        drawTrigger(canvas, w * 0.70f, h * 0.88f, barWidth, "R2", "r2",
                mRaw != null ? mRaw.r2 : 0);

        if (mRaw != null) {
            canvas.drawText(String.format(Locale.US, "%.0f", mRaw.leftX),
                    w * 0.28f - mText.measureText("-00000") / 2,
                    cy - radius - 8 * mDensity, mText);
            canvas.drawText(String.format(Locale.US, "%.0f", mRaw.rightX),
                    w * 0.72f - mText.measureText("-00000") / 2,
                    cy - radius - 8 * mDensity, mText);
        }
    }

    private void drawStick(Canvas canvas, float cx, float cy, float radius, String xAxis,
            String yAxis, boolean left) {
        canvas.drawCircle(cx, cy, radius, mRing);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, mRing);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, mRing);
        float nx = 0;
        float ny = 0;
        if (mRaw != null) {
            nx = normalise(xAxis, left ? mRaw.leftX : mRaw.rightX);
            ny = -normalise(yAxis, left ? mRaw.leftY : mRaw.rightY);
        }
        canvas.drawCircle(cx + clamp(nx) * radius * 0.9f, cy + clamp(ny) * radius * 0.9f,
                8 * mDensity, mDot);
        canvas.drawText(left ? "L" : "R", cx - mText.measureText("L") / 2,
                cy + radius + 16 * mDensity, mText);
    }

    private void drawTrigger(Canvas canvas, float cx, float cy, float width, String label,
            String axis, float value) {
        final float height = 10 * mDensity;
        mRect.set(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
        canvas.drawRoundRect(mRect, height / 2, height / 2, mRing);
        final float fraction = clamp01(normalise(axis, value));
        mRect.set(cx - width / 2, cy - height / 2,
                cx - width / 2 + width * fraction, cy + height / 2);
        canvas.drawRoundRect(mRect, height / 2, height / 2, mFill);
        canvas.drawText(label, cx - width / 2 - mText.measureText(label) - 8 * mDensity,
                cy + mText.getTextSize() / 3, mText);
    }

    private float normalise(String axis, float value) {
        if (mCalibration != null && mCalibration.isComplete()) {
            final float min = mCalibration.get(axis + "_min", -32768f);
            final float center = mCalibration.get(axis + "_center", 0f);
            final float max = mCalibration.get(axis + "_max", 32767f);
            if (value >= center) {
                return (value - center) / Math.max(1f, max - center);
            }
            return (value - center) / Math.max(1f, center - min);
        }
        return value / 32767f;
    }

    private int themeColor(int attr, int fallback) {
        final android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return fallback;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
