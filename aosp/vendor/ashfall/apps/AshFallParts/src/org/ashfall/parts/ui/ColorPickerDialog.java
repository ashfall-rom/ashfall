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

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.ashfall.parts.R;

import java.util.Locale;

/**
 * Preset swatches + a hue/saturation/value picker, in a Settings-styled
 * dialog.  Every change is applied live ({@code onColor}); the dialog only has
 * a Done button, so no "Set" step is needed.  The window is dimmed and focus
 * stays inside the dialog, which also keeps the gamepad from moving focus to
 * the page behind it.
 */
public class ColorPickerDialog extends Dialog {

    public interface OnColorSelected {
        void onColor(int argb);
    }

    private final OnColorSelected mListener;
    private int mColor;
    private ColorPickerView mPicker;
    private SeekBar mHue;
    private TextView mHueValue;

    public ColorPickerDialog(@NonNull Context context, int initialColor,
            OnColorSelected listener) {
        super(context, R.style.Theme_AshFall_Dialog);
        mListener = listener;
        mColor = initialColor;
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_colour_picker);
        final Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            Ui.fitDialog(window, findViewById(R.id.picker_root),
                    findViewById(R.id.picker_scroll), 480);
        }

        mPicker = findViewById(R.id.picker_square);
        mHue = findViewById(R.id.picker_hue);
        mHueValue = findViewById(R.id.picker_hue_value);

        mPicker.setColor(mColor);
        mPicker.setListener(argb -> apply(argb));

        final LinearLayout swatches = findViewById(R.id.picker_swatches);
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
                mColor = color;
                mPicker.setColor(color);
                mHue.setProgress(Math.round(getHue(color)));
                updateHueLabel();
                apply(color);
            });
            swatches.addView(swatch);
        }

        final float[] hsv = new float[3];
        Color.colorToHSV(mColor, hsv);
        mHue.setMax(360);
        mHue.setProgress(Math.round(hsv[0]));
        mHue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                mPicker.setHue(progress);
                mColor = mPicker.getColor();
                updateHueLabel();
                apply(mColor);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        updateHueLabel();

        findViewById(R.id.picker_done).setOnClickListener(view -> dismiss());
        mPicker.requestFocus();
    }

    private void apply(int argb) {
        mColor = argb;
        updateHueLabel();
        if (mListener != null) {
            mListener.onColor(argb);
        }
    }

    private void updateHueLabel() {
        if (mHueValue != null) {
            mHueValue.setText(String.format(Locale.US, "%d°", mHue.getProgress()));
        }
    }

    private static float getHue(int color) {
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }
}
