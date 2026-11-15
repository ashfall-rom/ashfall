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
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.ashfall.parts.hw.Curve;
import org.ashfall.parts.hw.Fan;
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
 *
 * <p>The Performance controls (throttling override, GPU max frequency, CPU caps
 * and presets) live on the Power page (#124); the one-line "Performance → Power"
 * row was removed again in #156 §9, because the Power page is a first-class
 * page next to Fan on the Handheld screen.
 */
public class FanFragment extends PreferenceFragmentCompat {

    /** The daemon's live status period while a fan UI is visible. */
    private static final long LIVE_TICK_MS = 500;
    private PresetStore mStore;
    private MainSwitchPreference mSwitch;
    private StatusPreference mStatus;
    private PreferenceCategory mPresetCategory;
    private Preference mEditRow;
    private Preference mCurveRow;
    private SliderPreference mManual;
    private SliderPreference mMinPct;
    private SliderPreference mMaxPct;
    private TogglePreference mKick;
    private TogglePreference mScreenOff;
    private PreferenceScreen mScreen;
    private boolean mRebuilding;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            Fan.refreshLive();
            // #132: the tick is the only place that appends a graph sample.
            updateStatus();
            updateLiveSummaries();
            // The daemon publishes ashfall.status.perf.throttle after boot and
            // when the override changes; keep the switch in step (#99).
            refreshToggles();
            mHandler.postDelayed(this, LIVE_TICK_MS);
        }
    };
    private boolean mFirstResume = true;

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
        // A fresh page starts a fresh 60 s window: the history lives in memory
        // only and the daemon keeps no samples (docs/phase5.md §7.2).  The
        // preference is new per page, so this is a guard (and documents the
        // reset point) rather than a recovery path.
        mStatus.resetHistory();
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

        // Output limits (docs/phase5.md §7.1/§7.3).  The app only writes the
        // two properties; the daemon is the single enforcement point, so the
        // output is never clamped here.
        final PreferenceCategory limitsCategory = new PreferenceCategory(requireContext());
        limitsCategory.setTitle(R.string.fan_limits_category);
        mScreen.addPreference(limitsCategory);

        mMinPct = new SliderPreference(requireContext());
        mMinPct.setKey("fan_min_pct");
        mMinPct.setTitle(R.string.fan_limit_min_title);
        mMinPct.setRange(0, 100);
        mMinPct.setValueFormat(getString(R.string.percent_value_format));
        mMinPct.setValueChangedListener((preference, value) -> onMinPctChanged(value));
        limitsCategory.addPreference(mMinPct);

        mMaxPct = new SliderPreference(requireContext());
        mMaxPct.setKey("fan_max_pct");
        mMaxPct.setTitle(R.string.fan_limit_max_title);
        mMaxPct.setRange(0, 100);
        mMaxPct.setValueFormat(getString(R.string.percent_value_format));
        mMaxPct.setValueChangedListener((preference, value) -> onMaxPctChanged(value));
        limitsCategory.addPreference(mMaxPct);

        mMinPct.setValue(Fan.minPct());
        mMaxPct.setValue(Fan.maxPct());
        if (mMinPct.getValue() > mMaxPct.getValue()) {
            // min > max is an invalid pair (the daemon would fall back to both
            // defaults), so the page normalises it once instead of showing an
            // impossible range.  The minimum follows the maximum: normalising
            // never raises a cap the user set.
            mMinPct.setValue(mMaxPct.getValue());
            Fan.setMinPct(mMaxPct.getValue());
        }
        updateLimitSummaries();

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

        final Preference versionRow = new Preference(requireContext());
        versionRow.setKey("fan_version");
        versionRow.setTitle(R.string.fan_version_title);
        versionRow.setSummary(getString(R.string.fan_version_summary,
                Ui.appVersion(requireContext())));
        versionRow.setSelectable(false);
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
        updateLiveSummaries();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final RecyclerView list = getListView();
        if (list == null) {
            return;
        }
        // The framework's D-pad focus search only sees the rows RecyclerView
        // has attached.  Once a focused row ends flush with the list edge, the
        // row below it is detached, the search finds nothing and the D-pad
        // stops there - even though more settings follow (a disabled row such
        // as Manual output is skipped only when its neighbours are attached).
        // Keeping one screen of rows attached on either side makes the whole
        // page reachable in both directions; the focused view is scrolled into
        // view by RecyclerView itself.
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
        Fan.setLive(true);
        if (mFirstResume) {
            mFirstResume = false;
        } else if (getListView() != null) {
            // Returning from the preset editor: rebuild after the list has
            // finished its current layout pass, never inside it.
            getListView().post(this::rebuildPresets);
        }
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
        }
    }

    private void activate(Preset preset) {
        if (preset == null) {
            return;
        }
        Fan.apply(preset);
        Prefs.setLastPreset(requireContext(), preset.id);
        rebuildPresets();
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

    /** Low limit moved: take the maximum with it if it fell below, then write. */
    private void onMinPctChanged(int value) {
        if (value > mMaxPct.getValue()) {
            // min above max is invalid, so the maximum follows the minimum.
            // The follower is written first: the daemon must never read
            // min > max, not even for one tick (it would apply both defaults).
            mMaxPct.setValue(value);
            Fan.setMaxPct(value);
        }
        Fan.setMinPct(value);
        updateLimitSummaries();
    }

    /** High limit moved: take the minimum with it if it rose above, then write. */
    private void onMaxPctChanged(int value) {
        if (value < mMinPct.getValue()) {
            // max below min is invalid, so the minimum follows the maximum.
            mMinPct.setValue(value);
            Fan.setMinPct(value);
        }
        Fan.setMaxPct(value);
        updateLimitSummaries();
    }

    /** The summaries state what the daemon does at the two ends of the range. */
    private void updateLimitSummaries() {
        if (mMinPct == null || mMaxPct == null) {
            return;
        }
        final int min = mMinPct.getValue();
        final int max = mMaxPct.getValue();
        if (min == 0) {
            mMinPct.setSummaryText(getString(R.string.fan_limit_min_summary_none));
        } else {
            String summary = getString(R.string.fan_limit_min_summary, min);
            if (min > Fan.QUIET_PCT) {
                // docs/phase5.md §7.1: the shipped 5 % minimum already stops
                // the built-in Quiet preset (2 %), and the row has to say so.
                summary += "\n" + getString(R.string.fan_limit_min_quiet_note, Fan.QUIET_PCT);
            }
            mMinPct.setSummaryText(summary);
        }
        mMaxPct.setSummaryText(max == 100
                ? getString(R.string.fan_limit_max_summary_none)
                : getString(R.string.fan_limit_max_summary, max));
    }

    private void refreshToggles() {
        if (mKick == null) {
            return;
        }
        mKick.setChecked(Fan.kick());
        mScreenOff.setChecked(Fan.offWhileScreenOff());
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
