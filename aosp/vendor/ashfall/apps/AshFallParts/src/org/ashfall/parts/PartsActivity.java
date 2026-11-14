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

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/**
 * Base class of every AshFall Parts page.
 *
 * <p>It is a {@link CollapsingToolbarBaseActivity}, the same base class the
 * platform's own Settings sub-pages use, so the collapsing app bar, the back
 * arrow, the insets and the theme all come from SettingsLib rather than from
 * this app.
 */
public abstract class PartsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getContentLayout());
        setTitle(getPageTitle());
        final Fragment fragment = createFragment();
        if (savedInstanceState == null && fragment != null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.parts_fragment, fragment)
                    .commitNow();
        }
    }

    /** Content layout; pages with a fragment use the default. */
    protected int getContentLayout() {
        return R.layout.parts_content;
    }

    /** Title shown in the collapsing toolbar. */
    protected abstract int getPageTitle();

    /** The single fragment hosted by the page, or {@code null} for custom content. */
    @Nullable
    protected Fragment createFragment() {
        return null;
    }
}
