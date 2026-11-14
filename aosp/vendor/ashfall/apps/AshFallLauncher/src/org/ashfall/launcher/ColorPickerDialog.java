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
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Controller- and touch-friendly hue colour picker used by every launcher
 * colour selector (#95).
 *
 * <p>The saturation/value area and the hue bar update the caller on every move;
 * there is no apply step. The settings screen behind the dialog previews the
 * colour live, and the dialog has its own preview swatch. The controller can
 * drive the area with the D-pad and the hue with L1/R1; B/Back closes.
 */
final class ColorPickerDialog {
    interface Listener {
        void onColor(int color);
    }

    private ColorPickerDialog() {
    }

    static void show(Activity activity, int initialColor, int accent, Listener listener) {
        Dialog dialog = new Dialog(activity, R.style.Theme_AshFall_Dialog);
        Context context = dialog.getContext();
        int padding = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        int primaryText = Ui.themeColor(context, android.R.attr.textColorPrimary, 0xFFE9EEF6);
        int secondaryText = Ui.themeColor(context, android.R.attr.textColorSecondary,
                0xFF9AA7BA);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        // Keep the whole picker inside the 480 dp-high landscape display.
        panel.setPadding(padding, Ui.dp(context, 18), padding, Ui.dp(context, 18));
        panel.setBackground(panelBackground(context, accent));
        panel.setClickable(true);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.color_picker_title);
        title.setTextColor(primaryText);
        title.setTextSize(20f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hex = new TextView(context);
        hex.setTextColor(secondaryText);
        hex.setTextSize(15f);
        header.addView(hex);
        panel.addView(header);

        View preview = new View(context);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 36));
        previewParams.topMargin = Ui.dp(context, 10);
        panel.addView(preview, previewParams);

        ColorPickerView picker = new ColorPickerView(context, null);
        picker.setAccent(accent);
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 150));
        pickerParams.topMargin = Ui.dp(context, 12);
        panel.addView(picker, pickerParams);

        LinearLayout hueHeader = new LinearLayout(context);
        hueHeader.setOrientation(LinearLayout.HORIZONTAL);
        hueHeader.setGravity(Gravity.CENTER_VERTICAL);
        hueHeader.setPadding(0, Ui.dp(context, 12), 0, 0);
        TextView hueLabel = new TextView(context);
        hueLabel.setText(R.string.color_picker_hue);
        hueLabel.setTextColor(primaryText);
        hueLabel.setTextSize(14f);
        hueLabel.setTypeface(hueLabel.getTypeface(), android.graphics.Typeface.BOLD);
        hueHeader.addView(hueLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hueValue = new TextView(context);
        hueValue.setTextColor(secondaryText);
        hueValue.setTextSize(13f);
        hueHeader.addView(hueValue);
        panel.addView(hueHeader);

        HueBarView hueBar = new HueBarView(context, null);
        hueBar.setAccent(accent);
        LinearLayout.LayoutParams hueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 36));
        hueParams.topMargin = Ui.dp(context, 2);
        panel.addView(hueBar, hueParams);

        TextView hint = new TextView(context);
        hint.setText(R.string.color_picker_hint);
        hint.setTextColor(Ui.themeColor(context, android.R.attr.textColorSecondary,
                0xFF9AA7BA));
        hint.setTextSize(12f);
        hint.setPadding(0, Ui.dp(context, 8), 0, 0);
        panel.addView(hint);

        TextView done = Ui.optionButton(context, context.getString(R.string.done));
        Ui.applyOptionBackground(done, accent);
        done.setOnClickListener(view -> dialog.dismiss());
        LinearLayout doneRow = new LinearLayout(context);
        doneRow.setOrientation(LinearLayout.HORIZONTAL);
        doneRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        doneRow.setPadding(0, Ui.dp(context, 12), 0, 0);
        doneRow.addView(done);
        panel.addView(doneRow);

        final float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);
        picker.setColor(initialColor);
        hueBar.setHue(hsv[0]);

        GradientDrawable previewShape = new GradientDrawable();
        previewShape.setCornerRadius(Ui.dp(context, 12));
        previewShape.setStroke(Ui.dp(context, 1), 0x55FFFFFF);

        Runnable updateUi = () -> {
            int color = picker.getColor();
            previewShape.setColor(color);
            preview.setBackground(previewShape);
            hex.setText(Appearance.hex(color));
            // Normalized hue is [0, 360); rounding must not print 360.
            hueValue.setText(String.format(Locale.US, "%d\u00B0",
                    Math.round(hueBar.getHue()) % 360));
        };
        picker.setListener(color -> {
            updateUi.run();
            listener.onColor(color);
        });
        hueBar.setListener(hue -> picker.setHue(hue));
        updateUi.run();

        // Full-screen modal container: the panel is centred and every input
        // that lands outside it is swallowed instead of reaching the settings
        // activity behind the dialog (#81).
        FrameLayout container = new FrameLayout(context);
        container.setClickable(true);
        container.setOnClickListener(view -> dialog.dismiss());
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                context.getResources().getDimensionPixelSize(R.dimen.dialog_width),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        container.addView(panel, panelParams);

        dialog.setContentView(container);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L1
                    || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    float step = keyCode == KeyEvent.KEYCODE_BUTTON_L1 ? -4f : 4f;
                    hueBar.setHue(hueBar.getHue() + step);
                    picker.setHue(hueBar.getHue());
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    done.performClick();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B
                        || keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                    View focused = dialog.getWindow() == null
                            ? null : dialog.getWindow().getCurrentFocus();
                    if (focused == done) {
                        done.performClick();
                    }
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                // Consume the down event so the framework does not also click.
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            Ui.makeDialogModal(dialog, 0.75f);
            picker.post(picker::requestFocus);
        });
        dialog.show();
    }

    private static GradientDrawable panelBackground(Context context, int accent) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.dialogBackgroundColor(context));
        background.setCornerRadius(Ui.dp(context, 20));
        background.setStroke(Ui.dp(context, 1.5f), accent);
        return background;
    }
}
