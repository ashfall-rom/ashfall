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
 * Memory page (#141): zram and swapfile.  The page itself is owned by the #141
 * app worker; this shell only registers it.
 */
public class MemoryActivity extends PartsActivity {

    @Override
    protected int getPageTitle() {
        return R.string.memory_title;
    }

    @Override
    protected Fragment createFragment() {
        return new MemoryFragment();
    }
}
