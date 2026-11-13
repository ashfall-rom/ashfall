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
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.ashfall.parts.R;
import org.ashfall.parts.hw.Preset;

/** A single-choice preset row with a radio indicator. */
public class PresetPreference extends Preference {

    private final Preset mPreset;
    private boolean mChecked;

    public PresetPreference(Context context, Preset preset) {
        this(context, null, preset);
    }

    private PresetPreference(Context context, AttributeSet attrs, Preset preset) {
        super(context, attrs);
        mPreset = preset;
        setLayoutResource(R.layout.preference_preset);
        setPersistent(false);
        setIconSpaceReserved(false);
        setKey("preset_" + preset.id);
    }

    public Preset getPreset() {
        return mPreset;
    }

    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            notifyChanged();
        }
    }

    public boolean isChecked() {
        return mChecked;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View item = holder.itemView;
        final TextView title = item.findViewById(R.id.preset_title);
        final TextView summary = item.findViewById(R.id.preset_summary);
        final RadioButton radio = item.findViewById(R.id.preset_radio);
        if (title != null) {
            title.setText(getTitle());
        }
        if (summary != null) {
            final CharSequence text = getSummary();
            summary.setText(text);
            summary.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
        if (radio != null) {
            radio.setChecked(mChecked);
        }
    }
}
