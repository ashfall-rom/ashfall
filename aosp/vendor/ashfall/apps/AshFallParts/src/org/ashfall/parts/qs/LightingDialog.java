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

package org.ashfall.parts.qs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.ashfall.parts.LedSyncService;
import org.ashfall.parts.Prefs;
import org.ashfall.parts.R;
import org.ashfall.parts.hw.Led;
import org.ashfall.parts.ui.ColorPickerView;
import org.ashfall.parts.ui.ColorSwatchView;
import org.ashfall.parts.ui.ScreenLedSync;
import org.ashfall.parts.ui.Ui;

import java.util.Locale;

/**
 * The Lighting Quick Settings dialog: on/off, target, colour (preset swatches
 * or an inline hue/saturation picker), brightness, effect and speed.  Every
 * change is applied to the daemon properties immediately.
 */
public class LightingDialog extends Dialog {

    public interface Listener {
        void onChanged();
    }

    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private Switch mSwitch;
    private RadioGroup mTargetGroup;
    private RadioGroup mEffectGroup;
    private LinearLayout mSwatches;
    private View mColourExpanded;
    private ColorPickerView mPicker;
    private SeekBar mHue;
    private TextView mHueValue;
    private SeekBar mBrightness;
    private TextView mBrightnessValue;
    private SeekBar mSpeed;
    private TextView mSpeedValue;
    private RadioButton mEffectScreen;
    private LinearLayout mScreenRow;
    private TextView mScreenRateValue;
    private LinearLayout mScreenDelayRow;
    private TextView mScreenDelayValue;
    private LinearLayout mScreenBoostRow;
    private TextView mScreenBoostValue;
    private LinearLayout mScreenDimRow;
    private TextView mScreenDimValue;
    private TextView mScreenNote;

    private boolean mUpdating;
    private int mPendingColor;
    private final Runnable mApplyColor = () -> {
        Led.setColor(Prefs.ledTarget(getContext()), Led.argbToColor(mPendingColor));
        notifyChanged();
    };

    public LightingDialog(@NonNull Context context, Listener listener) {
        super(context, R.style.Theme_AshFall_QsDialog);
        mListener = listener;
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_lighting);

        mSwitch = findViewById(R.id.qs_lighting_switch);
        mTargetGroup = findViewById(R.id.qs_lighting_target);
        mEffectGroup = findViewById(R.id.qs_lighting_effect);
        mSwatches = findViewById(R.id.qs_lighting_swatches);
        mColourExpanded = findViewById(R.id.qs_lighting_colour_expanded);
        mPicker = findViewById(R.id.qs_lighting_picker);
        mHue = findViewById(R.id.qs_lighting_hue);
        mHueValue = findViewById(R.id.qs_lighting_hue_value);
        mBrightness = findViewById(R.id.qs_lighting_brightness);
        mBrightnessValue = findViewById(R.id.qs_lighting_brightness_value);
        mSpeed = findViewById(R.id.qs_lighting_speed);
        mSpeedValue = findViewById(R.id.qs_lighting_speed_value);

        // The sixth effect entry, its rate row and the limits note are built in
        // code so the QS dialog stays in parity with the page without touching
        // the shared layout (#167).
        addScreenControls();
        buildSwatches();
        bindListeners();
        // A QS dialog is a user surface too: resume Match screen if it is the
        // stored effect (the service is otherwise only started from the page).
        LedSyncService.sync(getContext());
        refresh();
        findViewById(R.id.qs_lighting_done).setOnClickListener(view -> dismiss());

        // Size after the swatches exist so the footer is always on screen.
        final Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            Ui.fitDialog(window, findViewById(R.id.qs_lighting_root),
                    findViewById(R.id.qs_lighting_scroll), 0f);
        }
        mSwitch.requestFocus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mApplyColor);
    }

    private void addScreenControls() {
        final Context context = getContext();
        mEffectScreen = new RadioButton(context);
        mEffectScreen.setId(View.generateViewId());
        mEffectScreen.setText(R.string.lighting_effect_screen);
        mEffectScreen.setLayoutParams(new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 40)));
        mEffectGroup.addView(mEffectScreen);

        final LinearLayout container = (LinearLayout) mSpeed.getParent();
        final int index = container.indexOfChild(mSpeed) + 1;
        mScreenRow = screenRow(R.string.lighting_screen_rate_title, index,
                view -> cycleScreenRate());
        mScreenRateValue = (TextView) mScreenRow.getChildAt(1);

        mScreenDelayRow = screenRow(R.string.lighting_screen_delay_title, index + 1,
                view -> cycleScreenDelay());
        mScreenDelayValue = (TextView) mScreenDelayRow.getChildAt(1);

        mScreenBoostRow = screenRow(R.string.lighting_screen_boost_title, index + 2,
                view -> cycleScreenBoost());
        mScreenBoostValue = (TextView) mScreenBoostRow.getChildAt(1);

        mScreenDimRow = screenRow(R.string.lighting_screen_dim_title, index + 3,
                view -> toggleScreenDim());
        mScreenDimValue = (TextView) mScreenDimRow.getChildAt(1);

        mScreenNote = new TextView(context);
        mScreenNote.setTextAppearance(context, R.style.AshFallDialogSecondary);
        mScreenNote.setText(R.string.lighting_screen_note);
        mScreenNote.setPadding(0, 0, 0, Ui.dp(context, 8));
        container.addView(mScreenNote, index + 4);
    }

    /** One tappable "title ... value" row in the Match screen block. */
    private LinearLayout screenRow(int titleRes, int index, View.OnClickListener listener) {
        final Context context = getContext();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8));
        row.setFocusable(true);
        row.setClickable(true);
        final TypedValue background = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, background, true)
                && background.resourceId != 0) {
            row.setBackgroundResource(background.resourceId);
        }
        final TextView title = new TextView(context);
        title.setTextAppearance(context, R.style.AshFallSection);
        title.setText(titleRes);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = new TextView(context);
        value.setTextAppearance(context, R.style.AshFallDialogPrimary);
        row.addView(title);
        row.addView(value);
        row.setOnClickListener(listener);
        final LinearLayout container = (LinearLayout) mSpeed.getParent();
        container.addView(row, index);
        return row;
    }

    private void cycleScreenDelay() {
        final int[] delays = ScreenLedSync.SCREEN_DELAYS_MS;
        int next = delays[0];
        for (int i = 0; i < delays.length; i++) {
            if (delays[i] == ScreenLedSync.screenDelay()) {
                next = delays[(i + 1) % delays.length];
                break;
            }
        }
        ScreenLedSync.setScreenDelay(next);
        refresh();
    }

    private String delayLabel(int ms) {
        return ms <= 0 ? getContext().getString(R.string.lighting_screen_delay_off)
                : getContext().getString(R.string.lighting_screen_delay_value, ms);
    }

    private void toggleScreenDim() {
        ScreenLedSync.setScreenDim(!ScreenLedSync.screenDim());
        refresh();
    }

    private String dimLabel() {
        return getContext().getString(ScreenLedSync.screenDim()
                ? R.string.lighting_screen_dim_on : R.string.lighting_screen_dim_off);
    }

    private void cycleScreenBoost() {
        final int[] boosts = ScreenLedSync.SCREEN_BOOSTS_PCT;
        int next = boosts[0];
        for (int i = 0; i < boosts.length; i++) {
            if (boosts[i] == ScreenLedSync.screenBoost()) {
                next = boosts[(i + 1) % boosts.length];
                break;
            }
        }
        ScreenLedSync.setScreenBoost(next);
        refresh();
    }

    private String boostLabel(int pct) {
        if (pct <= 0) {
            return getContext().getString(R.string.lighting_screen_boost_off);
        }
        if (pct <= 30) {
            return getContext().getString(R.string.lighting_screen_boost_low);
        }
        if (pct <= 60) {
            return getContext().getString(R.string.lighting_screen_boost_medium);
        }
        return getContext().getString(R.string.lighting_screen_boost_high);
    }

    private void cycleScreenRate() {
        final int[] rates = Led.SCREEN_RATES;
        int next = rates[0];
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == Led.screenRate()) {
                next = rates[(i + 1) % rates.length];
                break;
            }
        }
        Led.setScreenRate(next);
        refresh();
    }

    private void buildSwatches() {
        final int size = Ui.dp(getContext(), 36);
        final int gap = Ui.dp(getContext(), 6);
        for (int colorRes : Ui.PRESET_COLORS) {
            final ColorSwatchView swatch = new ColorSwatchView(getContext());
            final int color = ContextCompat.getColor(getContext(), colorRes);
            swatch.setColor(color);
            swatch.setContentDescription(String.format(Locale.US, "#%06X", color & 0xFFFFFF));
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(gap);
            swatch.setLayoutParams(params);
            swatch.setOnClickListener(view -> {
                Led.setColor(Prefs.ledTarget(getContext()), Led.argbToColor(color));
                if (!Led.isOn()) {
                    Led.setMode(Prefs.lastLedMode(getContext()));
                }
                refresh();
                notifyChanged();
            });
            mSwatches.addView(swatch);
        }
    }

    private void bindListeners() {
        mSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mUpdating) {
                return;
            }
            if (checked) {
                final String last = Prefs.lastLedMode(getContext());
                Led.setMode(Led.MODE_OFF.equals(last) ? Led.MODE_STATIC : last);
            } else {
                Prefs.setLastLedMode(getContext(), Led.mode());
                Led.setMode(Led.MODE_OFF);
            }
            // Effect changed: the page does the same so the sampler stops in
            // the same frame instead of at the next supervisor tick.
            LedSyncService.sync(getContext());
            refresh();
            notifyChanged();
        });

        mTargetGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (mUpdating) {
                return;
            }
            final int target = checkedId == R.id.qs_target_right ? Led.TARGET_RIGHT
                    : checkedId == R.id.qs_target_left ? Led.TARGET_LEFT : Led.TARGET_BOTH;
            Prefs.setLedTarget(getContext(), target);
            refresh();
        });

        mEffectGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (mUpdating) {
                return;
            }
            final String mode = modeForId(checkedId);
            // "Match screen" leaves the last real effect in led.mode (#167).
            if (!Led.MODE_SCREEN.equals(mode)) {
                Prefs.setLastLedMode(getContext(), mode);
            } else if (!Led.isOn()) {
                final String last = Prefs.lastLedMode(getContext());
                Led.setMode(Led.MODE_OFF.equals(last) ? Led.MODE_STATIC : last);
            }
            Led.setMode(mode);
            LedSyncService.sync(getContext());
            refresh();
            notifyChanged();
        });

        findViewById(R.id.qs_lighting_custom).setOnClickListener(view -> {
            final boolean show = mColourExpanded.getVisibility() != View.VISIBLE;
            mColourExpanded.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                mPicker.setColor(Led.colorToArgb(Led.colorFor(Prefs.ledTarget(getContext()))));
                mPicker.requestFocus();
            }
        });

        mPicker.setListener(argb -> {
            if (mUpdating) {
                return;
            }
            mPendingColor = argb;
            mHandler.removeCallbacks(mApplyColor);
            mHandler.postDelayed(mApplyColor, 80);
        });

        mHue.setMax(360);
        mHue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                mPicker.setHue(progress);
                mHueValue.setText(String.format(Locale.US, "%d°", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mBrightness.setMax(100);
        mBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mBrightnessValue.setText(getContext().getString(R.string.percent_value, progress));
                if (!fromUser || mUpdating) {
                    return;
                }
                Led.setBrightness(progress);
                notifyChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mSpeed.setMax(9);
        mSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final int value = progress + 1;
                mSpeedValue.setText(getContext().getString(
                        R.string.lighting_speed_summary, value));
                if (!fromUser || mUpdating) {
                    return;
                }
                Led.setSpeed(value);
                notifyChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void refresh() {
        mUpdating = true;
        try {
            final boolean on = Led.isOn();
            final boolean screen = Led.screenSync();
            final String mode = on ? Led.effect() : Prefs.lastLedMode(getContext());
            mSwitch.setChecked(on);

            final int target = Prefs.ledTarget(getContext());
            mTargetGroup.check(target == Led.TARGET_RIGHT ? R.id.qs_target_right
                    : target == Led.TARGET_LEFT ? R.id.qs_target_left : R.id.qs_target_both);

            final int color = Led.colorToArgb(Led.colorFor(target));
            mPendingColor = color;
            mPicker.setColor(color);
            final float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            mHue.setProgress(Math.round(hsv[0]));
            mHueValue.setText(String.format(Locale.US, "%d°", Math.round(hsv[0])));

            for (int i = 0; i < mSwatches.getChildCount() && i < Ui.PRESET_COLORS.length; i++) {
                final View child = mSwatches.getChildAt(i);
                if (!(child instanceof ColorSwatchView)) {
                    continue;
                }
                final int preset = ContextCompat.getColor(getContext(), Ui.PRESET_COLORS[i]);
                ((ColorSwatchView) child).setSelected((preset & 0xFFFFFF) == (color & 0xFFFFFF));
            }

            mEffectGroup.check(modeIdFor(mode));
            mBrightness.setProgress(Led.brightness());
            mBrightnessValue.setText(getContext().getString(
                    R.string.percent_value, Led.brightness()));
            mSpeed.setProgress(Led.speed() - 1);
            mSpeedValue.setText(getContext().getString(
                    R.string.lighting_speed_summary, Led.speed()));
            // Duo is animated by the daemon with led.speed as well (#100).
            // Match screen is a literal per-half average plus an optional
            // delay, so led.speed no longer applies to it.
            final boolean animated = Led.MODE_BREATHING.equals(mode)
                    || Led.MODE_RAINBOW.equals(mode)
                    || Led.MODE_DUO.equals(mode);
            mSpeed.setEnabled(animated);
            mSpeed.setAlpha(animated ? 1f : 0.5f);
            if (mScreenRow != null) {
                mScreenRow.setVisibility(screen ? View.VISIBLE : View.GONE);
            }
            if (mScreenDelayRow != null) {
                mScreenDelayRow.setVisibility(screen ? View.VISIBLE : View.GONE);
            }
            if (mScreenBoostRow != null) {
                mScreenBoostRow.setVisibility(screen ? View.VISIBLE : View.GONE);
            }
            if (mScreenNote != null) {
                mScreenNote.setVisibility(screen ? View.VISIBLE : View.GONE);
            }
            if (mScreenRateValue != null) {
                mScreenRateValue.setText(getContext().getString(
                        R.string.lighting_screen_rate_value, Led.screenRate()));
            }
            if (mScreenDelayValue != null) {
                mScreenDelayValue.setText(delayLabel(ScreenLedSync.screenDelay()));
            }
            if (mScreenBoostValue != null) {
                mScreenBoostValue.setText(boostLabel(ScreenLedSync.screenBoost()));
            }
            if (mScreenDimValue != null) {
                mScreenDimValue.setText(dimLabel());
            }
        } finally {
            mUpdating = false;
        }
    }

    private String modeForId(int checkedId) {
        if (checkedId == R.id.qs_effect_breathing) {
            return Led.MODE_BREATHING;
        }
        if (checkedId == R.id.qs_effect_rainbow) {
            return Led.MODE_RAINBOW;
        }
        if (checkedId == R.id.qs_effect_duo) {
            return Led.MODE_DUO;
        }
        if (mEffectScreen != null && checkedId == mEffectScreen.getId()) {
            return Led.MODE_SCREEN;
        }
        return Led.MODE_STATIC;
    }

    private int modeIdFor(String mode) {
        switch (mode) {
            case Led.MODE_BREATHING:
                return R.id.qs_effect_breathing;
            case Led.MODE_RAINBOW:
                return R.id.qs_effect_rainbow;
            case Led.MODE_DUO:
                return R.id.qs_effect_duo;
            case Led.MODE_SCREEN:
                return mEffectScreen != null ? mEffectScreen.getId() : R.id.qs_effect_static;
            case Led.MODE_STATIC:
            default:
                return R.id.qs_effect_static;
        }
    }

    private void notifyChanged() {
        if (mListener != null) {
            mListener.onChanged();
        }
    }
}
