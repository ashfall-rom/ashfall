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
 * The live status graph of the Fan and Power pages.
 *
 * <p>It started life as the fan graph (docs/phase5.md §7.2) and is now a small
 * general chart: one or two y axes, any number of series, a fixed window of
 * samples and {@link Float#NaN} as the gap.  The fan chart is the default, so
 * the Fan page (and its {@link History}) is unchanged; the Power page installs
 * its own {@link Chart} with {@link #setChart} (#124).  Only Canvas primitives
 * are used: no chart library, no vector or RRO dependency.
 *
 * <p>Fan chart: temperature on the left axis (20..100 °C, labels at 40/60/80),
 * fan output (0..100 %) and fan speed against a fixed 16 000 rpm reference on
 * the right axis (labels at 0/50/100 %).
 *
 * <p>Power chart: battery power in watts on the left axis (0..12 W, labels at
 * 0/6/12) and the real CPU/GPU utilisation on the right axis (0..100 %, labels
 * at 0/50/100 %); the exact clocks are in the legend, not plotted (#156 §3).
 */
public class FanGraphView extends View {

    /** Samples in one fan window: 120 page ticks of 500 ms = the last 60 s. */
    public static final int SAMPLES = 120;
    /** Samples in one power window: 120 page ticks of 500 ms = the last 60 s. */
    public static final int POWER_SAMPLES = 120;

    private static final float TEMP_MIN_C = 20f;
    private static final float TEMP_MAX_C = 100f;
    /** The fan's right axis covers 0..100 %, and the RPM reference maps onto it. */
    private static final float PERCENT_MAX = 100f;
    /** Fixed rpm reference: the measured maximum is ~15 600 rpm. */
    private static final float RPM_REFERENCE = 16000f;
    private static final float[] TEMP_GRID_C = {40f, 60f, 80f};
    private static final float[] PERCENT_LABELS = {0f, 50f, 100f};

    /** One y axis: the range its labels cover, the grid values and the format. */
    public static final class Axis {

        public final float min;
        public final float max;
        public final float[] grid;
        /** A {@code getString} format with one {@code %d} (e.g. {@code %1$d W}). */
        public final String format;

        public Axis(float min, float max, float[] grid, String format) {
            this.min = min;
            this.max = max;
            this.grid = grid;
            this.format = format;
        }
    }

    /**
     * One plotted series: its colour, the axis it is labelled against and the
     * value range it is scaled to.  The range is separate from the axis so the
     * fan's rpm series can share the 0..100 % axis with its own 0..16 000
     * reference.
     */
    public static final class Series {

        /** 0 = left axis, 1 = right axis. */
        public final int axis;
        public final int color;
        public final float min;
        public final float max;

        public Series(int axis, int color, float min, float max) {
            this.axis = axis;
            this.color = color;
            this.min = min;
            this.max = max;
        }
    }

    /** A chart description: one or two axes, its series and its window width. */
    public static final class Chart {

        public final Axis left;
        /** {@code null} when the chart has a single axis. */
        @Nullable
        public final Axis right;
        public final Series[] series;
        /** Slots in one window; one slot is one page tick. */
        public final int samples;

        public Chart(Axis left, @Nullable Axis right, Series[] series, int samples) {
            this.left = left;
            this.right = right;
            this.series = series;
            this.samples = samples;
        }
    }

    /** A read-only window of samples; index 0 is the oldest. */
    public interface Data {
        int size();

        float value(int series, int index);
    }

    /**
     * The fan window.  It lives in the preference, not in the view, so a
     * recycled or re-inflated row never loses the history; the page clears it
     * when it is opened (a fresh page starts a fresh history).
     */
    public static final class History implements Data {

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

        @Override
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

        @Override
        public float value(int series, int index) {
            switch (series) {
                case 0:
                    return temperature(index);
                case 1:
                    return output(index);
                default:
                    return rpm(index);
            }
        }
    }

    /**
     * A generic fixed-size window for charts that are not the fan one.  The
     * POWER page appends one row per daemon tick.
     */
    public static final class Window implements Data {

        private final float[][] mValues;
        private final int mSamples;
        private int mStart;
        private int mCount;

        public Window(int seriesCount, int samples) {
            mValues = new float[Math.max(1, seriesCount)][Math.max(1, samples)];
            mSamples = mValues[0].length;
            clear();
        }

        public void clear() {
            for (float[] column : mValues) {
                Arrays.fill(column, Float.NaN);
            }
            mStart = 0;
            mCount = 0;
        }

        /** Appends one tick; a shorter row leaves the missing series as a gap. */
        public void append(float... values) {
            final int index;
            if (mCount < mSamples) {
                index = (mStart + mCount) % mSamples;
                mCount++;
            } else {
                index = mStart;
                mStart = (mStart + 1) % mSamples;
            }
            for (int series = 0; series < mValues.length; series++) {
                mValues[series][index] = series < values.length ? values[series] : Float.NaN;
            }
        }

        @Override
        public int size() {
            return mCount;
        }

        @Override
        public float value(int series, int index) {
            if (series < 0 || series >= mValues.length) {
                return Float.NaN;
            }
            return mValues[series][(mStart + index) % mSamples];
        }
    }

    private final Paint mSeriesPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    /** Axis labels: the theme's secondary text colour, resolved once. */
    private final int mAxisColor;
    private final float mStrokeWidth;
    private final float mLabelGap;
    /** The default (fan) chart, built from the theme's series colours. */
    private final Chart mFanChart;

    @Nullable
    private Chart mChart;
    @Nullable
    private History mHistory;
    @Nullable
    private Data mChartData;

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

        mFanChart = new Chart(
                new Axis(TEMP_MIN_C, TEMP_MAX_C, TEMP_GRID_C,
                        context.getString(R.string.temperature_value)),
                new Axis(0f, PERCENT_MAX, PERCENT_LABELS,
                        context.getString(R.string.percent_value)),
                new Series[] {
                        new Series(0, context.getColor(R.color.fan_graph_temp),
                                TEMP_MIN_C, TEMP_MAX_C),
                        new Series(1, context.getColor(R.color.fan_graph_pct),
                                0f, PERCENT_MAX),
                        new Series(1, context.getColor(R.color.fan_graph_rpm),
                                0f, RPM_REFERENCE),
                },
                SAMPLES);
    }

    /** Sets the fan window to draw; the caller invalidates when it appends. */
    public void setHistory(@Nullable History history) {
        mHistory = history;
        mChart = null;
        mChartData = null;
        invalidate();
    }

    /**
     * Installs a non-fan chart (the Power page).  The Fan page keeps using
     * {@link #setHistory}; the two setters are mutually exclusive.
     */
    public void setChart(@Nullable Chart chart, @Nullable Data data) {
        mChart = chart;
        mChartData = data;
        mHistory = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final Chart chart = mChart != null ? mChart : mFanChart;
        final Data data = mChart != null ? mChartData : mHistory;
        if (data == null) {
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
        final String leftTop = format(chart.left, chart.left.max);
        final String rightTop = chart.right == null ? "" : format(chart.right, chart.right.max);

        final float plotLeft = getPaddingLeft() + mLabelPaint.measureText(leftTop) + mLabelGap;
        final float plotRight = getWidth() - getPaddingRight()
                - (chart.right == null ? 0f : mLabelPaint.measureText(rightTop) + mLabelGap);
        final float plotTop = getPaddingTop() + labelHeight / 2f;
        final float plotBottom = getHeight() - getPaddingBottom() - labelHeight / 2f;
        if (plotRight <= plotLeft || plotBottom <= plotTop) {
            return;
        }

        // Left axis: its grid lines and their labels.
        mLabelPaint.setTextAlign(Paint.Align.RIGHT);
        for (float value : chart.left.grid) {
            final float y = yFor(fraction(value, chart.left.min, chart.left.max),
                    plotTop, plotBottom);
            canvas.drawLine(plotLeft, y, plotRight, y, mGridPaint);
            drawLabel(canvas, format(chart.left, value), plotLeft - mLabelGap, y, metrics);
        }

        // Right axis carries labels only; the grid lines belong to the left one.
        if (chart.right != null) {
            mLabelPaint.setTextAlign(Paint.Align.LEFT);
            for (float value : chart.right.grid) {
                final float y = yFor(fraction(value, chart.right.min, chart.right.max),
                        plotTop, plotBottom);
                drawLabel(canvas, format(chart.right, value), plotRight + mLabelGap, y, metrics);
            }
        }

        // Newest sample on the right edge; one slot is one page tick.
        final int count = data.size();
        if (count == 0) {
            return;
        }
        final float step = (plotRight - plotLeft) / (chart.samples - 1);
        for (int series = 0; series < chart.series.length; series++) {
            drawSeries(canvas, chart, data, series, plotRight, plotTop, plotBottom, step);
        }
    }

    private static String format(Axis axis, float value) {
        return String.format(java.util.Locale.US, axis.format, Math.round(value));
    }

    private void drawSeries(Canvas canvas, Chart chart, Data data, int series, float plotRight,
            float plotTop, float plotBottom, float step) {
        final Series description = chart.series[series];
        mSeriesPaint.setColor(description.color);
        mPath.reset();
        boolean started = false;
        // A lone valid sample between two gaps is a point, not a line.
        float runX = 0f;
        float runY = 0f;
        int runPoints = 0;
        final int count = data.size();
        for (int i = 0; i < count; i++) {
            final float value = data.value(series, i);
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
            final float y = yFor(fraction(value, description.min, description.max),
                    plotTop, plotBottom);
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
