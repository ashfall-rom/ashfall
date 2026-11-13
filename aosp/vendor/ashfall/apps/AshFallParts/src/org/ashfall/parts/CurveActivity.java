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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import org.ashfall.parts.hw.Curve;
import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.CurveEditorView;

/**
 * Curve editor page.  Changes are saved to the preset (and, when the edited
 * preset is the active Smart preset, to {@code persist.ashfall.fan.curve})
 * immediately, so leaving the page with Back can never lose an edit.
 */
public class CurveActivity extends PartsActivity {

    public static final String EXTRA_PRESET_ID = "org.ashfall.parts.extra.PRESET_ID";

    private PresetStore mStore;
    private Preset mPreset;
    private CurveEditorView mEditor;
    private TextView mInfo;
    private TextView mLive;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mEditor != null) {
                final int temperature = Fan.temperatureCelsius();
                mEditor.setTemperature(temperature);
                updateLive(temperature);
            }
            mHandler.postDelayed(this, 2000);
        }
    };

    public static Intent intent(Context context, String presetId) {
        final Intent intent = new Intent(context, CurveActivity.class);
        intent.putExtra(EXTRA_PRESET_ID, presetId);
        return intent;
    }

    @Override
    protected int getPageTitle() {
        return R.string.curve_title;
    }

    @Override
    protected int getContentLayout() {
        return R.layout.activity_curve;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStore = PresetStore.get(this);
        final String id = getIntent().getStringExtra(EXTRA_PRESET_ID);
        mPreset = id != null ? mStore.byId(id) : null;
        if (mPreset == null) {
            mPreset = mStore.byId(PresetStore.ID_SMART);
        }
        if (mPreset == null) {
            finish();
            return;
        }

        mEditor = findViewById(R.id.curve_editor);
        mInfo = findViewById(R.id.curve_info);
        mLive = findViewById(R.id.curve_live);
        mEditor.setListener(new CurveEditorView.Listener() {
            @Override
            public void onCurveChanged(Curve curve) {
                save(curve);
            }

            @Override
            public void onSelectionChanged(int index, int temp, int pct) {
                mInfo.setText(getString(R.string.curve_selected, temp, pct));
            }
        });
        mEditor.setCurve(mPreset.parsedCurve(), false);

        final Button add = findViewById(R.id.curve_add);
        final Button delete = findViewById(R.id.curve_delete);
        final Button reset = findViewById(R.id.curve_reset);
        add.setOnClickListener(view -> mEditor.addPoint());
        delete.setOnClickListener(view -> mEditor.removeSelected());
        reset.setOnClickListener(view -> mEditor.reset());

        updateLive(Fan.temperatureCelsius());
        mEditor.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    private void updateLive(int temperature) {
        if (mLive == null) {
            return;
        }
        if (temperature < 0) {
            mLive.setText("");
            return;
        }
        final int percent = mEditor.getCurve().percentFor(temperature);
        mLive.setText(getString(R.string.curve_live_marker, temperature, percent));
    }

    private void save(Curve curve) {
        mPreset.curve = curve.format();
        mStore.save(mPreset);
        if (mPreset.id.equals(Fan.presetId())) {
            Fan.setCurve(mPreset.curve);
        }
        updateLive(Fan.temperatureCelsius());
    }
}
