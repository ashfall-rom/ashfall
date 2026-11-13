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
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Battery outline with a proportional fill and a charging bolt. */
public final class BatteryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt = new Path();
    private final RectF body = new RectF();

    private int level = -1;
    private boolean charging;
    private final int normalColor;
    private final int lowColor;
    private final int chargingColor;

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        normalColor = context.getColor(R.color.text_secondary);
        lowColor = context.getColor(R.color.warning);
        chargingColor = context.getColor(R.color.ok);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setLevel(int level, boolean charging) {
        this.level = Math.max(-1, Math.min(100, level));
        this.charging = charging;
        setContentDescription(getContext().getString(R.string.cd_battery)
                + ": " + (this.level < 0 ? "?" : this.level + "%"));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (level < 0) {
            return;
        }
        float stroke = dp(1.4f);
        float nubWidth = dp(2.2f);
        float nubHeight = getHeight() * 0.42f;
        body.set(stroke, stroke, getWidth() - nubWidth - dp(2f), getHeight() - stroke);
        float radius = dp(2.5f);

        int color = charging ? chargingColor : (level <= 15 ? lowColor : normalColor);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(body, radius, radius, paint);

        // Nub.
        paint.setStyle(Paint.Style.FILL);
        float nubTop = (getHeight() - nubHeight) / 2f;
        canvas.drawRoundRect(new RectF(body.right + dp(1.5f), nubTop,
                body.right + dp(1.5f) + nubWidth, nubTop + nubHeight), dp(1f), dp(1f), paint);

        // Fill.
        float inset = stroke + dp(1.6f);
        float fillWidth = (body.width() - inset * 2f) * (level / 100f);
        if (fillWidth > 0f) {
            RectF fill = new RectF(body.left + inset, body.top + inset,
                    body.left + inset + fillWidth, body.bottom - inset);
            canvas.drawRoundRect(fill, dp(1.2f), dp(1.2f), paint);
        }

        if (charging) {
            bolt.reset();
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float half = dp(4.2f);
            bolt.moveTo(cx + half * 0.15f, cy - half);
            bolt.lineTo(cx - half * 0.55f, cy + half * 0.10f);
            bolt.lineTo(cx - half * 0.02f, cy + half * 0.10f);
            bolt.lineTo(cx - half * 0.20f, cy + half);
            bolt.lineTo(cx + half * 0.60f, cy - half * 0.15f);
            bolt.lineTo(cx + half * 0.05f, cy - half * 0.15f);
            bolt.close();
            paint.setColor(getContext().getColor(R.color.background_top));
            canvas.drawPath(bolt, paint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
