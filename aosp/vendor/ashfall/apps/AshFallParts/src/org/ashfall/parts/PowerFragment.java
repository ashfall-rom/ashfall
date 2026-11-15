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

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

/** Placeholder content of the Power page; the #124 worker replaces this file. */
public class PowerFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final PreferenceScreen screen = getPreferenceManager()
                .createPreferenceScreen(requireContext());
        final Preference note = new Preference(requireContext());
        note.setKey("power_placeholder");
        note.setSelectable(false);
        note.setSummary(R.string.page_placeholder);
        screen.addPreference(note);
        setPreferenceScreen(screen);
    }
}
