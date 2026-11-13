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
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;

/** Small view/drawable helpers shared by the launcher and its settings. */
final class Ui {
    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Draw edge-to-edge behind the system bars and inset the content instead,
     * so the status bar and gesture area never overlap the grid. The bars
     * stay transparent and use light icons on the dark launcher background.
     */
    static void applyEdgeToEdge(Activity activity, View content, int extraTopDp,
            int extraBottomDp) {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarDividerColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setDecorFitsSystemWindows(false);

        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }

        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();
        final int extraTop = dp(activity, extraTopDp);
        final int extraBottom = dp(activity, extraBottomDp);

        content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
            Insets cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout());
            view.setPadding(
                    baseLeft + Math.max(bars.left, cutout.left),
                    baseTop + Math.max(bars.top, cutout.top) + extraTop,
                    baseRight + Math.max(bars.right, cutout.right),
                    baseBottom + Math.max(bars.bottom, cutout.bottom) + extraBottom);
            return windowInsets;
        });
        content.requestApplyInsets();
    }

    static void applyTileBackground(View view, int accent) {
        view.setBackground(tileBackground(view.getContext(), accent));
    }

    static void applyActionBackground(View view, int accent) {
        view.setBackground(actionBackground(view.getContext(), accent));
    }

    static void applyOptionBackground(View view, int accent) {
        view.setBackground(optionBackground(view.getContext(), accent));
    }

    static StateListDrawable tileBackground(Context context, int accent) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                shape(context, 0xFF232E3E, 14, 2, accent));
        states.addState(new int[]{android.R.attr.state_pressed},
                shape(context, 0xFF1A2230, 14, 0, 0));
        states.addState(new int[]{}, shape(context, 0x00000000, 14, 0, 0));
        return states;
    }

    static StateListDrawable actionBackground(Context context, int accent) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                shape(context, 0xFF202A39, 12, 2, accent));
        states.addState(new int[]{android.R.attr.state_pressed},
                shape(context, 0xFF202A39, 12, 0, 0));
        states.addState(new int[]{}, shape(context, 0x00000000, 12, 0, 0));
        return states;
    }

    static StateListDrawable optionBackground(Context context, int accent) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected},
                shape(context, 0xFF202A39, 12, 2, accent));
        states.addState(new int[]{android.R.attr.state_selected},
                shape(context, 0xFF171F2B, 12, 1, accent));
        states.addState(new int[]{android.R.attr.state_focused},
                shape(context, 0xFF202A39, 12, 2, accent));
        states.addState(new int[]{android.R.attr.state_pressed},
                shape(context, 0xFF202A39, 12, 0, 0));
        states.addState(new int[]{}, shape(context, 0x00000000, 12, 1, 0xFF243041));
        return states;
    }

    static TextView optionButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(14f);
        button.setTextColor(0xFFE9EEF6);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        button.setBackground(actionBackground(context, Appearance.DEFAULT_ACCENT));
        return button;
    }

    static TextView sectionTitle(Context context, String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextSize(13f);
        title.setTextColor(0xFF9AA7BA);
        title.setLetterSpacing(0.06f);
        title.setPadding(0, dp(context, 22), 0, dp(context, 8));
        return title;
    }

    static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(0xFF243041);
        divider.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1))));
        return divider;
    }

    private static GradientDrawable shape(Context context, int fill, float radiusDp,
            float strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        }
        return drawable;
    }
}
