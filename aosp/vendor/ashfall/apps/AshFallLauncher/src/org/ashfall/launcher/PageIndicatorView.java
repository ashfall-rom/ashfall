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
import android.util.AttributeSet;
import android.view.View;

/** The row of page dots below the grid. */
public final class PageIndicatorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float spacing;
    private final float dotRadius;
    private final float selectedRadius;
    private int activeColor = Appearance.DEFAULT_ACCENT;
    private final int inactiveColor;

    private int page;
    private int total = 1;

    public PageIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        spacing = 16f * density;
        dotRadius = 3.2f * density;
        selectedRadius = 4.8f * density;
        inactiveColor = context.getColor(R.color.divider);
    }

    void setAccent(int accent) {
        activeColor = accent;
        invalidate();
    }

    void setPage(int page, int total) {
        this.page = page;
        this.total = Math.max(1, total);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (total <= 1) {
            return;
        }
        float centerY = getHeight() / 2f;
        float width = (total - 1) * spacing;
        float startX = (getWidth() - width) / 2f;
        for (int i = 0; i < total; i++) {
            boolean active = i == page;
            paint.setColor(active ? activeColor : inactiveColor);
            canvas.drawCircle(startX + i * spacing, centerY,
                    active ? selectedRadius : dotRadius, paint);
        }
    }
}
