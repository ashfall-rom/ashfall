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
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;

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

    /**
     * The one width of the app's floating dialogs, from
     * {@code R.dimen.dialog_width}.
     *
     * <p>Every dialog root is {@code match_parent} and every window is sized
     * from this dimen, so the card (the window background) and the content it
     * holds always agree.  Before #166 each window passed a hardcoded 440 dp
     * while the roots were laid out at 480 dp: 40 dp of content, including the
     * whole end inset, sat outside the rounded card and was clipped.
     */
    public static int dialogWidth(Context context) {
        return context.getResources().getDimensionPixelSize(R.dimen.dialog_width);
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
            case Led.MODE_SCREEN:
                return context.getString(R.string.lighting_effect_screen);
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
     * <p>The root is measured with the requested width, the final height is the
     * natural content height clamped to [{@code minFraction} of the screen,
     * 85 %], and the given {@code scroll} view gets whatever the header and
     * footer leave over, so the Done/OK footer is pinned and visible while the
     * list scrolls.
     *
     * <p>The sizing is done twice, because a floating dialog does not know its
     * real height before the window manager has laid it out:
     *
     * <ol>
     *   <li>before the window is attached, with the display height as the
     *       bound.  This is right for a dialog whose window may be as tall as
     *       the screen (the colour picker, the Fan dialog);</li>
     *   <li>in a one-shot post-layout pass, with the height and width the window
     *       really got.  A Quick Settings tile dialog is hosted inside the QS
     *       panel, which clamps it, and the old code kept the content at the
     *       requested size: the last row and the Done button were laid out past
     *       the rounded card and clipped (#156 §15; the Fan, Lighting, Power and
     *       colour-picker dialogs share this helper).  The second pass
     *       recomputes the real chrome from an uncapped measure at the real
     *       width and gives the list exactly the space that is left.</li>
     * </ol>
     *
     * <p>The width is not a parameter: it is {@link #dialogWidth(Context)}, the
     * same dimen the layout roots are sized from (#166).
     */
    public static void fitDialog(@NonNull Window window, @NonNull View root,
            @Nullable View scroll, float minFraction) {
        final Context context = root.getContext();
        final int width = dialogWidth(context);
        final int screen = usableHeight(root);
        final int minHeight = Math.max(dp(context, 160),
                Math.round(screen * Math.max(0f, minFraction)));
        final int maxHeight = Math.max(minHeight, Math.round(screen * 0.85f));

        if (scroll != null) {
            // Start from the natural list height, not from a stale fixed one.
            setHeight(scroll, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final int natural = measure(root, width, maxHeight);
        int target = Math.min(Math.max(natural, minHeight), maxHeight);
        if (scroll != null && natural > maxHeight) {
            final int chrome = Math.max(0, natural - scroll.getMeasuredHeight());
            setHeight(scroll, Math.max(dp(context, 96), target - chrome));
            target = Math.min(Math.max(measure(root, width, maxHeight), minHeight), maxHeight);
        }
        window.setLayout(width, target);
        fitToRealWindow(window, root, scroll, width, minHeight);
    }

    /**
     * One-shot post-attach pass: sizes the list to the height the window really
     * got.  A dialog is already laid out by the time {@code post} runs, so the
     * pass applies at once; if the window has no height yet it waits for the
     * first global layout and then applies once.
     */
    private static void fitToRealWindow(Window window, View root, @Nullable View scroll,
            int width, int minHeight) {
        if (scroll == null) {
            return;
        }
        final View decor = window.getDecorView();
        final ViewTreeObserver.OnGlobalLayoutListener listener =
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean mDone;

                    @Override
                    public void onGlobalLayout() {
                        final int actual = decor.getHeight();
                        if (mDone || actual <= 0) {
                            return;
                        }
                        mDone = true;
                        decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        applyRealHeight(window, root, scroll, width, minHeight, actual);
                    }
                };
        root.post(() -> {
            if (decor.getHeight() > 0) {
                listener.onGlobalLayout();
            } else {
                decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            }
        });
    }

    /**
     * Fits the dialog to the real window size: the list gets
     * {@code actual - chrome} where the chrome is the header and footer measured
     * without a cap, so the footer is always inside the card.  A window the
     * window manager clamped narrower than {@code width} (a Quick Settings
     * dialog is hosted inside the panel) is measured at the width it really
     * got, because the root is {@code match_parent} and its text has re-wrapped
     * at that width (#166).
     */
    private static void applyRealHeight(Window window, View root, View scroll, int width,
            int minHeight, int actual) {
        final Context context = root.getContext();
        final View decor = window.getDecorView();
        final int decorWidth = decor.getWidth();
        if (decorWidth > 0) {
            width = decorWidth;
        }
        final int floor = dp(context, 96);
        final int tolerance = dp(context, 2);
        final int bound = root.getResources().getDisplayMetrics().heightPixels * 4;
        setHeight(scroll, ViewGroup.LayoutParams.WRAP_CONTENT);
        final int natural = measure(root, width, bound);
        final int scrollNatural = scroll.getMeasuredHeight();
        if (natural <= actual + tolerance) {
            window.setLayout(width, Math.max(minHeight, Math.min(natural, actual)));
            return;
        }
        final int chrome = Math.max(0, natural - scrollNatural);
        final int scrollHeight = Math.max(floor, actual - chrome);
        if (scrollHeight >= scrollNatural) {
            return;
        }
        setHeight(scroll, scrollHeight);
        final int laidOut = measure(root, width, actual);
        window.setLayout(width, Math.min(laidOut, actual));
    }

    /** Measures the root at the requested width and returns its height. */
    private static int measure(View root, int width, int maxHeight) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        return root.getMeasuredHeight();
    }

    private static void setHeight(View view, int height) {
        final ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) {
            return;
        }
        params.height = height;
        view.setLayoutParams(params);
    }

    /**
     * The height a dialog may use: the display height minus the system bars and
     * a small margin, so the rounded card never touches them.  The real window
     * insets are not known before the window is attached, and a Quick Settings
     * dialog is attached only after {@code onCreate}, so this is a conservative
     * estimate refined later by {@link #fitToRealWindow}.
     */
    private static int usableHeight(View root) {
        final int screen = root.getResources().getDisplayMetrics().heightPixels;
        final int margin = dp(root.getContext(), 48);
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            final int bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                    + insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).bottom;
            return Math.max(dp(root.getContext(), 320), screen - bars - margin);
        }
        return Math.max(dp(root.getContext(), 320), screen - margin);
    }
}
