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
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import org.ashfall.parts.hw.Curve;

/**
 * Interactive fan-curve editor.
 *
 * <p>Touch: drag a point.  Gamepad: left/right move the selected point along
 * the temperature axis, up/down change its fan percentage, L1/R1 select the
 * previous/next point, A adds a point and X deletes the selected one.  The
 * current temperature is drawn as a live vertical marker.
 */
public class CurveEditorView extends View {

    public interface Listener {
        void onCurveChanged(Curve curve);

        void onSelectionChanged(int index, int temp, int pct);
    }

    private static final float TOUCH_RADIUS_DP = 32f;

    private final Paint mGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPoint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mPlot = new RectF();
    private final Path mPath = new Path();

    private Curve mCurve = Curve.defaults();
    private int mSelected;
    private float mTemperature = -1;
    private Listener mListener;
    private int mDragging = -1;

    public CurveEditorView(Context context) {
        this(context, null);
    }

    public CurveEditorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);

        final float density = getResources().getDisplayMetrics().density;
        final int accent = themeColor(android.R.attr.colorAccent, 0xFFFF2020);
        final int secondary = themeColor(android.R.attr.textColorSecondary, 0xFF888888);
        final int primary = themeColor(android.R.attr.textColorPrimary, 0xFFFFFFFF);
        mGrid.setColor(Ui.fade(secondary, 0.25f));
        mGrid.setStrokeWidth(density);
        mAxis.setColor(Ui.fade(secondary, 0.6f));
        mAxis.setStrokeWidth(density);
        mLine.setColor(accent);
        mLine.setStrokeWidth(3 * density);
        mLine.setStyle(Paint.Style.STROKE);
        mPoint.setColor(accent);
        mSelectedPaint.setColor(primary);
        mSelectedPaint.setStyle(Paint.Style.STROKE);
        mSelectedPaint.setStrokeWidth(3 * density);
        mText.setColor(secondary);
        mText.setTextSize(11 * density);
        mMarker.setColor(0xFFFFC107);
        mMarker.setStrokeWidth(2 * density);
        mMarker.setStyle(Paint.Style.STROKE);
        mMarker.setPathEffect(new DashPathEffect(new float[] {6 * density, 4 * density}, 0));
        mFocus.setColor(accent);
        mFocus.setStyle(Paint.Style.STROKE);
        mFocus.setStrokeWidth(2 * density);
        mFocus.setPathEffect(new DashPathEffect(new float[] {8 * density, 6 * density}, 0));
    }

    private int themeColor(int attr, int fallback) {
        final android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return fallback;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void setCurve(Curve curve, boolean keepSelection) {
        mCurve = curve != null ? curve : Curve.defaults();
        if (!keepSelection || mSelected >= mCurve.size()) {
            mSelected = 0;
        }
        invalidate();
        dispatchSelection();
    }

    public Curve getCurve() {
        return mCurve;
    }

    public void setTemperature(float temperature) {
        mTemperature = temperature;
        invalidate();
    }

    public int getSelectedIndex() {
        return mSelected;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float padLeft = Ui.dp(getContext(), 40);
        final float padRight = Ui.dp(getContext(), 16);
        final float padTop = Ui.dp(getContext(), 12);
        final float padBottom = Ui.dp(getContext(), 28);
        mPlot.set(padLeft, padTop, getWidth() - padRight, getHeight() - padBottom);

        if (isFocused()) {
            final float inset = Ui.dp(getContext(), 2);
            canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset,
                    Ui.dp(getContext(), 12), Ui.dp(getContext(), 12), mFocus);
        }

        // Grid and labels: 25..95 °C across, 0..100 % up.
        for (int temp = 30; temp <= 90; temp += 10) {
            final float x = xForTemp(temp);
            canvas.drawLine(x, mPlot.top, x, mPlot.bottom, mGrid);
            final String label = temp + "°";
            canvas.drawText(label, x - mText.measureText(label) / 2, mPlot.bottom
                    + Ui.dp(getContext(), 16), mText);
        }
        for (int pct = 0; pct <= 100; pct += 25) {
            final float y = yForPct(pct);
            canvas.drawLine(mPlot.left, y, mPlot.right, y, mGrid);
            final String label = pct + "%";
            canvas.drawText(label, mPlot.left - mText.measureText(label)
                    - Ui.dp(getContext(), 6), y + mText.getTextSize() / 3, mText);
        }
        canvas.drawLine(mPlot.left, mPlot.top, mPlot.left, mPlot.bottom, mAxis);
        canvas.drawLine(mPlot.left, mPlot.bottom, mPlot.right, mPlot.bottom, mAxis);

        // Curve.
        mPath.reset();
        for (int i = 0; i < mCurve.size(); i++) {
            final float x = xForTemp(mCurve.temp(i));
            final float y = yForPct(mCurve.pct(i));
            if (i == 0) {
                mPath.moveTo(x, y);
            } else {
                mPath.lineTo(x, y);
            }
        }
        canvas.drawPath(mPath, mLine);

        final float radius = Ui.dp(getContext(), 7);
        for (int i = 0; i < mCurve.size(); i++) {
            final float x = xForTemp(mCurve.temp(i));
            final float y = yForPct(mCurve.pct(i));
            canvas.drawCircle(x, y, radius, mPoint);
            if (i == mSelected) {
                canvas.drawCircle(x, y, radius + Ui.dp(getContext(), 4), mSelectedPaint);
            }
        }

        // Live temperature marker.
        if (mTemperature >= 0) {
            final int temp = Math.round(mTemperature);
            final float x = xForTemp(Math.max(Curve.MIN_TEMP, Math.min(Curve.MAX_TEMP, temp)));
            canvas.drawLine(x, mPlot.top, x, mPlot.bottom, mMarker);
            final float y = yForPct(mCurve.percentFor(temp));
            canvas.drawCircle(x, y, Ui.dp(getContext(), 5), mMarker);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = findPoint(event.getX(), event.getY());
                if (mDragging >= 0) {
                    final android.view.ViewParent parent = getParent();
                    if (parent != null) {
                        // The page is scrollable; a point drag must not turn
                        // into a page scroll.
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    mSelected = mDragging;
                    dispatchSelection();
                    requestFocus();
                    invalidate();
                    return true;
                }
                return super.onTouchEvent(event);
            case MotionEvent.ACTION_MOVE:
                if (mDragging >= 0) {
                    dragTo(mDragging, event.getX(), event.getY());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging >= 0) {
                    mDragging = -1;
                    final android.view.ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void dragTo(int index, float x, float y) {
        final int lo = index > 0 ? mCurve.temp(index - 1) + 1 : Curve.MIN_TEMP;
        final int hi = index < mCurve.size() - 1 ? mCurve.temp(index + 1) - 1 : Curve.MAX_TEMP;
        final int temp = clamp(Math.round(tempForX(x)), lo, Math.max(lo, hi));
        final int pct = clamp(Math.round(pctForY(y)), 0, 100);
        apply(mCurve.movePoint(index, temp, pct), index);
    }

    private int findPoint(float x, float y) {
        final float radius = Ui.dp(getContext(), TOUCH_RADIUS_DP);
        for (int i = 0; i < mCurve.size(); i++) {
            final float px = xForTemp(mCurve.temp(i));
            final float py = yForPct(mCurve.pct(i));
            if (Math.hypot(px - x, py - y) <= radius) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveSelected(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveSelected(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!canMovePct(1)) {
                    // No point can be raised further: let the framework move
                    // the focus to the controls below the graph (the stress
                    // test sliders), so the page stays gamepad-navigable.
                    return super.onKeyDown(keyCode, event);
                }
                moveSelected(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!canMovePct(-1)) {
                    return super.onKeyDown(keyCode, event);
                }
                moveSelected(0, -1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_PAGE_UP:
                selectRelative(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                selectRelative(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                addPoint();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_DEL:
                removeSelected();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Whether the selected point's output can still change in {@code delta}. */
    private boolean canMovePct(int delta) {
        if (mCurve.size() == 0) {
            return false;
        }
        final int next = mCurve.pct(mSelected) + delta;
        return next >= 0 && next <= 100;
    }

    private void moveSelected(int deltaTemp, int deltaPct) {
        if (mCurve.size() == 0) {
            return;
        }
        final int lo = mSelected > 0 ? mCurve.temp(mSelected - 1) + 1 : Curve.MIN_TEMP;
        final int hi = mSelected < mCurve.size() - 1
                ? mCurve.temp(mSelected + 1) - 1 : Curve.MAX_TEMP;
        final int temp = clamp(mCurve.temp(mSelected) + deltaTemp, lo, Math.max(lo, hi));
        final int pct = clamp(mCurve.pct(mSelected) + deltaPct, 0, 100);
        if (temp == mCurve.temp(mSelected) && pct == mCurve.pct(mSelected)) {
            return;
        }
        apply(mCurve.movePoint(mSelected, temp, pct), mSelected);
    }

    private void selectRelative(int delta) {
        if (mCurve.size() == 0) {
            return;
        }
        mSelected = Math.floorMod(mSelected + delta, mCurve.size());
        dispatchSelection();
        invalidate();
    }

    public void addPoint() {
        if (mCurve.size() >= Curve.MAX_POINTS) {
            return;
        }
        int bestGap = -1;
        int bestTemp = Curve.MIN_TEMP;
        for (int i = 1; i < mCurve.size(); i++) {
            final int gap = mCurve.temp(i) - mCurve.temp(i - 1);
            if (gap > bestGap) {
                bestGap = gap;
                bestTemp = (mCurve.temp(i) + mCurve.temp(i - 1)) / 2;
            }
        }
        if (bestGap < 2) {
            return;
        }
        apply(mCurve.addPoint(bestTemp, mCurve.percentFor(bestTemp)), mSelected);
    }

    public void removeSelected() {
        if (mCurve.size() <= Curve.MIN_POINTS) {
            return;
        }
        final Curve next = mCurve.removePoint(mSelected);
        mSelected = Math.max(0, Math.min(mSelected, next.size() - 1));
        apply(next, mSelected);
    }

    public void reset() {
        apply(Curve.defaults(), 0);
    }

    private void apply(Curve curve, int selected) {
        mCurve = curve;
        mSelected = Math.max(0, Math.min(selected, curve.size() - 1));
        invalidate();
        dispatchSelection();
        if (mListener != null) {
            mListener.onCurveChanged(curve);
        }
    }

    private void dispatchSelection() {
        if (mListener == null || mCurve.size() == 0) {
            return;
        }
        mListener.onSelectionChanged(mSelected, mCurve.temp(mSelected), mCurve.pct(mSelected));
    }

    private float xForTemp(int temp) {
        final float ratio = (temp - Curve.MIN_TEMP) / (float) (Curve.MAX_TEMP - Curve.MIN_TEMP);
        return mPlot.left + ratio * mPlot.width();
    }

    private float yForPct(int pct) {
        return mPlot.bottom - (pct / 100f) * mPlot.height();
    }

    private float tempForX(float x) {
        if (mPlot.width() <= 0) {
            return Curve.MIN_TEMP;
        }
        final float ratio = (x - mPlot.left) / mPlot.width();
        return Curve.MIN_TEMP + ratio * (Curve.MAX_TEMP - Curve.MIN_TEMP);
    }

    private float pctForY(float y) {
        if (mPlot.height() <= 0) {
            return 0;
        }
        final float ratio = (mPlot.bottom - y) / mPlot.height();
        return ratio * 100f;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
