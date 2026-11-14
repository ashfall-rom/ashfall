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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
            case "builtin.manual":
                return context.getString(R.string.fan_manual_preset);
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

    /** The app's versionName, or an empty string when it cannot be read. */
    public static String appVersion(Context context) {
        try {
            final android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code m:ss} for a countdown shown in the stress test footer. */
    public static String countdown(long millis) {
        final long seconds = Math.max(0, millis) / 1000L;
        return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    /**
     * Sizes a floating dialog so its footer always stays on screen.
     *
     * <p>The root is measured with the requested width and at most 86 % of the
     * display height.  If the natural height exceeds that, only the given
     * {@code scroll} view is shrunk to the space left over by the header and
     * footer, so the Done button can never be clipped into a corner.  Without
     * this a {@code ScrollView} would grow to its full content height (it has
     * no {@code maxHeight}) and push the footer out of the window.
     */
    public static void fitDialog(@NonNull Window window, @NonNull View root,
            @Nullable View scroll, int widthDp) {
        final int width = dp(root.getContext(), widthDp);
        final int screen = root.getResources().getDisplayMetrics().heightPixels;
        final int maxHeight = Math.max(dp(root.getContext(), 160),
                Math.round(screen * 0.86f));
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int height = root.getMeasuredHeight();
        if (height > maxHeight && scroll != null) {
            final int chrome = Math.max(0, height - scroll.getMeasuredHeight());
            final int scrollHeight = Math.max(dp(root.getContext(), 96), maxHeight - chrome);
            final ViewGroup.LayoutParams params = scroll.getLayoutParams();
            params.height = scrollHeight;
            scroll.setLayoutParams(params);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
            height = Math.min(root.getMeasuredHeight(), maxHeight);
        }
        window.setLayout(width, Math.min(height, maxHeight));
    }
}
