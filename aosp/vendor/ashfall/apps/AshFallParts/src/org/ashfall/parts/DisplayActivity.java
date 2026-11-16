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
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.ashfall.parts.hw.Desktop;
import org.ashfall.parts.qs.DesktopTileService;
import org.ashfall.parts.ui.MainSwitchPreference;

/**
 * Display page (#131 F2): the internal-display desktop mode switch.
 *
 * <p>The switch mirrors the real windowing mode of display 0, not just
 * {@code persist.ashfall.pad.mode}, so a mode change made outside this page is
 * reflected here within one tick.  Turning it on applies the desktop density,
 * switches the display and then writes the pad mode; turning it off reverses
 * that order ({@link Desktop}).
 *
 * <p>The copy is deliberately short and user-facing: internal screen only, the
 * taskbar appears in desktop mode, and apps restart while the density changes.
 */
public class DisplayActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.display_title;
    }

    @Override
    protected Fragment createFragment() {
        return new DisplayFragment();
    }

    public static class DisplayFragment extends PreferenceFragmentCompat {

        /** Catch a chord or a Settings/dev-option switch while the page is open. */
        private static final long TICK_MS = 1500;

        private MainSwitchPreference mSwitch;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                refresh();
                mHandler.postDelayed(this, TICK_MS);
            }
        };

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                    requireContext());

            mSwitch = new MainSwitchPreference(requireContext());
            mSwitch.setKey("display_desktop");
            mSwitch.setTitle(R.string.display_desktop_title);
            mSwitch.setCheckedChangeListener((preference, checked) -> onSwitch(checked));
            screen.addPreference(mSwitch);

            final PreferenceCategory about = new PreferenceCategory(requireContext());
            about.setTitle(R.string.display_about_title);
            screen.addPreference(about);

            final Preference limits = new Preference(requireContext());
            limits.setKey("display_limits");
            limits.setSelectable(false);
            limits.setSummary(R.string.display_limits_note);
            about.addPreference(limits);

            final Preference chord = new Preference(requireContext());
            chord.setKey("display_chord");
            chord.setSelectable(false);
            chord.setSummary(R.string.display_chord_note);
            about.addPreference(chord);

            setPreferenceScreen(screen);
            refresh();
        }

        @Override
        public void onResume() {
            super.onResume();
            refresh();
            mHandler.removeCallbacks(mTick);
            mHandler.postDelayed(mTick, TICK_MS);
        }

        @Override
        public void onPause() {
            super.onPause();
            mHandler.removeCallbacks(mTick);
        }

        private void onSwitch(boolean checked) {
            final boolean applied = checked ? Desktop.enter() : Desktop.exit();
            if (!applied) {
                Toast.makeText(requireContext(), R.string.display_desktop_failed,
                        Toast.LENGTH_SHORT).show();
            }
            DesktopTileService.requestUpdate(requireContext());
            refresh();
        }

        private void refresh() {
            if (mSwitch == null) {
                return;
            }
            final int mode = Desktop.windowingMode();
            final boolean known = mode >= 0;
            final boolean desktop = mode == Desktop.WINDOWING_MODE_FREEFORM;
            mSwitch.setChecked(desktop);
            mSwitch.setEnabled(known);
            mSwitch.setSummaryText(getString(known
                    ? (desktop ? R.string.display_desktop_summary_on
                            : R.string.display_desktop_summary_off)
                    : R.string.display_desktop_summary_unknown));
        }
    }
}
