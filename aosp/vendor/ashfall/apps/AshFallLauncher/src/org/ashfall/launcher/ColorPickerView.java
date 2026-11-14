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

package org.ashfall.launcher;

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

/**
 * Saturation / value area of the launcher's hue picker (#95).
 *
 * <p>The hue comes from {@link HueBarView}; this view owns saturation (x) and
 * value (y). Touch drags the crosshair, the D-pad moves it in 2 % steps, and
 * every move reports the resulting colour immediately (no apply button).
 */
final class ColorPickerView extends View {
    interface Listener {
        void onColorChanged(int color);
    }

    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] hsv = new float[3];

    private float hue;
    private float saturation = 1f;
    private float value = 1f;
    private int alpha = 0xFF;
    private int accent = 0xFFFF2020;
    private Listener listener;

    ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        // We draw an accent crosshair when focused; no rectangular highlight.
        setDefaultFocusHighlightEnabled(false);
        setBackground(null);
        float density = getResources().getDisplayMetrics().density;
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(3 * density);
        crosshairPaint.setColor(0xFFFFFFFF);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(density);
        ringPaint.setColor(0x66000000);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setAccent(int accent) {
        this.accent = accent;
        invalidate();
    }

    void setColor(int color) {
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        alpha = Color.alpha(color);
        invalidate();
    }

    void setHue(float hue) {
        this.hue = ((hue % 360f) + 360f) % 360f;
        invalidate();
        dispatch();
    }

    int getColor() {
        return Color.HSVToColor(alpha, new float[]{hue, saturation, value});
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = Ui.dp(getContext(), 2);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // White -> full-saturation hue across, transparent -> black down.
        huePaint.setShader(new LinearGradient(rect.left, 0f, rect.right, 0f, 0xFFFFFFFF,
                Color.HSVToColor(new float[]{hue, 1f, 1f}), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, huePaint);
        blackPaint.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom, 0x00000000,
                0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, blackPaint);

        float x = rect.left + saturation * rect.width();
        float y = rect.top + (1f - value) * rect.height();
        float radius = Ui.dp(getContext(), 10);
        crosshairPaint.setColor(isFocused() ? accent : 0xFFFFFFFF);
        canvas.drawCircle(x, y, radius, crosshairPaint);
        ringPaint.setColor(saturation > 0.7f && value > 0.7f ? 0x66000000 : 0x66FFFFFF);
        canvas.drawCircle(x, y, radius + Ui.dp(getContext(), 1), ringPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                requestFocus();
                setFromPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                saturation = clamp01(saturation - 0.02f);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                saturation = clamp01(saturation + 0.02f);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                value = clamp01(value + 0.02f);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                value = clamp01(value - 0.02f);
                break;
            default:
                return super.onKeyDown(keyCode, event);
        }
        invalidate();
        dispatch();
        return true;
    }

    private void setFromPoint(float x, float y) {
        if (rect.width() <= 0f || rect.height() <= 0f) {
            return;
        }
        saturation = clamp01((x - rect.left) / rect.width());
        value = clamp01(1f - (y - rect.top) / rect.height());
        invalidate();
        dispatch();
    }

    private void dispatch() {
        if (listener != null) {
            listener.onColorChanged(getColor());
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
