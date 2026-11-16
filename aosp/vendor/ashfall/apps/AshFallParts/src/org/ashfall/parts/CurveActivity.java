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
import android.widget.SeekBar;
import android.widget.TextView;

import org.ashfall.parts.hw.Curve;
import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.hw.Stress;
import org.ashfall.parts.ui.CurveEditorView;
import org.ashfall.parts.ui.Ui;

/**
 * Curve editor page.  Changes are saved to the preset (and, when the edited
 * preset is the active Smart preset, previewed to {@code
 * ashfall.ui.fan_preview_curve} and to {@code persist.ashfall.fan.curve})
 * immediately, so leaving the page with Back can never lose an edit.
 *
 * <p>While the page is visible it asks the daemon for the fast status cadence
 * ({@code ashfall.ui.fan_live}).  The stress-test sliders at the bottom apply
 * load immediately and are always stopped when the page leaves the foreground.
 */
public class CurveActivity extends PartsActivity {

    public static final String EXTRA_PRESET_ID = "org.ashfall.parts.extra.PRESET_ID";

    /**
     * Fan UI refresh period.  It is also the cadence of the
     * {@code ashfall.ui.fan_live} heartbeat: while the editor publishes a
     * preview curve, the daemon honours it only while this tick keeps writing
     * the property (1.5 s timeout, issue #94).  Do not lengthen it past that
     * timeout.
     */
    private static final long LIVE_TICK_MS = 500;

    private PresetStore mStore;
    private Preset mPreset;
    private CurveEditorView mEditor;
    private TextView mInfo;
    private TextView mLive;
    private TextView mStressCpuValue;
    private TextView mStressGpuValue;
    private TextView mStressStatus;
    private SeekBar mStressCpu;
    private SeekBar mStressGpu;
    private boolean mUpdatingStress;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            Fan.refreshLive();
            if (mEditor != null) {
                final int temperature = Fan.temperatureCelsius();
                mEditor.setTemperature(temperature);
                updateLive(temperature);
            }
            if (mStressStatus != null) {
                // The engine's ten-minute cap can stop the load without a
                // slider change; reflect that (and zero the sliders so the
                // next visit does not restart at a stale level).
                if (!Stress.get().isRunning()
                        && (mStressCpu.getProgress() > 0 || mStressGpu.getProgress() > 0)) {
                    resetStressSliders();
                }
                updateStressStatus();
            }
            mHandler.postDelayed(this, LIVE_TICK_MS);
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

        bindStress();
        updateLive(Fan.temperatureCelsius());
        // The window/toolbar can take focus during the first layout pass on
        // A17, which would make the first D-pad press move focus instead of a
        // curve point.  Request again once the view is attached.
        mEditor.requestFocus();
        mEditor.post(mEditor::requestFocus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Fan.setLive(true);
        publishPreview();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
        // Leaving the editor stops the load and clears the live preview, so the
        // daemon goes back to the saved curve immediately.
        stopStress();
        Fan.clearPreviewCurve();
        Fan.setLive(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A crash/process-free path may skip onPause; never leave load or a
        // preview curve behind.
        Stress.get().stop();
        Fan.clearPreviewCurve();
    }

    private void bindStress() {
        mStressCpu = findViewById(R.id.stress_cpu);
        mStressGpu = findViewById(R.id.stress_gpu);
        mStressCpuValue = findViewById(R.id.stress_cpu_value);
        mStressGpuValue = findViewById(R.id.stress_gpu_value);
        mStressStatus = findViewById(R.id.stress_status);

        // A fresh visit always starts idle; the load is never resumed silently.
        mStressCpu.setProgress(0);
        mStressGpu.setProgress(0);
        final SeekBar.OnSeekBarChangeListener listener =
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser || mUpdatingStress) {
                            return;
                        }
                        applyStress();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                };
        mStressCpu.setOnSeekBarChangeListener(listener);
        mStressGpu.setOnSeekBarChangeListener(listener);
        updateStressLabels();
    }

    private void applyStress() {
        final int cpu = mStressCpu.getProgress();
        final int gpu = mStressGpu.getProgress();
        Stress.get().setLevels(cpu, gpu);
        updateStressLabels();
    }

    private void stopStress() {
        Stress.get().stop();
        resetStressSliders();
    }

    private void resetStressSliders() {
        if (mStressCpu == null || mStressGpu == null) {
            return;
        }
        mUpdatingStress = true;
        try {
            mStressCpu.setProgress(0);
            mStressGpu.setProgress(0);
        } finally {
            mUpdatingStress = false;
        }
        updateStressLabels();
    }

    private void updateStressLabels() {
        if (mStressCpuValue == null) {
            return;
        }
        mStressCpuValue.setText(getString(R.string.percent_value, mStressCpu.getProgress()));
        mStressGpuValue.setText(getString(R.string.percent_value, mStressGpu.getProgress()));
        updateStressStatus();
    }

    private void updateStressStatus() {
        if (mStressStatus == null) {
            return;
        }
        final Stress stress = Stress.get();
        if (stress.isRunning()) {
            if (mStressGpu.getProgress() > 0 && !stress.gpuAvailable()) {
                mStressStatus.setText(R.string.curve_stress_gpu_unavailable);
            } else {
                mStressStatus.setText(getString(R.string.curve_stress_running,
                        Ui.countdown(stress.remainingMs())));
            }
        } else {
            mStressStatus.setText(R.string.curve_stress_hint);
        }
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
        // The daemon follows persist.ashfall.fan.curve, not the preset JSON.
        // The active preset is resolved from the recorded id with the mode
        // fallback, so editing Smart on a fresh install (empty preset id) or
        // after the recorded preset was deleted still reaches the daemon.
        final Preset active = Fan.activePreset(mStore);
        if (active != null && active.id.equals(mPreset.id)
                && Preset.MODE_SMART.equals(Fan.mode())) {
            Fan.setCurve(mPreset.curve);
            // Also preview it live; this is what the daemon follows while the
            // editor is open, even before the saved value is flushed.
            Fan.setPreviewCurve(mPreset.curve);
        }
        updateLive(Fan.temperatureCelsius());
    }

    /** Re-publishes the preview after a resume (the property is not sticky). */
    private void publishPreview() {
        if (mPreset == null) {
            return;
        }
        final Preset active = Fan.activePreset(mStore);
        if (active != null && active.id.equals(mPreset.id)
                && Preset.MODE_SMART.equals(Fan.mode())) {
            Fan.setPreviewCurve(mPreset.curve);
        }
    }
}
