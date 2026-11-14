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
 * Clean-room drawing of the RP5 front face with live input.
 *
 * <p><b>Button positions.</b>  The layout is not the generic Xbox one: it is
 * derived from the button table in {@code docs/rp5/input.md}, which is what the
 * stock firmware itself uses.  The MCU reports bit 12 as {@code BTN_X} (Linux
 * {@code BTN_NORTH}, 0x133) and bit 13 as {@code BTN_Y} (Linux
 * {@code BTN_WEST}, 0x134), so on this device <b>X is the top button and Y the
 * left one</b>, while A is south and B east:
 *
 * <pre>
 *          X
 *      Y       B
 *          A
 * </pre>
 *
 * The previous drawing used the Xbox arrangement (Y top, X left) and therefore
 * showed X and Y swapped on real hardware (issue #84).
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
    private static final long HOLD_MS = 2000;

    private final boolean[] mFallback = new boolean[BTN_COUNT];
    private PadState mState = PadState.rest();
    private RawSample mRaw;
    private CalData mCalibration;
    private boolean mRawAvailable;
    private int mRawButtons;
    private long mLastRawAt;
    private boolean mGrabActive;
    private float mExitHold;

    private final Paint mBodyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBodyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mSilhouette = new Path();
    private final Path mTempPath = new Path();
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final RectF mScreen = new RectF();
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
        mBodyFill.setStyle(Paint.Style.FILL);
        mBodyStroke.setStyle(Paint.Style.STROKE);
        mBodyStroke.setStrokeWidth(1.5f * mDensity);
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(1.5f * mDensity);
        mLabel.setTextSize(13 * mDensity);
        mLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSmall.setTextSize(11 * mDensity);
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
        final Context context = getContext();
        mAccent = themeColor(android.R.attr.colorAccent, 0xFFFF2020);
        mPrimary = themeColor(android.R.attr.textColorPrimary, 0xFFEEEEEE);
        mSecondary = themeColor(android.R.attr.textColorSecondary, 0xFF888888);
        mBodyFill.setColor(Ui.fade(mPrimary, 0.05f));
        mBodyStroke.setColor(Ui.fade(mSecondary, 0.75f));
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
        final float unit = Math.min(w * 0.052f, h * 0.085f);

        drawInfo(canvas, w, h);
        drawBody(canvas, w, h);
        drawScreen(canvas, w, h);
        drawTriggers(canvas, w, h);
        drawShoulders(canvas, w, h);
        drawSticks(canvas, w, h, unit);
        drawDpad(canvas, w, h, unit);
        drawFaceButtons(canvas, w, h, unit);
        drawSystemButtons(canvas, w, h);
        drawExitHold(canvas, w, h);
    }

    /** Top strip: raw counts, grab state and calibration source. */
    private void drawInfo(Canvas canvas, float w, float h) {
        final String text;
        if (!mRawAvailable || mRaw == null) {
            text = "RAW stream unavailable - using normal input events";
        } else {
            text = String.format(Locale.US,
                    "RAW  lx %.0f  ly %.0f  rx %.0f  ry %.0f   l2 %.0f  r2 %.0f   buttons 0x%04X"
                            + "   %s%s", mRaw.leftX, mRaw.leftY, mRaw.rightX, mRaw.rightY,
                    mRaw.l2, mRaw.r2, mRaw.buttons,
                    mCalibration == null ? "cal default"
                            : "cal " + mCalibration.source(),
                    mGrabActive ? "  ·  GRAB ON" : "");
        }
        mSmall.setColor(mRaw != null ? mSecondary : Ui.fade(mSecondary, 0.9f));
        canvas.drawText(text, w * 0.03f, h * 0.055f, mSmall);
    }

    /**
     * The handheld outline: a rounded body with two grips and shoulder bumps,
     * unioned into one path so the fill and the outline stay clean.
     */
    private void drawBody(Canvas canvas, float w, float h) {
        final float left = w * 0.045f;
        final float right = w * 0.955f;
        final float top = h * 0.12f;
        final float bottom = h * 0.93f;
        mRect.set(left, top, right, bottom);
        mSilhouette.reset();
        mSilhouette.addRoundRect(mRect, h * 0.075f, h * 0.075f, Path.Direction.CW);

        // Grips: lower, slightly wider than the body.
        mTempPath.reset();
        mRect.set(w * 0.02f, h * 0.62f, w * 0.245f, h * 0.995f);
        mTempPath.addRoundRect(mRect, h * 0.10f, h * 0.10f, Path.Direction.CW);
        mRect.set(w * 0.755f, h * 0.62f, w * 0.98f, h * 0.995f);
        mTempPath.addRoundRect(mRect, h * 0.10f, h * 0.10f, Path.Direction.CW);
        mSilhouette.op(mTempPath, Path.Op.UNION);

        canvas.drawPath(mSilhouette, mBodyFill);
        canvas.drawPath(mSilhouette, mBodyStroke);

        // Shoulder bumps on the top edge, behind the L1/R1 pills.
        mRect.set(w * 0.07f, h * 0.055f, w * 0.30f, h * 0.145f);
        canvas.drawRoundRect(mRect, h * 0.03f, h * 0.03f, mBodyFill);
        canvas.drawRoundRect(mRect, h * 0.03f, h * 0.03f, mStroke);
        mRect.set(w * 0.70f, h * 0.055f, w * 0.93f, h * 0.145f);
        canvas.drawRoundRect(mRect, h * 0.03f, h * 0.03f, mBodyFill);
        canvas.drawRoundRect(mRect, h * 0.03f, h * 0.03f, mStroke);
    }

    private void drawScreen(Canvas canvas, float w, float h) {
        mScreen.set(w * 0.315f, h * 0.185f, w * 0.685f, h * 0.80f);
        // Bezel.
        mRect.set(mScreen);
        mRect.inset(-6 * mDensity, -6 * mDensity);
        mFill.setColor(Ui.fade(mPrimary, 0.07f));
        canvas.drawRoundRect(mRect, h * 0.02f, h * 0.02f, mFill);
        mFill.setColor(Ui.fade(mPrimary, 0.025f));
        canvas.drawRoundRect(mScreen, h * 0.015f, h * 0.015f, mFill);
        canvas.drawRoundRect(mScreen, h * 0.015f, h * 0.015f, mStroke);

        mSmall.setColor(Ui.fade(mSecondary, 0.8f));
        final String label = "AshFall";
        canvas.drawText(label, mScreen.centerX() - mSmall.measureText(label) / 2,
                mScreen.centerY(), mSmall);
    }

    /** L2/R2 as vertical analog bars: the fill is the real value, never a snap. */
    private void drawTriggers(Canvas canvas, float w, float h) {
        drawTriggerBar(canvas, w * 0.062f, h * 0.19f, h * 0.79f, mState.l2, "L2");
        drawTriggerBar(canvas, w * 0.938f, h * 0.19f, h * 0.79f, mState.r2, "R2");
    }

    private void drawTriggerBar(Canvas canvas, float cx, float top, float bottom, float value,
            String label) {
        final float halfW = 13 * mDensity;
        mRect.set(cx - halfW, top, cx + halfW, bottom);
        mFill.setColor(Ui.fade(mPrimary, 0.04f));
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
        canvas.drawText(label, cx - mLabel.measureText(label) / 2, top - 8 * mDensity, mLabel);
        final String percent = String.format(Locale.US, "%d%%", Math.round(value * 100));
        mSmall.setColor(mSecondary);
        canvas.drawText(percent, cx - mSmall.measureText(percent) / 2,
                bottom + 16 * mDensity, mSmall);
    }

    private void drawShoulders(Canvas canvas, float w, float h) {
        drawButton(canvas, w * 0.135f, h * 0.10f, w * 0.055f, h * 0.026f,
                isPressed(BTN_L1), "L1");
        drawButton(canvas, w * 0.865f, h * 0.10f, w * 0.055f, h * 0.026f,
                isPressed(BTN_R1), "R1");
    }

    /**
     * RP5 stick positions: the left stick sits above the D-pad and the right
     * stick below the face buttons (offset, like the device).
     */
    private void drawSticks(Canvas canvas, float w, float h, float radius) {
        drawStick(canvas, w * 0.15f, h * 0.31f, radius, mState.leftX, mState.leftY, true);
        drawStick(canvas, w * 0.85f, h * 0.665f, radius, mState.rightX, mState.rightY, false);
    }

    private void drawStick(Canvas canvas, float cx, float cy, float radius, float nx, float ny,
            boolean left) {
        final float inner = radius * (Pad.inner() / 100f);
        final float outer = radius * (1f - Pad.outer() / 100f);
        mFill.setColor(Ui.fade(mPrimary, 0.03f));
        canvas.drawCircle(cx, cy, radius, mFill);
        mStroke.setColor(Ui.fade(mSecondary, 0.75f));
        canvas.drawCircle(cx, cy, radius, mStroke);
        if (inner > 0.5f) {
            mStroke.setColor(Ui.fade(mSecondary, 0.30f));
            canvas.drawCircle(cx, cy, inner, mStroke);
        }
        if (outer < radius - 0.5f) {
            mStroke.setColor(Ui.fade(mSecondary, 0.30f));
            canvas.drawCircle(cx, cy, outer, mStroke);
        }
        mStroke.setColor(Ui.fade(mSecondary, 0.22f));
        canvas.drawLine(cx - radius, cy, cx + radius, cy, mStroke);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, mStroke);

        final float dotX = cx + clamp(nx) * radius * 0.90f;
        final float dotY = cy + clamp(ny) * radius * 0.90f;
        mDot.setColor(isPressed(left ? BTN_L3 : BTN_R3) ? mAccent : Ui.fade(mAccent, 0.85f));
        canvas.drawCircle(dotX, dotY, Math.max(6 * mDensity, radius * 0.17f), mDot);

        final String click = left ? "L3" : "R3";
        mSmall.setColor(isPressed(left ? BTN_L3 : BTN_R3) ? mAccent : mSecondary);
        canvas.drawText(click, cx + radius * 0.78f, cy + radius * 0.15f, mSmall);

        final String value = String.format(Locale.US, "%.2f  %.2f", nx, ny);
        mSmall.setColor(mSecondary);
        canvas.drawText(value, cx - mSmall.measureText(value) / 2,
                cy + radius + 16 * mDensity, mSmall);
    }

    /** D-pad at the lower left; X/Y/A/B diamond at the upper right (see class docs). */
    private void drawDpad(Canvas canvas, float w, float h, float arm) {
        final float cx = w * 0.15f;
        final float cy = h * 0.665f;
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
        final float notch = 4 * mDensity;
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

    private void drawFaceButtons(Canvas canvas, float w, float h, float unit) {
        final float cx = w * 0.85f;
        final float cy = h * 0.31f;
        final float radius = unit * 0.42f;
        final float offset = radius * 2.05f;
        drawCircle(canvas, cx, cy - offset, radius, isPressed(BTN_X), "X");
        drawCircle(canvas, cx - offset, cy, radius, isPressed(BTN_Y), "Y");
        drawCircle(canvas, cx, cy + offset, radius, isPressed(BTN_A), "A");
        drawCircle(canvas, cx + offset, cy, radius, isPressed(BTN_B), "B");
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
        // Home/Back sit on the top edge, as on the device.
        drawButton(canvas, w * 0.40f, h * 0.115f, w * 0.038f, h * 0.021f,
                isPressed(BTN_BACK), "Back");
        drawButton(canvas, w * 0.60f, h * 0.115f, w * 0.038f, h * 0.021f,
                isPressed(BTN_HOME), "Home");

        // Start/Select below the screen, with the exit-hold bar.
        drawButton(canvas, w * 0.465f, h * 0.875f, w * 0.042f, h * 0.023f,
                isPressed(BTN_SELECT), "Select");
        drawButton(canvas, w * 0.535f, h * 0.875f, w * 0.042f, h * 0.023f,
                isPressed(BTN_START), "Start");
    }

    private void drawButton(Canvas canvas, float cx, float cy, float halfW, float halfH,
            boolean pressed, String label) {
        mRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        mFill.setColor(pressed ? mAccent : Ui.fade(mPrimary, 0.05f));
        canvas.drawRoundRect(mRect, halfH, halfH, mFill);
        mStroke.setColor(pressed ? mAccent : Ui.fade(mSecondary, 0.8f));
        canvas.drawRoundRect(mRect, halfH, halfH, mStroke);
        mSmall.setColor(pressed ? mPrimary : mSecondary);
        canvas.drawText(label, cx - mSmall.measureText(label) / 2,
                cy + mSmall.getTextSize() * 0.35f, mSmall);
    }

    /** Progress of the Start+Select exit chord, drawn under the two pills. */
    private void drawExitHold(Canvas canvas, float w, float h) {
        if (mExitHold <= 0f) {
            return;
        }
        final float width = w * 0.18f;
        final float height = 5 * mDensity;
        mRect.set(w * 0.5f - width / 2, h * 0.925f, w * 0.5f + width / 2,
                h * 0.925f + height);
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
