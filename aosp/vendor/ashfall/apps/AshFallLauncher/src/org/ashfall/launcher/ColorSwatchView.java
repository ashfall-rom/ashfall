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
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** Round color swatch with a focus ring and a selected ring. */
final class ColorSwatchView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFF000000;
    private int accent = Appearance.DEFAULT_ACCENT;
    private boolean selected;

    ColorSwatchView(Context context) {
        super(context);
        setFocusable(true);
        setClickable(true);
        // The view draws its own circular focus ring; the platform's default
        // rectangular focus highlight would draw a grey square around it (#95).
        setDefaultFocusHighlightEnabled(false);
        setBackground(null);
        updateDescription();
    }

    int getColor() {
        return color;
    }

    void setColor(int color) {
        this.color = color;
        updateDescription();
        invalidate();
    }

    void setAccent(int accent) {
        this.accent = accent;
        invalidate();
    }

    @Override
    public void setSelected(boolean selected) {
        this.selected = selected;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float ringStroke = Ui.dp(getContext(), 2);
        float focusOffset = Ui.dp(getContext(), 5);
        // The focus ring is the outermost circle; keep its stroke inside the
        // view so a focused swatch in a dense row is never clipped (#81).
        float radius = Math.min(getWidth(), getHeight()) / 2f
                - ringStroke / 2f - focusOffset - Ui.dp(getContext(), 1);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(0x55FFFFFF);
        canvas.drawCircle(cx, cy, radius, paint);

        if (selected) {
            paint.setStrokeWidth(ringStroke);
            paint.setColor(accent);
            canvas.drawCircle(cx, cy, radius + Ui.dp(getContext(), 3), paint);
        }
        if (isFocused()) {
            paint.setStrokeWidth(ringStroke);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, radius + focusOffset, paint);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(Appearance.hex(color));
    }
}
