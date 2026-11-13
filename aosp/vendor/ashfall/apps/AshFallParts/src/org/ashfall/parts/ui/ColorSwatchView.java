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
import androidx.core.content.ContextCompat;

import org.ashfall.parts.R;

/** A round colour swatch with a subtle outline, used in rows and pickers. */
public class ColorSwatchView extends View {

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mOval = new RectF();
    private int mColor = 0xFFFF2020;
    private boolean mSelected;

    public ColorSwatchView(Context context) {
        this(context, null);
    }

    public ColorSwatchView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mOutline.setStyle(Paint.Style.STROKE);
        mOutline.setStrokeWidth(Ui.dp(context, 2));
        mOutline.setColor(ContextCompat.getColor(context, android.R.color.darker_gray));
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
    }

    public void setColor(int color) {
        mColor = color;
        invalidate();
    }

    public int getColor() {
        return mColor;
    }

    public void setSelected(boolean selected) {
        if (mSelected != selected) {
            mSelected = selected;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float inset = Ui.dp(getContext(), 3);
        final float ring = mSelected || isFocused() ? Ui.dp(getContext(), 2.5f) : Ui.dp(getContext(), 1);
        mOval.set(inset + ring, inset + ring, getWidth() - inset - ring,
                getHeight() - inset - ring);
        mFill.setColor(mColor);
        mFill.setStyle(Paint.Style.FILL);
        canvas.drawOval(mOval, mFill);

        mOutline.setStrokeWidth(ring);
        mOutline.setColor(mSelected || isFocused()
                ? getThemeAccent()
                : 0x33000000);
        canvas.drawOval(mOval, mOutline);
    }

    private int getThemeAccent() {
        final android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            return value.data;
        }
        return ContextCompat.getColor(getContext(), R.color.ashfall_red);
    }
}
