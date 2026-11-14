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
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import org.ashfall.parts.hw.Pad;
import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.RawSample;

import java.util.Locale;

/**
 * A drawn RP5 outline with live input: buttons light up, stick dots move inside
 * the configured deadzone rings, trigger bars fill, and the raw values from
 * {@code RAW ON} are shown next to the calibrated {@code MotionEvent} values.
 */
public class ControllerTesterView extends View {

    private static final int BTN_A = 0;
    private static final int BTN_B = 1;
    private static final int BTN_X = 2;
    private static final int BTN_Y = 3;
    private static final int BTN_L1 = 4;
    private static final int BTN_R1 = 5;
    private static final int BTN_L2 = 6;
    private static final int BTN_R2 = 7;
    private static final int BTN_START = 8;
    private static final int BTN_SELECT = 9;
    private static final int BTN_UP = 10;
    private static final int BTN_DOWN = 11;
    private static final int BTN_LEFT = 12;
    private static final int BTN_RIGHT = 13;
    private static final int BTN_L3 = 14;
    private static final int BTN_R3 = 15;
    private static final int BTN_COUNT = 16;

    private final boolean[] mPressed = new boolean[BTN_COUNT];

    private float mLeftX;
    private float mLeftY;
    private float mRightX;
    private float mRightY;
    private float mL2;
    private float mR2;
    private float mHatX;
    private float mHatY;

    private RawSample mRaw;
    private CalData mCalibration;
    private boolean mShowRaw = true;
    private boolean mRawAvailable;

    private final Paint mBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();

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
        mAccent = themeColor(android.R.attr.colorAccent, 0xFFFF2020);
        mPrimary = themeColor(android.R.attr.textColorPrimary, 0xFFFFFFFF);
        mSecondary = themeColor(android.R.attr.textColorSecondary, 0xFF888888);

        mBody.setStyle(Paint.Style.STROKE);
        mBody.setStrokeWidth(2 * mDensity);
        mBody.setColor(mSecondary);
        mFill.setStyle(Paint.Style.FILL);
        mText.setColor(mPrimary);
        mText.setTextSize(14 * mDensity);
        mSmallText.setColor(mSecondary);
        mSmallText.setTextSize(12 * mDensity);
        mGrid.setStyle(Paint.Style.STROKE);
        mGrid.setStrokeWidth(mDensity);
        mGrid.setColor(Ui.fade(mSecondary, 0.45f));
        mDot.setStyle(Paint.Style.FILL);
        mDot.setColor(mAccent);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setCalibration(CalData calibration) {
        mCalibration = calibration;
        invalidate();
    }

    public void setRawAvailable(boolean available) {
        mRawAvailable = available;
        invalidate();
    }

    public void setRaw(RawSample sample) {
        mRaw = sample;
        mRawAvailable = true;
        updateContentDescription();
        if (mShowRaw) {
            invalidate();
        }
    }

    /** Screen-reader/test description of the live state (also drives uiautomator). */
    private void updateContentDescription() {
        final StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "Left %.2f %.2f", mLeftX, mLeftY));
        text.append(String.format(Locale.US, ", right %.2f %.2f", mRightX, mRightY));
        text.append(String.format(Locale.US, ", L2 %.2f, R2 %.2f", mL2, mR2));
        if (mRaw != null) {
            text.append(String.format(Locale.US, ", raw lx %.0f ly %.0f rx %.0f ry %.0f",
                    mRaw.leftX, mRaw.leftY, mRaw.rightX, mRaw.rightY));
            text.append(String.format(Locale.US, ", buttons 0x%02X", mRaw.buttons));
        }
        final String[] names = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Start",
                "Select", "Up", "Down", "Left", "Right", "L3", "R3"};
        final StringBuilder pressed = new StringBuilder();
        for (int i = 0; i < mPressed.length; i++) {
            if (mPressed[i]) {
                if (pressed.length() > 0) {
                    pressed.append(' ');
                }
                pressed.append(names[i]);
            }
        }
        if (pressed.length() > 0) {
            text.append(", pressed ").append(pressed);
        }
        setContentDescription(text.toString());
    }

    public boolean isShowingRaw() {
        return mShowRaw;
    }

    public void setShowRaw(boolean show) {
        mShowRaw = show;
        invalidate();
    }

    public void toggleRaw() {
        setShowRaw(!mShowRaw);
    }

    /** Feeds a key event into the display; returns true if it is a gamepad key. */
    public boolean handleKey(KeyEvent event) {
        final int index = indexForKey(event.getKeyCode());
        if (index < 0) {
            return false;
        }
        mPressed[index] = event.getAction() == KeyEvent.ACTION_DOWN;
        updateContentDescription();
        invalidate();
        return true;
    }

    /** Feeds a joystick motion event into the display. */
    public boolean handleMotion(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            return false;
        }
        mLeftX = event.getAxisValue(MotionEvent.AXIS_X);
        mLeftY = event.getAxisValue(MotionEvent.AXIS_Y);
        mRightX = event.getAxisValue(MotionEvent.AXIS_Z);
        mRightY = event.getAxisValue(MotionEvent.AXIS_RZ);
        if (mRightX == 0f && mRightY == 0f) {
            mRightX = event.getAxisValue(MotionEvent.AXIS_RX);
            mRightY = event.getAxisValue(MotionEvent.AXIS_RY);
        }
        mL2 = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        mR2 = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        mHatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        mHatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (mHatX > 0.5f) {
            mPressed[BTN_RIGHT] = true;
        } else if (mHatX < -0.5f) {
            mPressed[BTN_LEFT] = true;
        } else {
            mPressed[BTN_RIGHT] = false;
            mPressed[BTN_LEFT] = false;
        }
        if (mHatY > 0.5f) {
            mPressed[BTN_DOWN] = true;
        } else if (mHatY < -0.5f) {
            mPressed[BTN_UP] = true;
        } else {
            mPressed[BTN_DOWN] = false;
            mPressed[BTN_UP] = false;
        }
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
            case KeyEvent.KEYCODE_BUTTON_L2:
                return BTN_L2;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return BTN_R2;
            case KeyEvent.KEYCODE_BUTTON_START:
                return BTN_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return BTN_SELECT;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return BTN_R3;
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Handheld body.
        mRect.set(w * 0.08f, h * 0.18f, w * 0.92f, h * 0.88f);
        canvas.drawRoundRect(mRect, h * 0.10f, h * 0.10f, mBody);

        // Screen.
        mRect.set(w * 0.40f, h * 0.30f, w * 0.60f, h * 0.62f);
        canvas.drawRoundRect(mRect, h * 0.02f, h * 0.02f, mBody);
        canvas.drawText("AshFall", w * 0.5f - mText.measureText("AshFall") / 2,
                h * 0.48f, mText);

        // Sticks.
        final float stickRadius = h * 0.11f;
        final float leftCx = w * 0.27f;
        final float rightCx = w * 0.73f;
        final float stickCy = h * 0.68f;
        drawStick(canvas, leftCx, stickCy, stickRadius, mLeftX, mLeftY, true);
        drawStick(canvas, rightCx, stickCy, stickRadius, mRightX, mRightY, false);
        canvas.drawText(String.format(Locale.US, "L  %.2f  %.2f", mLeftX, mLeftY),
                leftCx - mSmallText.measureText("L  -0.00  -0.00") / 2,
                stickCy + stickRadius + 18 * mDensity, mSmallText);
        canvas.drawText(String.format(Locale.US, "R  %.2f  %.2f", mRightX, mRightY),
                rightCx - mSmallText.measureText("R  -0.00  -0.00") / 2,
                stickCy + stickRadius + 18 * mDensity, mSmallText);

        // D-pad and face buttons.
        drawDpad(canvas, w * 0.19f, h * 0.44f, h * 0.045f);
        drawFaceButtons(canvas, w * 0.81f, h * 0.44f, h * 0.026f);

        // Start / Select / stick clicks.
        drawPill(canvas, w * 0.44f, h * 0.36f, mPressed[BTN_SELECT], "SEL");
        drawPill(canvas, w * 0.56f, h * 0.36f, mPressed[BTN_START], "STA");

        // Shoulder buttons and analog triggers.
        drawTrigger(canvas, w * 0.13f, h * 0.24f, w * 0.13f, h * 0.035f,
                mPressed[BTN_L1], "L1", -1);
        drawTrigger(canvas, w * 0.13f, h * 0.17f, w * 0.13f, h * 0.035f,
                mPressed[BTN_L2] || mL2 > 0.5f, "L2", mL2);
        drawTrigger(canvas, w * 0.87f, h * 0.24f, w * 0.13f, h * 0.035f,
                mPressed[BTN_R1], "R1", -1);
        drawTrigger(canvas, w * 0.87f, h * 0.17f, w * 0.13f, h * 0.035f,
                mPressed[BTN_R2] || mR2 > 0.5f, "R2", mR2);

        // Raw stream read-out.
        if (mShowRaw) {
            drawRaw(canvas, w, h);
        }
    }

    private void drawStick(Canvas canvas, float cx, float cy, float radius, float nx, float ny,
            boolean left) {
        mGrid.setColor(Ui.fade(mSecondary, 0.35f));
        canvas.drawCircle(cx, cy, radius, mGrid);
        final float inner = radius * (Pad.inner() / 100f);
        if (inner > 0.5f) {
            canvas.drawCircle(cx, cy, inner, mGrid);
        }
        final float outer = radius * (1f - Pad.outer() / 100f);
        if (outer < radius - 0.5f) {
            canvas.drawCircle(cx, cy, outer, mGrid);
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, mGrid);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, mGrid);

        final float dotX = cx + clamp(nx) * radius * 0.92f;
        final float dotY = cy + clamp(ny) * radius * 0.92f;
        canvas.drawCircle(dotX, dotY, 10 * mDensity, mDot);
        if (mPressed[left ? BTN_L3 : BTN_R3]) {
            mGrid.setColor(mAccent);
            canvas.drawCircle(cx, cy, radius - 2 * mDensity, mGrid);
        }
    }

    private void drawDpad(Canvas canvas, float cx, float cy, float arm) {
        final float half = arm * 0.34f;
        drawBox(canvas, cx, cy - arm, half, arm, mPressed[BTN_UP]);
        drawBox(canvas, cx, cy + arm, half, arm, mPressed[BTN_DOWN]);
        drawBox(canvas, cx - arm, cy, arm, half, mPressed[BTN_LEFT]);
        drawBox(canvas, cx + arm, cy, arm, half, mPressed[BTN_RIGHT]);
    }

    private void drawFaceButtons(Canvas canvas, float cx, float cy, float radius) {
        drawCircleButton(canvas, cx, cy - radius * 2.2f, radius, mPressed[BTN_Y], "Y");
        drawCircleButton(canvas, cx, cy + radius * 2.2f, radius, mPressed[BTN_B], "B");
        drawCircleButton(canvas, cx - radius * 2.2f, cy, radius, mPressed[BTN_X], "X");
        drawCircleButton(canvas, cx + radius * 2.2f, cy, radius, mPressed[BTN_A], "A");
    }

    private void drawCircleButton(Canvas canvas, float cx, float cy, float radius, boolean pressed,
            String label) {
        mFill.setColor(pressed ? mAccent : 0x00000000);
        canvas.drawCircle(cx, cy, radius, mFill);
        mGrid.setColor(pressed ? mAccent : mSecondary);
        canvas.drawCircle(cx, cy, radius, mGrid);
        mText.setColor(pressed ? mPrimary : mSecondary);
        canvas.drawText(label, cx - mText.measureText(label) / 2, cy + mText.getTextSize() / 3,
                mText);
        mText.setColor(mPrimary);
    }

    private void drawBox(Canvas canvas, float cx, float cy, float halfW, float halfH,
            boolean pressed) {
        mRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        mFill.setColor(pressed ? mAccent : 0x00000000);
        canvas.drawRect(mRect, mFill);
        mGrid.setColor(pressed ? mAccent : mSecondary);
        canvas.drawRect(mRect, mGrid);
    }

    private void drawPill(Canvas canvas, float cx, float cy, boolean pressed, String label) {
        final float halfW = 26 * mDensity;
        final float halfH = 11 * mDensity;
        mRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        mFill.setColor(pressed ? mAccent : 0x00000000);
        canvas.drawRoundRect(mRect, halfH, halfH, mFill);
        mGrid.setColor(pressed ? mAccent : mSecondary);
        canvas.drawRoundRect(mRect, halfH, halfH, mGrid);
        mSmallText.setColor(pressed ? mPrimary : mSecondary);
        canvas.drawText(label, cx - mSmallText.measureText(label) / 2,
                cy + mSmallText.getTextSize() / 3, mSmallText);
        mSmallText.setColor(mSecondary);
    }

    private void drawTrigger(Canvas canvas, float cx, float cy, float width, float height,
            boolean pressed, String label, float analog) {
        mRect.set(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
        mFill.setColor(pressed ? mAccent : 0x00000000);
        canvas.drawRoundRect(mRect, height / 2, height / 2, mFill);
        if (analog >= 0) {
            mRect.set(cx - width / 2, cy + height / 2 - height * clamp(analog),
                    cx + width / 2, cy + height / 2);
            mFill.setColor(Ui.fade(mAccent, 0.55f));
            canvas.drawRoundRect(mRect, height / 2, height / 2, mFill);
        }
        mGrid.setColor(pressed ? mAccent : mSecondary);
        canvas.drawRoundRect(mRect, height / 2, height / 2, mGrid);
        mSmallText.setColor(pressed ? mPrimary : mSecondary);
        canvas.drawText(label, cx - mSmallText.measureText(label) / 2,
                cy + mSmallText.getTextSize() / 3, mSmallText);
        mSmallText.setColor(mSecondary);
    }

    private void drawRaw(Canvas canvas, float w, float h) {
        final float x = w * 0.60f;
        float y = h * 0.90f;
        if (!mRawAvailable || mRaw == null) {
            mSmallText.setColor(0xFFFFC107);
            canvas.drawText("RAW unavailable", x, y, mSmallText);
            mSmallText.setColor(mSecondary);
            return;
        }
        final String[] lines = {
                String.format(Locale.US, "RAW  lx %.0f  ly %.0f", mRaw.leftX, mRaw.leftY),
                String.format(Locale.US, "          rx %.0f  ry %.0f", mRaw.rightX, mRaw.rightY),
                String.format(Locale.US, "          l2 %.0f  r2 %.0f", mRaw.l2, mRaw.r2),
                String.format(Locale.US, "          buttons 0x%02X", mRaw.buttons),
        };
        for (String line : lines) {
            canvas.drawText(line, x, y, mSmallText);
            y += 16 * mDensity;
        }
        if (mCalibration != null && mCalibration.isComplete()) {
            canvas.drawText("cal: " + mCalibration.source(), x, y, mSmallText);
        }
    }

    private int themeColor(int attr, int fallback) {
        final android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return fallback;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
