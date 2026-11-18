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

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Led;
import org.ashfall.parts.ui.ColorPickerDialog;
import org.ashfall.parts.ui.ColorPreference;
import org.ashfall.parts.ui.MainSwitchPreference;
import org.ashfall.parts.ui.ScreenLedSync;
import org.ashfall.parts.ui.SliderPreference;
import org.ashfall.parts.ui.Ui;

/** Lighting page: main switch, target, colour, brightness, effect, speed. */
public class LightingActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.lighting_title;
    }

    @Override
    protected Fragment createFragment() {
        return new LightingFragment();
    }

    public static class LightingFragment extends PreferenceFragmentCompat {

        private static final String[] EFFECT_VALUES = {
                Led.MODE_STATIC, Led.MODE_BREATHING, Led.MODE_RAINBOW, Led.MODE_DUO,
                Led.MODE_SCREEN,
        };

        private MainSwitchPreference mSwitch;
        private Preference mTarget;
        private ColorPreference mColour;
        private SliderPreference mBrightness;
        private Preference mEffect;
        private SliderPreference mSpeed;
        private Preference mScreenRate;
        private Preference mScreenDelay;
        private Preference mScreenNote;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            mSwitch = new MainSwitchPreference(requireContext());
            mSwitch.setKey("lighting_switch");
            mSwitch.setTitle(R.string.lighting_switch_title);
            mSwitch.setCheckedChangeListener((preference, checked) -> onSwitch(checked));
            screen.addPreference(mSwitch);

            final PreferenceCategory appearance = new PreferenceCategory(requireContext());
            appearance.setTitle(R.string.lighting_title);
            screen.addPreference(appearance);

            mTarget = new Preference(requireContext());
            mTarget.setKey("lighting_target");
            mTarget.setTitle(R.string.lighting_target_title);
            mTarget.setOnPreferenceClickListener(preference -> {
                showTargetDialog();
                return true;
            });
            appearance.addPreference(mTarget);

            mColour = new ColorPreference(requireContext());
            mColour.setKey("lighting_colour");
            mColour.setTitle(R.string.lighting_colour_title);
            mColour.setIcon(R.drawable.ic_ashfall_lighting);
            mColour.setOnPreferenceClickListener(preference -> {
                final int target = Prefs.ledTarget(requireContext());
                new ColorPickerDialog(requireContext(), mColour.getColor(),
                        color -> {
                            // Live: every hue/square/swatch change is applied
                            // immediately, there is no Set step.
                            Led.setColor(target, Led.argbToColor(color));
                            refresh();
                        }).show();
                return true;
            });
            appearance.addPreference(mColour);

            mBrightness = new SliderPreference(requireContext());
            mBrightness.setKey("lighting_brightness");
            mBrightness.setTitle(R.string.lighting_brightness_title);
            mBrightness.setRange(0, 100);
            mBrightness.setValue(Led.brightness());
            mBrightness.setValueFormat(getString(R.string.percent_value_format));
            mBrightness.setValueChangedListener((preference, value) -> {
                Led.setBrightness(value);
                refresh();
            });
            appearance.addPreference(mBrightness);

            final PreferenceCategory effectCategory = new PreferenceCategory(requireContext());
            effectCategory.setTitle(R.string.lighting_effect_title);
            screen.addPreference(effectCategory);

            mEffect = new Preference(requireContext());
            mEffect.setKey("lighting_effect");
            mEffect.setTitle(R.string.lighting_effect_title);
            mEffect.setOnPreferenceClickListener(preference -> {
                showEffectDialog();
                return true;
            });
            effectCategory.addPreference(mEffect);

            mSpeed = new SliderPreference(requireContext());
            mSpeed.setKey("lighting_speed");
            mSpeed.setTitle(R.string.lighting_speed_title);
            mSpeed.setRange(1, 10);
            mSpeed.setValue(Led.speed());
            mSpeed.setValueFormat(getString(R.string.lighting_speed_summary_format));
            mSpeed.setValueChangedListener((preference, value) -> {
                Led.setSpeed(value);
                refresh();
            });
            effectCategory.addPreference(mSpeed);

            // Match screen (#167): capture rate plus the short limits note.
            mScreenRate = new Preference(requireContext());
            mScreenRate.setKey("lighting_screen_rate");
            mScreenRate.setTitle(R.string.lighting_screen_rate_title);
            mScreenRate.setOnPreferenceClickListener(preference -> {
                showRateDialog();
                return true;
            });
            effectCategory.addPreference(mScreenRate);

            mScreenDelay = new Preference(requireContext());
            mScreenDelay.setKey("lighting_screen_delay");
            mScreenDelay.setTitle(R.string.lighting_screen_delay_title);
            mScreenDelay.setOnPreferenceClickListener(preference -> {
                showDelayDialog();
                return true;
            });
            effectCategory.addPreference(mScreenDelay);

            mScreenNote = new Preference(requireContext());
            mScreenNote.setKey("lighting_screen_note");
            mScreenNote.setSelectable(false);
            mScreenNote.setSummary(R.string.lighting_screen_note);
            effectCategory.addPreference(mScreenNote);

            final Preference footer = new Preference(requireContext());
            footer.setKey("lighting_footer");
            footer.setSelectable(false);
            footer.setSummary(R.string.footer_lighting);
            screen.addPreference(footer);

            setPreferenceScreen(screen);
            refresh();
        }

        @Override
        public void onResume() {
            super.onResume();
            // Match screen keeps its effect across reboots; the service is only
            // ever started from here (a user-visible page), never at boot.
            LedSyncService.sync(requireContext());
            refresh();
        }

        private void onSwitch(boolean checked) {
            if (checked) {
                final String last = Prefs.lastLedMode(requireContext());
                Led.setMode(Led.MODE_OFF.equals(last) ? Led.MODE_STATIC : last);
            } else {
                Prefs.setLastLedMode(requireContext(), Led.mode());
                Led.setMode(Led.MODE_OFF);
            }
            LedSyncService.sync(requireContext());
            refresh();
        }

        /** Platform single-choice dialog (Settings' dialog theme, pure black aware). */
        private void showTargetDialog() {
            final CharSequence[] labels = {
                    getString(R.string.lighting_target_both),
                    getString(R.string.lighting_target_left),
                    getString(R.string.lighting_target_right),
            };
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.lighting_target_title)
                    .setSingleChoiceItems(labels, Prefs.ledTarget(requireContext()),
                            (dialog, which) -> {
                                Prefs.setLedTarget(requireContext(), which);
                                dialog.dismiss();
                                refresh();
                            })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        /** Platform single-choice dialog for the effect (no AppCompat dialog). */
        private void showEffectDialog() {
            final CharSequence[] labels = {
                    getString(R.string.lighting_effect_static),
                    getString(R.string.lighting_effect_breathing),
                    getString(R.string.lighting_effect_rainbow),
                    getString(R.string.lighting_effect_duo),
                    getString(R.string.lighting_effect_screen),
            };
            final String current = Led.isOn() ? Led.effect()
                    : Prefs.lastLedMode(requireContext());
            int checked = 0;
            for (int i = 0; i < EFFECT_VALUES.length; i++) {
                if (EFFECT_VALUES[i].equals(current)) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.lighting_effect_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        final String mode = EFFECT_VALUES[which];
                        // "Match screen" is a UI value: it leaves the last real
                        // effect in led.mode, so clearing it restores that.
                        if (!Led.MODE_SCREEN.equals(mode)) {
                            Prefs.setLastLedMode(requireContext(), mode);
                        } else if (!Led.isOn()) {
                            // Selecting an effect while off turns lighting on;
                            // keep the last real effect behind Match screen.
                            final String last = Prefs.lastLedMode(requireContext());
                            Led.setMode(Led.MODE_OFF.equals(last) ? Led.MODE_STATIC : last);
                        }
                        Led.setMode(mode);
                        LedSyncService.sync(requireContext());
                        dialog.dismiss();
                        refresh();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void showRateDialog() {
            final int[] rates = Led.SCREEN_RATES;
            final CharSequence[] labels = new CharSequence[rates.length];
            int checked = 0;
            for (int i = 0; i < rates.length; i++) {
                labels[i] = getString(R.string.lighting_screen_rate_value, rates[i]);
                if (rates[i] == Led.screenRate()) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.lighting_screen_rate_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        Led.setScreenRate(rates[which]);
                        dialog.dismiss();
                        refresh();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void showDelayDialog() {
            final int[] delays = ScreenLedSync.SCREEN_DELAYS_MS;
            final CharSequence[] labels = new CharSequence[delays.length];
            int checked = 0;
            for (int i = 0; i < delays.length; i++) {
                labels[i] = delayLabel(delays[i]);
                if (delays[i] == ScreenLedSync.screenDelay()) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.lighting_screen_delay_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        ScreenLedSync.setScreenDelay(delays[which]);
                        dialog.dismiss();
                        refresh();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private String delayLabel(int ms) {
            return ms <= 0 ? getString(R.string.lighting_screen_delay_off)
                    : getString(R.string.lighting_screen_delay_value, ms);
        }

        private String effectName(String effect) {
            return Led.MODE_SCREEN.equals(effect)
                    ? getString(R.string.lighting_effect_screen)
                    : Ui.ledModeName(requireContext(), effect);
        }

        private void refresh() {
            if (mSwitch == null) {
                return;
            }
            final boolean on = Led.isOn();
            final boolean screen = Led.screenSync();
            mSwitch.setChecked(on);
            mSwitch.setSummaryText(on
                    ? getString(R.string.lighting_switch_summary_on,
                            effectName(Led.effect()), Led.brightness())
                    : getString(R.string.lighting_switch_summary_off));

            final int target = Prefs.ledTarget(requireContext());
            final String targetName = target == Led.TARGET_RIGHT
                    ? getString(R.string.lighting_target_right)
                    : target == Led.TARGET_LEFT
                            ? getString(R.string.lighting_target_left)
                            : getString(R.string.lighting_target_both);
            mTarget.setSummary(getString(R.string.lighting_target_summary, targetName,
                    Ui.hex(Led.colorFor(target))));

            mColour.setColor(Led.colorToArgb(Led.colorFor(target)));
            mColour.setSummary(getString(R.string.lighting_colour_summary,
                    Ui.hex(Led.colorLeft()), Ui.hex(Led.colorRight())));

            mBrightness.setValue(Led.brightness());

            final String effect = on ? Led.effect() : Prefs.lastLedMode(requireContext());
            mEffect.setSummary(effectName(effect));

            mSpeed.setTitle(R.string.lighting_speed_title);
            mSpeed.setValue(Led.speed());
            // Match screen is a literal per-half average with an optional
            // delay, so led.speed no longer drives it.  Breathing, rainbow and
            // duo keep the speed slider (duo is animated with led.speed too,
            // 5000/speed, #100).
            final boolean animated = Led.MODE_BREATHING.equals(effect)
                    || Led.MODE_RAINBOW.equals(effect)
                    || Led.MODE_DUO.equals(effect);
            mSpeed.setEnabled(animated);

            mScreenRate.setVisible(screen);
            mScreenRate.setSummary(getString(R.string.lighting_screen_rate_value,
                    Led.screenRate()));
            mScreenDelay.setVisible(screen);
            mScreenDelay.setSummary(delayLabel(ScreenLedSync.screenDelay()));
            mScreenNote.setVisible(screen);
        }
    }
}
