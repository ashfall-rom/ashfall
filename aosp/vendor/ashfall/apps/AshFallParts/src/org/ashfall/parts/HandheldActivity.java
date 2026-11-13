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

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Led;
import org.ashfall.parts.hw.Pad;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.Ui;

/** The injected Settings homepage entry: three rows into the three pages. */
public class HandheldActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.handheld_title;
    }

    @Override
    protected Fragment createFragment() {
        return new HandheldFragment();
    }

    public static class HandheldFragment extends PreferenceFragmentCompat {

        private Preference mFanRow;
        private Preference mLightingRow;
        private Preference mControllerRow;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                refreshSummaries();
                mHandler.postDelayed(this, 3000);
            }
        };

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            final PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(R.string.handheld_title);
            screen.addPreference(category);

            mFanRow = new Preference(requireContext());
            mFanRow.setKey("home_fan");
            mFanRow.setTitle(R.string.fan_title);
            mFanRow.setIcon(R.drawable.ic_fan);
            mFanRow.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), FanActivity.class));
                return true;
            });
            category.addPreference(mFanRow);

            mLightingRow = new Preference(requireContext());
            mLightingRow.setKey("home_lighting");
            mLightingRow.setTitle(R.string.lighting_title);
            mLightingRow.setIcon(R.drawable.ic_led);
            mLightingRow.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), LightingActivity.class));
                return true;
            });
            category.addPreference(mLightingRow);

            mControllerRow = new Preference(requireContext());
            mControllerRow.setKey("home_controller");
            mControllerRow.setTitle(R.string.controller_title);
            mControllerRow.setIcon(R.drawable.ic_gamepad);
            mControllerRow.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), ControllerActivity.class));
                return true;
            });
            category.addPreference(mControllerRow);

            final Preference note = new Preference(requireContext());
            note.setKey("home_note");
            note.setSelectable(false);
            note.setSummary(R.string.home_daemon_note);
            screen.addPreference(note);

            setPreferenceScreen(screen);
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshSummaries();
            mHandler.removeCallbacks(mTick);
            mHandler.postDelayed(mTick, 3000);
        }

        @Override
        public void onPause() {
            super.onPause();
            mHandler.removeCallbacks(mTick);
        }

        private void refreshSummaries() {
            if (mFanRow == null) {
                return;
            }
            final PresetStore store = PresetStore.get(requireContext());
            Preset preset = store.byId(Fan.presetId());
            if (preset == null) {
                preset = store.byId(activeIdForMode(store));
            }
            final String presetName = Ui.presetName(requireContext(), preset);
            final int temperature = Fan.temperatureCelsius();
            final String temperatureText = temperature < 0 ? "—"
                    : getString(R.string.temperature_value, temperature);
            mFanRow.setSummary(getString(R.string.home_fan_summary, presetName, temperatureText));

            final int target = Prefs.ledTarget(requireContext());
            final String colour = Led.colorFor(target);
            final String lighting = Ui.ledModeName(requireContext(), Led.mode()) + " · "
                    + (Led.isOn() ? Ui.hex(colour) : getString(R.string.off));
            mLightingRow.setSummary(lighting);

            mControllerRow.setSummary(getString(R.string.home_controller_summary,
                    Pad.inner(), Pad.outer()));
        }

        private static String activeIdForMode(PresetStore store) {
            switch (Fan.mode()) {
                case Preset.MODE_OFF:
                    return PresetStore.ID_OFF;
                case Preset.MODE_QUIET:
                    return PresetStore.ID_QUIET;
                case Preset.MODE_BALANCED:
                    return PresetStore.ID_BALANCED;
                case Preset.MODE_MAX:
                    return PresetStore.ID_MAX;
                default:
                    return PresetStore.ID_SMART;
            }
        }
    }
}
