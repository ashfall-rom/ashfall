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

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Pad;
import org.ashfall.parts.ipc.CalClient;
import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.Ui;

/** Controller page: tester, calibration wizard and deadzones. */
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

        private Preference mCalibrationRow;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private volatile boolean mResumed;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

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

        @Override
        public void onResume() {
            super.onResume();
            mResumed = true;
            refreshCalibration();
        }

        @Override
        public void onPause() {
            super.onPause();
            mResumed = false;
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
