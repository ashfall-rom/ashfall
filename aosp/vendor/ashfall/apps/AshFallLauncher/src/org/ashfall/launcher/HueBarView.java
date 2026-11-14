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
 * Hue slider of the launcher's colour picker (#95): a rainbow track with a
 * draggable thumb. Touch drags it; the D-pad moves it in 2 degree steps. The
 * value is 0..360 degrees and every change is reported immediately.
 */
final class HueBarView extends View {
    interface Listener {
        void onHueChanged(float hue);
    }

    private static final int[] RAINBOW = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
            0xFF0000FF, 0xFFFF00FF, 0xFFFF0000,
    };

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    private float hue;
    private int accent = 0xFFFF2020;
    private Listener listener;

    HueBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        // The thumb and track outline are our focus indicators; no grey box.
        setDefaultFocusHighlightEnabled(false);
        setBackground(null);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Ui.dp(context, 2));
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(Ui.dp(context, 2));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setAccent(int accent) {
        this.accent = accent;
        invalidate();
    }

    float getHue() {
        return hue;
    }

    void setHue(float hue) {
        this.hue = normalize(hue);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = Ui.dp(getContext(), 11);
        float centerY = getHeight() / 2f;
        float trackHeight = Ui.dp(getContext(), 20);
        float left = radius;
        float right = getWidth() - radius;
        if (right <= left) {
            return;
        }
        track.set(left, centerY - trackHeight / 2f, right, centerY + trackHeight / 2f);

        trackPaint.setShader(new LinearGradient(track.left, 0f, track.right, 0f, RAINBOW,
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(track, trackHeight / 2f, trackHeight / 2f, trackPaint);

        if (isFocused()) {
            focusPaint.setColor(accent);
            canvas.drawRoundRect(track, trackHeight / 2f, trackHeight / 2f, focusPaint);
        }

        float x = track.left + (hue / 360f) * track.width();
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
        canvas.drawCircle(x, centerY, radius, thumbPaint);

        strokePaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, centerY, radius, strokePaint);
        strokePaint.setColor(0x66000000);
        strokePaint.setStrokeWidth(Ui.dp(getContext(), 1));
        canvas.drawCircle(x, centerY, radius + Ui.dp(getContext(), 1), strokePaint);
        strokePaint.setStrokeWidth(Ui.dp(getContext(), 2));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                requestFocus();
                setFromX(event.getX());
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
                hue = normalize(hue - 2f);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                hue = normalize(hue + 2f);
                break;
            default:
                // Up/down stay free for focus navigation to the other controls.
                return super.onKeyDown(keyCode, event);
        }
        invalidate();
        dispatch();
        return true;
    }

    private void setFromX(float x) {
        float radius = Ui.dp(getContext(), 11);
        float left = radius;
        float width = getWidth() - radius * 2f;
        if (width <= 0f) {
            return;
        }
        hue = normalize((x - left) / width * 360f);
        invalidate();
        dispatch();
    }

    private void dispatch() {
        if (listener != null) {
            listener.onHueChanged(hue);
        }
    }

    private static float normalize(float hue) {
        return ((hue % 360f) + 360f) % 360f;
    }
}
