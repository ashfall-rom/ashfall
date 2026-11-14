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

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Curve;
import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.MainSwitchPreference;
import org.ashfall.parts.ui.PresetPreference;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.StatusPreference;
import org.ashfall.parts.ui.Ui;

import java.util.List;

/**
 * The Fan page.  Every change is written to the {@code persist.ashfall.fan.*}
 * properties immediately; the daemon applies it within a second.
 */
public class FanFragment extends PreferenceFragmentCompat {

    private PresetStore mStore;
    private MainSwitchPreference mSwitch;
    private StatusPreference mStatus;
    private PreferenceCategory mPresetCategory;
    private Preference mEditRow;
    private Preference mCurveRow;
    private SliderPreference mManual;
    private SliderPreference mSafety;
    private boolean mRebuilding;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            updateLiveSummaries();
            mHandler.postDelayed(this, 2000);
        }
    };
    private boolean mFirstResume = true;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mStore = PresetStore.get(requireContext());
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                requireContext());

        mSwitch = new MainSwitchPreference(requireContext());
        mSwitch.setKey("fan_switch");
        mSwitch.setTitle(R.string.fan_switch_title);
        mSwitch.setCheckedChangeListener((preference, checked) -> onSwitchChanged(checked));
        screen.addPreference(mSwitch);

        final PreferenceCategory statusCategory = new PreferenceCategory(requireContext());
        statusCategory.setTitle(R.string.fan_status_title);
        screen.addPreference(statusCategory);
        mStatus = new StatusPreference(requireContext());
        mStatus.setKey("fan_status");
        statusCategory.addPreference(mStatus);

        mPresetCategory = new PreferenceCategory(requireContext());
        mPresetCategory.setTitle(R.string.fan_presets_category);
        screen.addPreference(mPresetCategory);

        mEditRow = new Preference(requireContext());
        mEditRow.setKey("edit_presets");
        mEditRow.setTitle(R.string.fan_preset_edit_title);
        mEditRow.setSummary(R.string.fan_preset_edit_summary);
        mEditRow.setIcon(R.drawable.ic_fan);
        mEditRow.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(requireContext(), PresetActivity.class));
            return true;
        });

        mCurveRow = new Preference(requireContext());
        mCurveRow.setKey("edit_curve");
        mCurveRow.setTitle(R.string.fan_curve_title);
        mCurveRow.setIcon(R.drawable.ic_fan);
        mCurveRow.setOnPreferenceClickListener(preference -> {
            final Preset target = curveTarget();
            final Intent intent = new Intent(requireContext(), CurveActivity.class);
            intent.putExtra(CurveActivity.EXTRA_PRESET_ID, target.id);
            startActivity(intent);
            return true;
        });

        final PreferenceCategory manualCategory = new PreferenceCategory(requireContext());
        manualCategory.setTitle(R.string.fan_manual_category);
        screen.addPreference(manualCategory);
        mManual = new SliderPreference(requireContext());
        mManual.setKey("manual_pct");
        mManual.setTitle(R.string.fan_manual_title);
        mManual.setRange(0, 100);
        mManual.setValueFormat(getString(R.string.percent_value_format));
        mManual.setValueChangedListener((preference, value) -> {
            Fan.setManualPct(value);
            updateLiveSummaries();
        });
        manualCategory.addPreference(mManual);

        final PreferenceCategory safetyCategory = new PreferenceCategory(requireContext());
        safetyCategory.setTitle(R.string.fan_safety_category);
        screen.addPreference(safetyCategory);
        mSafety = new SliderPreference(requireContext());
        mSafety.setKey("safety_c");
        mSafety.setTitle(R.string.fan_safety_title);
        mSafety.setRange(Fan.MIN_SAFETY_C, Fan.MAX_SAFETY_C);
        mSafety.setValueFormat(getString(R.string.temperature_value_format));
        mSafety.setValue(Fan.safetyC());
        mSafety.setValueChangedListener((preference, value) -> {
            Fan.setSafetyC(value);
            updateLiveSummaries();
        });
        safetyCategory.addPreference(mSafety);

        final Preference footer = new Preference(requireContext());
        footer.setKey("fan_footer");
        footer.setSelectable(false);
        footer.setSummary(R.string.footer_fan);
        screen.addPreference(footer);

        // Populate the (dynamic) preset rows before the screen is attached, so
        // the adapter is built from a stable hierarchy.  Later changes are
        // rebuilt only when returning from the preset editor (see onResume).
        rebuildPresets();
        setPreferenceScreen(screen);
        updateStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFirstResume) {
            mFirstResume = false;
        } else if (getListView() != null) {
            // Returning from the preset editor: rebuild after the list has
            // finished its current layout pass, never inside it.
            getListView().post(this::rebuildPresets);
        }
        updateStatus();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    private void onSwitchChanged(boolean checked) {
        if (checked) {
            Preset last = mStore.byId(Prefs.lastPreset(requireContext(), PresetStore.ID_SMART));
            if (last == null || last.isOff()) {
                last = mStore.byId(PresetStore.ID_SMART);
            }
            activate(last);
        } else {
            Fan.setMode(Preset.MODE_OFF);
            Fan.setPresetId(PresetStore.ID_OFF);
            rebuildPresets();
            updateStatus();
        }
    }

    private void activate(Preset preset) {
        if (preset == null) {
            return;
        }
        Fan.apply(preset);
        Prefs.setLastPreset(requireContext(), preset.id);
        rebuildPresets();
        updateStatus();
    }

    /** Curves belong to Smart presets; fall back to the built-in Smart preset. */
    private Preset curveTarget() {
        final Preset active = Fan.activePreset(mStore);
        if (active != null && active.isCurveEditable()) {
            return active;
        }
        return mStore.byId(PresetStore.ID_SMART);
    }

    private void rebuildPresets() {
        if (mPresetCategory == null || mRebuilding) {
            return;
        }
        mRebuilding = true;
        try {
            final Preset active = Fan.activePreset(mStore);
            final String activeId = active == null ? null : active.id;
            mPresetCategory.removeAll();
            final List<Preset> presets = mStore.all();
            for (Preset preset : presets) {
                final PresetPreference preference = new PresetPreference(requireContext(), preset);
                preference.setTitle(Ui.presetName(requireContext(), preset));
                preference.setSummary(Ui.presetModeSummary(requireContext(), preset));
                preference.setChecked(preset.id.equals(activeId));
                preference.setOnPreferenceClickListener(p -> {
                    activate(preset);
                    return true;
                });
                mPresetCategory.addPreference(preference);
            }
            mPresetCategory.addPreference(mEditRow);
            mPresetCategory.addPreference(mCurveRow);
        } finally {
            mRebuilding = false;
        }
        updateLiveSummaries();
    }

    private void updateStatus() {
        if (mStatus != null) {
            mStatus.update(Fan.temperatureCelsius(), Fan.outputPct(), Fan.rpm(),
                    Fan.safetyActive(), Fan.daemonAlive());
        }
    }

    private void updateLiveSummaries() {
        if (mSwitch == null) {
            return;
        }
        final Preset active = Fan.activePreset(mStore);
        mSwitch.setChecked(Fan.isOn());
        if (Fan.isOn()) {
            final String name = Ui.presetName(requireContext(), active);
            mSwitch.setSummaryText(getString(R.string.fan_switch_summary_on, name));
        } else {
            mSwitch.setSummaryText(getString(R.string.fan_switch_summary_off));
        }

        if (mManual != null) {
            mManual.setValue(Fan.manualPct());
            final boolean manual = Preset.MODE_MANUAL.equals(Fan.mode());
            mManual.setEnabled(manual);
            mManual.setSummaryText(manual ? null : getString(R.string.fan_manual_disabled));
        }
        if (mSafety != null) {
            mSafety.setValue(Fan.safetyC());
            mSafety.setSummaryText(getString(R.string.fan_safety_summary, Fan.safetyC()));
        }
        if (mCurveRow != null) {
            final Preset target = curveTarget();
            final Curve curve = target != null ? target.parsedCurve() : Fan.parsedCurve();
            final int temperature = Fan.temperatureCelsius();
            final int percent = curve.percentFor(temperature < 0 ? 25 : temperature);
            mCurveRow.setSummary(getString(R.string.fan_curve_summary, curve.size(),
                    temperature < 0 ? 0 : temperature, percent));
            mCurveRow.setEnabled(target != null);
        }
    }
}
