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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.ashfall.parts.R;

/** A row that shows one or two colours and opens the colour picker. */
public class ColorPreference extends Preference {

    private int mColor = 0xFFFF8A3D;

    public ColorPreference(Context context) {
        this(context, null);
    }

    public ColorPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_colour);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    public void setColor(int color) {
        mColor = color;
        notifyChanged();
    }

    public int getColor() {
        return mColor;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View item = holder.itemView;
        final TextView title = item.findViewById(R.id.colour_title);
        final TextView summary = item.findViewById(R.id.colour_summary);
        final ColorSwatchView swatch = item.findViewById(R.id.colour_swatch);
        if (title != null) {
            title.setText(getTitle());
        }
        if (summary != null) {
            final CharSequence text = getSummary();
            summary.setText(text);
            summary.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
        if (swatch != null) {
            swatch.setColor(mColor);
        }
    }
}
