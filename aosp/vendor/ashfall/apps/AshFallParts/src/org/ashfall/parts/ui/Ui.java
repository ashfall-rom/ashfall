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
import android.util.TypedValue;

import org.ashfall.parts.R;
import org.ashfall.parts.hw.Curve;
import org.ashfall.parts.hw.Led;
import org.ashfall.parts.hw.Preset;

import java.util.Locale;

/** Small formatting helpers shared by fragments, dialogs and tiles. */
public final class Ui {

    private Ui() {
    }

    public static final int[] PRESET_COLORS = {
            R.color.swatch_white,
            R.color.swatch_red,
            R.color.swatch_orange,
            R.color.swatch_amber,
            R.color.swatch_green,
            R.color.swatch_cyan,
            R.color.swatch_blue,
            R.color.swatch_violet,
    };

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static int sp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static String presetName(Context context, Preset preset) {
        if (preset == null) {
            return context.getString(R.string.fan_preset_none);
        }
        switch (preset.id) {
            case "builtin.off":
                return context.getString(R.string.fan_off_preset);
            case "builtin.quiet":
                return context.getString(R.string.fan_quiet_preset);
            case "builtin.balanced":
                return context.getString(R.string.fan_balanced_preset);
            case "builtin.max":
                return context.getString(R.string.fan_max_preset);
            case "builtin.smart":
                return context.getString(R.string.fan_smart_preset);
            default:
                return preset.name;
        }
    }

    public static String modeName(Context context, String mode) {
        switch (Preset.sanitizeMode(mode)) {
            case Preset.MODE_OFF:
                return context.getString(R.string.fan_mode_off);
            case Preset.MODE_QUIET:
                return context.getString(R.string.fan_mode_quiet);
            case Preset.MODE_BALANCED:
                return context.getString(R.string.fan_mode_balanced);
            case Preset.MODE_MAX:
                return context.getString(R.string.fan_mode_max);
            case Preset.MODE_MANUAL:
                return context.getString(R.string.fan_mode_manual);
            case Preset.MODE_SMART:
            default:
                return context.getString(R.string.fan_mode_smart);
        }
    }

    public static String presetModeSummary(Context context, Preset preset) {
        if (preset == null) {
            return "";
        }
        if (Preset.MODE_MANUAL.equals(preset.mode)) {
            return context.getString(R.string.fan_preset_manual_summary, preset.manualPct);
        }
        if (Preset.MODE_SMART.equals(preset.mode)) {
            final Curve curve = preset.parsedCurve();
            return context.getString(R.string.fan_preset_curve_summary, curve.size());
        }
        return modeName(context, preset.mode);
    }

    public static String ledModeName(Context context, String mode) {
        switch (mode) {
            case Led.MODE_OFF:
                return context.getString(R.string.off);
            case Led.MODE_BREATHING:
                return context.getString(R.string.lighting_effect_breathing);
            case Led.MODE_RAINBOW:
                return context.getString(R.string.lighting_effect_rainbow);
            case Led.MODE_DUO:
                return context.getString(R.string.lighting_effect_duo);
            case Led.MODE_STATIC:
            default:
                return context.getString(R.string.lighting_effect_static);
        }
    }

    public static String calibrationSourceName(Context context, String source) {
        if ("user".equals(source)) {
            return context.getString(R.string.controller_cal_source_user);
        }
        if ("factory".equals(source)) {
            return context.getString(R.string.controller_cal_source_factory);
        }
        return context.getString(R.string.controller_cal_source_default);
    }

    public static String hex(String rgb) {
        return String.format(Locale.US, "#%s", Led.sanitizeColor(rgb, Led.DEFAULT_COLOR));
    }

    public static int fade(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
    }
}
