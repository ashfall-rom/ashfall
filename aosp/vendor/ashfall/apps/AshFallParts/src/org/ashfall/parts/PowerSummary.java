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

import org.ashfall.parts.hw.Power;
import org.ashfall.parts.hw.PowerPreset;
import org.ashfall.parts.hw.PowerPresetStore;

import java.util.Locale;

/**
 * Live summary of the Handheld "Power" row (#124).
 *
 * <ul>
 *   <li>daemon running: active preset, battery power and the fastest CPU clock
 *       and the GPU clock — {@code —} for a value the daemon has not published
 *       yet (power is {@code —} while charging, by contract);</li>
 *   <li>daemon absent: the preset and the documented unavailable state, never
 *       a fabricated value.</li>
 * </ul>
 */
public class PowerSummary implements Preference.SummaryProvider<Preference> {

    private final Context mContext;

    public PowerSummary(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public CharSequence provideSummary(Preference preference) {
        final PowerPresetStore store = PowerPresetStore.get(mContext);
        final PowerPreset preset = Power.activePreset(store);
        final String name = preset == null
                ? mContext.getString(R.string.power_preset_balanced)
                : preset.displayName(mContext);
        if (!Power.daemonAlive()) {
            return mContext.getString(R.string.power_home_offline, name);
        }
        final int powerMw = Power.powerMw(mContext);
        final String watts = powerMw < 0
                ? mContext.getString(R.string.power_unknown)
                : mContext.getString(R.string.power_home_watts,
                        String.format(Locale.US, "%.1f", powerMw / 1000f));
        return mContext.getString(R.string.power_home_summary, name, watts,
                mhzText(Power.cpuMhzMax()), mhzText(Power.gpuMhz()));
    }

    private String mhzText(int mhz) {
        return mhz < 0
                ? mContext.getString(R.string.power_unknown)
                : mContext.getString(R.string.power_home_mhz, mhz);
    }
}
