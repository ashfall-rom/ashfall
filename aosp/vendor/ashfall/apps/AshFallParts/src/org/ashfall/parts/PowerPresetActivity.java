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
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import org.ashfall.parts.hw.Power;
import org.ashfall.parts.hw.PowerPreset;
import org.ashfall.parts.hw.PowerPresetStore;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.Ui;

/**
 * The Power preset editor (#156 §5, §6, §12): a list of every preset — the
 * built-ins included — and one screen per preset with its CPU caps, governor
 * and GPU cap.
 *
 * <p>The Power page keeps the live graph and the preset list; this editor is
 * the only place that changes a preset's values, so the page cannot silently
 * edit the active preset while the user is looking at another one.  An edit is
 * saved immediately; when the edited preset is the active one it is also
 * written to the {@code persist.ashfall.perf.*} properties, so the device
 * follows at once.  "Apply" makes any preset the active one.
 *
 * <p>Built-ins keep their id and name, can be edited and are restored by
 * "Reset to factory values"; user presets can be duplicated, renamed and
 * deleted, and every GPU step (940 and 1000 MHz included) can be stored in a
 * preset.
 */
public class PowerPresetActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.power_editor_title;
    }

    @Override
    protected Fragment createFragment() {
        return new PresetListFragment();
    }

    /** Opens the per-preset editor on top of the list. */
    private void openEditor(String presetId) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.parts_fragment, PresetEditFragment.newInstance(presetId))
                .addToBackStack(null)
                .commit();
    }

    // ---------------------------------------------------------------------
    // Preset list
    // ---------------------------------------------------------------------

    /** Every preset, the active one checked; a tap opens it for editing. */
    public static class PresetListFragment extends PreferenceFragmentCompat {

        private PowerPresetStore mStore;
        private PreferenceCategory mCategory;
        private Preference mAddRow;
        private Preference mResetRow;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mStore = PowerPresetStore.get(requireContext());
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            mCategory = new PreferenceCategory(requireContext());
            mCategory.setTitle(R.string.power_editor_list_title);
            screen.addPreference(mCategory);

            mAddRow = new Preference(requireContext());
            mAddRow.setKey("power_preset_add");
            mAddRow.setTitle(R.string.power_editor_add_title);
            mAddRow.setSummary(R.string.power_editor_add_summary);
            mAddRow.setIcon(R.drawable.ic_ashfall_power);
            mAddRow.setOnPreferenceClickListener(preference -> {
                addPreset();
                return true;
            });

            mResetRow = new Preference(requireContext());
            mResetRow.setKey("power_preset_reset_all");
            mResetRow.setTitle(R.string.power_editor_reset_title);
            mResetRow.setSummary(R.string.power_editor_reset_summary);
            mResetRow.setOnPreferenceClickListener(preference -> {
                confirmResetAll();
                return true;
            });

            // Build the dynamic rows before the screen is attached.
            rebuild();
            setPreferenceScreen(screen);
        }

        @Override
        public void onResume() {
            super.onResume();
            ((PowerPresetActivity) requireActivity()).setTitle(R.string.power_editor_title);
            // Returning from the editor: rebuild after the current layout pass.
            if (getListView() != null) {
                getListView().post(this::rebuild);
            }
        }

        private void rebuild() {
            if (mCategory == null) {
                return;
            }
            final PowerPreset active = Power.activePreset(mStore);
            final String activeId = active == null ? null : active.id;
            mCategory.removeAll();
            for (PowerPreset preset : mStore.all()) {
                final Preference row = new Preference(requireContext());
                row.setKey("power_editor_preset_" + preset.id);
                row.setTitle(preset.displayName(requireContext()));
                String summary = preset.summary(requireContext());
                if (preset.id.equals(activeId)) {
                    summary = getString(R.string.power_editor_active_note) + "\n" + summary;
                }
                row.setSummary(summary);
                row.setOnPreferenceClickListener(preference -> {
                    ((PowerPresetActivity) requireActivity()).openEditor(preset.id);
                    return true;
                });
                mCategory.addPreference(row);
            }
            // Reused rows keep their old order; reset it so they stay last.
            mAddRow.setOrder(Preference.DEFAULT_ORDER);
            mCategory.addPreference(mAddRow);
            mResetRow.setOrder(Preference.DEFAULT_ORDER);
            mCategory.addPreference(mResetRow);
        }

        /** A new user preset starts as a copy of the active one. */
        private void addPreset() {
            final PowerPreset active = Power.activePreset(mStore);
            if (active == null) {
                return;
            }
            final PowerPreset preset = mStore.add(active,
                    mStore.newPresetName(requireContext()));
            if (preset == null) {
                Toast.makeText(requireContext(), R.string.power_preset_limit,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            rebuild();
            ((PowerPresetActivity) requireActivity()).openEditor(preset.id);
        }

        private void confirmResetAll() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.power_reset_presets_confirm)
                    .setMessage(R.string.power_reset_presets_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.reset, (dialog, which) -> {
                        mStore.resetAll();
                        rebuild();
                    })
                    .show();
        }
    }

    // ---------------------------------------------------------------------
    // One preset
    // ---------------------------------------------------------------------

    /** The CPU caps, the governor and the GPU cap of one preset. */
    public static class PresetEditFragment extends PreferenceFragmentCompat {

        private static final String ARG_ID = "org.ashfall.parts.extra.POWER_PRESET_ID";
        /** One D-pad press moves a cap slider by this many MHz. */
        private static final int CAP_STEP_MHZ = 50;

        private PowerPresetStore mStore;
        private PowerPreset mPreset;
        private PreferenceCategory mHeader;
        private CapSliderPreference mSilver;
        private CapSliderPreference mGold;
        private CapSliderPreference mPrime;
        private Preference mGovernor;
        private Preference mGpu;

        static PresetEditFragment newInstance(String presetId) {
            final PresetEditFragment fragment = new PresetEditFragment();
            final Bundle args = new Bundle();
            args.putString(ARG_ID, presetId);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mStore = PowerPresetStore.get(requireContext());
            final String id = getArguments() == null ? null : getArguments().getString(ARG_ID);
            mPreset = id == null ? null : mStore.byId(id);
            if (mPreset == null) {
                // The preset was deleted (e.g. by another window) between the
                // list and this screen.  Popping the back stack is illegal
                // inside onCreatePreferences (the transaction is still being
                // executed), so do it on the next main-thread message.
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> getParentFragmentManager().popBackStack());
                setPreferenceScreen(getPreferenceManager()
                        .createPreferenceScreen(requireContext()));
                return;
            }

            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            addPresetCategory(screen);
            addCpuCategory(screen);
            addGovernorRow(screen);
            addGpuRow(screen);

            syncControls();
            setPreferenceScreen(screen);
        }

        @Override
        public void onResume() {
            super.onResume();
            ((PowerPresetActivity) requireActivity())
                    .setTitle(R.string.power_editor_edit_title);
            if (mPreset != null) {
                syncControls();
            }
        }

        // -----------------------------------------------------------------
        // Rows
        // -----------------------------------------------------------------

        private void addPresetCategory(PreferenceScreen screen) {
            final PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setKey("power_editor_header");
            category.setTitle(mPreset.displayName(requireContext()));
            mHeader = category;
            screen.addPreference(category);

            final Preference summary = new Preference(requireContext());
            summary.setKey("power_editor_preset_summary");
            summary.setTitle(R.string.power_editor_values_title);
            summary.setSelectable(false);
            summary.setSummary(mPreset.summary(requireContext()));
            category.addPreference(summary);

            final Preference apply = new Preference(requireContext());
            apply.setKey("power_editor_apply");
            apply.setTitle(R.string.power_editor_apply_title);
            apply.setOnPreferenceClickListener(preference -> {
                Power.apply(mPreset);
                Toast.makeText(requireContext(), R.string.power_editor_applied,
                        Toast.LENGTH_SHORT).show();
                updateApplyRow(preference);
                return true;
            });
            category.addPreference(apply);

            final Preference duplicate = new Preference(requireContext());
            duplicate.setKey("power_editor_duplicate");
            duplicate.setTitle(R.string.duplicate);
            duplicate.setSummary(R.string.power_editor_duplicate_summary);
            duplicate.setOnPreferenceClickListener(preference -> {
                final PowerPreset copy = mStore.add(mPreset,
                        mStore.newPresetName(requireContext()));
                if (copy == null) {
                    Toast.makeText(requireContext(), R.string.power_preset_limit,
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                ((PowerPresetActivity) requireActivity()).openEditor(copy.id);
                return true;
            });
            category.addPreference(duplicate);

            if (mPreset.builtin) {
                final Preference reset = new Preference(requireContext());
                reset.setKey("power_editor_reset");
                reset.setTitle(R.string.power_editor_reset_one_title);
                reset.setSummary(R.string.power_editor_reset_one_summary);
                reset.setOnPreferenceClickListener(preference -> {
                    mStore.resetPreset(mPreset);
                    if (isActive()) {
                        Power.apply(mPreset);
                    }
                    syncControls();
                    return true;
                });
                category.addPreference(reset);
            } else {
                final Preference rename = new Preference(requireContext());
                rename.setKey("power_editor_rename");
                rename.setTitle(R.string.rename);
                rename.setSummary(mPreset.name);
                rename.setOnPreferenceClickListener(preference -> {
                    showRenameDialog();
                    return true;
                });
                category.addPreference(rename);

                final Preference delete = new Preference(requireContext());
                delete.setKey("power_editor_delete");
                delete.setTitle(R.string.delete);
                delete.setSummary(R.string.power_editor_delete_summary);
                delete.setOnPreferenceClickListener(preference -> {
                    confirmDelete();
                    return true;
                });
                category.addPreference(delete);
            }
        }

        private void addCpuCategory(PreferenceScreen screen) {
            final PreferenceCategory cpu = new PreferenceCategory(requireContext());
            cpu.setTitle(R.string.power_cpu_category);
            screen.addPreference(cpu);

            mSilver = newCapSlider("power_editor_cpu_silver", R.string.power_cpu_silver_title,
                    Power.SILVER_MIN_MHZ, Power.SILVER_STOCK_MHZ);
            mSilver.setValueChangedListener((preference, value) -> {
                mPreset.silverMhz = value;
                commit();
            });
            cpu.addPreference(mSilver);

            mGold = newCapSlider("power_editor_cpu_gold", R.string.power_cpu_gold_title,
                    Power.GOLD_MIN_MHZ, Power.GOLD_STOCK_MHZ);
            mGold.setValueChangedListener((preference, value) -> {
                mPreset.goldMhz = value;
                commit();
            });
            cpu.addPreference(mGold);

            mPrime = newCapSlider("power_editor_cpu_prime", R.string.power_cpu_prime_title,
                    Power.PRIME_MIN_MHZ, Power.PRIME_STOCK_MHZ);
            mPrime.setValueChangedListener((preference, value) -> {
                mPreset.primeMhz = value;
                commit();
            });
            cpu.addPreference(mPrime);
        }

        private void addGovernorRow(PreferenceScreen screen) {
            mGovernor = new Preference(requireContext());
            mGovernor.setKey("power_editor_governor");
            mGovernor.setTitle(R.string.power_governor_title);
            mGovernor.setOnPreferenceClickListener(preference -> {
                showGovernorDialog();
                return true;
            });
            screen.addPreference(mGovernor);
        }

        private void addGpuRow(PreferenceScreen screen) {
            mGpu = new Preference(requireContext());
            mGpu.setKey("power_editor_gpu");
            mGpu.setTitle(R.string.power_gpu_title);
            mGpu.setOnPreferenceClickListener(preference -> {
                showGpuDialog();
                return true;
            });
            screen.addPreference(mGpu);
        }

        private CapSliderPreference newCapSlider(String key, int title, int min, int max) {
            final CapSliderPreference slider =
                    new CapSliderPreference(requireContext(), CAP_STEP_MHZ);
            slider.setKey(key);
            slider.setTitle(title);
            slider.setRange(min, max);
            slider.setValueFormat(getString(R.string.power_mhz_format));
            return slider;
        }

        // -----------------------------------------------------------------
        // State
        // -----------------------------------------------------------------

        private boolean isActive() {
            final PowerPreset active = Power.activePreset(mStore);
            return active != null && active.id.equals(mPreset.id);
        }

        /**
         * Saves the edit and, when the preset is the active one, applies it so
         * the daemon and the Power page follow immediately.
         */
        private void commit() {
            mStore.save(mPreset);
            if (isActive()) {
                Power.apply(mPreset);
            }
            syncControls();
        }

        private void syncControls() {
            if (mPreset == null) {
                return;
            }
            final boolean inert = Power.GOV_POWERSAVE.equals(mPreset.governor);
            if (mSilver != null) {
                mSilver.setValue(mPreset.silverMhz);
                mGold.setValue(mPreset.goldMhz);
                mPrime.setValue(mPreset.primeMhz);
                // powersave pins every policy to its lowest step and ignores
                // scaling_max_freq, so the caps cannot do anything (#124).
                mSilver.setEnabled(!inert);
                mGold.setEnabled(!inert);
                mPrime.setEnabled(!inert);
                final CharSequence capSummary = inert
                        ? getString(R.string.power_cpu_powersave_note)
                        : null;
                mSilver.setSummaryText(capSummary);
                mGold.setSummaryText(capSummary);
                mPrime.setSummaryText(capSummary);
            }
            if (mGovernor != null) {
                String summary = getString(R.string.power_governor_summary,
                        Power.sanitizeGovernor(mPreset.governor));
                if (Power.GOV_POWERSAVE.equals(mPreset.governor)) {
                    summary += "\n" + getString(R.string.power_governor_powersave_note);
                } else if (Power.GOV_PERFORMANCE.equals(mPreset.governor)) {
                    summary += "\n" + getString(R.string.power_governor_performance_note);
                }
                mGovernor.setSummary(summary);
            }
            if (mGpu != null) {
                String summary = getString(R.string.power_editor_gpu_summary,
                        mPreset.gpuMhz);
                if (mPreset.gpuMhz > Power.GPU_STOCK_MHZ) {
                    summary += "\n" + getString(R.string.power_gpu_warning_fan);
                }
                // The daemon clamps an above-stock cap while the throttling
                // override is active; say so on the active preset (docs/phase6.md §3).
                if (isActive() && mPreset.gpuMhz > Power.GPU_STOCK_MHZ
                        && Power.gpuClampedToStock()) {
                    summary += "\n" + getString(R.string.power_gpu_summary_clamped,
                            mPreset.gpuMhz, Power.gpuActualMhz());
                }
                mGpu.setSummary(summary);
            }
            if (mHeader != null) {
                mHeader.setTitle(mPreset.displayName(requireContext()));
            }
            final Preference rename = findPreference("power_editor_rename");
            if (rename != null) {
                rename.setSummary(mPreset.name);
            }
            final Preference apply = findPreference("power_editor_apply");
            if (apply != null) {
                updateApplyRow(apply);
            }
            final Preference values = findPreference("power_editor_preset_summary");
            if (values != null) {
                values.setSummary(mPreset.summary(requireContext()));
            }
        }

        private void updateApplyRow(Preference apply) {
            if (isActive()) {
                apply.setSummary(R.string.power_editor_apply_active);
                apply.setEnabled(false);
            } else {
                apply.setSummary(R.string.power_editor_apply_inactive);
                apply.setEnabled(true);
            }
        }

        // -----------------------------------------------------------------
        // Dialogs
        // -----------------------------------------------------------------

        private void showGovernorDialog() {
            if (!isAdded()) {
                return;
            }
            final String[] governors = Power.GOVERNORS;
            final CharSequence[] labels = new CharSequence[governors.length];
            for (int i = 0; i < governors.length; i++) {
                labels[i] = governorLabel(governors[i]);
            }
            int checked = 0;
            for (int i = 0; i < governors.length; i++) {
                if (governors[i].equals(mPreset.governor)) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.power_governor_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        dialog.dismiss();
                        mPreset.governor = governors[which];
                        commit();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        /** The governor list, with `performance` added in #156 §6. */
        private CharSequence governorLabel(String governor) {
            switch (governor) {
                case Power.GOV_CONSERVATIVE:
                    return getString(R.string.power_governor_conservative);
                case Power.GOV_POWERSAVE:
                    return getString(R.string.power_governor_powersave);
                case Power.GOV_PERFORMANCE:
                    return getString(R.string.power_governor_performance);
                case Power.GOV_SCHEDUTIL:
                default:
                    return getString(R.string.power_governor_schedutil);
            }
        }

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
            int checked = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == mPreset.gpuMhz) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.power_gpu_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        dialog.dismiss();
                        onGpuStepSelected(values[which]);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void onGpuStepSelected(int mhz) {
            if (mhz == Power.GPU_EXPERIMENTAL_MHZ) {
                confirmGpuExperimental(mhz);
                return;
            }
            applyGpuStep(mhz);
        }

        /** 1000 MHz is opt-in and always goes through the thermal warning. */
        private void confirmGpuExperimental(int mhz) {
            if (!isAdded()) {
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.power_gpu_confirm_title)
                    .setMessage(R.string.power_gpu_confirm_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.power_gpu_confirm_enable,
                            (dialog, which) -> applyGpuStep(mhz))
                    .show();
        }

        /** 940 and 1000 MHz are ordinary preset values since #156 §5. */
        private void applyGpuStep(int mhz) {
            mPreset.gpuMhz = Power.sanitizePresetGpuMhz(mhz);
            commit();
        }

        private void showRenameDialog() {
            if (!isAdded() || mPreset.builtin) {
                return;
            }
            final EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setText(mPreset.name);
            input.setSelectAllOnFocus(true);
            final int padding = Ui.dp(requireContext(), 20);
            input.setPadding(padding, padding / 2, padding, padding / 2);
            final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.power_rename_title)
                    .setView(input)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save, null)
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        final String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            input.setError(getString(R.string.power_name_empty));
                            return;
                        }
                        mStore.rename(mPreset, name);
                        dialog.dismiss();
                        syncControls();
                    }));
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
            input.requestFocus();
        }

        private void confirmDelete() {
            if (!isAdded() || mPreset.builtin) {
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.power_delete_confirm,
                            mPreset.displayName(requireContext())))
                    .setMessage(R.string.power_delete_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        mStore.delete(mPreset);
                        getParentFragmentManager().popBackStack();
                    })
                    .show();
        }
    }

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

        CapSliderPreference(android.content.Context context, int step) {
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
}
