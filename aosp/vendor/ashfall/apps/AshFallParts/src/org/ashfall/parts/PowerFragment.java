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
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
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
import org.ashfall.parts.hw.Stress;
import org.ashfall.parts.ui.FanGraphView;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.TogglePreference;
import org.ashfall.parts.ui.Ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Power page (#124, revised by #156): the live 60 s graph, the preset list,
 * the stress test, the throttling override and "Reset to stock".
 *
 * <p>The CPU caps, the governor and the GPU cap moved into the preset editor
 * ({@link PowerPresetActivity}) in #156 §12: this page is the live view and the
 * entry point, and every preset — built-ins included — is edited there.
 *
 * <p>Every control applies immediately: {@code ashfall-hwd} applies the
 * properties and publishes the effective values in
 * {@code ashfall.status.perf.*}.  While the page is visible it sets
 * {@code ashfall.ui.power_live=1}, so the daemon publishes every 500 ms, and it
 * ticks at the same rate: one tick is one graph sample, 120 samples = 60 s.
 * The plotted percentages are the real CPU/GPU loads (#156 §3), never a
 * percentage derived from the clocks.
 */
public class PowerFragment extends PreferenceFragmentCompat {

    /** The daemon's live status period while a power UI is visible (#156 §2). */
    private static final long TICK_MS = 500;
    /** Number of taps on the version row that reveal the advanced settings. */
    private static final int ADVANCED_TAPS = 7;

    private PowerPresetStore mStore;
    private PowerStatusPreference mStatus;
    private PreferenceCategory mPresetCategory;
    private Preference mEditRow;
    private final List<PowerPresetPreference> mPresetRows = new ArrayList<>();
    private SliderPreference mStressCpu;
    private SliderPreference mStressGpu;
    private Preference mStressStatus;
    private TogglePreference mThrottle;
    private TogglePreference mPermanent;
    private PreferenceCategory mAdvancedCategory;
    private PreferenceScreen mScreen;
    private int mVersionTaps;
    private boolean mRebuilding;
    private boolean mFirstResume = true;
    /** Set while the page resets the sliders itself, so no load is applied. */
    private boolean mUpdatingStress;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            // The tick is the only place that appends a graph sample (#132).
            Power.refreshLive();
            updateStatus();
            updateStressStatus();
            // The daemon publishes the throttle state after boot and whenever
            // the override changes; keep the switch in step (#99).
            refreshToggles();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

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
            startActivity(new Intent(requireContext(), PowerPresetActivity.class));
            return true;
        });

        addStressCategory();
        addThrottleCategory();

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
        rebuildPresets();
        setPreferenceScreen(mScreen);
        refreshToggles();
        updateStressStatus();
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
        Power.setLive(true);
        // One sticky battery receiver for the whole visible page: the tick then
        // costs one current read instead of a broadcast per sample.
        PowerBattery.start(requireContext());
        if (mFirstResume) {
            mFirstResume = false;
        } else if (getListView() != null) {
            // Returning from the preset editor: rebuild after the current
            // layout pass, never inside it.
            getListView().post(this::rebuildPresets);
        }
        // No updateStatus() here: the tick that starts right below is the only
        // place that appends a graph sample (#132).
        updateStressStatus();
        refreshToggles();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
        // Leaving the page always stops the load: the same safety rule as the
        // Fan page's stress test (#156 §4).
        stopStress();
        PowerBattery.stop(requireContext());
        Power.setLive(false);
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mTick);
        Stress.get().stop();
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
                row.setSummary(preset.summary(requireContext()));
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
        rebuildPresets();
    }

    private void resetToStock() {
        // The built-in Balanced preset is the stock configuration; restoring it
        // keeps the preset row and the properties in agreement (#156 §5).
        mStore.resetPreset(mStore.byId(PowerPresetStore.ID_BALANCED));
        Power.resetToStock();
        rebuildPresets();
        refreshToggles();
        Toast.makeText(requireContext(), R.string.power_reset_done, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------------
    // Stress test (#156 §4)
    // ---------------------------------------------------------------------

    private void addStressCategory() {
        final PreferenceCategory stress = new PreferenceCategory(requireContext());
        stress.setTitle(R.string.power_stress_category);
        mScreen.addPreference(stress);

        mStressCpu = new SliderPreference(requireContext());
        mStressCpu.setKey("power_stress_cpu");
        mStressCpu.setTitle(R.string.curve_stress_cpu);
        mStressCpu.setRange(0, 100);
        mStressCpu.setValueFormat(getString(R.string.percent_value_format));
        mStressCpu.setValueChangedListener((preference, value) -> applyStress());
        stress.addPreference(mStressCpu);

        mStressGpu = new SliderPreference(requireContext());
        mStressGpu.setKey("power_stress_gpu");
        mStressGpu.setTitle(R.string.curve_stress_gpu);
        mStressGpu.setRange(0, 100);
        mStressGpu.setValueFormat(getString(R.string.percent_value_format));
        mStressGpu.setValueChangedListener((preference, value) -> applyStress());
        stress.addPreference(mStressGpu);

        // Start = raise a slider; this row is the explicit stop and the
        // countdown, exactly like the Fan page's stress footer.
        mStressStatus = new Preference(requireContext());
        mStressStatus.setKey("power_stress_status");
        mStressStatus.setTitle(R.string.curve_stress_title);
        mStressStatus.setOnPreferenceClickListener(preference -> {
            if (Stress.get().isRunning()) {
                stopStress();
            }
            return true;
        });
        stress.addPreference(mStressStatus);
    }

    /** A fresh page always starts idle; the load is never resumed silently. */
    private void applyStress() {
        if (mUpdatingStress || mStressCpu == null || mStressGpu == null) {
            return;
        }
        Stress.get().setLevels(mStressCpu.getValue(), mStressGpu.getValue());
        updateStressStatus();
    }

    private void stopStress() {
        Stress.get().stop();
        resetStressSliders();
    }

    private void resetStressSliders() {
        if (mStressCpu == null || mStressGpu == null) {
            return;
        }
        mUpdatingStress = true;
        try {
            mStressCpu.setValue(0);
            mStressGpu.setValue(0);
        } finally {
            mUpdatingStress = false;
        }
        updateStressStatus();
    }

    private void updateStressStatus() {
        if (mStressStatus == null || mStressCpu == null || mStressGpu == null) {
            return;
        }
        final Stress stress = Stress.get();
        if (stress.isRunning()) {
            if (mStressGpu.getValue() > 0 && !stress.gpuAvailable()) {
                mStressStatus.setSummary(R.string.curve_stress_gpu_unavailable);
            } else {
                mStressStatus.setSummary(getString(R.string.curve_stress_running,
                        Ui.countdown(stress.remainingMs())));
            }
            mStressStatus.setEnabled(true);
            return;
        }
        // The engine's ten-minute cap can stop the load without a slider
        // change; zero the sliders so the next visit does not restart it.
        if (mStressCpu.getValue() > 0 || mStressGpu.getValue() > 0) {
            mUpdatingStress = true;
            try {
                mStressCpu.setValue(0);
                mStressGpu.setValue(0);
            } finally {
                mUpdatingStress = false;
            }
        }
        mStressStatus.setSummary(R.string.curve_stress_hint);
        mStressStatus.setEnabled(false);
    }

    // ---------------------------------------------------------------------
    // Throttling
    // ---------------------------------------------------------------------

    private void addThrottleCategory() {
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
    }

    private void confirmThrottleOverride() {
        if (!isAdded()) {
            return;
        }
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_throttle_confirm_title)
                .setMessage(R.string.power_throttle_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.power_throttle_confirm_enable, (dialog, which) -> {
                    Power.setThrottleOverride(true);
                    if (isAdded()) {
                        refreshToggles();
                    }
                })
                .show();
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
            if (!desired) {
                Power.setThrottlePermanent(false);
                refreshToggles();
                return;
            }
            confirmPermanentOverride();
        });
        mAdvancedCategory.addPreference(mPermanent);
        refreshToggles();
    }

    /**
     * Arming the permanent override also arms the override itself and survives
     * a reboot, so it goes through the same risk text as the override switch
     * instead of being one silent D-pad press (issue #177).
     */
    private void confirmPermanentOverride() {
        if (!isAdded()) {
            return;
        }
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.power_advanced_permanent_confirm_title)
                .setMessage(R.string.power_advanced_permanent_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.power_throttle_confirm_enable, (dialog, which) -> {
                    Power.setThrottlePermanent(true);
                    // Also apply it now, otherwise "at every boot" would do
                    // nothing until the next boot.
                    Power.setThrottleOverride(true);
                    if (isAdded()) {
                        refreshToggles();
                    }
                })
                .show();
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
    // Live state
    // ---------------------------------------------------------------------

    private void updateStatus() {
        if (mStatus == null) {
            return;
        }
        mStatus.update(Power.powerMw(requireContext()), Power.powerAvailable(requireContext()),
                Power.powerCharging(requireContext()), Power.cpuLoadPct(), Power.gpuLoadPct(),
                Power.cpuMhzSilver(), Power.cpuMhzGold(), Power.cpuMhzPrime(), Power.gpuMhz());
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

    // ---------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------

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
     * The live power row: one legend line with the exact power and the real
     * CPU/GPU utilisation, the per-cluster and GPU clocks under it, and a 60 s
     * plot (power W on the left axis, load % on the right).  One sample per
     * 500 ms page tick; {@code -1} is a gap, never a drawn zero.
     *
     * <p>Not selectable and not focusable: it is a read-out, so the D-pad skips
     * it.  It lives here rather than in {@code ui/} because the row is tied to
     * this page's property contract and the shared status row belongs to the
     * Fan page.
     */
    private static final class PowerStatusPreference extends Preference {

        private final FanGraphView.Window mWindow =
                new FanGraphView.Window(7, FanGraphView.POWER_SAMPLES);

        private int mPowerMw = -1;
        private boolean mCharging;
        private int mCpuLoad = -1;
        private int mGpuLoad = -1;
        private int mSilverMhz = -1;
        private int mGoldMhz = -1;
        private int mPrimeMhz = -1;
        private int mGpuMhz = -1;
        /** The row has something to draw (battery, telemetry or both). */
        private boolean mAvailable;
        /** No telemetry at all: the daemon note is shown above the plot. */
        private boolean mTelemetryUnavailable;

        private WeakReference<FanGraphView> mGraph = new WeakReference<>(null);
        private WeakReference<View> mBody = new WeakReference<>(null);
        private WeakReference<View> mUnavailable = new WeakReference<>(null);
        private WeakReference<View> mNote = new WeakReference<>(null);
        private WeakReference<TextView> mPowerText = new WeakReference<>(null);
        private WeakReference<TextView> mCpuText = new WeakReference<>(null);
        private WeakReference<TextView> mGpuText = new WeakReference<>(null);
        private WeakReference<TextView> mClocksText = new WeakReference<>(null);
        /** The coloured clock legend, rebuilt only when a clock moves (#264). */
        private CharSequence mClocksLegend;
        private boolean mClocksLegendStale = true;

        PowerStatusPreference(Context context) {
            super(context);
            setLayoutResource(R.layout.power_status);
            setSelectable(false);
            setPersistent(false);
            setIconSpaceReserved(false);
        }

        /**
         * Appends exactly one 500 ms sample.  Negative values are the daemon's
         * "unknown" ({@code -1}, which includes power while charging) and
         * become gaps in the plot.
         */
        void update(int powerMw, boolean powerKnown, boolean charging, int cpuLoad,
                int gpuLoad, int silverMhz, int goldMhz, int primeMhz, int gpuMhz) {
            mPowerMw = powerMw;
            mCharging = charging;
            mCpuLoad = cpuLoad;
            mGpuLoad = gpuLoad;
            if (silverMhz != mSilverMhz || goldMhz != mGoldMhz
                    || primeMhz != mPrimeMhz || gpuMhz != mGpuMhz) {
                mClocksLegendStale = true;
            }
            mSilverMhz = silverMhz;
            mGoldMhz = goldMhz;
            mPrimeMhz = primeMhz;
            mGpuMhz = gpuMhz;
            final boolean clocks = silverMhz >= 0 || goldMhz >= 0 || primeMhz >= 0 || gpuMhz >= 0;
            final boolean loads = cpuLoad >= 0 || gpuLoad >= 0;
            mTelemetryUnavailable = !clocks && !loads;
            // The battery series does not need the daemon, so a page with no
            // telemetry still draws power and says only the telemetry is missing.
            mAvailable = powerKnown || clocks || loads;
            mWindow.append(
                    powerMw < 0 ? Float.NaN : powerMw / 1000f,
                    cpuLoad < 0 ? Float.NaN : cpuLoad,
                    gpuLoad < 0 ? Float.NaN : gpuLoad,
                    silverMhz < 0 ? Float.NaN : silverMhz,
                    goldMhz < 0 ? Float.NaN : goldMhz,
                    primeMhz < 0 ? Float.NaN : primeMhz,
                    gpuMhz < 0 ? Float.NaN : gpuMhz);
            apply();
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
            // A rebind may land on a different theme, so re-resolve the colours.
            mClocksLegendStale = true;
            if (graph != null) {
                graph.setChart(powerChart(item.getContext()), mWindow);
            }
            apply();
        }

        /** Power W on the left dial, the real load percentages on the right one,
         *  and the CPU/GPU clocks on the shared MHz (third) axis. */
        private static FanGraphView.Chart powerChart(Context context) {
            final FanGraphView.Axis left = new FanGraphView.Axis(0f, 12f,
                    new float[] {0f, 6f, 12f}, context.getString(R.string.power_axis_watts));
            final FanGraphView.Axis right = new FanGraphView.Axis(0f, 100f,
                    new float[] {0f, 50f, 100f}, context.getString(R.string.percent_value));
            final FanGraphView.Axis third = new FanGraphView.Axis(0f, 3000f,
                    new float[] {0f, 1000f, 2000f, 3000f},
                    context.getString(R.string.power_axis_mhz));
            return new FanGraphView.Chart(left, right, third, new FanGraphView.Series[] {
                    new FanGraphView.Series(0, context.getColor(R.color.fan_graph_temp), 0f, 12f),
                    new FanGraphView.Series(1, context.getColor(R.color.fan_graph_pct), 0f, 100f),
                    new FanGraphView.Series(1, context.getColor(R.color.fan_graph_rpm), 0f, 100f),
                    new FanGraphView.Series(2, context.getColor(R.color.fan_graph_clock_silver), 0f, 3000f),
                    new FanGraphView.Series(2, context.getColor(R.color.fan_graph_clock_gold), 0f, 3000f),
                    new FanGraphView.Series(2, context.getColor(R.color.fan_graph_clock_prime), 0f, 3000f),
                    new FanGraphView.Series(2, context.getColor(R.color.fan_graph_clock_gpu), 0f, 3000f),
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
                unavailable.setVisibility(mTelemetryUnavailable ? View.VISIBLE : View.GONE);
            }
            final View note = mNote.get();
            if (note != null) {
                // -1 while charging is the contract; an unreadable current is
                // just a gap, so only charging gets the note.
                note.setVisibility(mCharging ? View.VISIBLE : View.GONE);
            }
            setText(mPowerText.get(), powerText());
            setText(mCpuText.get(), percentText(R.string.power_legend_cpu, mCpuLoad));
            setText(mGpuText.get(), percentText(R.string.power_legend_gpu, mGpuLoad));
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

        private CharSequence powerText() {
            if (mPowerMw < 0) {
                return getContext().getString(R.string.power_legend_power,
                        getContext().getString(R.string.power_unknown));
            }
            return getContext().getString(R.string.power_legend_power,
                    String.format(Locale.US, "%.1f", mPowerMw / 1000f));
        }

        private CharSequence percentText(int format, int percent) {
            if (percent < 0) {
                return getContext().getString(format,
                        getContext().getString(R.string.power_unknown));
            }
            return getContext().getString(format, percent);
        }

        private CharSequence clocksText() {
            if (!mClocksLegendStale && mClocksLegend != null) {
                return mClocksLegend;
            }
            final SpannableStringBuilder text = new SpannableStringBuilder();
            appendClock(text, R.string.power_legend_clock_silver,
                    R.color.fan_graph_clock_silver, mSilverMhz);
            appendClock(text, R.string.power_legend_clock_gold,
                    R.color.fan_graph_clock_gold, mGoldMhz);
            appendClock(text, R.string.power_legend_clock_prime,
                    R.color.fan_graph_clock_prime, mPrimeMhz);
            appendClock(text, R.string.power_legend_clock_gpu,
                    R.color.fan_graph_clock_gpu, mGpuMhz);
            mClocksLegend = text;
            mClocksLegendStale = false;
            return text;
        }

        /** One "@bullet name value" segment in its own series colour, so the
         *  legend cannot disagree with the four plotted clock lines. */
        private void appendClock(SpannableStringBuilder text, int label, int color, int mhz) {
            final Context context = getContext();
            if (text.length() > 0) {
                text.append(context.getString(R.string.power_legend_clock_gap));
            }
            final int start = text.length();
            text.append(context.getString(R.string.power_legend_clock_bullet))
                    .append(' ')
                    .append(context.getString(label, mhzText(mhz)));
            text.setSpan(new ForegroundColorSpan(context.getColor(color)),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        private String mhzText(int mhz) {
            return mhz < 0
                    ? getContext().getString(R.string.power_unknown) : Integer.toString(mhz);
        }
    }
}
