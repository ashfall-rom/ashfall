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

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Controller-friendly RGB color picker used by every color selector. */
final class ColorPickerDialog {
    interface Listener {
        void onColor(int color);
    }

    private ColorPickerDialog() {
    }

    static void show(Activity activity, int initialColor, int accent, Listener listener) {
        Dialog dialog = new Dialog(activity, R.style.Theme_AshFall_Dialog);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(activity.getResources().getDimensionPixelSize(R.dimen.dialog_padding),
                activity.getResources().getDimensionPixelSize(R.dimen.dialog_padding),
                activity.getResources().getDimensionPixelSize(R.dimen.dialog_padding),
                activity.getResources().getDimensionPixelSize(R.dimen.dialog_padding));
        panel.setBackground(panelBackground(activity, accent));

        TextView title = new TextView(activity);
        title.setText(R.string.color_picker_title);
        title.setTextColor(activity.getColor(R.color.text_primary));
        title.setTextSize(20f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(title);

        final int[] rgb = {Color.red(initialColor), Color.green(initialColor),
                Color.blue(initialColor)};

        View preview = new View(activity);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 64));
        previewParams.topMargin = Ui.dp(activity, 14);
        preview.setLayoutParams(previewParams);
        panel.addView(preview);

        TextView hex = new TextView(activity);
        hex.setTextColor(activity.getColor(R.color.text_secondary));
        hex.setTextSize(13f);
        hex.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 4));
        panel.addView(hex);

        SeekBar[] sliders = new SeekBar[3];
        TextView[] values = new TextView[3];
        String[] labels = {"R", "G", "B"};

        Runnable refresh = () -> {
            int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
            GradientDrawable swatch = new GradientDrawable();
            swatch.setColor(color);
            swatch.setCornerRadius(Ui.dp(activity, 12));
            swatch.setStroke(Ui.dp(activity, 1), 0x55FFFFFF);
            preview.setBackground(swatch);
            hex.setText(Appearance.hex(color));
        };

        for (int i = 0; i < 3; i++) {
            final int channel = i;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(activity, 8), 0, 0);

            TextView label = new TextView(activity);
            label.setText(labels[i]);
            label.setTextColor(activity.getColor(R.color.text_primary));
            label.setTextSize(14f);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            label.setWidth(Ui.dp(activity, 26));
            row.addView(label);

            SeekBar slider = new SeekBar(activity);
            slider.setMax(255);
            slider.setProgress(rgb[i]);
            ColorStateList accentState = ColorStateList.valueOf(accent);
            slider.setProgressTintList(accentState);
            slider.setThumbTintList(accentState);
            slider.setProgressBackgroundTintList(ColorStateList.valueOf(
                    Appearance.withAlpha(accent, 0x55)));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    rgb[channel] = progress;
                    values[channel].setText(String.valueOf(progress));
                    refresh.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            row.addView(slider, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            sliders[i] = slider;

            TextView value = new TextView(activity);
            value.setText(String.valueOf(rgb[i]));
            value.setTextColor(activity.getColor(R.color.text_secondary));
            value.setTextSize(13f);
            value.setGravity(Gravity.END);
            value.setWidth(Ui.dp(activity, 46));
            row.addView(value);
            values[i] = value;

            panel.addView(row);
        }

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(activity, 18), 0, 0);

        TextView cancel = Ui.optionButton(activity, activity.getString(R.string.cancel));
        Ui.applyOptionBackground(cancel, accent);
        cancel.setOnClickListener(view -> dialog.dismiss());

        TextView apply = Ui.optionButton(activity, activity.getString(R.string.apply));
        Ui.applyOptionBackground(apply, accent);
        apply.setOnClickListener(view -> {
            listener.onColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
            dialog.dismiss();
        });

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.leftMargin = Ui.dp(activity, 8);
        buttons.addView(cancel);
        buttons.addView(apply, buttonParams);
        panel.addView(buttons);

        refresh.run();

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    dialog.dismiss();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    return true;
                }
                View focused = dialog.getWindow() == null
                        ? null : dialog.getWindow().getCurrentFocus();
                if (focused == cancel || focused == apply) {
                    focused.performClick();
                }
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams params = window.getAttributes();
                params.width = activity.getResources().getDimensionPixelSize(
                        R.dimen.dialog_width);
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.gravity = Gravity.CENTER;
                params.dimAmount = 0.75f;
                window.setAttributes(params);
            }
            sliders[0].post(sliders[0]::requestFocus);
        });
        dialog.show();
    }

    private static GradientDrawable panelBackground(Activity activity, int accent) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.BLACK);
        background.setCornerRadius(Ui.dp(activity, 20));
        background.setStroke(Ui.dp(activity, 1.5f), accent);
        return background;
    }
}
