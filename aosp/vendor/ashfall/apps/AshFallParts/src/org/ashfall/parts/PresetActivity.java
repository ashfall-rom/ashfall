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
import android.view.View;
import android.widget.EditText;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.Ui;

/** Preset editor: create, rename, duplicate and delete named fan presets. */
public class PresetActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.presets_title;
    }

    @Override
    protected Fragment createFragment() {
        return new PresetListFragment();
    }

    public static class PresetListFragment extends PreferenceFragmentCompat {

        private PresetStore mStore;
        private PreferenceCategory mCategory;

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
                if (preset != null) {
                    rebuild();
                    showActions(preset);
                }
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
            // only after returning from a dialog/curve editor (see onResume).
            rebuild();
            setPreferenceScreen(screen);
        }

        private Preference mAddRow;
        private Preference mResetRow;
        private boolean mFirstResume = true;

        @Override
        public void onResume() {
            super.onResume();
            if (mFirstResume) {
                mFirstResume = false;
            } else if (getListView() != null) {
                getListView().post(this::rebuild);
            }
        }

        private void rebuild() {
            if (mCategory == null) {
                return;
            }
            mCategory.removeAll();
            for (Preset preset : mStore.all()) {
                final Preference row = new Preference(requireContext());
                row.setKey("preset_row_" + preset.id);
                row.setTitle(Ui.presetName(requireContext(), preset));
                final String mode = Ui.presetModeSummary(requireContext(), preset);
                row.setSummary(preset.builtin
                        ? getString(R.string.preset_builtin_summary, mode)
                        : getString(R.string.preset_user_summary,
                                getString(R.string.fan_preset_none_custom), mode));
                row.setOnPreferenceClickListener(preference -> {
                    showActions(preset);
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

        private void showActions(Preset preset) {
            final boolean builtin = preset.builtin;
            final boolean smart = Preset.MODE_SMART.equals(preset.mode);
            final java.util.List<String> actions = new java.util.ArrayList<>();
            actions.add(getString(R.string.activate));
            // Built-in presets have a fixed mode (docs/phase5.md §3); only a
            // user preset can be changed between fixed %, manual and Smart.
            if (!builtin) {
                actions.add(getString(R.string.preset_change_mode));
            }
            if (smart) {
                actions.add(getString(R.string.fan_curve_title));
            }
            if (!builtin) {
                actions.add(getString(R.string.rename));
            }
            actions.add(getString(R.string.duplicate));
            if (!builtin) {
                actions.add(getString(R.string.delete));
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle(Ui.presetName(requireContext(), preset))
                    .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                        final String action = actions.get(which);
                        if (action.equals(getString(R.string.activate))) {
                            Fan.apply(preset);
                            Prefs.setLastPreset(requireContext(), preset.id);
                            rebuild();
                        } else if (action.equals(getString(R.string.preset_change_mode))) {
                            chooseMode(preset);
                        } else if (action.equals(getString(R.string.fan_curve_title))) {
                            startActivity(CurveActivity.intent(requireContext(), preset.id));
                        } else if (action.equals(getString(R.string.rename))) {
                            rename(preset);
                        } else if (action.equals(getString(R.string.duplicate))) {
                            mStore.duplicate(preset);
                            rebuild();
                        } else if (action.equals(getString(R.string.delete))) {
                            confirmDelete(preset);
                        }
                    })
                    .show();
        }

        private void chooseMode(Preset preset) {
            final String[] modes = {
                    Preset.MODE_OFF, Preset.MODE_QUIET, Preset.MODE_BALANCED, Preset.MODE_MAX,
                    Preset.MODE_MANUAL, Preset.MODE_SMART,
            };
            final String[] labels = new String[modes.length];
            int checked = 0;
            for (int i = 0; i < modes.length; i++) {
                labels[i] = Ui.modeName(requireContext(), modes[i]);
                if (modes[i].equals(preset.mode)) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.preset_change_mode)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        preset.mode = modes[which];
                        mStore.save(preset);
                        if (Preset.MODE_SMART.equals(preset.mode)) {
                            dialog.dismiss();
                            startActivity(CurveActivity.intent(requireContext(), preset.id));
                        } else {
                            dialog.dismiss();
                        }
                        rebuild();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void rename(Preset preset) {
            final EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setText(preset.name);
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
                        if (!mStore.rename(preset, name)) {
                            input.setError(getString(R.string.preset_delete_builtin));
                            return;
                        }
                        dialog.dismiss();
                        rebuild();
                    }));
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
            input.requestFocus();
        }

        private void confirmDelete(Preset preset) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.preset_delete_confirm,
                            Ui.presetName(requireContext(), preset)))
                    .setMessage(R.string.preset_delete_confirm_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        mStore.delete(preset);
                        rebuild();
                    })
                    .show();
        }
    }
}
