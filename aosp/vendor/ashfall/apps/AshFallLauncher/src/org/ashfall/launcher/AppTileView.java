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

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One app in the grid: icon plus label, with a focus frame.
 *
 * <p>The stock RP3 launcher hides labels and shows only the focused app name at
 * the top of the screen. AshFall keeps that focused-name header but also prints
 * a small label under every icon, so the grid is readable without moving the
 * focus.
 */
final class AppTileView extends LinearLayout {
    private final AppEntry entry;
    private int accent = Appearance.DEFAULT_ACCENT;

    AppTileView(Context context, AppEntry entry) {
        super(context);
        this.entry = entry;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setFocusable(true);
        setClickable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(false);
        setClipToPadding(false);
        Ui.applyTileBackground(this, accent);
        setPadding(dp(6), dp(6), dp(6), dp(6));
        setContentDescription(entry.label);

        ImageView icon = new ImageView(context);
        int iconSize = getResources().getDimensionPixelSize(R.dimen.tile_icon_size);
        LayoutParams iconParams = new LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setLayoutParams(iconParams);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(entry.icon);
        icon.setContentDescription(context.getString(R.string.cd_app_icon, entry.label));
        icon.setFocusable(false);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(icon);

        TextView label = new TextView(context);
        LayoutParams labelParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(6);
        label.setLayoutParams(labelParams);
        label.setText(entry.label);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.tile_label_text_size));
        label.setTextColor(context.getColorStateList(R.color.tile_label));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setFocusable(false);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        // A tiny scale makes the focus frame readable across the room without
        // changing the grid geometry (the gap absorbs it).
        animate()
                .scaleX(gainFocus ? 1.06f : 1.0f)
                .scaleY(gainFocus ? 1.06f : 1.0f)
                .setDuration(120)
                .start();
    }

    void setAccent(int accent) {
        this.accent = accent;
        Ui.applyTileBackground(this, accent);
    }

    AppEntry getEntry() {
        return entry;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
