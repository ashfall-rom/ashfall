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
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bottom strip: launcher-settings shortcut, controller hints, focused app name. */
public final class BottomBarView extends LinearLayout {
    private final View settingsButton;
    private final TextView focusedLabel;

    public BottomBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0);
        LayoutInflater.from(context).inflate(R.layout.view_bottom_bar, this, true);

        settingsButton = findViewById(R.id.settings_button);
        focusedLabel = findViewById(R.id.focused_label);
        settingsButton.setFocusable(true);
        settingsButton.setFocusableInTouchMode(true);
    }

    boolean focusSettings() {
        return settingsButton.requestFocus();
    }

    boolean isSettingsFocused() {
        return settingsButton.hasFocus();
    }

    void setOnSettingsClickListener(OnClickListener listener) {
        settingsButton.setOnClickListener(listener);
    }

    void setFocusedLabel(CharSequence label) {
        focusedLabel.setText(label);
    }

    void setAccent(int accent) {
        Ui.applyActionBackground(settingsButton, accent);
    }
}
