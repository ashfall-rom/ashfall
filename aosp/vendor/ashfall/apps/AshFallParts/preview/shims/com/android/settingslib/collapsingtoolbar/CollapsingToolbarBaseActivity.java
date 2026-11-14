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

// PREVIEW ONLY.  Inside the AOSP tree the app extends
// com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity, which
// is linked from SettingsLib.  This file only exists so the same app sources can
// be built with Gradle for the SDK emulator, and reproduces that activity's
// public surface (collapsing app bar + content frame) with Material components.

package com.android.settingslib.collapsingtoolbar;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.appbar.CollapsingToolbarLayout;

import org.ashfall.parts.R;

public class CollapsingToolbarBaseActivity extends AppCompatActivity {

    private CollapsingToolbarLayout mCollapsing;
    private Toolbar mToolbar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        super.setContentView(R.layout.preview_collapsing_toolbar);
        mCollapsing = findViewById(R.id.preview_collapsing);
        mToolbar = findViewById(R.id.preview_toolbar);
        setSupportActionBar(mToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        mToolbar.setNavigationOnClickListener(view -> onBackPressed());
    }

    private ViewGroup contentFrame() {
        return findViewById(R.id.content_frame);
    }

    @Override
    public void setContentView(int layoutResID) {
        final ViewGroup parent = contentFrame();
        if (parent == null) {
            super.setContentView(layoutResID);
            return;
        }
        parent.removeAllViews();
        LayoutInflater.from(this).inflate(layoutResID, parent);
    }

    @Override
    public void setContentView(View view) {
        final ViewGroup parent = contentFrame();
        if (parent == null) {
            super.setContentView(view);
            return;
        }
        parent.removeAllViews();
        parent.addView(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        final ViewGroup parent = contentFrame();
        if (parent == null) {
            super.setContentView(view, params);
            return;
        }
        parent.removeAllViews();
        parent.addView(view, params);
    }

    @Override
    public void setTitle(int titleId) {
        setTitle(getText(titleId));
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        if (mCollapsing != null) {
            mCollapsing.setTitle(title);
        }
    }
}
