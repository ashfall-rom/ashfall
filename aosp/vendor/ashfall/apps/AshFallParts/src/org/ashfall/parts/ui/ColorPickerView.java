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
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Saturation / value square with a hue-independent crosshair.
 *
 * <p>Touch drags the crosshair; with the D-pad, left/right change saturation
 * and up/down change the value.  The hue comes from the picker's slider.
 */
public class ColorPickerView extends View {

    public interface Listener {
        void onColorChanged(int argb);
    }

    private final Paint mHuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBlackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCrosshair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private float mHue;
    private float mSaturation = 1f;
    private float mValue = 1f;
    private Listener mListener;

    public ColorPickerView(Context context) {
        this(context, null);
    }

    public ColorPickerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        final float density = getResources().getDisplayMetrics().density;
        mCrosshair.setStyle(Paint.Style.STROKE);
        mCrosshair.setStrokeWidth(3 * density);
        mCrosshair.setColor(0xFFFFFFFF);
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(density);
        mRing.setColor(0x66000000);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void setColor(int argb) {
        final float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        mHue = hsv[0];
        mSaturation = hsv[1];
        mValue = hsv[2];
        invalidate();
    }

    public void setHue(float hue) {
        mHue = ((hue % 360f) + 360f) % 360f;
        invalidate();
        dispatch();
    }

    public float getHue() {
        return mHue;
    }

    public int getColor() {
        return Color.HSVToColor(new float[] {mHue, mSaturation, mValue});
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float inset = Ui.dp(getContext(), 2);
        mRect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        mHuePaint.setShader(new LinearGradient(mRect.left, 0, mRect.right, 0, 0xFFFFFFFF,
                Color.HSVToColor(new float[] {mHue, 1f, 1f}), Shader.TileMode.CLAMP));
        canvas.drawRect(mRect, mHuePaint);

        mBlackPaint.setShader(new LinearGradient(0, mRect.top, 0, mRect.bottom, 0x00000000,
                0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRect(mRect, mBlackPaint);

        final float x = mRect.left + mSaturation * mRect.width();
        final float y = mRect.top + (1f - mValue) * mRect.height();
        final float radius = Ui.dp(getContext(), 10);
        mCrosshair.setColor(isFocused() ? getAccent() : 0xFFFFFFFF);
        canvas.drawCircle(x, y, radius, mCrosshair);
        mRing.setColor(mSaturation > 0.7f && mValue > 0.7f ? 0x66000000 : 0x66FFFFFF);
        canvas.drawCircle(x, y, radius + Ui.dp(getContext(), 1), mRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                final android.view.ViewParent parent = getParent();
                if (parent != null) {
                    // The picker lives in a scrollable dialog; dragging the
                    // square must change saturation/value, not scroll.
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                requestFocus();
                setFromPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                requestFocus();
                setFromPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void setFromPoint(float x, float y) {
        if (mRect.width() <= 0 || mRect.height() <= 0) {
            return;
        }
        mSaturation = clamp01((x - mRect.left) / mRect.width());
        mValue = clamp01(1f - (y - mRect.top) / mRect.height());
        invalidate();
        dispatch();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mSaturation = clamp01(mSaturation - 0.02f);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                mSaturation = clamp01(mSaturation + 0.02f);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mValue >= 1f) {
                    return super.onKeyDown(keyCode, event);
                }
                mValue = clamp01(mValue + 0.02f);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mValue <= 0f) {
                    return super.onKeyDown(keyCode, event);
                }
                mValue = clamp01(mValue - 0.02f);
                break;
            default:
                return super.onKeyDown(keyCode, event);
        }
        invalidate();
        dispatch();
        return true;
    }

    private void dispatch() {
        if (mListener != null) {
            mListener.onColorChanged(getColor());
        }
    }

    private int getAccent() {
        final android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            return value.data;
        }
        return 0xFFFF8A3D;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
