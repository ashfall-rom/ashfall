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

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Curve;
import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Perf;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.MainSwitchPreference;
import org.ashfall.parts.ui.PresetPreference;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.StatusPreference;
import org.ashfall.parts.ui.TogglePreference;
import org.ashfall.parts.ui.Ui;

import java.util.List;

/**
 * The Fan page.  Every change is written to the {@code persist.ashfall.fan.*}
 * properties immediately; the daemon applies it within a second.
 *
 * <p>While the page is visible it sets {@code ashfall.ui.fan_live=1} so the
 * daemon publishes status every 500 ms instead of every 2 s.
 */
public class FanFragment extends PreferenceFragmentCompat {

    /** The daemon's live status period while a fan UI is visible. */
    private static final long LIVE_TICK_MS = 500;
    /** Number of taps on the version row that reveal the advanced settings. */
    private static final int ADVANCED_TAPS = 7;

    private PresetStore mStore;
    private MainSwitchPreference mSwitch;
    private StatusPreference mStatus;
    private PreferenceCategory mPresetCategory;
    private Preference mEditRow;
    private Preference mCurveRow;
    private SliderPreference mManual;
    private TogglePreference mKick;
    private TogglePreference mScreenOff;
    private TogglePreference mThrottle;
    private Preference mGpu;
    private TogglePreference mPermanent;
    private PreferenceCategory mAdvancedCategory;
    private PreferenceScreen mScreen;
    private int mVersionTaps;
    private boolean mRebuilding;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            Fan.refreshLive();
            updateStatus();
            updateLiveSummaries();
            // The daemon publishes ashfall.status.perf.throttle after boot and
            // when the override changes; keep the switch in step (#99).
            refreshToggles();
            mHandler.postDelayed(this, LIVE_TICK_MS);
        }
    };
    private boolean mFirstResume = true;
    private AlertDialog mThrottleDialog;
    private AlertDialog mGpuDialog;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mStore = PresetStore.get(requireContext());
        mScreen = getPreferenceManager().createPreferenceScreen(requireContext());

        mSwitch = new MainSwitchPreference(requireContext());
        mSwitch.setKey("fan_switch");
        mSwitch.setTitle(R.string.fan_switch_title);
        mSwitch.setCheckedChangeListener((preference, checked) -> onSwitchChanged(checked));
        mScreen.addPreference(mSwitch);

        final PreferenceCategory statusCategory = new PreferenceCategory(requireContext());
        statusCategory.setTitle(R.string.fan_status_title);
        mScreen.addPreference(statusCategory);
        mStatus = new StatusPreference(requireContext());
        mStatus.setKey("fan_status");
        statusCategory.addPreference(mStatus);

        mPresetCategory = new PreferenceCategory(requireContext());
        mPresetCategory.setTitle(R.string.fan_presets_category);
        mScreen.addPreference(mPresetCategory);

        mEditRow = new Preference(requireContext());
        mEditRow.setKey("edit_presets");
        mEditRow.setTitle(R.string.fan_preset_edit_title);
        mEditRow.setSummary(R.string.fan_preset_edit_summary);
        mEditRow.setIcon(R.drawable.ic_ashfall_fan);
        mEditRow.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(requireContext(), PresetActivity.class));
            return true;
        });

        mCurveRow = new Preference(requireContext());
        mCurveRow.setKey("edit_curve");
        mCurveRow.setTitle(R.string.fan_curve_title);
        mCurveRow.setIcon(R.drawable.ic_ashfall_fan);
        mCurveRow.setOnPreferenceClickListener(preference -> {
            final Preset target = curveTarget();
            final Intent intent = new Intent(requireContext(), CurveActivity.class);
            intent.putExtra(CurveActivity.EXTRA_PRESET_ID, target.id);
            startActivity(intent);
            return true;
        });

        final PreferenceCategory manualCategory = new PreferenceCategory(requireContext());
        manualCategory.setTitle(R.string.fan_manual_category);
        mScreen.addPreference(manualCategory);
        mManual = new SliderPreference(requireContext());
        mManual.setKey("manual_pct");
        mManual.setTitle(R.string.fan_manual_title);
        mManual.setRange(0, 100);
        mManual.setValueFormat(getString(R.string.percent_value_format));
        mManual.setValueChangedListener((preference, value) -> {
            Fan.setManualPct(value);
            syncActiveManual(value);
            updateLiveSummaries();
        });
        manualCategory.addPreference(mManual);

        final PreferenceCategory behaviour = new PreferenceCategory(requireContext());
        behaviour.setTitle(R.string.fan_behaviour_category);
        mScreen.addPreference(behaviour);

        mKick = new TogglePreference(requireContext());
        mKick.setKey("fan_kick");
        mKick.setTitle(R.string.fan_kick_title);
        mKick.setSummary(R.string.fan_kick_summary);
        mKick.setOnToggleRequestedListener((preference, desired) -> {
            Fan.setKick(desired);
            refreshToggles();
        });
        behaviour.addPreference(mKick);

        mScreenOff = new TogglePreference(requireContext());
        mScreenOff.setKey("fan_screen_off");
        mScreenOff.setTitle(R.string.fan_screen_off_title);
        mScreenOff.setSummary(R.string.fan_screen_off_summary);
        mScreenOff.setOnToggleRequestedListener((preference, desired) -> {
            Fan.setOffWhileScreenOff(desired);
            refreshToggles();
        });
        behaviour.addPreference(mScreenOff);

        final PreferenceCategory performance = new PreferenceCategory(requireContext());
        performance.setTitle(R.string.fan_performance_category);
        mScreen.addPreference(performance);

        mThrottle = new TogglePreference(requireContext());
        mThrottle.setKey("fan_throttle_override");
        mThrottle.setTitle(R.string.fan_throttle_title);
        mThrottle.setOnToggleRequestedListener((preference, desired) -> {
            if (!desired) {
                Perf.setOverride(false);
                refreshToggles();
                return;
            }
            confirmThrottleOverride();
        });
        performance.addPreference(mThrottle);

        // GPU cap (docs/phase6.md §3): the selector lives in the same
        // Performance section; the daemon publishes the actual cap and
        // whether the throttle override clamps it.
        mGpu = new Preference(requireContext());
        mGpu.setKey("fan_gpu_max");
        mGpu.setPersistent(false);
        mGpu.setTitle(R.string.fan_gpu_title);
        mGpu.setOnPreferenceClickListener(preference -> {
            showGpuDialog();
            return true;
        });
        performance.addPreference(mGpu);

        // Always build the hidden category (just above the version row) and
        // only make it visible once it has been unlocked, so its position is
        // identical whether it was unlocked now or in an earlier visit.
        addAdvancedCategory();
        setAdvancedVisible(Prefs.advancedUnlocked(requireContext()));

        final Preference versionRow = new Preference(requireContext());
        versionRow.setKey("fan_version");
        versionRow.setTitle(R.string.fan_version_title);
        versionRow.setSummary(getString(R.string.fan_version_summary,
                Ui.appVersion(requireContext())));
        versionRow.setOnPreferenceClickListener(preference -> {
            onVersionTapped();
            return true;
        });
        mScreen.addPreference(versionRow);

        final Preference footer = new Preference(requireContext());
        footer.setKey("fan_footer");
        footer.setSelectable(false);
        footer.setSummary(R.string.footer_fan);
        mScreen.addPreference(footer);

        // Populate the (dynamic) preset rows before the screen is attached, so
        // the adapter is built from a stable hierarchy.  Later changes are
        // rebuilt only when returning from the preset editor (see onResume).
        rebuildPresets();
        setPreferenceScreen(mScreen);
        refreshToggles();
        updateStatus();
        updateLiveSummaries();
    }

    @Override
    public void onResume() {
        super.onResume();
        Fan.setLive(true);
        if (mFirstResume) {
            mFirstResume = false;
        } else if (getListView() != null) {
            // Returning from the preset editor: rebuild after the list has
            // finished its current layout pass, never inside it.
            getListView().post(this::rebuildPresets);
        }
        updateStatus();
        updateLiveSummaries();
        refreshToggles();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
        Fan.setLive(false);
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mTick);
        if (mThrottleDialog != null) {
            mThrottleDialog.dismiss();
            mThrottleDialog = null;
        }
        if (mGpuDialog != null) {
            mGpuDialog.dismiss();
            mGpuDialog = null;
        }
        super.onDestroyView();
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
            // The reuse of these rows across rebuilds keeps their old order,
            // which would otherwise sort them above the freshly added preset
            // rows (the category appends in order of addition).  Reset to
            // DEFAULT_ORDER so addPreference assigns a new, higher order and
            // they stay after the preset list, as designed.
            mEditRow.setOrder(Preference.DEFAULT_ORDER);
            mCurveRow.setOrder(Preference.DEFAULT_ORDER);
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

    /** Keeps the active Manual preset's stored output in step with the slider. */
    private void syncActiveManual(int value) {
        final Preset active = Fan.activePreset(mStore);
        if (active != null && Preset.MODE_MANUAL.equals(active.mode) && active.manualPct != value) {
            active.manualPct = value;
            mStore.save(active);
        }
    }

    private void refreshToggles() {
        if (mKick == null) {
            return;
        }
        mKick.setChecked(Fan.kick());
        mScreenOff.setChecked(Fan.offWhileScreenOff());
        final boolean throttleActive = Perf.overrideActive();
        mThrottle.setChecked(throttleActive);
        final int throttleSummary;
        if (!Perf.overrideSupported()) {
            throttleSummary = R.string.fan_throttle_summary_unsupported;
        } else if (throttleActive) {
            throttleSummary = Perf.permanent()
                    ? R.string.fan_throttle_summary_permanent
                    : R.string.fan_throttle_summary_on;
        } else {
            throttleSummary = R.string.fan_throttle_summary_off;
        }
        mThrottle.setSummaryText(getString(throttleSummary));
        if (mPermanent != null) {
            mPermanent.setChecked(Perf.permanent());
        }
        refreshGpu();
    }

    /** Keeps the GPU row's actual cap / clamp summary in step with the daemon. */
    private void refreshGpu() {
        if (mGpu == null) {
            return;
        }
        final int requested = Perf.gpuRequestedMhz();
        final int actual = Perf.gpuActualMhz();
        final int oc = Perf.gpuOc();
        final boolean aboveStock = requested > Perf.GPU_STOCK_MHZ;
        final StringBuilder text = new StringBuilder();
        if (oc == -1) {
            text.append(getString(R.string.fan_gpu_summary_no_sysfs, requested));
        } else if (oc == Integer.MIN_VALUE || oc < -1 || actual == Integer.MIN_VALUE) {
            text.append(getString(R.string.fan_gpu_summary_unavailable, requested));
        } else if (Perf.gpuClampedToStock()) {
            // The daemon clamps the cap to stock while the phase-5 throttle
            // override is active (docs/phase6.md §3); otherwise the requested
            // OC step is simply missing from the GPU table.
            text.append(Perf.overrideActive()
                    ? getString(R.string.fan_gpu_summary_clamped, requested, actual)
                    : getString(R.string.fan_gpu_summary_not_applied, requested, actual));
        } else if (actual == requested) {
            if (actual == Perf.GPU_STOCK_MHZ) {
                text.append(getString(R.string.fan_gpu_summary_stock));
            } else if (aboveStock) {
                text.append(getString(R.string.fan_gpu_summary_overclock, actual));
            } else {
                text.append(getString(R.string.fan_gpu_summary_value, actual));
            }
        } else {
            text.append(getString(R.string.fan_gpu_summary_actual, actual, requested));
        }
        if (aboveStock && !(oc == 0 && Perf.overrideActive())) {
            text.append('\n').append(getString(R.string.fan_gpu_warning_fan));
        }
        if (Perf.mockEnabled()) {
            text.append('\n').append(getString(R.string.fan_gpu_mock_note));
        }
        mGpu.setSummary(text);
    }

    /** Single-choice dialog with the five contract steps (stock is the default). */
    private void showGpuDialog() {
        if (!isAdded()) {
            return;
        }
        final int[] values = Perf.GPU_STEPS;
        final CharSequence[] labels = {
                getString(R.string.fan_gpu_value_stock),
                getString(R.string.fan_gpu_value_765),
                getString(R.string.fan_gpu_value_850),
                getString(R.string.fan_gpu_value_940),
                getString(R.string.fan_gpu_value_experimental),
        };
        final int requested = Perf.gpuRequestedMhz();
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == requested) {
                checked = i;
            }
        }
        mGpuDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.fan_gpu_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onGpuStepSelected(values[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        mGpuDialog.show();
    }

    private void onGpuStepSelected(int mhz) {
        if (mhz == Perf.GPU_EXPERIMENTAL_MHZ) {
            confirmGpuExperimental(mhz);
            return;
        }
        applyGpuStep(mhz);
    }

    /** 1000 MHz is opt-in and always goes through the thermal/stability warning. */
    private void confirmGpuExperimental(int mhz) {
        if (!isAdded()) {
            return;
        }
        mGpuDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.fan_gpu_confirm_title)
                .setMessage(R.string.fan_gpu_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.fan_gpu_confirm_enable,
                        (dialog, which) -> applyGpuStep(mhz))
                .create();
        mGpuDialog.show();
    }

    private void applyGpuStep(int mhz) {
        Perf.setGpuRequestedMhz(mhz);
        refreshGpu();
    }

    private void confirmThrottleOverride() {
        if (!isAdded()) {
            return;
        }
        mThrottleDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.fan_throttle_confirm_title)
                .setMessage(R.string.fan_throttle_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.fan_throttle_confirm_enable, (dialog, which) -> {
                    Perf.setOverride(true);
                    if (isAdded()) {
                        refreshToggles();
                    }
                })
                .create();
        mThrottleDialog.show();
    }

    private void addAdvancedCategory() {
        if (mAdvancedCategory != null || mScreen == null) {
            return;
        }
        mAdvancedCategory = new PreferenceCategory(requireContext());
        mAdvancedCategory.setTitle(R.string.fan_advanced_category);
        // Attach the category to the screen *before* adding children: a
        // detached PreferenceGroup has no PreferenceManager, and
        // PreferenceGroup.addPreference() -> getNextId() dereferences it
        // (P0 regression #96).  This also keeps the category just above the
        // version row, because it is added before the version/footer rows.
        mScreen.addPreference(mAdvancedCategory);
        mPermanent = new TogglePreference(requireContext());
        mPermanent.setKey("fan_throttle_permanent");
        mPermanent.setTitle(R.string.fan_advanced_permanent_title);
        mPermanent.setSummary(R.string.fan_advanced_permanent_summary);
        mPermanent.setOnToggleRequestedListener((preference, desired) -> {
            Perf.setPermanent(desired);
            if (desired) {
                // The hidden switch is also an immediate override, otherwise
                // "apply at every boot" would do nothing until the next boot.
                Perf.setOverride(true);
            }
            refreshToggles();
        });
        mAdvancedCategory.addPreference(mPermanent);
        refreshToggles();
    }

    private void onVersionTapped() {
        if (Prefs.advancedUnlocked(requireContext())) {
            return;
        }
        mVersionTaps++;
        if (mVersionTaps < ADVANCED_TAPS) {
            return;
        }
        Prefs.setAdvancedUnlocked(requireContext(), true);
        setAdvancedVisible(true);
        Toast.makeText(requireContext(), R.string.fan_advanced_unlocked, Toast.LENGTH_SHORT)
                .show();
    }

    /** androidx flattens groups into rows, so hide both the header and the row. */
    private void setAdvancedVisible(boolean visible) {
        if (mAdvancedCategory != null) {
            mAdvancedCategory.setVisible(visible);
        }
        if (mPermanent != null) {
            mPermanent.setVisible(visible);
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
