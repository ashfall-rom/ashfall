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
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Pad;
import org.ashfall.parts.ipc.CalClient;
import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.FusionClient;
import org.ashfall.parts.ui.MainSwitchPreference;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.Ui;

import java.util.Collections;
import java.util.List;

/**
 * Controller page: fusion, tester, calibration wizard and deadzones.
 *
 * <p>The Fusion category is the gamepad menu the user asked for (#138): the
 * main switch writes {@code persist.ashfall.pad.fusion}, the connected-pad rows
 * come from {@code FUSION STATUS} on the control socket, and every layout or
 * custom-mapping change is persisted in {@code persist.ashfall.pad.layouts} /
 * {@code .remap} and then applied with the matching socket command.  No shell,
 * no root, no {@code dumpsys}.
 */
public class ControllerActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.controller_title;
    }

    @Override
    protected Fragment createFragment() {
        return new ControllerFragment();
    }

    public static class ControllerFragment extends PreferenceFragmentCompat {

        /** The daemon reports hotplug by itself; this only refreshes the list. */
        private static final long FUSION_TICK_MS = 3000;

        private Preference mCalibrationRow;
        private MainSwitchPreference mFusionSwitch;
        private PreferenceCategory mFusionPads;
        private Preference mFusionState;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private volatile boolean mResumed;
        private volatile boolean mFusionFetching;
        /** The rendered pad list, so a tick never rebuilds an unchanged list. */
        private String mPadSignature = "";

        private final Runnable mFusionTick = new Runnable() {
            @Override
            public void run() {
                refreshFusion();
                mHandler.postDelayed(this, FUSION_TICK_MS);
            }
        };

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            buildFusionCategory(screen);

            final PreferenceCategory tools = new PreferenceCategory(requireContext());
            tools.setTitle(R.string.controller_title);
            screen.addPreference(tools);

            final Preference tester = new Preference(requireContext());
            tester.setKey("controller_tester");
            tester.setTitle(R.string.controller_tester_title);
            tester.setSummary(R.string.controller_tester_summary_v2);
            tester.setIcon(R.drawable.ic_gamepad);
            tester.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), TesterActivity.class));
                return true;
            });
            tools.addPreference(tester);

            mCalibrationRow = new Preference(requireContext());
            mCalibrationRow.setKey("controller_calibration");
            mCalibrationRow.setTitle(R.string.controller_calibration_title);
            mCalibrationRow.setIcon(R.drawable.ic_gamepad);
            mCalibrationRow.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), CalibrationActivity.class));
                return true;
            });
            tools.addPreference(mCalibrationRow);

            final Preference reset = new Preference(requireContext());
            reset.setKey("controller_reset");
            reset.setTitle(R.string.controller_reset_cal_title);
            reset.setSummary(R.string.controller_reset_cal_summary);
            reset.setOnPreferenceClickListener(preference -> {
                confirmReset();
                return true;
            });
            tools.addPreference(reset);

            final PreferenceCategory deadzones = new PreferenceCategory(requireContext());
            deadzones.setTitle(R.string.controller_deadzone_category);
            screen.addPreference(deadzones);

            final SliderPreference inner = new SliderPreference(requireContext());
            inner.setKey("deadzone_inner");
            inner.setTitle(R.string.controller_deadzone_inner);
            inner.setRange(0, Pad.MAX_INNER);
            inner.setValue(Pad.inner());
            inner.setValueFormat(getString(R.string.percent_value_format));
            inner.setValueChangedListener((preference, value) -> {
                Pad.setInner(value);
                preference.setSummaryText(getString(
                        R.string.controller_deadzone_inner_summary, value));
            });
            inner.setSummaryText(getString(R.string.controller_deadzone_inner_summary,
                    Pad.inner()));
            deadzones.addPreference(inner);

            final SliderPreference outer = new SliderPreference(requireContext());
            outer.setKey("deadzone_outer");
            outer.setTitle(R.string.controller_deadzone_outer);
            outer.setRange(0, Pad.MAX_OUTER);
            outer.setValue(Pad.outer());
            outer.setValueFormat(getString(R.string.percent_value_format));
            outer.setValueChangedListener((preference, value) -> {
                Pad.setOuter(value);
                preference.setSummaryText(getString(
                        R.string.controller_deadzone_outer_summary, value));
            });
            outer.setSummaryText(getString(R.string.controller_deadzone_outer_summary,
                    Pad.outer()));
            deadzones.addPreference(outer);

            final SliderPreference trigger = new SliderPreference(requireContext());
            trigger.setKey("deadzone_trigger");
            trigger.setTitle(R.string.controller_deadzone_trigger);
            trigger.setRange(0, Pad.MAX_TRIGGER);
            trigger.setValue(Pad.trigger());
            trigger.setValueFormat(getString(R.string.percent_value_format));
            trigger.setValueChangedListener((preference, value) -> {
                Pad.setTrigger(value);
                preference.setSummaryText(getString(
                        R.string.controller_deadzone_trigger_summary, value));
            });
            trigger.setSummaryText(getString(R.string.controller_deadzone_trigger_summary,
                    Pad.trigger()));
            deadzones.addPreference(trigger);

            final Preference footer = new Preference(requireContext());
            footer.setKey("controller_footer");
            footer.setSelectable(false);
            footer.setSummary(R.string.controller_footer_v2);
            screen.addPreference(footer);

            setPreferenceScreen(screen);
        }

        private void buildFusionCategory(PreferenceScreen screen) {
            final PreferenceCategory fusion = new PreferenceCategory(requireContext());
            fusion.setTitle(R.string.fusion_category_title);
            screen.addPreference(fusion);

            mFusionSwitch = new MainSwitchPreference(requireContext());
            mFusionSwitch.setKey("fusion_switch");
            mFusionSwitch.setTitle(R.string.fusion_switch_title);
            mFusionSwitch.setSummary(R.string.fusion_switch_summary);
            mFusionSwitch.setChecked(FusionClient.isOn());
            mFusionSwitch.setCheckedChangeListener(
                    (preference, checked) -> setFusion(checked));
            fusion.addPreference(mFusionSwitch);

            mFusionPads = new PreferenceCategory(requireContext());
            mFusionPads.setTitle(R.string.fusion_pads_title);
            fusion.addPreference(mFusionPads);

            final Preference rescan = new Preference(requireContext());
            rescan.setKey("fusion_rescan");
            rescan.setTitle(R.string.fusion_rescan_title);
            rescan.setSummary(R.string.fusion_rescan_summary);
            rescan.setOnPreferenceClickListener(preference -> {
                sendFusion("FUSION RESCAN", R.string.fusion_rescan_done);
                return true;
            });
            fusion.addPreference(rescan);

            mFusionState = new Preference(requireContext());
            mFusionState.setKey("fusion_state");
            mFusionState.setTitle(R.string.fusion_state_title);
            mFusionState.setSelectable(false);
            mFusionState.setSummary(FusionClient.stateText(requireContext(), null));
            fusion.addPreference(mFusionState);

            final Preference fusionFooter = new Preference(requireContext());
            fusionFooter.setKey("fusion_footer");
            fusionFooter.setSelectable(false);
            fusionFooter.setSummary(R.string.fusion_footer);
            fusion.addPreference(fusionFooter);

            showPads(null);
        }

        @Override
        public void onResume() {
            super.onResume();
            mResumed = true;
            refreshCalibration();
            mHandler.removeCallbacks(mFusionTick);
            mHandler.post(mFusionTick);
        }

        @Override
        public void onPause() {
            super.onPause();
            mResumed = false;
            mHandler.removeCallbacks(mFusionTick);
        }

        private void setFusion(boolean on) {
            // The property is the setting; FUSION ON/OFF applies it without
            // waiting for the daemon's next property read.
            FusionClient.setOn(on);
            sendFusion(on ? "FUSION ON" : "FUSION OFF", 0);
            mFusionState.setSummary(FusionClient.stateText(requireContext(),
                    FusionClient.cache()));
        }

        private void refreshFusion() {
            if (mFusionFetching || mFusionSwitch == null) {
                return;
            }
            mFusionFetching = true;
            mFusionSwitch.setChecked(FusionClient.isOn());
            new Thread(() -> {
                final FusionClient.Status status = FusionClient.query();
                mHandler.post(() -> {
                    mFusionFetching = false;
                    if (!mResumed || mFusionState == null) {
                        return;
                    }
                    mFusionSwitch.setChecked(FusionClient.isOn());
                    mFusionState.setSummary(FusionClient.stateText(requireContext(), status));
                    showPads(status);
                });
            }, "ashfall-fusion-status").start();
        }

        /** Rebuilds the connected-pad rows, but only when the list changed. */
        private void showPads(FusionClient.Status status) {
            if (mFusionPads == null) {
                return;
            }
            final boolean available = status != null && status.available;
            final boolean fusionOn = FusionClient.isOn();
            final List<FusionClient.Pad> pads = available
                    ? status.pads : Collections.emptyList();
            final StringBuilder signature = new StringBuilder(available ? "ok" : "down");
            signature.append('|').append(fusionOn);
            if (available) {
                signature.append('|').append(status.fusion);
            }
            for (FusionClient.Pad pad : pads) {
                signature.append('|').append(pad.key).append(':')
                        .append(pad.layout).append(':').append(pad.name);
            }
            if (signature.toString().equals(mPadSignature)) {
                return;
            }
            mPadSignature = signature.toString();

            mFusionPads.removeAll();
            if (pads.isEmpty()) {
                final Preference none = new Preference(requireContext());
                none.setKey("fusion_pads_none");
                none.setSelectable(false);
                int resId = FusionClient.padListMessage(status, fusionOn);
                none.setSummary(resId);
                mFusionPads.addPreference(none);
                return;
            }
            for (FusionClient.Pad pad : pads) {
                final Preference row = new Preference(requireContext());
                row.setKey("fusion_pad_" + pad.key);
                row.setTitle(pad.name);
                row.setIcon(R.drawable.ic_gamepad);
                row.setSummary(getString(R.string.fusion_pad_summary,
                        FusionClient.modeName(requireContext(), pad.layout)));
                row.setOnPreferenceClickListener(preference -> {
                    showLayoutDialog(pad);
                    return true;
                });
                mFusionPads.addPreference(row);
            }
        }

        /** Default / Xbox / Nintendo / swap A-B / swap X-Y / Custom. */
        private void showLayoutDialog(FusionClient.Pad pad) {
            final String[] labels = new String[FusionClient.MODES.length];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = getString(FusionClient.MODE_LABELS[i]);
            }
            // The daemon's reply is the truth; the property only covers the
            // moment before the first status arrives.
            final String current = pad.layout != null
                    ? pad.layout : FusionClient.layoutFor(pad.key);
            new AlertDialog.Builder(requireContext())
                    .setTitle(pad.name)
                    .setSingleChoiceItems(labels, FusionClient.modeIndex(current),
                            (dialog, which) -> {
                                dialog.dismiss();
                                applyLayout(pad, FusionClient.MODES[which]);
                            })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void applyLayout(FusionClient.Pad pad, String mode) {
            if (FusionClient.MODE_CUSTOM.equals(mode)) {
                // v1: the raw from->to code list (record-a-button is a
                // follow-up, docs/phase6.md §9.7).  Saving the map is what
                // makes the pad Custom, so cancelling changes nothing.
                showRemapDialog(pad);
            } else {
                FusionClient.setLayoutFor(pad.key, mode);
                sendFusion("FUSION LAYOUT " + pad.key + " " + mode,
                        R.string.fusion_layout_saved);
            }
            refreshFusion();
        }

        private void showRemapDialog(FusionClient.Pad pad) {
            final EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setHint(R.string.fusion_remap_hint);
            input.setText(FusionClient.remapFor(pad.key));
            input.setSelection(input.getText().length());

            final FrameLayout container = new FrameLayout(requireContext());
            final int padding = Ui.dp(requireContext(), 20);
            container.setPadding(padding, Ui.dp(requireContext(), 8), padding, 0);
            container.addView(input, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.fusion_remap_title)
                    .setMessage(R.string.fusion_remap_body)
                    .setView(container)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save, null)
                    .create();
            // An invalid list must keep the field and the dialog: the built-in
            // positive button would dismiss and silently drop the edit.
            dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        final String map = input.getText().toString().trim();
                        if (!map.isEmpty() && !FusionClient.isValidMap(map)) {
                            Toast.makeText(requireContext(), R.string.fusion_remap_invalid,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        dialog.dismiss();
                        saveRemap(pad, map);
                    }));
            dialog.show();
        }

        private void saveRemap(FusionClient.Pad pad, String text) {
            final String map = text == null ? "" : text.trim();
            FusionClient.setRemapFor(pad.key, map);
            FusionClient.setLayoutFor(pad.key, FusionClient.MODE_CUSTOM);
            if (map.isEmpty()) {
                Toast.makeText(requireContext(), R.string.fusion_remap_empty,
                        Toast.LENGTH_LONG).show();
                sendFusion("FUSION LAYOUT " + pad.key + " " + FusionClient.MODE_CUSTOM, 0);
            } else {
                sendFusion("FUSION REMAP " + pad.key + " " + map,
                        R.string.fusion_remap_saved);
            }
            refreshFusion();
        }

        /** One write command; a daemon that is gone is not an error path. */
        private void sendFusion(String command, int doneMessage) {
            new Thread(() -> {
                String error = null;
                FusionClient client = null;
                try {
                    client = FusionClient.open();
                    client.command(command);
                } catch (Exception e) {
                    error = e.getMessage();
                } finally {
                    FusionClient.closeQuietly(client);
                }
                final boolean failed = error != null;
                mHandler.post(() -> {
                    if (!mResumed || getContext() == null) {
                        return;
                    }
                    if (failed) {
                        Toast.makeText(requireContext(), R.string.not_connected,
                                Toast.LENGTH_SHORT).show();
                    } else if (doneMessage != 0) {
                        Toast.makeText(requireContext(), doneMessage,
                                Toast.LENGTH_SHORT).show();
                    }
                    refreshFusion();
                });
            }, "ashfall-fusion-command").start();
        }

        private void refreshCalibration() {
            mCalibrationRow.setSummary(R.string.controller_calibration_missing);
            new Thread(() -> {
                String source = null;
                CalClient client = null;
                try {
                    client = CalClient.open();
                    final CalData data = client.getCal();
                    source = data.source();
                } catch (Exception ignored) {
                    // Reported below.
                } finally {
                    CalClient.closeQuietly(client);
                }
                final String result = source;
                mHandler.post(() -> {
                    if (!mResumed || mCalibrationRow == null) {
                        return;
                    }
                    mCalibrationRow.setSummary(result == null
                            ? getString(R.string.controller_calibration_missing)
                            : getString(R.string.controller_calibration_summary,
                                    Ui.calibrationSourceName(requireContext(), result)));
                });
            }, "ashfall-cal-source").start();
        }

        private void confirmReset() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.controller_reset_cal_title)
                    .setMessage(R.string.controller_reset_cal_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.reset, (dialog, which) -> reset())
                    .show();
        }

        private void reset() {
            new Thread(() -> {
                String error = null;
                CalClient client = null;
                try {
                    client = CalClient.open();
                    client.reset();
                } catch (Exception e) {
                    error = e.getMessage();
                } finally {
                    CalClient.closeQuietly(client);
                }
                final String message = error;
                mHandler.post(() -> {
                    if (getContext() == null) {
                        return;
                    }
                    Toast.makeText(requireContext(), message == null
                            ? R.string.controller_cal_reset_done
                            : R.string.not_connected, Toast.LENGTH_SHORT).show();
                    refreshCalibration();
                });
            }, "ashfall-cal-reset").start();
        }
    }
}
