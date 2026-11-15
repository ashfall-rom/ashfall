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
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.ashfall.parts.R;

import java.util.Arrays;

/**
 * The live fan graph of the Fan page (docs/phase5.md §7.2): a scrolling 60 s
 * line chart with three series in one plot area.
 *
 * <ul>
 *   <li>temperature on the <b>left</b> axis, fixed 20..100 °C with grid lines
 *       and labels at 40/60/80;</li>
 *   <li>fan output on the <b>right</b> axis, 0..100 % with labels at 0/50/100;</li>
 *   <li>fan speed on the same right axis against a fixed 16 000 rpm reference
 *       (the measured maximum is ~15 600 rpm), without labels of its own.</li>
 * </ul>
 *
 * <p>Missing values are {@link Float#NaN} and break the line, so a sample the
 * daemon has not published yet is a gap and never a drawn zero.  Only Canvas
 * primitives are used: no chart library, no vector or RRO dependency.
 */
public class FanGraphView extends View {

    /** Samples in one window: 120 page ticks of 500 ms = the last 60 s. */
    public static final int SAMPLES = 120;

    private static final float TEMP_MIN_C = 20f;
    private static final float TEMP_MAX_C = 100f;
    /** The right axis covers 0..100 %, and the RPM reference maps onto it. */
    private static final float PERCENT_MAX = 100f;
    /** Fixed rpm reference: the measured maximum is ~15 600 rpm. */
    private static final float RPM_REFERENCE = 16000f;
    private static final float[] TEMP_GRID_C = {40f, 60f, 80f};
    private static final float[] PERCENT_LABELS = {0f, 50f, 100f};

    /**
     * The sample window.  It lives in the preference, not in the view, so a
     * recycled or re-inflated row never loses the history; the page clears it
     * when it is opened (a fresh page starts a fresh history).
     */
    public static final class History {

        private final float[] mTemp = new float[SAMPLES];
        private final float[] mOutput = new float[SAMPLES];
        private final float[] mRpm = new float[SAMPLES];
        /** Index of the oldest sample in the ring. */
        private int mStart;
        private int mCount;

        public History() {
            clear();
        }

        /** Drops every sample: the next one starts a new 60 s window. */
        public void clear() {
            Arrays.fill(mTemp, Float.NaN);
            Arrays.fill(mOutput, Float.NaN);
            Arrays.fill(mRpm, Float.NaN);
            mStart = 0;
            mCount = 0;
        }

        /**
         * Appends one tick.  Pass {@link Float#NaN} for a value the daemon has
         * not published: the plot leaves a gap there.
         */
        public void append(float temperatureC, float outputPct, float rpm) {
            final int index;
            if (mCount < SAMPLES) {
                index = (mStart + mCount) % SAMPLES;
                mCount++;
            } else {
                // Full ring: overwrite the oldest sample and advance the start.
                index = mStart;
                mStart = (mStart + 1) % SAMPLES;
            }
            mTemp[index] = temperatureC;
            mOutput[index] = outputPct;
            mRpm[index] = rpm;
        }

        public int size() {
            return mCount;
        }

        /** Oldest first; {@code 0 <= index < size()}. */
        public float temperature(int index) {
            return mTemp[(mStart + index) % SAMPLES];
        }

        public float output(int index) {
            return mOutput[(mStart + index) % SAMPLES];
        }

        public float rpm(int index) {
            return mRpm[(mStart + index) % SAMPLES];
        }
    }

    private final Paint mSeriesPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private final int mTempColor;
    private final int mOutputColor;
    private final int mRpmColor;
    /** Axis labels: the theme's secondary text colour, resolved once. */
    private final int mAxisColor;
    private final float mStrokeWidth;
    private final float mLabelGap;

    @Nullable
    private History mHistory;

    public FanGraphView(Context context) {
        this(context, null);
    }

    public FanGraphView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // The row is a read-out: the D-pad must skip it in both directions.
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        mTempColor = context.getColor(R.color.fan_graph_temp);
        mOutputColor = context.getColor(R.color.fan_graph_pct);
        mRpmColor = context.getColor(R.color.fan_graph_rpm);
        mAxisColor = resolveAxisColor(context);

        mStrokeWidth = Ui.dp(context, 1.5f);
        mLabelGap = Ui.dp(context, 4f);

        mSeriesPaint.setStyle(Paint.Style.STROKE);
        mSeriesPaint.setStrokeWidth(mStrokeWidth);
        mSeriesPaint.setStrokeCap(Paint.Cap.ROUND);
        mSeriesPaint.setStrokeJoin(Paint.Join.ROUND);

        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(Ui.dp(context, 1f));

        mLabelPaint.setTextSize(Ui.sp(context, 12f));
        mLabelPaint.setAntiAlias(true);
    }

    /** Sets the window to draw; the caller invalidates when it appends. */
    public void setHistory(@Nullable History history) {
        mHistory = history;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final History history = mHistory;
        if (history == null) {
            return;
        }

        if (getWidth() - getPaddingLeft() - getPaddingRight() <= 0
                || getHeight() - getPaddingTop() - getPaddingBottom() <= 0) {
            return;
        }

        // Axis labels use the theme's secondary text colour, so day, night and
        // the pure-black overlay all stay readable; the grid is the same colour
        // at 12 % alpha.
        mLabelPaint.setColor(mAxisColor);
        mGridPaint.setColor(withAlpha(mAxisColor, 31));

        final Paint.FontMetrics metrics = mLabelPaint.getFontMetrics();
        final float labelHeight = metrics.descent - metrics.ascent;
        final String tempTop = getContext().getString(R.string.temperature_value,
                Math.round(TEMP_MAX_C));
        final String percentTop = getContext().getString(R.string.percent_value,
                Math.round(PERCENT_MAX));

        final float plotLeft = getPaddingLeft() + mLabelPaint.measureText(tempTop) + mLabelGap;
        final float plotRight = getWidth() - getPaddingRight()
                - mLabelPaint.measureText(percentTop) - mLabelGap;
        final float plotTop = getPaddingTop() + labelHeight / 2f;
        final float plotBottom = getHeight() - getPaddingBottom() - labelHeight / 2f;
        if (plotRight <= plotLeft || plotBottom <= plotTop) {
            return;
        }

        // Temperature grid lines and their left-hand labels (40/60/80 °C).
        mLabelPaint.setTextAlign(Paint.Align.RIGHT);
        for (float celsius : TEMP_GRID_C) {
            final float y = yFor(fraction(celsius, TEMP_MIN_C, TEMP_MAX_C), plotTop, plotBottom);
            canvas.drawLine(plotLeft, y, plotRight, y, mGridPaint);
            drawLabel(canvas, getContext().getString(R.string.temperature_value,
                    Math.round(celsius)), plotLeft - mLabelGap, y, metrics);
        }

        // Output labels on the right-hand axis (0/50/100 %); the grid lines
        // themselves belong to the temperature scale only.
        mLabelPaint.setTextAlign(Paint.Align.LEFT);
        for (float percent : PERCENT_LABELS) {
            final float y = yFor(fraction(percent, 0f, PERCENT_MAX), plotTop, plotBottom);
            drawLabel(canvas, getContext().getString(R.string.percent_value,
                    Math.round(percent)), plotRight + mLabelGap, y, metrics);
        }

        // Newest sample on the right edge; one slot is one 500 ms tick.
        final int count = history.size();
        if (count == 0) {
            return;
        }
        final float step = (plotRight - plotLeft) / (SAMPLES - 1);
        drawSeries(canvas, history, plotRight, plotTop, plotBottom, step, mTempColor,
                Series.TEMPERATURE);
        drawSeries(canvas, history, plotRight, plotTop, plotBottom, step, mOutputColor,
                Series.OUTPUT);
        drawSeries(canvas, history, plotRight, plotTop, plotBottom, step, mRpmColor,
                Series.RPM);
    }

    private enum Series {
        TEMPERATURE,
        OUTPUT,
        RPM,
    }

    private void drawSeries(Canvas canvas, History history, float plotRight, float plotTop,
            float plotBottom, float step, int color, Series series) {
        mSeriesPaint.setColor(color);
        mPath.reset();
        boolean started = false;
        // A lone valid sample between two gaps is a point, not a line.
        float runX = 0f;
        float runY = 0f;
        int runPoints = 0;
        final int count = history.size();
        for (int i = 0; i < count; i++) {
            final float value;
            switch (series) {
                case TEMPERATURE:
                    value = history.temperature(i);
                    break;
                case OUTPUT:
                    value = history.output(i);
                    break;
                case RPM:
                default:
                    value = history.rpm(i);
                    break;
            }
            final float x = plotRight - (count - 1 - i) * step;
            if (Float.isNaN(value)) {
                // A missing sample is a gap: end the run instead of drawing
                // through it (or worse, through a zero).
                if (runPoints == 1) {
                    canvas.drawPoint(runX, runY, mSeriesPaint);
                }
                runPoints = 0;
                started = false;
                continue;
            }
            final float y = yFor(fractionFor(series, value), plotTop, plotBottom);
            if (started) {
                mPath.lineTo(x, y);
                runPoints++;
            } else {
                mPath.moveTo(x, y);
                started = true;
                runX = x;
                runY = y;
                runPoints = 1;
            }
        }
        if (runPoints == 1) {
            canvas.drawPoint(runX, runY, mSeriesPaint);
        }
        canvas.drawPath(mPath, mSeriesPaint);
    }

    private static float fractionFor(Series series, float value) {
        switch (series) {
            case TEMPERATURE:
                return fraction(value, TEMP_MIN_C, TEMP_MAX_C);
            case RPM:
                // Fixed reference: RPM shares the 0..100 % right-hand axis.
                return fraction(value, 0f, RPM_REFERENCE);
            case OUTPUT:
            default:
                return fraction(value, 0f, PERCENT_MAX);
        }
    }

    private static float fraction(float value, float min, float max) {
        final float clamped = Math.max(min, Math.min(max, value));
        return (clamped - min) / (max - min);
    }

    private static float yFor(float fraction, float plotTop, float plotBottom) {
        return plotBottom - fraction * (plotBottom - plotTop);
    }

    private void drawLabel(Canvas canvas, String text, float x, float centerY,
            Paint.FontMetrics metrics) {
        canvas.drawText(text, x, centerY - (metrics.ascent + metrics.descent) / 2f, mLabelPaint);
    }

    /** The theme's secondary text colour: day, night and pure black agree. */
    private static int resolveAxisColor(Context context) {
        final TypedArray array = context.obtainStyledAttributes(
                new int[] {android.R.attr.textColorSecondary});
        try {
            final ColorStateList colors = array.getColorStateList(0);
            if (colors != null) {
                return colors.getDefaultColor();
            }
        } finally {
            array.recycle();
        }
        return android.graphics.Color.GRAY;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
