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

import org.ashfall.parts.R;
import org.ashfall.parts.hw.Power;
import org.ashfall.parts.hw.PowerPreset;
import org.ashfall.parts.hw.PowerPresetStore;
import org.ashfall.parts.ui.Ui;

import java.util.Locale;

/**
 * The Power Quick Settings dialog (#124): a single-choice list of presets plus
 * "Edit presets", the same interaction model as the Fan dialog.
 */
public class PowerDialog extends Dialog {

    /** The daemon's live status period while a power UI is visible (#156 §2). */
    private static final long TICK_MS = 500;

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
            updateSubtitle();
            Power.refreshLive();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    public PowerDialog(@NonNull Context context, Listener listener) {
        super(context, R.style.Theme_AshFall_QsDialog);
        mListener = listener;
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.power_dialog);

        mList = findViewById(R.id.power_dialog_list);
        mSubtitle = findViewById(R.id.power_dialog_subtitle);

        findViewById(R.id.power_dialog_done).setOnClickListener(view -> dismiss());
        findViewById(R.id.power_dialog_edit).setOnClickListener(view -> {
            if (mListener != null) {
                mListener.onEditPresets();
            }
            dismiss();
        });

        rebuild();
        updateSubtitle();

        // Size only after the runtime-built preset rows exist, otherwise the
        // window is measured with an empty list and stays tiny (#98).
        final Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            Ui.fitDialog(window, findViewById(R.id.power_dialog_root),
                    findViewById(R.id.power_dialog_scroll), 440, 0.60f);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Power.setLive(true);
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, TICK_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mTick);
        Power.setLive(false);
    }

    private void rebuild() {
        if (mList == null) {
            return;
        }
        mList.removeAllViews();
        final PowerPresetStore store = PowerPresetStore.get(getContext());
        final PowerPreset active = Power.activePreset(store);
        final String activeId = active == null ? null : active.id;
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        View first = null;
        for (PowerPreset preset : store.all()) {
            final View row = inflater.inflate(R.layout.item_qs_preset, mList, false);
            final RadioButton radio = row.findViewById(R.id.qs_preset_radio);
            final TextView title = row.findViewById(R.id.qs_preset_title);
            final TextView summary = row.findViewById(R.id.qs_preset_summary);
            title.setText(preset.displayName(getContext()));
            summary.setText(preset.summary(getContext()));
            radio.setChecked(preset.id.equals(activeId));
            row.setOnClickListener(view -> {
                Power.apply(preset);
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
        final String unknown = getContext().getString(R.string.power_unknown);
        final int powerMw = Power.powerMw(getContext());
        final String watts = powerMw < 0
                ? unknown
                : getContext().getString(R.string.power_home_watts,
                        String.format(Locale.US, "%.1f", powerMw / 1000f));
        mSubtitle.setText(getContext().getString(R.string.power_tile_dialog_subtitle, watts,
                mhzText(Power.cpuMhzMax(), unknown), mhzText(Power.gpuMhz(), unknown)));
    }

    private String mhzText(int mhz, String unknown) {
        return mhz < 0 ? unknown : Integer.toString(mhz);
    }
}
