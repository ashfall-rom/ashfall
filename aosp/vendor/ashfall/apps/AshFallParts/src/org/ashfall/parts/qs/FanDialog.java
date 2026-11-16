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

package org.ashfall.parts.qs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.ashfall.parts.Prefs;
import org.ashfall.parts.R;
import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.Ui;

/** The Fan Quick Settings dialog: a single-choice list of presets. */
public class FanDialog extends Dialog {

    public interface Listener {
        void onPresetChanged();

        void onEditPresets();
    }

    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private LinearLayout mList;
    private TextView mSubtitle;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            // Also the heartbeat that keeps a live preview curve valid: the
            // daemon expires ashfall.ui.fan_preview_curve after 1.5 s without
            // a fresh ashfall.ui.fan_live write (issue #94).
            Fan.refreshLive();
            updateSubtitle();
            mHandler.postDelayed(this, 500);
        }
    };

    public FanDialog(@NonNull Context context, Listener listener) {
        super(context, R.style.Theme_AshFall_QsDialog);
        mListener = listener;
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_fan);

        mList = findViewById(R.id.qs_fan_list);
        mSubtitle = findViewById(R.id.qs_fan_subtitle);

        findViewById(R.id.qs_fan_done).setOnClickListener(view -> dismiss());
        findViewById(R.id.qs_fan_edit).setOnClickListener(view -> {
            if (mListener != null) {
                mListener.onEditPresets();
            }
            dismiss();
        });

        rebuild();
        updateSubtitle();

        // Size only after the (runtime-built) preset rows exist, otherwise the
        // window is measured with an empty list and stays tiny (#98).
        final Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            Ui.fitDialog(window, findViewById(R.id.qs_fan_root),
                    findViewById(R.id.qs_fan_scroll), 0.60f);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Fan.setLive(true);
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, 500);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mTick);
        Fan.setLive(false);
    }

    private void rebuild() {
        if (mList == null) {
            return;
        }
        mList.removeAllViews();
        final PresetStore store = PresetStore.get(getContext());
        final Preset active = Fan.isOn()
                ? Fan.activePreset(store) : store.byId(PresetStore.ID_OFF);
        final String activeId = active == null ? null : active.id;
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        View first = null;
        for (Preset preset : store.all()) {
            final View row = inflater.inflate(R.layout.item_qs_preset, mList, false);
            final RadioButton radio = row.findViewById(R.id.qs_preset_radio);
            final TextView title = row.findViewById(R.id.qs_preset_title);
            final TextView summary = row.findViewById(R.id.qs_preset_summary);
            title.setText(Ui.presetName(getContext(), preset));
            summary.setText(Ui.presetModeSummary(getContext(), preset));
            radio.setChecked(preset.id.equals(activeId));
            row.setOnClickListener(view -> {
                Fan.apply(preset);
                Prefs.setLastPreset(getContext(), preset.id);
                if (mListener != null) {
                    mListener.onPresetChanged();
                }
                rebuild();
                updateSubtitle();
            });
            mList.addView(row);
            if (first == null) {
                first = row;
            }
        }
        if (first != null) {
            first.requestFocus();
        }
    }

    private void updateSubtitle() {
        if (mSubtitle == null) {
            return;
        }
        final PresetStore store = PresetStore.get(getContext());
        final Preset preset = Fan.isOn()
                ? Fan.activePreset(store) : store.byId(PresetStore.ID_OFF);
        final int temperature = Fan.temperatureCelsius();
        mSubtitle.setText(getContext().getString(R.string.tile_fan_dialog_subtitle,
                Ui.presetName(getContext(), preset), Math.max(0, temperature)));
    }
}
