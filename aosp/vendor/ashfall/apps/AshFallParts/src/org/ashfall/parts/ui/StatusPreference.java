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
import android.util.AttributeSet;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.ashfall.parts.R;

/**
 * The live fan status block: temperature, output and RPM, a small output gauge
 * and the safety-override warning.  Not selectable: it is a read-out, not a
 * setting, so the D-pad skips it.
 */
public class StatusPreference extends Preference {

    private int mTemperatureC = -1;
    private int mOutputPct = -1;
    private int mRpm = -1;
    private boolean mSafety;
    private boolean mAvailable;

    public StatusPreference(Context context) {
        this(context, null);
    }

    public StatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_status);
        setSelectable(false);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    public void update(int temperatureC, int outputPct, int rpm, boolean safety,
            boolean available) {
        mTemperatureC = temperatureC;
        mOutputPct = outputPct;
        mRpm = rpm;
        mSafety = safety;
        mAvailable = available;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View item = holder.itemView;
        final View body = item.findViewById(R.id.status_body);
        final TextView unavailable = item.findViewById(R.id.status_unavailable);
        final TextView temp = item.findViewById(R.id.status_temp);
        final TextView pct = item.findViewById(R.id.status_pct);
        final TextView rpm = item.findViewById(R.id.status_rpm);
        final ProgressBar gauge = item.findViewById(R.id.status_gauge);
        final TextView safety = item.findViewById(R.id.status_safety);

        if (body != null) {
            body.setVisibility(mAvailable ? View.VISIBLE : View.GONE);
        }
        if (unavailable != null) {
            unavailable.setVisibility(mAvailable ? View.GONE : View.VISIBLE);
        }
        if (temp != null) {
            temp.setText(mTemperatureC < 0 ? "—"
                    : getContext().getString(R.string.temperature_value, mTemperatureC));
        }
        if (pct != null) {
            pct.setText(mOutputPct < 0 ? "—"
                    : getContext().getString(R.string.percent_value, mOutputPct));
        }
        if (rpm != null) {
            if (mRpm < 0) {
                rpm.setText("—");
            } else {
                rpm.setText(getContext().getString(R.string.rpm_value, mRpm));
            }
        }
        if (gauge != null) {
            gauge.setProgress(Math.max(0, Math.min(100, mOutputPct)));
        }
        if (safety != null) {
            safety.setVisibility(mSafety ? View.VISIBLE : View.GONE);
        }
    }
}
