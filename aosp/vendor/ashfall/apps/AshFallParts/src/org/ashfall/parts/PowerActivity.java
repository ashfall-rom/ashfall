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

import androidx.fragment.app.Fragment;

/**
 * Power page (#124): CPU clock caps, governor, GPU cap, throttling override,
 * presets and the live power/clock graph.  The page is
 * {@link PowerFragment}; this activity is the shell every AshFall page shares
 * (collapsing toolbar, insets, title).
 */
public class PowerActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.power_title;
    }

    @Override
    protected Fragment createFragment() {
        return new PowerFragment();
    }
}
