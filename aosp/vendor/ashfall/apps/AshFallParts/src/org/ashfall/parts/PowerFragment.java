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
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.ashfall.parts.hw.Power;
import org.ashfall.parts.hw.PowerBattery;
import org.ashfall.parts.hw.PowerPreset;
import org.ashfall.parts.hw.PowerPresetStore;
import org.ashfall.parts.ui.FanGraphView;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.TogglePreference;
import org.ashfall.parts.ui.Ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Power page (#124): presets, one CPU cap slider per cluster, the CPU
 * governor, the GPU cap, the throttling override, "Reset to stock" and the live
 * 60 s graph.
 *
 * <p>Every control applies immediately: the sliders and the selectors write
 * {@code persist.ashfall.perf.*}, {@code ashfall-hwd} applies them and
 * publishes the effective values in {@code ashfall.status.perf.*}.  The page
 * ticks every 2 s (the daemon's own period) and appends exactly one sample per
 * tick, so the window is 30 samples = 60 s.
 *
 * <p>Changing a control edits the <b>active</b> preset in place: the built-ins
 * are editable like a user preset, and "Reset presets" restores the #124 table.
 * 940/1000 MHz are manual-only — they are written to the GPU property but never
 * stored in a preset.
 */
public class PowerFragment extends PreferenceFragmentCompat {

    /** The daemon publishes status every 2 s; one tick is one graph sample. */
    private static final long TICK_MS = 2000;
    /** Number of taps on the version row that reveal the advanced settings. */
    private static final int ADVANCED_TAPS = 7;
    /** One D-pad press moves a cap slider by this many MHz. */
    private static final int CAP_STEP_MHZ = 50;

    private PowerPresetStore mStore;
    private PowerStatusPreference mStatus;
    private PreferenceCategory mPresetCategory;
    private Preference mEditRow;
    private final List<PowerPresetPreference> mPresetRows = new ArrayList<>();
    private CapSliderPreference mSilver;
    private CapSliderPreference mGold;
    private CapSliderPreference mPrime;
    private Preference mGovernor;
    private Preference mGpu;
    private TogglePreference mThrottle;
    private TogglePreference mPermanent;
    private PreferenceCategory mAdvancedCategory;
    private PreferenceScreen mScreen;
    private int mVersionTaps;
    private boolean mRebuilding;
    private boolean mFirstResume = true;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            // The tick is the only place that appends a graph sample (#132).
            updateStatus();
            updateLiveSummaries();
            // The daemon publishes the throttle state after boot and whenever
            // the override changes; keep the switch in step (#99).
            refreshToggles();
            mHandler.postDelayed(this, TICK_MS);
        }
    };
    private AlertDialog mDialog;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mStore = PowerPresetStore.get(requireContext());
        mScreen = getPreferenceManager().createPreferenceScreen(requireContext());

        final PreferenceCategory statusCategory = new PreferenceCategory(requireContext());
        statusCategory.setTitle(R.string.power_status_title);
        mScreen.addPreference(statusCategory);
        mStatus = new PowerStatusPreference(requireContext());
        mStatus.setKey("power_status");
        statusCategory.addPreference(mStatus);

        mPresetCategory = new PreferenceCategory(requireContext());
        mPresetCategory.setTitle(R.string.power_presets_category);
        mScreen.addPreference(mPresetCategory);

        mEditRow = new Preference(requireContext());
        mEditRow.setKey("power_edit_presets");
        mEditRow.setTitle(R.string.power_preset_edit_title);
        mEditRow.setSummary(R.string.power_preset_edit_summary);
        mEditRow.setIcon(R.drawable.ic_ashfall_power);
        mEditRow.setOnPreferenceClickListener(preference -> {
            showPresetActions();
            return true;
        });

        final PreferenceCategory cpuCategory = new PreferenceCategory(requireContext());
        cpuCategory.setTitle(R.string.power_cpu_category);
        mScreen.addPreference(cpuCategory);

        mSilver = newCapSlider("power_cpu_silver", R.string.power_cpu_silver_title,
                Power.SILVER_MIN_MHZ, Power.SILVER_STOCK_MHZ);
        mSilver.setValueChangedListener((preference, value) -> {
            Power.setCpuCapSilverMhz(value);
            storeActivePreset();
            updateLiveSummaries();
        });
        cpuCategory.addPreference(mSilver);

        mGold = newCapSlider("power_cpu_gold", R.string.power_cpu_gold_title,
                Power.GOLD_MIN_MHZ, Power.GOLD_STOCK_MHZ);
        mGold.setValueChangedListener((preference, value) -> {
            Power.setCpuCapGoldMhz(value);
            storeActivePreset();
            updateLiveSummaries();
        });
        cpuCategory.addPreference(mGold);

        mPrime = newCapSlider("power_cpu_prime", R.string.power_cpu_prime_title,
                Power.PRIME_MIN_MHZ, Power.PRIME_STOCK_MHZ);
        mPrime.setValueChangedListener((preference, value) -> {
            Power.setCpuCapPrimeMhz(value);
            storeActivePreset();
            updateLiveSummaries();
        });
        cpuCategory.addPreference(mPrime);

        mGovernor = new Preference(requireContext());
        mGovernor.setKey("power_governor");
        mGovernor.setTitle(R.string.power_governor_title);
        mGovernor.setOnPreferenceClickListener(preference -> {
            showGovernorDialog();
            return true;
        });
        cpuCategory.addPreference(mGovernor);

        mGpu = new Preference(requireContext());
        mGpu.setKey("power_gpu_max");
        mGpu.setPersistent(false);
        mGpu.setTitle(R.string.power_gpu_title);
        mGpu.setOnPreferenceClickListener(preference -> {
            showGpuDialog();
            return true;
        });
        cpuCategory.addPreference(mGpu);

        final PreferenceCategory performance = new PreferenceCategory(requireContext());
        performance.setTitle(R.string.power_throttle_category);
        mScreen.addPreference(performance);

        mThrottle = new TogglePreference(requireContext());
        mThrottle.setKey("power_throttle_override");
        mThrottle.setTitle(R.string.power_throttle_title);
        mThrottle.setOnToggleRequestedListener((preference, desired) -> {
            if (!desired) {
                Power.setThrottleOverride(false);
                refreshToggles();
                return;
            }
            confirmThrottleOverride();
        });
        performance.addPreference(mThrottle);

        // Always build the hidden category even when it stays hidden, so its
        // position is identical whether it was unlocked now or earlier.
        addAdvancedCategory();
        setAdvancedVisible(Prefs.advancedUnlocked(requireContext()));

        final Preference reset = new Preference(requireContext());
        reset.setKey("power_reset");
        reset.setTitle(R.string.power_reset_title);
        reset.setSummary(R.string.power_reset_summary);
        reset.setOnPreferenceClickListener(preference -> {
            resetToStock();
            return true;
        });
        mScreen.addPreference(reset);

        final Preference versionRow = new Preference(requireContext());
        versionRow.setKey("power_version");
        versionRow.setTitle(R.string.power_version_title);
        versionRow.setSummary(getString(R.string.power_version_summary,
                Ui.appVersion(requireContext())));
        versionRow.setOnPreferenceClickListener(preference -> {
            onVersionTapped();
            return true;
        });
        mScreen.addPreference(versionRow);

        final Preference footer = new Preference(requireContext());
        footer.setKey("power_footer");
        footer.setSelectable(false);
        footer.setSummary(R.string.power_footer);
        mScreen.addPreference(footer);

        // Populate the dynamic preset rows before the screen is attached, so
        // the adapter is built from a stable hierarchy.
        syncControlsFromProperties();
        rebuildPresets();
        setPreferenceScreen(mScreen);
        refreshToggles();
        updateLiveSummaries();
    }

    private CapSliderPreference newCapSlider(String key, int title, int min, int max) {
        final CapSliderPreference slider = new CapSliderPreference(requireContext(), CAP_STEP_MHZ);
        slider.setKey(key);
        slider.setTitle(title);
        slider.setRange(min, max);
        slider.setValueFormat(getString(R.string.power_mhz_format));
        return slider;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final RecyclerView list = getListView();
        if (list == null) {
            return;
        }
        // The framework's D-pad focus search only sees attached rows, and this
        // page is longer than one screen.  Keeping one screen of extra rows
        // attached makes every row reachable in both directions (the same
        // workaround the Fan page uses).
        final RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (manager instanceof LinearLayoutManager) {
            final LinearLayoutManager linear = (LinearLayoutManager) manager;
            final int orientation = linear.getOrientation();
            final boolean reversed = linear.getReverseLayout();
            list.setLayoutManager(new LinearLayoutManager(list.getContext(), orientation, reversed) {
                @Override
                protected int getExtraLayoutSpace(RecyclerView.State state) {
                    return Math.max(super.getExtraLayoutSpace(state), getHeight());
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // One sticky battery receiver for the whole visible page: the tick then
        // costs one current read instead of a broadcast per sample.
        PowerBattery.start(requireContext());
        if (mFirstResume) {
            mFirstResume = false;
        } else if (getListView() != null) {
            // Returning from another page: rebuild after the current layout
            // pass, never inside it.
            getListView().post(this::rebuildPresets);
        }
        // No updateStatus() here: the tick that starts right below is the only
        // place that appends a graph sample (#132).
        updateLiveSummaries();
        refreshToggles();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
        PowerBattery.stop(requireContext());
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mTick);
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        super.onDestroyView();
    }

    // ---------------------------------------------------------------------
    // Presets
    // ---------------------------------------------------------------------

    private void rebuildPresets() {
        if (mPresetCategory == null || mRebuilding) {
            return;
        }
        mRebuilding = true;
        try {
            final PowerPreset active = Power.activePreset(mStore);
            final String activeId = active == null ? null : active.id;
            mPresetCategory.removeAll();
            mPresetRows.clear();
            for (PowerPreset preset : mStore.all()) {
                final PowerPresetPreference row =
                        new PowerPresetPreference(requireContext(), preset);
                row.setTitle(preset.displayName(requireContext()));
                row.setSummary(presetSummary(preset, preset.id.equals(activeId)));
                row.setChecked(preset.id.equals(activeId));
                row.setOnPreferenceClickListener(preference -> {
                    applyPreset(preset);
                    return true;
                });
                mPresetCategory.addPreference(row);
                mPresetRows.add(row);
            }
            // A reused row keeps its old order, which would sort it above the
            // freshly added presets; resetting to DEFAULT_ORDER appends it.
            mEditRow.setOrder(Preference.DEFAULT_ORDER);
            mPresetCategory.addPreference(mEditRow);
        } finally {
            mRebuilding = false;
        }
    }

    private void applyPreset(PowerPreset preset) {
        if (preset == null) {
            return;
        }
        Power.apply(preset);
        syncControlsFromProperties();
        rebuildPresets();
        updateLiveSummaries();
    }

    /**
     * Every control writes its property and then keeps the active preset in
     * step, so the preset row shows what the page is actually applying.
     */
    private void storeActivePreset() {
        final PowerPreset active = Power.activePreset(mStore);
        if (active == null) {
            return;
        }
        active.silverMhz = Power.cpuCapSilverMhz();
        active.goldMhz = Power.cpuCapGoldMhz();
        active.primeMhz = Power.cpuCapPrimeMhz();
        active.governor = Power.governor();
        mStore.save(active);
        updatePresetRows();
    }

    /** Keeps the values of the existing preset rows in step without a rebuild. */
    private void updatePresetRows() {
        final PowerPreset active = Power.activePreset(mStore);
        final String activeId = active == null ? null : active.id;
        for (PowerPresetPreference row : mPresetRows) {
            final boolean checked = row.getPreset().id.equals(activeId);
            row.setTitle(row.getPreset().displayName(requireContext()));
            row.setSummary(presetSummary(row.getPreset(), checked));
            row.setChecked(checked);
        }
    }

    private CharSequence presetSummary(PowerPreset preset, boolean active) {
        final String governor = Power.sanitizeGovernor(preset.governor);
        String text = preset.isStock()
                ? getString(R.string.power_preset_summary_stock, governor, preset.gpuMhz)
                : getString(R.string.power_preset_summary, preset.silverMhz, preset.goldMhz,
                        preset.primeMhz, governor, preset.gpuMhz);
        if (active && Power.gpuRequestedMhz() > preset.gpuMhz) {
            // A manual 940/1000 MHz step is never stored in the preset, so the
            // row says why the GPU row and the preset disagree.
            text += "\n" + getString(R.string.power_preset_manual_gpu_note, preset.gpuMhz);
        }
        return text;
    }

    private void showPresetActions() {
        if (!isAdded()) {
            return;
        }
        final PowerPreset active = Power.activePreset(mStore);
        final List<String> actions = new ArrayList<>();
        final String duplicate = getString(R.string.duplicate);
        final String rename = getString(R.string.rename);
        final String delete = getString(R.string.delete);
        final String reset = getString(R.string.power_edit_reset);
        actions.add(duplicate);
        if (active != null && !active.builtin) {
            actions.add(rename);
            actions.add(delete);
        }
        actions.add(reset);
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_edit_actions_title)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    final String action = actions.get(which);
                    if (action.equals(duplicate)) {
                        duplicateActive();
                    } else if (action.equals(rename)) {
                        renameActive(active);
                    } else if (action.equals(delete)) {
                        confirmDelete(active);
                    } else {
                        confirmResetPresets();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        mDialog.show();
    }

    private void duplicateActive() {
        final PowerPreset active = Power.activePreset(mStore);
        if (active == null) {
            return;
        }
        if (mStore.duplicate(active) == null) {
            Toast.makeText(requireContext(), R.string.power_preset_limit, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        rebuildPresets();
        updateLiveSummaries();
    }

    private void renameActive(PowerPreset preset) {
        if (preset == null || preset.builtin || !isAdded()) {
            return;
        }
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(preset.name);
        input.setSelectAllOnFocus(true);
        final int padding = Ui.dp(requireContext(), 20);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_rename_title)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        mDialog.setOnShowListener(ignored -> mDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError(getString(R.string.power_name_empty));
                        return;
                    }
                    mStore.rename(preset, name);
                    mDialog.dismiss();
                    rebuildPresets();
                }));
        mDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        mDialog.show();
        input.requestFocus();
    }

    private void confirmDelete(PowerPreset preset) {
        if (preset == null || !isAdded()) {
            return;
        }
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.power_delete_confirm,
                        preset.displayName(requireContext())))
                .setMessage(R.string.power_delete_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    mStore.delete(preset);
                    rebuildPresets();
                    updateLiveSummaries();
                })
                .create();
        mDialog.show();
    }

    private void confirmResetPresets() {
        if (!isAdded()) {
            return;
        }
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_reset_presets_confirm)
                .setMessage(R.string.power_reset_presets_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset, (dialog, which) -> {
                    mStore.resetAll();
                    rebuildPresets();
                    updateLiveSummaries();
                })
                .create();
        mDialog.show();
    }

    // ---------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------

    private void showGovernorDialog() {
        if (!isAdded()) {
            return;
        }
        final String[] governors = Power.GOVERNORS;
        final CharSequence[] labels = {
                getString(R.string.power_governor_schedutil),
                getString(R.string.power_governor_conservative),
                getString(R.string.power_governor_powersave),
        };
        final String current = Power.governor();
        int checked = 0;
        for (int i = 0; i < governors.length; i++) {
            if (governors[i].equals(current)) {
                checked = i;
            }
        }
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_governor_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    Power.setGovernor(governors[which]);
                    storeActivePreset();
                    updateLiveSummaries();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        mDialog.show();
    }

    /** The six GPU steps; 1000 MHz always goes through the danger warning. */
    private void showGpuDialog() {
        if (!isAdded()) {
            return;
        }
        final int[] values = Power.GPU_STEPS;
        final CharSequence[] labels = {
                getString(R.string.power_gpu_value_305),
                getString(R.string.power_gpu_value_670),
                getString(R.string.power_gpu_value_765),
                getString(R.string.power_gpu_value_850),
                getString(R.string.power_gpu_value_940),
                getString(R.string.power_gpu_value_experimental),
        };
        final int requested = Power.gpuRequestedMhz();
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == requested) {
                checked = i;
            }
        }
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_gpu_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onGpuStepSelected(values[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        mDialog.show();
    }

    private void onGpuStepSelected(int mhz) {
        if (mhz == Power.GPU_EXPERIMENTAL_MHZ) {
            confirmGpuExperimental(mhz);
            return;
        }
        applyGpuStep(mhz);
    }

    private void confirmGpuExperimental(int mhz) {
        if (!isAdded()) {
            return;
        }
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_gpu_confirm_title)
                .setMessage(R.string.power_gpu_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.power_gpu_confirm_enable,
                        (dialog, which) -> applyGpuStep(mhz))
                .create();
        mDialog.show();
    }

    private void applyGpuStep(int mhz) {
        Power.setGpuMhz(mhz);
        if (mhz <= Power.GPU_PRESET_MAX_MHZ) {
            // 940/1000 MHz are manual-only: the preset keeps its own step.
            final PowerPreset active = Power.activePreset(mStore);
            if (active != null) {
                active.gpuMhz = mhz;
                mStore.save(active);
            }
        }
        refreshGpu();
        updateLiveSummaries();
    }

    private void confirmThrottleOverride() {
        if (!isAdded()) {
            return;
        }
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_throttle_confirm_title)
                .setMessage(R.string.power_throttle_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.power_throttle_confirm_enable, (dialog, which) -> {
                    Power.setThrottleOverride(true);
                    if (isAdded()) {
                        refreshToggles();
                    }
                })
                .create();
        mDialog.show();
    }

    private void resetToStock() {
        Power.resetToStock();
        syncControlsFromProperties();
        rebuildPresets();
        refreshToggles();
        updateLiveSummaries();
        Toast.makeText(requireContext(), R.string.power_reset_done, Toast.LENGTH_SHORT).show();
    }

    /** Pulls every control back from the properties (apply, reset, resume). */
    private void syncControlsFromProperties() {
        if (mSilver == null) {
            return;
        }
        mSilver.setValue(Power.cpuCapSilverMhz());
        mGold.setValue(Power.cpuCapGoldMhz());
        mPrime.setValue(Power.cpuCapPrimeMhz());
    }

    // ---------------------------------------------------------------------
    // Live state
    // ---------------------------------------------------------------------

    private void updateStatus() {
        if (mStatus == null) {
            return;
        }
        mStatus.update(Power.powerMw(requireContext()), Power.powerAvailable(requireContext()),
                Power.powerCharging(requireContext()), Power.cpuMhzSilver(), Power.cpuMhzGold(),
                Power.cpuMhzPrime(), Power.gpuMhz());
    }

    private void updateLiveSummaries() {
        updateCapSummaries();
        updateGovernorSummary();
        refreshGpu();
    }

    private void updateCapSummaries() {
        if (mSilver == null) {
            return;
        }
        final boolean inert = Power.capsInert();
        mSilver.setEnabled(!inert);
        mGold.setEnabled(!inert);
        mPrime.setEnabled(!inert);
        mSilver.setSummaryText(capSummary(Power.cpuCapSilverMhz(), Power.SILVER_STOCK_MHZ,
                Power.cpuMhzSilver(), inert));
        mGold.setSummaryText(capSummary(Power.cpuCapGoldMhz(), Power.GOLD_STOCK_MHZ,
                Power.cpuMhzGold(), inert));
        mPrime.setSummaryText(capSummary(Power.cpuCapPrimeMhz(), Power.PRIME_STOCK_MHZ,
                Power.cpuMhzPrime(), inert));
    }

    private CharSequence capSummary(int cap, int stock, int now, boolean inert) {
        if (inert) {
            // docs/phase6.md §9: powersave pins every policy to its lowest step
            // and ignores the caps, so the rows say so instead of lying.
            return getString(R.string.power_cpu_powersave_note);
        }
        final String nowText = now < 0
                ? getString(R.string.power_unknown) : getString(R.string.power_home_mhz, now);
        if (cap >= stock) {
            return now < 0
                    ? getString(R.string.power_cpu_cap_summary_stock_idle, stock)
                    : getString(R.string.power_cpu_cap_summary_stock, stock, nowText);
        }
        return now < 0
                ? getString(R.string.power_cpu_cap_summary_idle, cap)
                : getString(R.string.power_cpu_cap_summary, cap, nowText);
    }

    private void updateGovernorSummary() {
        if (mGovernor == null) {
            return;
        }
        final String name = Power.sanitizeGovernor(Power.governor());
        String summary = getString(R.string.power_governor_summary, name);
        if (Power.capsInert()) {
            summary += "\n" + getString(R.string.power_governor_powersave_note);
        }
        mGovernor.setSummary(summary);
    }

    private void refreshToggles() {
        if (mThrottle == null) {
            return;
        }
        final boolean active = Power.throttleActive();
        mThrottle.setChecked(active);
        final int summary;
        if (!Power.throttleSupported()) {
            summary = R.string.power_throttle_summary_unsupported;
        } else if (active) {
            summary = Power.throttlePermanent()
                    ? R.string.power_throttle_summary_permanent
                    : R.string.power_throttle_summary_on;
        } else {
            summary = R.string.power_throttle_summary_off;
        }
        mThrottle.setSummaryText(getString(summary));
        if (mPermanent != null) {
            mPermanent.setChecked(Power.throttlePermanent());
        }
    }

    /** Keeps the GPU row's actual cap / clamp summary in step with the daemon. */
    private void refreshGpu() {
        if (mGpu == null) {
            return;
        }
        final int requested = Power.gpuRequestedMhz();
        final int actual = Power.gpuActualMhz();
        final int oc = Power.gpuOc();
        final boolean aboveStock = requested > Power.GPU_STOCK_MHZ;
        final StringBuilder text = new StringBuilder();
        if (oc == -1) {
            text.append(getString(R.string.power_gpu_summary_no_sysfs, requested));
        } else if (oc == Integer.MIN_VALUE || oc < -1 || actual == Integer.MIN_VALUE) {
            text.append(getString(R.string.power_gpu_summary_unavailable, requested));
        } else if (Power.gpuClampedToStock()) {
            text.append(Power.throttleActive()
                    ? getString(R.string.power_gpu_summary_clamped, requested, actual)
                    : getString(R.string.power_gpu_summary_not_applied, requested, actual));
        } else if (actual == requested) {
            if (actual == Power.GPU_STOCK_MHZ) {
                text.append(getString(R.string.power_gpu_summary_stock));
            } else if (aboveStock) {
                text.append(getString(R.string.power_gpu_summary_overclock, actual));
            } else if (actual < Power.GPU_STOCK_MHZ) {
                text.append(getString(R.string.power_gpu_summary_low, actual));
            } else {
                text.append(getString(R.string.power_gpu_summary_value, actual));
            }
        } else {
            text.append(getString(R.string.power_gpu_summary_actual, actual, requested));
        }
        if (aboveStock && !(oc == 0 && Power.throttleActive())) {
            text.append('\n').append(getString(R.string.power_gpu_warning_fan));
        }
        if (requested > Power.GPU_PRESET_MAX_MHZ) {
            text.append('\n').append(getString(R.string.power_gpu_manual_note));
        }
        mGpu.setSummary(text);
    }

    private void addAdvancedCategory() {
        if (mAdvancedCategory != null || mScreen == null) {
            return;
        }
        mAdvancedCategory = new PreferenceCategory(requireContext());
        mAdvancedCategory.setTitle(R.string.power_advanced_category);
        // Attach the category to the screen before adding children: a detached
        // PreferenceGroup has no PreferenceManager (#96).
        mScreen.addPreference(mAdvancedCategory);
        mPermanent = new TogglePreference(requireContext());
        mPermanent.setKey("power_throttle_permanent");
        mPermanent.setTitle(R.string.power_advanced_permanent_title);
        mPermanent.setSummary(R.string.power_advanced_permanent_summary);
        mPermanent.setOnToggleRequestedListener((preference, desired) -> {
            Power.setThrottlePermanent(desired);
            if (desired) {
                // Also apply it now, otherwise "at every boot" would do nothing
                // until the next boot.
                Power.setThrottleOverride(true);
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
        Toast.makeText(requireContext(), R.string.power_advanced_unlocked, Toast.LENGTH_SHORT)
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

    // ---------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------

    /**
     * A cap slider whose D-pad step is coarser than its value domain: one MHz
     * per press over a 1500-step slider would be unusable, so left/right move
     * by {@code step} and the touch drag keeps the full range.
     */
    private static final class CapSliderPreference extends SliderPreference {

        private final int mStep;
        private int mMin;
        private int mMax;
        private OnValueChangedListener mListener;

        CapSliderPreference(Context context, int step) {
            super(context);
            mStep = step;
        }

        @Override
        public void setRange(int min, int max) {
            mMin = min;
            mMax = max;
            super.setRange(min, max);
        }

        @Override
        public void setValueChangedListener(OnValueChangedListener listener) {
            // The base class keeps its listener private and only calls it from
            // the SeekBar, so the D-pad path keeps its own copy.
            super.setValueChangedListener(listener);
            mListener = listener;
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            final View item = holder.itemView;
            item.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || !isEnabled()) {
                    return false;
                }
                final int delta;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    delta = -mStep;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    delta = mStep;
                } else {
                    return false;
                }
                final int next = Math.max(mMin, Math.min(mMax, getValue() + delta));
                if (next == getValue()) {
                    return true;
                }
                setValue(next);
                if (mListener != null) {
                    mListener.onValueChanged(this, next);
                }
                return true;
            });
        }
    }

    /** A single-choice preset row with a radio indicator. */
    private static final class PowerPresetPreference extends Preference {

        private final PowerPreset mPreset;
        private boolean mChecked;

        PowerPresetPreference(Context context, PowerPreset preset) {
            super(context);
            mPreset = preset;
            setLayoutResource(R.layout.preference_preset);
            setPersistent(false);
            setIconSpaceReserved(false);
            setKey("power_preset_" + preset.id);
        }

        PowerPreset getPreset() {
            return mPreset;
        }

        void setChecked(boolean checked) {
            if (mChecked != checked) {
                mChecked = checked;
                notifyChanged();
            }
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            final View item = holder.itemView;
            final TextView title = item.findViewById(R.id.preset_title);
            final TextView summary = item.findViewById(R.id.preset_summary);
            final View radio = item.findViewById(R.id.preset_radio);
            if (title != null) {
                title.setText(getTitle());
            }
            if (summary != null) {
                summary.setText(getSummary());
            }
            if (radio instanceof android.widget.RadioButton) {
                ((android.widget.RadioButton) radio).setChecked(mChecked);
            }
        }
    }

    /**
     * The live power row: a legend with the exact power/clock values, the
     * per-cluster clocks, and a 60 s plot (power W on the left axis, CPU/GPU as
     * percent of their maximum available clock on the right).  One sample per
     * 2 s page tick; {@code -1} is a gap, never a drawn zero.
     *
     * <p>Not selectable and not focusable: it is a read-out, so the D-pad skips
     * it.  It lives here rather than in {@code ui/} because the row is tied to
     * this page's property contract and the shared status row belongs to the
     * Fan page.
     */
    private static final class PowerStatusPreference extends Preference {

        private final FanGraphView.Window mWindow =
                new FanGraphView.Window(3, FanGraphView.POWER_SAMPLES);

        private int mPowerMw = -1;
        private boolean mCharging;
        private int mSilverMhz = -1;
        private int mGoldMhz = -1;
        private int mPrimeMhz = -1;
        private int mGpuMhz = -1;
        /** The row has something to draw (battery, clocks or both). */
        private boolean mAvailable;
        /** The clocks are missing: the daemon note is shown above the plot. */
        private boolean mClocksUnavailable;

        private WeakReference<FanGraphView> mGraph = new WeakReference<>(null);
        private WeakReference<View> mBody = new WeakReference<>(null);
        private WeakReference<View> mUnavailable = new WeakReference<>(null);
        private WeakReference<View> mNote = new WeakReference<>(null);
        private WeakReference<TextView> mPowerText = new WeakReference<>(null);
        private WeakReference<TextView> mCpuText = new WeakReference<>(null);
        private WeakReference<TextView> mGpuText = new WeakReference<>(null);
        private WeakReference<TextView> mClocksText = new WeakReference<>(null);

        PowerStatusPreference(Context context) {
            super(context);
            setLayoutResource(R.layout.power_status);
            setSelectable(false);
            setPersistent(false);
            setIconSpaceReserved(false);
        }

        /**
         * Appends exactly one 2 s sample.  Negative values are the daemon's
         * "unknown" ({@code -1}, which includes power while charging) and
         * become gaps in the plot.
         */
        void update(int powerMw, boolean powerKnown, boolean charging, int silverMhz,
                int goldMhz, int primeMhz, int gpuMhz) {
            mPowerMw = powerMw;
            mCharging = charging;
            mSilverMhz = silverMhz;
            mGoldMhz = goldMhz;
            mPrimeMhz = primeMhz;
            mGpuMhz = gpuMhz;
            final boolean clocks = silverMhz >= 0 || goldMhz >= 0 || primeMhz >= 0 || gpuMhz >= 0;
            mClocksUnavailable = !clocks;
            // The battery series does not need the daemon, so a page with no
            // clocks still draws power and says only the clocks are missing.
            mAvailable = powerKnown || clocks;
            final int cpuMhz = Math.max(silverMhz, Math.max(goldMhz, primeMhz));
            mWindow.append(
                    powerMw < 0 ? Float.NaN : powerMw / 1000f,
                    cpuMhz < 0 ? Float.NaN : percent(cpuMhz, Power.CPU_REFERENCE_MHZ),
                    gpuMhz < 0 ? Float.NaN : percent(gpuMhz, Power.GPU_REFERENCE_MHZ));
            apply();
        }

        private static float percent(int mhz, int reference) {
            return Math.min(100f, 100f * mhz / reference);
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            final View item = holder.itemView;
            // A read-out row: never a focus stop, and its children never steal
            // the D-pad either, so focus moves from the row above to the row
            // below.
            item.setFocusable(false);
            item.setFocusableInTouchMode(false);
            item.setClickable(false);
            item.setLongClickable(false);
            if (item instanceof ViewGroup) {
                ((ViewGroup) item).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }

            final FanGraphView graph = item.findViewById(R.id.power_graph);
            mGraph = new WeakReference<>(graph);
            mBody = new WeakReference<>(item.findViewById(R.id.power_body));
            mUnavailable = new WeakReference<>(item.findViewById(R.id.power_unavailable));
            mNote = new WeakReference<>(item.findViewById(R.id.power_legend_note));
            mPowerText = new WeakReference<>(item.findViewById(R.id.power_legend_power));
            mCpuText = new WeakReference<>(item.findViewById(R.id.power_legend_cpu));
            mGpuText = new WeakReference<>(item.findViewById(R.id.power_legend_gpu));
            mClocksText = new WeakReference<>(item.findViewById(R.id.power_legend_clocks));
            if (graph != null) {
                graph.setChart(powerChart(item.getContext()), mWindow);
            }
            apply();
        }

        /** Power W on the left dial, clock percent on the right one. */
        private static FanGraphView.Chart powerChart(Context context) {
            final FanGraphView.Axis left = new FanGraphView.Axis(0f, 12f,
                    new float[] {0f, 6f, 12f}, context.getString(R.string.power_axis_watts));
            final FanGraphView.Axis right = new FanGraphView.Axis(0f, 100f,
                    new float[] {0f, 50f, 100f}, context.getString(R.string.percent_value));
            return new FanGraphView.Chart(left, right, new FanGraphView.Series[] {
                    new FanGraphView.Series(0, context.getColor(R.color.fan_graph_temp), 0f, 12f),
                    new FanGraphView.Series(1, context.getColor(R.color.fan_graph_pct), 0f, 100f),
                    new FanGraphView.Series(1, context.getColor(R.color.fan_graph_rpm), 0f, 100f),
            }, FanGraphView.POWER_SAMPLES);
        }

        /** Pushes the current values into the bound views; safe when unbound. */
        private void apply() {
            final View body = mBody.get();
            if (body != null) {
                body.setVisibility(mAvailable ? View.VISIBLE : View.GONE);
            }
            final View unavailable = mUnavailable.get();
            if (unavailable != null) {
                unavailable.setVisibility(mClocksUnavailable ? View.VISIBLE : View.GONE);
            }
            final View note = mNote.get();
            if (note != null) {
                // -1 while charging is the contract; an unreadable current is
                // just a gap, so only charging gets the note.
                note.setVisibility(mCharging ? View.VISIBLE : View.GONE);
            }
            setText(mPowerText.get(), powerText());
            setText(mCpuText.get(), percentText(R.string.power_legend_cpu, cpuMhz(),
                    Power.CPU_REFERENCE_MHZ));
            setText(mGpuText.get(), percentText(R.string.power_legend_gpu, mGpuMhz,
                    Power.GPU_REFERENCE_MHZ));
            setText(mClocksText.get(), clocksText());

            final FanGraphView graph = mGraph.get();
            if (graph != null) {
                graph.invalidate();
            }
        }

        private static void setText(TextView view, CharSequence text) {
            if (view != null && !TextUtils.equals(view.getText(), text)) {
                view.setText(text);
            }
        }

        private int cpuMhz() {
            return Math.max(mSilverMhz, Math.max(mGoldMhz, mPrimeMhz));
        }

        private CharSequence powerText() {
            if (mPowerMw < 0) {
                return getContext().getString(R.string.power_legend_power,
                        getContext().getString(R.string.power_unknown));
            }
            return getContext().getString(R.string.power_legend_power,
                    String.format(Locale.US, "%.1f", mPowerMw / 1000f));
        }

        private CharSequence percentText(int format, int mhz, int reference) {
            if (mhz < 0) {
                return getContext().getString(format,
                        getContext().getString(R.string.power_unknown));
            }
            return getContext().getString(format, Math.round(percent(mhz, reference)));
        }

        private CharSequence clocksText() {
            return getContext().getString(R.string.power_legend_clocks,
                    mhzText(mSilverMhz), mhzText(mGoldMhz), mhzText(mPrimeMhz));
        }

        private String mhzText(int mhz) {
            return mhz < 0
                    ? getContext().getString(R.string.power_unknown) : Integer.toString(mhz);
        }
    }
}
