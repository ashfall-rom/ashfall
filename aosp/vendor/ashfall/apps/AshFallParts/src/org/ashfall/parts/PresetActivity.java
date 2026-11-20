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
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.Ui;

/**
 * The fan preset editor (#265): a list of every preset and one screen per
 * preset with its mode, manual percentage and curve — the same shape as the
 * Power preset editor ({@link PowerPresetActivity}).
 *
 * <p>The Fan page keeps the live controls; this editor is the only place that
 * changes a preset's values.  An edit is saved immediately; when the edited
 * preset is the active one it is also applied to the daemon-visible
 * properties, so the fan follows at once.
 *
 * <p>{@code builtin.off} is the fan-off state, not a preset: its list row
 * activates the fan-off state directly and it has no editor.  Every other
 * preset — built-ins included — can change its mode, manual percentage and
 * curve, apply itself, be duplicated and deleted, and the stock ids can be
 * reset to their factory values.
 */
public class PresetActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.presets_title;
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

    /** Every preset, the active one marked; a tap opens it for editing. */
    public static class PresetListFragment extends PreferenceFragmentCompat {

        private PresetStore mStore;
        private PreferenceCategory mCategory;
        private Preference mAddRow;
        private Preference mResetRow;
        private boolean mFirstResume = true;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mStore = PresetStore.get(requireContext());
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            mCategory = new PreferenceCategory(requireContext());
            mCategory.setTitle(R.string.presets_title);
            screen.addPreference(mCategory);

            final Preference add = new Preference(requireContext());
            add.setKey("preset_add");
            add.setTitle(R.string.fan_new_preset);
            add.setIcon(R.drawable.ic_ashfall_fan);
            add.setOnPreferenceClickListener(preference -> {
                final Preset preset = mStore.add(
                        mStore.newPresetName(requireContext()), Preset.MODE_SMART,
                        Fan.manualPct(), Fan.curve());
                if (preset == null) {
                    Toast.makeText(requireContext(), R.string.preset_limit,
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                rebuild();
                ((PresetActivity) requireActivity()).openEditor(preset.id);
                return true;
            });

            final Preference reset = new Preference(requireContext());
            reset.setKey("preset_reset");
            reset.setTitle(R.string.preset_reset_title);
            reset.setSummary(R.string.preset_reset_summary);
            reset.setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.preset_reset_title)
                        .setMessage(R.string.preset_reset_confirm)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.reset, (dialog, which) -> {
                            mStore.resetUserPresets();
                            rebuild();
                        })
                        .show();
                return true;
            });

            mAddRow = add;
            mResetRow = reset;
            // Build the dynamic rows before the screen is attached, and rebuild
            // only after returning from the editor (see onResume).
            rebuild();
            setPreferenceScreen(screen);
        }

        @Override
        public void onResume() {
            super.onResume();
            ((PresetActivity) requireActivity()).setTitle(R.string.presets_title);
            if (mFirstResume) {
                mFirstResume = false;
            } else if (getListView() != null) {
                // Returning from the editor: rebuild after this layout pass.
                getListView().post(this::rebuild);
            }
        }

        private void rebuild() {
            if (mCategory == null) {
                return;
            }
            final Preset active = Fan.activePreset(mStore);
            final String activeId = active == null ? null : active.id;
            mCategory.removeAll();
            for (Preset preset : mStore.all()) {
                final Preference row = new Preference(requireContext());
                row.setKey("preset_row_" + preset.id);
                row.setTitle(Ui.presetName(requireContext(), preset));
                final String mode = Ui.presetModeSummary(requireContext(), preset);
                String summary = preset.builtin
                        ? getString(R.string.preset_builtin_summary, mode)
                        : getString(R.string.preset_user_summary,
                                getString(R.string.fan_preset_none_custom), mode);
                if (preset.id.equals(activeId)) {
                    summary = getString(R.string.preset_editor_active_note) + "\n" + summary;
                }
                row.setSummary(summary);
                row.setOnPreferenceClickListener(preference -> {
                    if (preset.id.equals(PresetStore.ID_OFF)) {
                        // Off is the fan state, not an editable preset: a tap
                        // turns the fan off, exactly like the Fan page's row.
                        applyPreset(preset);
                        rebuild();
                    } else {
                        ((PresetActivity) requireActivity()).openEditor(preset.id);
                    }
                    return true;
                });
                mCategory.addPreference(row);
            }
            // Same ordering caveat as the Fan page: these rows are reused, so
            // reset their order before re-adding or they sort above the preset
            // rows that were just created.
            if (mAddRow != null) {
                mAddRow.setOrder(Preference.DEFAULT_ORDER);
                mCategory.addPreference(mAddRow);
            }
            if (mResetRow != null) {
                mResetRow.setOrder(Preference.DEFAULT_ORDER);
                mCategory.addPreference(mResetRow);
            }
        }

        /** Applies a preset and remembers it for the Fan page switch. */
        private void applyPreset(Preset preset) {
            Fan.apply(preset);
            Prefs.setLastPreset(requireContext(), preset.id);
        }
    }

    // ---------------------------------------------------------------------
    // One preset
    // ---------------------------------------------------------------------

    /** The mode, manual percentage and curve of one preset. */
    public static class PresetEditFragment extends PreferenceFragmentCompat {

        private static final String ARG_ID = "org.ashfall.parts.extra.PRESET_ID";
        /** The editable modes, in display order.  Off is a state, not a mode. */
        private static final String[] MODES = {
                Preset.MODE_QUIET, Preset.MODE_BALANCED, Preset.MODE_MAX,
                Preset.MODE_MANUAL, Preset.MODE_SMART,
        };

        private PresetStore mStore;
        private Preset mPreset;
        private PreferenceCategory mHeader;
        private Preference mMode;
        private SliderPreference mManual;
        private Preference mCurve;

        static PresetEditFragment newInstance(String presetId) {
            final PresetEditFragment fragment = new PresetEditFragment();
            final Bundle args = new Bundle();
            args.putString(ARG_ID, presetId);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mStore = PresetStore.get(requireContext());
            final String id = getArguments() == null ? null : getArguments().getString(ARG_ID);
            mPreset = id == null ? null : mStore.byId(id);
            if (mPreset == null || mPreset.id.equals(PresetStore.ID_OFF)) {
                // The preset was deleted (e.g. by another window) between the
                // list and this screen, or Off slipped in.  Popping the back
                // stack is illegal inside onCreatePreferences (the transaction
                // is still being executed), so do it on the next message.
                new Handler(Looper.getMainLooper())
                        .post(() -> getParentFragmentManager().popBackStack());
                setPreferenceScreen(getPreferenceManager()
                        .createPreferenceScreen(requireContext()));
                return;
            }

            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            addHeader(screen);
            addModeRow(screen);
            addManualRow(screen);
            addCurveRow(screen);
            addApplyRow(screen);
            addDuplicateRow(screen);
            addResetRow(screen);
            addRenameRow(screen);
            addDeleteRow(screen);

            syncControls();
            setPreferenceScreen(screen);
        }

        @Override
        public void onResume() {
            super.onResume();
            ((PresetActivity) requireActivity())
                    .setTitle(R.string.preset_editor_edit_title);
            if (mPreset == null) {
                return;
            }
            if (mStore.byId(mPreset.id) == null) {
                // Deleted while this screen was open; the list is underneath.
                getParentFragmentManager().popBackStack();
                return;
            }
            syncControls();
        }

        // -----------------------------------------------------------------
        // Rows
        // -----------------------------------------------------------------

        private void addHeader(PreferenceScreen screen) {
            final PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setKey("preset_editor_header");
            category.setTitle(Ui.presetName(requireContext(), mPreset));
            mHeader = category;
            screen.addPreference(category);

            final Preference summary = new Preference(requireContext());
            summary.setKey("preset_editor_values");
            summary.setTitle(R.string.preset_editor_values_title);
            summary.setSelectable(false);
            summary.setSummary(summaryText());
            category.addPreference(summary);
        }

        private void addModeRow(PreferenceScreen screen) {
            mMode = new Preference(requireContext());
            mMode.setKey("preset_editor_mode");
            mMode.setTitle(R.string.preset_editor_mode_title);
            mMode.setSummary(Ui.modeName(requireContext(), mPreset.mode));
            mMode.setOnPreferenceClickListener(preference -> {
                showModeDialog();
                return true;
            });
            screen.addPreference(mMode);
        }

        private void addManualRow(PreferenceScreen screen) {
            mManual = new SliderPreference(requireContext());
            mManual.setKey("preset_editor_manual");
            mManual.setTitle(R.string.preset_editor_manual_title);
            mManual.setRange(0, 100);
            mManual.setValueFormat(getString(R.string.percent_value_format));
            mManual.setValue(mPreset.manualPct);
            mManual.setValueChangedListener((preference, value) -> {
                mPreset.manualPct = value;
                commit();
            });
            screen.addPreference(mManual);
        }

        private void addCurveRow(PreferenceScreen screen) {
            mCurve = new Preference(requireContext());
            mCurve.setKey("preset_editor_curve");
            mCurve.setTitle(R.string.fan_curve_title);
            mCurve.setSummary(getString(R.string.fan_preset_curve_summary,
                    mPreset.parsedCurve().size()));
            mCurve.setOnPreferenceClickListener(preference -> {
                startActivity(CurveActivity.intent(requireContext(), mPreset.id));
                return true;
            });
            screen.addPreference(mCurve);
        }

        private void addApplyRow(PreferenceScreen screen) {
            final Preference apply = new Preference(requireContext());
            apply.setKey("preset_editor_apply");
            apply.setTitle(R.string.preset_editor_apply_title);
            apply.setOnPreferenceClickListener(preference -> {
                applyPreset();
                Toast.makeText(requireContext(), R.string.preset_editor_applied,
                        Toast.LENGTH_SHORT).show();
                updateApplyRow(preference);
                return true;
            });
            screen.addPreference(apply);
        }

        private void addDuplicateRow(PreferenceScreen screen) {
            final Preference duplicate = new Preference(requireContext());
            duplicate.setKey("preset_editor_duplicate");
            duplicate.setTitle(R.string.duplicate);
            duplicate.setSummary(R.string.preset_editor_duplicate_summary);
            duplicate.setOnPreferenceClickListener(preference -> {
                final Preset copy = mStore.duplicate(mPreset);
                if (copy == null) {
                    Toast.makeText(requireContext(), R.string.preset_limit,
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                ((PresetActivity) requireActivity()).openEditor(copy.id);
                return true;
            });
            screen.addPreference(duplicate);
        }

        private void addResetRow(PreferenceScreen screen) {
            if (!PresetStore.isStockId(mPreset.id)) {
                return;
            }
            final Preference reset = new Preference(requireContext());
            reset.setKey("preset_editor_reset");
            reset.setTitle(R.string.preset_editor_reset_one_title);
            reset.setSummary(R.string.preset_editor_reset_one_summary);
            reset.setOnPreferenceClickListener(preference -> {
                if (!mStore.resetPreset(mPreset)) {
                    return true;
                }
                // A deleted stock id is re-created, so pick the stored object.
                final Preset stored = mStore.byId(mPreset.id);
                if (stored != null) {
                    mPreset = stored;
                }
                if (isActive()) {
                    Fan.apply(mPreset);
                }
                syncControls();
                return true;
            });
            screen.addPreference(reset);
        }

        private void addRenameRow(PreferenceScreen screen) {
            final Preference rename = new Preference(requireContext());
            rename.setKey("preset_editor_rename");
            rename.setTitle(R.string.rename);
            rename.setSummary(mPreset.name);
            rename.setOnPreferenceClickListener(preference -> {
                showRenameDialog();
                return true;
            });
            screen.addPreference(rename);
        }

        private void addDeleteRow(PreferenceScreen screen) {
            final Preference delete = new Preference(requireContext());
            delete.setKey("preset_editor_delete");
            delete.setTitle(R.string.delete);
            delete.setSummary(R.string.preset_editor_delete_summary);
            delete.setOnPreferenceClickListener(preference -> {
                confirmDelete();
                return true;
            });
            screen.addPreference(delete);
        }

        // -----------------------------------------------------------------
        // State
        // -----------------------------------------------------------------

        private boolean isActive() {
            final Preset active = Fan.activePreset(mStore);
            return active != null && active.id.equals(mPreset.id);
        }

        /** Applies a preset to the daemon and remembers it for the Fan switch. */
        private void applyPreset() {
            Fan.apply(mPreset);
            Prefs.setLastPreset(requireContext(), mPreset.id);
        }

        /**
         * Saves the edit and, when the preset is the active one, applies it so
         * the daemon and the Fan page follow immediately.
         */
        private void commit() {
            mStore.save(mPreset);
            if (isActive()) {
                applyPreset();
            }
            syncControls();
        }

        /** The read-only "mode / manual % / curve" line. */
        private String summaryText() {
            final StringBuilder summary =
                    new StringBuilder(Ui.modeName(requireContext(), mPreset.mode));
            if (Preset.MODE_MANUAL.equals(mPreset.mode)) {
                summary.append('\n').append(getString(R.string.fan_preset_manual_summary,
                        mPreset.manualPct));
            } else if (Preset.MODE_SMART.equals(mPreset.mode)) {
                summary.append('\n').append(getString(R.string.fan_preset_curve_summary,
                        mPreset.parsedCurve().size()));
            }
            return summary.toString();
        }

        private void syncControls() {
            if (mPreset == null) {
                return;
            }
            if (mHeader != null) {
                mHeader.setTitle(Ui.presetName(requireContext(), mPreset));
            }
            final Preference values = findPreference("preset_editor_values");
            if (values != null) {
                values.setSummary(summaryText());
            }
            final boolean manualMode = Preset.MODE_MANUAL.equals(mPreset.mode);
            final boolean smartMode = Preset.MODE_SMART.equals(mPreset.mode);
            if (mMode != null) {
                mMode.setSummary(Ui.modeName(requireContext(), mPreset.mode));
            }
            if (mManual != null) {
                // The output slider only means something in Manual mode.
                mManual.setVisible(manualMode);
                mManual.setValue(mPreset.manualPct);
            }
            if (mCurve != null) {
                mCurve.setVisible(smartMode);
                mCurve.setSummary(getString(R.string.fan_preset_curve_summary,
                        mPreset.parsedCurve().size()));
            }
            final Preference rename = findPreference("preset_editor_rename");
            if (rename != null) {
                rename.setSummary(mPreset.name);
            }
            final Preference apply = findPreference("preset_editor_apply");
            if (apply != null) {
                updateApplyRow(apply);
            }
        }

        private void updateApplyRow(Preference apply) {
            if (isActive()) {
                apply.setSummary(R.string.preset_editor_apply_active);
                apply.setEnabled(false);
            } else {
                apply.setSummary(R.string.preset_editor_apply_inactive);
                apply.setEnabled(true);
            }
        }

        // -----------------------------------------------------------------
        // Dialogs
        // -----------------------------------------------------------------

        /** Quiet / Balanced / Performance / Manual / Smart — never Off. */
        private void showModeDialog() {
            if (!isAdded()) {
                return;
            }
            final CharSequence[] labels = new CharSequence[MODES.length];
            int checked = 0;
            for (int i = 0; i < MODES.length; i++) {
                labels[i] = Ui.modeName(requireContext(), MODES[i]);
                if (MODES[i].equals(mPreset.mode)) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.preset_editor_mode_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        dialog.dismiss();
                        mPreset.mode = MODES[which];
                        commit();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void showRenameDialog() {
            if (!isAdded()) {
                return;
            }
            final EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setText(mPreset.name);
            input.setSelectAllOnFocus(true);
            final int padding = Ui.dp(requireContext(), 20);
            input.setPadding(padding, padding / 2, padding, padding / 2);
            final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.preset_rename_title)
                    .setView(input)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save, null)
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        final String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            input.setError(getString(R.string.preset_name_empty));
                            return;
                        }
                        if (!mStore.rename(mPreset, name)) {
                            input.setError(getString(R.string.preset_rename_failed));
                            return;
                        }
                        dialog.dismiss();
                        syncControls();
                    }));
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
            input.requestFocus();
        }

        private void confirmDelete() {
            if (!isAdded()) {
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.preset_delete_confirm,
                            Ui.presetName(requireContext(), mPreset)))
                    .setMessage(R.string.preset_delete_confirm_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        mStore.delete(mPreset);
                        getParentFragmentManager().popBackStack();
                    })
                    .show();
        }
    }
}
