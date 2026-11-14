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

import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Led;
import org.ashfall.parts.ui.ColorPickerDialog;
import org.ashfall.parts.ui.ColorPreference;
import org.ashfall.parts.ui.MainSwitchPreference;
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
        };

        private MainSwitchPreference mSwitch;
        private ListPreference mTarget;
        private ColorPreference mColour;
        private SliderPreference mBrightness;
        private ListPreference mEffect;
        private SliderPreference mSpeed;

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

            mTarget = new ListPreference(requireContext());
            mTarget.setKey("lighting_target");
            mTarget.setPersistent(false);
            mTarget.setTitle(R.string.lighting_target_title);
            mTarget.setEntries(new CharSequence[] {
                    getString(R.string.lighting_target_both),
                    getString(R.string.lighting_target_left),
                    getString(R.string.lighting_target_right),
            });
            mTarget.setEntryValues(new CharSequence[] {"0", "1", "2"});
            mTarget.setValue(Integer.toString(Prefs.ledTarget(requireContext())));
            mTarget.setOnPreferenceChangeListener((preference, newValue) -> {
                final int target = Integer.parseInt((String) newValue);
                Prefs.setLedTarget(requireContext(), target);
                refresh();
                return true;
            });
            appearance.addPreference(mTarget);

            mColour = new ColorPreference(requireContext());
            mColour.setKey("lighting_colour");
            mColour.setTitle(R.string.lighting_colour_title);
            mColour.setIcon(R.drawable.ic_led);
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

            mEffect = new ListPreference(requireContext());
            mEffect.setKey("lighting_effect");
            mEffect.setPersistent(false);
            mEffect.setTitle(R.string.lighting_effect_title);
            mEffect.setEntries(new CharSequence[] {
                    getString(R.string.lighting_effect_static),
                    getString(R.string.lighting_effect_breathing),
                    getString(R.string.lighting_effect_rainbow),
                    getString(R.string.lighting_effect_duo),
            });
            mEffect.setEntryValues(EFFECT_VALUES);
            mEffect.setValue(Led.isOn() ? Led.mode() : Prefs.lastLedMode(requireContext()));
            mEffect.setOnPreferenceChangeListener((preference, newValue) -> {
                final String mode = (String) newValue;
                Prefs.setLastLedMode(requireContext(), mode);
                Led.setMode(mode);
                refresh();
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
            refresh();
        }

        private void refresh() {
            if (mSwitch == null) {
                return;
            }
            final boolean on = Led.isOn();
            mSwitch.setChecked(on);
            mSwitch.setSummaryText(on
                    ? getString(R.string.lighting_switch_summary_on,
                            Ui.ledModeName(requireContext(), Led.mode()), Led.brightness())
                    : getString(R.string.lighting_switch_summary_off));

            final int target = Prefs.ledTarget(requireContext());
            mTarget.setValue(Integer.toString(target));
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

            final String effect = on ? Led.mode() : Prefs.lastLedMode(requireContext());
            mEffect.setValue(effect);
            mEffect.setSummary(Ui.ledModeName(requireContext(), effect));

            mSpeed.setValue(Led.speed());
            final boolean animated = Led.MODE_BREATHING.equals(effect)
                    || Led.MODE_RAINBOW.equals(effect);
            mSpeed.setEnabled(animated);
        }
    }
}
