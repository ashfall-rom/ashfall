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
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import org.ashfall.parts.hw.Pad;
import org.ashfall.parts.hw.PadState;
import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.RawSample;

import java.util.Locale;

/**
 * Live controller tester: only the controls, no handheld silhouette (issue #101).
 *
 * <p>Every control is a big, focus-independent drawing that uses the full
 * landscape screen: the left stick and the D-pad on the left, the face buttons
 * and the right stick on the right, the triggers on the outer edges, the
 * shoulder buttons above them, and Start/Select plus Home/Back in the middle.
 * Between the two halves the live RAW counts, the calibration source and the
 * GRAB state are shown.
 *
 * <p><b>Button positions (physical RP5, issue #101):</b> X top, Y left,
 * A right, B bottom.
 *
 * <pre>
 *          X
 *      Y       A
 *          B
 * </pre>
 *
 * <p>The MCU bits are unchanged (12 X, 13 Y, 14 A, 15 B); only the drawn
 * position of A and B differs from the first version of this view.
 *
 * <p><b>Input.</b>  While the tester is open the daemon is grabbed
 * ({@code GRAB ON}), so nothing is injected into uinput and no {@code KeyEvent}
 * ever reaches the app: buttons are taken from the {@code RAW} button mask and
 * sticks/triggers from the raw MCU counts, run through the same calibration and
 * deadzone mapping the daemon uses ({@link PadState}).  The key/motion path is
 * kept as a fallback for a missing daemon (and for the emulator preview).
 *
 * <p>Only theme attributes are used, so the drawing follows the light, dark and
 * pure-black themes.
 */
public class ControllerTesterView extends View {

    /**
     * MCU button bits, exactly as documented in {@code docs/rp5/input.md} and
     * required by {@code docs/phase5.md} §6.2 for the {@code RAW} mask.
     */
    public static final int BTN_L1 = 0;
    public static final int BTN_R1 = 1;
    public static final int BTN_SELECT = 2;
    public static final int BTN_START = 3;
    public static final int BTN_L3 = 4;
    public static final int BTN_R3 = 5;
    public static final int BTN_HOME = 6;
    public static final int BTN_BACK = 7;
    public static final int BTN_UP = 8;
    public static final int BTN_DOWN = 9;
    public static final int BTN_LEFT = 10;
    public static final int BTN_RIGHT = 11;
    public static final int BTN_X = 12;
    public static final int BTN_Y = 13;
    public static final int BTN_A = 14;
    public static final int BTN_B = 15;
    public static final int BTN_COUNT = 16;

    private static final String[] BIT_NAMES = {
            "L1", "R1", "Select", "Start", "L3", "R3", "Home", "Back",
            "Up", "Down", "Left", "Right", "X", "Y", "A", "B",
    };

    private static final long RAW_STALE_MS = 500;

    private final boolean[] mFallback = new boolean[BTN_COUNT];
    private PadState mState = PadState.rest();
    private RawSample mRaw;
    private CalData mCalibration;
    private boolean mRawAvailable;
    private int mRawButtons;
    private long mLastRawAt;
    private boolean mGrabActive;
    private float mExitHold;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final TypedValue mValue = new TypedValue();

    private int mAccent;
    private int mPrimary;
    private int mSecondary;
    private float mDensity;

    public ControllerTesterView(Context context) {
        this(context, null);
    }

    public ControllerTesterView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(2 * mDensity);
        mLabel.setTextSize(16 * mDensity);
        mLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSmall.setTextSize(14 * mDensity);
        mSmall.setTypeface(Typeface.MONOSPACE);
        mDot.setStyle(Paint.Style.FILL);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        resolveTheme();
    }

    public void setCalibration(CalData calibration) {
        mCalibration = calibration;
        invalidate();
    }

    /** The daemon refused (or did not answer) {@code GRAB ON}. */
    public void setGrabActive(boolean grabbed) {
        mGrabActive = grabbed;
        invalidate();
    }

    public boolean isGrabActive() {
        return mGrabActive;
    }

    public void setRawAvailable(boolean available) {
        mRawAvailable = available;
        if (!available) {
            mRaw = null;
            mState = PadState.rest();
        }
        invalidate();
    }

    /** New raw MCU sample: the authoritative input while the daemon is grabbed. */
    public void setRaw(RawSample sample) {
        mRaw = sample;
        mRawAvailable = true;
        mRawButtons = sample.buttons;
        mLastRawAt = System.currentTimeMillis();
        mState = PadState.from(sample, mCalibration);
        updateContentDescription();
        invalidate();
    }

    /** Called with 0..1 while Start+Select is held; drives the exit progress bar. */
    public void setExitHold(float fraction) {
        final float value = Math.max(0f, Math.min(1f, fraction));
        if (Math.abs(value - mExitHold) < 0.01f) {
            return;
        }
        mExitHold = value;
        invalidate();
    }

    public PadState getState() {
        return mState;
    }

    public int getButtons() {
        if (rawLive()) {
            return mRawButtons;
        }
        int mask = 0;
        for (int i = 0; i < BTN_COUNT; i++) {
            if (mFallback[i]) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    public boolean isPressed(int bit) {
        return (getButtons() & (1 << bit)) != 0;
    }

    public RawSample getRaw() {
        return mRaw;
    }

    private boolean rawLive() {
        return mRawAvailable && System.currentTimeMillis() - mLastRawAt < RAW_STALE_MS;
    }

    /** Screen-reader/test description of the live state (also drives uiautomator). */
    private void updateContentDescription() {
        final StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "Left %.2f %.2f", mState.leftX, mState.leftY));
        text.append(String.format(Locale.US, ", right %.2f %.2f", mState.rightX, mState.rightY));
        text.append(String.format(Locale.US, ", L2 %.2f, R2 %.2f", mState.l2, mState.r2));
        if (mRaw != null) {
            text.append(String.format(Locale.US, ", raw lx %.0f ly %.0f rx %.0f ry %.0f",
                    mRaw.leftX, mRaw.leftY, mRaw.rightX, mRaw.rightY));
            text.append(String.format(Locale.US, ", l2 %.0f r2 %.0f", mRaw.l2, mRaw.r2));
            text.append(String.format(Locale.US, ", buttons 0x%04X", mRaw.buttons));
        }
        final StringBuilder pressed = new StringBuilder();
        final int mask = getButtons();
        for (int i = 0; i < BTN_COUNT; i++) {
            if ((mask & (1 << i)) != 0) {
                if (pressed.length() > 0) {
                    pressed.append(' ');
                }
                pressed.append(BIT_NAMES[i]);
            }
        }
        if (pressed.length() > 0) {
            text.append(", pressed ").append(pressed);
        }
        setContentDescription(text.toString());
    }

    /** Feeds a key event into the fallback display; false when the key is not ours. */
    public boolean handleKey(KeyEvent event) {
        if (rawLive()) {
            // The daemon owns the pad while grabbed; never mix the two paths.
            return false;
        }
        final int index = indexForKey(event.getKeyCode());
        if (index < 0) {
            return false;
        }
        mFallback[index] = event.getAction() == KeyEvent.ACTION_DOWN;
        updateContentDescription();
        invalidate();
        return true;
    }

    /** Feeds a joystick motion event into the fallback display. */
    public boolean handleMotion(MotionEvent event) {
        if (rawLive()) {
            return false;
        }
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            return false;
        }
        float rightX = event.getAxisValue(MotionEvent.AXIS_Z);
        float rightY = event.getAxisValue(MotionEvent.AXIS_RZ);
        if (rightX == 0f && rightY == 0f) {
            rightX = event.getAxisValue(MotionEvent.AXIS_RX);
            rightY = event.getAxisValue(MotionEvent.AXIS_RY);
        }
        mState = PadState.ofAxes(event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y), rightX, rightY,
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER));
        final float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        final float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        mFallback[BTN_RIGHT] = hatX > 0.5f;
        mFallback[BTN_LEFT] = hatX < -0.5f;
        mFallback[BTN_DOWN] = hatY > 0.5f;
        mFallback[BTN_UP] = hatY < -0.5f;
        updateContentDescription();
        invalidate();
        return true;
    }

    private int indexForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return BTN_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return BTN_B;
            case KeyEvent.KEYCODE_BUTTON_X:
                return BTN_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return BTN_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return BTN_L1;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return BTN_R1;
            case KeyEvent.KEYCODE_BUTTON_START:
                return BTN_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return BTN_SELECT;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return BTN_R3;
            case KeyEvent.KEYCODE_HOME:
                return BTN_HOME;
            case KeyEvent.KEYCODE_BACK:
                return BTN_BACK;
            case KeyEvent.KEYCODE_DPAD_UP:
                return BTN_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return BTN_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return BTN_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return BTN_RIGHT;
            default:
                return -1;
        }
    }

    private void resolveTheme() {
        mAccent = themeColor(android.R.attr.colorAccent, 0xFFFF2020);
        mPrimary = themeColor(android.R.attr.textColorPrimary, 0xFFEEEEEE);
        mSecondary = themeColor(android.R.attr.textColorSecondary, 0xFF888888);
        mFill.setColor(Ui.fade(mPrimary, 0.05f));
        mStroke.setColor(Ui.fade(mSecondary, 0.75f));
        mLabel.setColor(mPrimary);
        mSmall.setColor(mSecondary);
        mDot.setColor(mAccent);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resolveTheme();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        resolveTheme();

        // Sizes scale with the shorter axis so the controls stay large in
        // landscape (1920x1080 / 853x480 dp) without overlapping.
        final float stickRadius = Math.min(h * 0.175f, w * 0.10f);
        final float dpadArm = Math.min(h * 0.130f, w * 0.072f);
        final float faceRadius = Math.min(h * 0.057f, w * 0.030f);

        drawStatus(canvas, w, h);
        drawTriggerBars(canvas, w, h);
        drawShoulders(canvas, w, h);
        drawSticks(canvas, w, h, stickRadius);
        drawDpad(canvas, w, h, dpadArm);
        drawFaceButtons(canvas, w, h, faceRadius);
        drawSystemButtons(canvas, w, h);
        drawExitHold(canvas, w, h);
    }

    /**
     * Live RAW read-out in the free space between the two control clusters.
     * It replaces the old "screen" of the silhouette; no body art is drawn.
     */
    private void drawStatus(Canvas canvas, float w, float h) {
        final float x = w * 0.365f;
        float y = h * 0.24f;
        final float line = 22 * mDensity;
        mSmall.setTextSize(14 * mDensity);
        mLabel.setTextSize(18 * mDensity);

        mLabel.setColor(mSecondary);
        canvas.drawText("RAW", x, y, mLabel);
        y += line * 1.35f;

        if (!mRawAvailable || mRaw == null) {
            mSmall.setColor(mSecondary);
            canvas.drawText("Input daemon not reachable", x, y, mSmall);
            y += line;
            canvas.drawText("Normal buttons are shown", x, y, mSmall);
            y += line;
            canvas.drawText("B or Back leaves", x, y, mSmall);
            mLabel.setTextSize(16 * mDensity);
            return;
        }

        mSmall.setColor(mPrimary);
        canvas.drawText(String.format(Locale.US, "lx %7.0f    ly %7.0f",
                mRaw.leftX, mRaw.leftY), x, y, mSmall);
        y += line;
        canvas.drawText(String.format(Locale.US, "rx %7.0f    ry %7.0f",
                mRaw.rightX, mRaw.rightY), x, y, mSmall);
        y += line;
        canvas.drawText(String.format(Locale.US, "l2 %7.0f    r2 %7.0f",
                mRaw.l2, mRaw.r2), x, y, mSmall);
        y += line * 1.25f;
        mSmall.setColor(mSecondary);
        canvas.drawText(String.format(Locale.US, "buttons 0x%04X", mRaw.buttons), x, y, mSmall);
        y += line;
        canvas.drawText("cal " + (mCalibration == null ? "default" : mCalibration.source()),
                x, y, mSmall);
        y += line;
        if (mGrabActive) {
            mSmall.setColor(mAccent);
            canvas.drawText("GRAB ON", x, y, mSmall);
        } else {
            mSmall.setColor(mSecondary);
            canvas.drawText("GRAB OFF", x, y, mSmall);
        }
        mLabel.setTextSize(16 * mDensity);
        mSmall.setTextSize(14 * mDensity);
    }

    /** L2/R2 as tall vertical analog bars on the outer edges: exact values. */
    private void drawTriggerBars(Canvas canvas, float w, float h) {
        drawTriggerBar(canvas, w * 0.028f, h * 0.16f, h * 0.85f, mState.l2, "L2");
        drawTriggerBar(canvas, w * 0.972f, h * 0.16f, h * 0.85f, mState.r2, "R2");
    }

    private void drawTriggerBar(Canvas canvas, float cx, float top, float bottom, float value,
            String label) {
        final float halfW = 13 * mDensity;
        mRect.set(cx - halfW, top, cx + halfW, bottom);
        mFill.setColor(Ui.fade(mPrimary, 0.05f));
        canvas.drawRoundRect(mRect, halfW, halfW, mFill);
        if (value > 0.001f) {
            mRect.set(cx - halfW, bottom - (bottom - top) * value, cx + halfW, bottom);
            mFill.setColor(mAccent);
            canvas.drawRoundRect(mRect, halfW, halfW, mFill);
        }
        mStroke.setColor(Ui.fade(mSecondary, 0.8f));
        mRect.set(cx - halfW, top, cx + halfW, bottom);
        canvas.drawRoundRect(mRect, halfW, halfW, mStroke);
        mLabel.setColor(mPrimary);
        canvas.drawText(label, cx - mLabel.measureText(label) / 2, top - 10 * mDensity, mLabel);
        final String percent = String.format(Locale.US, "%d%%", Math.round(value * 100));
        mSmall.setColor(mSecondary);
        canvas.drawText(percent, cx - mSmall.measureText(percent) / 2,
                bottom + 26 * mDensity, mSmall);
    }

    private void drawShoulders(Canvas canvas, float w, float h) {
        drawButton(canvas, w * 0.115f, h * 0.075f, w * 0.065f, h * 0.036f,
                isPressed(BTN_L1), "L1");
        drawButton(canvas, w * 0.885f, h * 0.075f, w * 0.065f, h * 0.036f,
                isPressed(BTN_R1), "R1");
    }

    /**
     * Left stick above the D-pad, right stick below the face buttons: the
     * physical RP5 arrangement, without the body around it.
     */
    private void drawSticks(Canvas canvas, float w, float h, float radius) {
        drawStick(canvas, w * 0.165f, h * 0.30f, radius, mState.leftX, mState.leftY, true);
        drawStick(canvas, w * 0.835f, h * 0.72f, radius, mState.rightX, mState.rightY, false);
    }

    private void drawStick(Canvas canvas, float cx, float cy, float radius, float nx, float ny,
            boolean left) {
        final float inner = radius * (Pad.inner() / 100f);
        final float outer = radius * (1f - Pad.outer() / 100f);
        mFill.setColor(Ui.fade(mPrimary, 0.04f));
        canvas.drawCircle(cx, cy, radius, mFill);
        mStroke.setColor(Ui.fade(mSecondary, 0.75f));
        canvas.drawCircle(cx, cy, radius, mStroke);
        if (inner > 0.5f) {
            mStroke.setColor(Ui.fade(mSecondary, 0.32f));
            canvas.drawCircle(cx, cy, inner, mStroke);
        }
        if (outer < radius - 0.5f) {
            mStroke.setColor(Ui.fade(mSecondary, 0.32f));
            canvas.drawCircle(cx, cy, outer, mStroke);
        }
        mStroke.setColor(Ui.fade(mSecondary, 0.24f));
        canvas.drawLine(cx - radius, cy, cx + radius, cy, mStroke);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, mStroke);

        final float dotX = cx + clamp(nx) * radius * 0.90f;
        final float dotY = cy + clamp(ny) * radius * 0.90f;
        mDot.setColor(isPressed(left ? BTN_L3 : BTN_R3) ? mAccent : Ui.fade(mAccent, 0.9f));
        canvas.drawCircle(dotX, dotY, Math.max(10 * mDensity, radius * 0.18f), mDot);

        final String click = left ? "L3" : "R3";
        mLabel.setColor(isPressed(left ? BTN_L3 : BTN_R3) ? mAccent : mSecondary);
        canvas.drawText(click, cx + radius * 0.66f, cy + radius * 0.18f, mLabel);

        final String value = String.format(Locale.US, "%.2f  %.2f", nx, ny);
        mSmall.setColor(mSecondary);
        canvas.drawText(value, cx - mSmall.measureText(value) / 2,
                cy + radius + 22 * mDensity, mSmall);
    }

    private void drawDpad(Canvas canvas, float w, float h, float arm) {
        final float cx = w * 0.165f;
        final float cy = h * 0.72f;
        final float half = arm * 0.36f;
        drawArrow(canvas, cx, cy - arm, half, arm, isPressed(BTN_UP), false, -1f);
        drawArrow(canvas, cx, cy + arm, half, arm, isPressed(BTN_DOWN), false, 1f);
        drawArrow(canvas, cx - arm, cy, arm, half, isPressed(BTN_LEFT), true, -1f);
        drawArrow(canvas, cx + arm, cy, arm, half, isPressed(BTN_RIGHT), true, 1f);
        mFill.setColor(Ui.fade(mPrimary, 0.04f));
        mRect.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRect(mRect, mFill);
    }

    private void drawArrow(Canvas canvas, float cx, float cy, float halfW, float halfH,
            boolean pressed, boolean sideways, float sign) {
        mRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        mFill.setColor(pressed ? mAccent : Ui.fade(mPrimary, 0.04f));
        canvas.drawRect(mRect, mFill);
        mStroke.setColor(pressed ? mAccent : Ui.fade(mSecondary, 0.7f));
        canvas.drawRect(mRect, mStroke);
        // Small direction notch in the middle of the arm.
        mFill.setColor(pressed ? mPrimary : Ui.fade(mSecondary, 0.55f));
        final float notch = 7 * mDensity;
        mPath.reset();
        if (sideways) {
            mPath.moveTo(cx + halfW * 0.5f * sign, cy);
            mPath.lineTo(cx, cy - notch);
            mPath.lineTo(cx, cy + notch);
        } else {
            mPath.moveTo(cx, cy + halfH * 0.5f * sign);
            mPath.lineTo(cx - notch, cy);
            mPath.lineTo(cx + notch, cy);
        }
        mPath.close();
        canvas.drawPath(mPath, mFill);
    }

    /** X top, Y left, A right, B bottom (physical RP5, issue #101). */
    private void drawFaceButtons(Canvas canvas, float w, float h, float radius) {
        final float cx = w * 0.835f;
        final float cy = h * 0.32f;
        final float offset = radius * 2.05f;
        drawCircle(canvas, cx, cy - offset, radius, isPressed(BTN_X), "X");
        drawCircle(canvas, cx - offset, cy, radius, isPressed(BTN_Y), "Y");
        drawCircle(canvas, cx + offset, cy, radius, isPressed(BTN_A), "A");
        drawCircle(canvas, cx, cy + offset, radius, isPressed(BTN_B), "B");
    }

    private void drawCircle(Canvas canvas, float cx, float cy, float radius, boolean pressed,
            String label) {
        mFill.setColor(pressed ? mAccent : Ui.fade(mPrimary, 0.05f));
        canvas.drawCircle(cx, cy, radius, mFill);
        mStroke.setColor(pressed ? mAccent : Ui.fade(mSecondary, 0.8f));
        canvas.drawCircle(cx, cy, radius, mStroke);
        mLabel.setColor(pressed ? mPrimary : mSecondary);
        canvas.drawText(label, cx - mLabel.measureText(label) / 2,
                cy + mLabel.getTextSize() * 0.36f, mLabel);
    }

    private void drawSystemButtons(Canvas canvas, float w, float h) {
        drawButton(canvas, w * 0.415f, h * 0.145f, w * 0.055f, h * 0.037f,
                isPressed(BTN_BACK), "Back");
        drawButton(canvas, w * 0.585f, h * 0.145f, w * 0.055f, h * 0.037f,
                isPressed(BTN_HOME), "Home");

        drawButton(canvas, w * 0.445f, h * 0.885f, w * 0.062f, h * 0.043f,
                isPressed(BTN_SELECT), "Select");
        drawButton(canvas, w * 0.555f, h * 0.885f, w * 0.062f, h * 0.043f,
                isPressed(BTN_START), "Start");
    }

    private void drawButton(Canvas canvas, float cx, float cy, float halfW, float halfH,
            boolean pressed, String label) {
        mRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        mFill.setColor(pressed ? mAccent : Ui.fade(mPrimary, 0.05f));
        canvas.drawRoundRect(mRect, halfH, halfH, mFill);
        mStroke.setColor(pressed ? mAccent : Ui.fade(mSecondary, 0.8f));
        canvas.drawRoundRect(mRect, halfH, halfH, mStroke);
        mLabel.setColor(pressed ? mPrimary : mSecondary);
        canvas.drawText(label, cx - mLabel.measureText(label) / 2,
                cy + mLabel.getTextSize() * 0.36f, mLabel);
    }

    /** Progress of the Start+Select exit chord, drawn under the two pills. */
    private void drawExitHold(Canvas canvas, float w, float h) {
        if (mExitHold <= 0f) {
            return;
        }
        final float width = w * 0.20f;
        final float height = 6 * mDensity;
        mRect.set(w * 0.5f - width / 2, h * 0.965f, w * 0.5f + width / 2,
                h * 0.965f + height);
        mFill.setColor(Ui.fade(mSecondary, 0.35f));
        canvas.drawRoundRect(mRect, height / 2, height / 2, mFill);
        mRect.right = mRect.left + width * mExitHold;
        mFill.setColor(mAccent);
        canvas.drawRoundRect(mRect, height / 2, height / 2, mFill);
    }

    /**
     * Resolves a theme colour.  {@code resolveAttribute} can return a colour
     * state-list reference on some themes, in which case {@link TypedValue#data}
     * is a resource id rather than an ARGB value; resolve that properly so the
     * drawing follows light, dark and pure-black instead of using a hash of the
     * resource id.
     */
    private int themeColor(int attr, int fallback) {
        final Context context = getContext();
        if (context == null || !context.getTheme().resolveAttribute(attr, mValue, true)) {
            return fallback;
        }
        if (mValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && mValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return mValue.data;
        }
        if (mValue.resourceId != 0) {
            try {
                return context.getColorStateList(mValue.resourceId).getDefaultColor();
            } catch (RuntimeException ignored) {
                try {
                    return context.getResources().getColor(mValue.resourceId,
                            context.getTheme());
                } catch (RuntimeException alsoIgnored) {
                    // Fall through to the raw value.
                }
            }
        }
        return mValue.data != 0 ? mValue.data : fallback;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
