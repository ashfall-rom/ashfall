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

package org.ashfall.parts;

import android.content.Context;

import androidx.preference.Preference;

/** Live summary of the Power row; the #124 app worker replaces this file. */
public class PowerSummary implements Preference.SummaryProvider<Preference> {

    private final Context mContext;

    public PowerSummary(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public CharSequence provideSummary(Preference preference) {
        return mContext.getString(R.string.home_power_summary_empty);
    }
}
