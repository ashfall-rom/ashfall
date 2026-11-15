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

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.ashfall.parts.hw.Memory;

/**
 * The Memory page (#141): the swapfile, plus the live status the daemon
 * publishes in {@code ashfall.status.mem.*}.  The page is swapfile-only
 * (#156 §7); compressed swap belongs to the platform, not to this app.
 *
 * <p>The single control is off by default and writes one property,
 * {@code persist.ashfall.mem.swap_mb}, leaving every clamp to
 * {@code ashfall-hwd} - the same contract as the fan and LED pages.  The
 * swapfile cap is deliberately independent of the installed RAM.
 *
 * <p>The swap warning is part of the feature, never decoration: it is always in
 * the row summary and repeated in a confirmation every time the swapfile is
 * switched on from off.  There is no "do not show again".
 */
public class MemoryFragment extends PreferenceFragmentCompat {

    /**
     * The daemon publishes memory status on its 2 s tick; the page reads the
     * three swap properties once a second so a slider change shows up promptly.
     */
    private static final long TICK_MS = 1000;

    private StepSlider mSwapSize;
    private Preference mSwapStatus;
    private Preference mStorageStatus;
    private AlertDialog mSwapDialog;

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
        final PreferenceScreen screen = getPreferenceManager()
                .createPreferenceScreen(requireContext());

        final PreferenceCategory statusCategory = new PreferenceCategory(requireContext());
        statusCategory.setTitle(R.string.mem_status_category);
        screen.addPreference(statusCategory);
        mSwapStatus = readOnlyRow(statusCategory, "mem_status_swap",
                R.string.mem_swap_status_title);
        mStorageStatus = readOnlyRow(statusCategory, "mem_status_storage",
                R.string.mem_storage_status_title);

        final PreferenceCategory swapCategory = new PreferenceCategory(requireContext());
        swapCategory.setTitle(R.string.mem_swap_category);
        screen.addPreference(swapCategory);

        mSwapSize = new StepSlider(requireContext());
        mSwapSize.setKey("mem_swap_size");
        mSwapSize.setTitle(R.string.mem_swap_size_title);
        mSwapSize.setRange(0, Memory.SWAP_MAX_MB);
        mSwapSize.setValue(Memory.swapMb());
        mSwapSize.setValueChangedListener((preference, value) -> onSwapSize(value));
        swapCategory.addPreference(mSwapSize);

        final Preference footer = new Preference(requireContext());
        footer.setKey("mem_footer");
        footer.setSelectable(false);
        footer.setIconSpaceReserved(false);
        footer.setSummary(R.string.mem_footer);
        screen.addPreference(footer);

        setPreferenceScreen(screen);
        refresh();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final RecyclerView list = getListView();
        if (list == null) {
            return;
        }
        // The framework's D-pad focus search only sees the rows RecyclerView
        // has attached; a focused row flush with the list edge leaves the row
        // below it detached and the D-pad stops there.  Keeping one screen of
        // rows attached on either side makes the whole page reachable, the
        // same fix the Fan page uses.
        final RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (manager instanceof LinearLayoutManager) {
            final LinearLayoutManager linear = (LinearLayoutManager) manager;
            final int orientation = linear.getOrientation();
            final boolean reversed = linear.getReverseLayout();
            list.setLayoutManager(new LinearLayoutManager(list.getContext(), orientation, reversed) {
                @Override
                protected int getExtraLayoutSpace(RecyclerView.State state) {
                    return Math.max(super.getExtraLayoutSpace(state), getHeight());
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mTick);
        if (mSwapDialog != null) {
            mSwapDialog.dismiss();
            mSwapDialog = null;
        }
        super.onDestroyView();
    }

    private Preference readOnlyRow(PreferenceCategory category, String key, int title) {
        final Preference row = new Preference(requireContext());
        row.setKey(key);
        row.setTitle(title);
        row.setSelectable(false);
        row.setIconSpaceReserved(false);
        category.addPreference(row);
        return row;
    }

    /**
     * Swapfile slider.  Moving from off to a size always goes through the
     * warning dialog first; every other change is written immediately.
     */
    private void onSwapSize(int value) {
        if (value > 0 && Memory.swapMb() == 0) {
            confirmSwap(value);
            return;
        }
        Memory.setSwapMb(value);
        refresh();
    }

    /** The warning dialog: not dismissible via "do not show again", no shortcut. */
    private void confirmSwap(int mb) {
        if (!isAdded()) {
            mSwapSize.setValue(Memory.swapMb());
            return;
        }
        mSwapDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.mem_swap_confirm_title)
                .setMessage(getString(R.string.mem_swap_confirm_body,
                        MemorySummary.formatMb(requireContext(), mb)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    // Nothing was written: put the slider back where it was.
                    mSwapSize.setValue(Memory.swapMb());
                    refresh();
                })
                .setPositiveButton(R.string.mem_swap_confirm_enable, (dialog, which) -> {
                    Memory.setSwapMb(mb);
                    refresh();
                })
                .create();
        mSwapDialog.show();
    }

    /** Pushes the property and the daemon's published values into the rows. */
    private void refresh() {
        final int swapStatus = Memory.statusSwapMb();
        final int swapRequested = Memory.swapMb();

        mSwapSize.setValue(swapRequested);
        mSwapSize.setEnabled(Memory.swapAvailable());
        mSwapSize.setSummaryText(swapSummary(swapRequested, swapStatus));

        mSwapStatus.setSummary(swapStatusSummary(swapStatus));
        mStorageStatus.setSummary(storageSummary());
    }

    private CharSequence swapSummary(int requested, int applied) {
        if (!Memory.swapAvailable()) {
            return getString(R.string.mem_swap_unavailable);
        }
        final long free = Memory.freeDataBytes();
        final StringBuilder text = new StringBuilder();
        text.append(getString(R.string.mem_swap_free, MemorySummary.formatBytes(requireContext(),
                free)));
        if (requested > 0 && free >= 0
                && requested * 1024L * 1024L > free - Memory.DATA_RESERVE_BYTES) {
            text.append('\n').append(getString(R.string.mem_swap_too_large));
        }
        if (applied >= 0 && applied != requested) {
            text.append('\n').append(getString(R.string.mem_applied_mismatch, requested, applied));
        }
        // Never dismissible: the warning is part of the row, not a one-off.
        text.append('\n').append(getString(R.string.mem_swap_warning));
        return text;
    }

    private CharSequence swapStatusSummary(int statusMb) {
        if (statusMb < 0) {
            return getString(R.string.mem_dash);
        }
        final int totalKb = Memory.statusSwapTotalKb();
        final int usedKb = Memory.statusSwapUsedKb();
        if (statusMb == 0 || totalKb <= 0 || usedKb < 0) {
            return getString(statusMb == 0 ? R.string.mem_off : R.string.mem_dash);
        }
        return getString(R.string.mem_swap_status_value,
                MemorySummary.formatKb(requireContext(), usedKb),
                MemorySummary.formatKb(requireContext(), totalKb));
    }

    private CharSequence storageSummary() {
        final Context context = requireContext();
        final long free = Memory.freeDataBytes();
        final long total = Memory.totalDataBytes();
        if (free < 0 || total <= 0) {
            return getString(R.string.mem_dash);
        }
        return getString(R.string.mem_storage_status_value,
                MemorySummary.formatBytes(context, free),
                MemorySummary.formatBytes(context, total));
    }

    /**
     * A Settings-style slider row in megabytes with a fixed step.
     *
     * <p>{@code ui/SliderPreference} steps by one unit, which is meaningless for
     * a size - the contract's step is 256 MB - so the page carries its own row.
     * Like that slider, the row (not the SeekBar) owns the D-pad, and one
     * left/right press moves the size by exactly one step.  The value comes
     * from the property on every open, so no state is saved.
     */
    private static final class StepSlider extends Preference {

        interface OnValueChangedListener {
            void onValueChanged(StepSlider preference, int value);
        }

        private int mMin;
        private int mMax = Memory.STEP_MB;
        private int mValue;
        private CharSequence mSummaryText;
        private OnValueChangedListener mListener;

        StepSlider(Context context) {
            super(context);
            setLayoutResource(R.layout.memory_slider);
            setPersistent(false);
            setIconSpaceReserved(false);
        }

        void setRange(int min, int max) {
            mMin = min;
            mMax = Math.max(min + Memory.STEP_MB, Memory.snapMb(max));
            notifyChanged();
        }

        int getValue() {
            return mValue;
        }

        void setValue(int value) {
            final int snapped = snap(value);
            if (mValue != snapped) {
                mValue = snapped;
                notifyChanged();
            }
        }

        void setValueChangedListener(OnValueChangedListener listener) {
            mListener = listener;
        }

        void setSummaryText(CharSequence summary) {
            if (TextUtils.equals(mSummaryText, summary)) {
                return;
            }
            mSummaryText = summary;
            notifyChanged();
        }

        private int snap(int value) {
            if (value <= mMin) {
                return mMin;
            }
            final int steps = Math.round((value - mMin) / (float) Memory.STEP_MB);
            return Math.min(mMax, mMin + steps * Memory.STEP_MB);
        }

        private CharSequence valueText() {
            return mValue <= mMin
                    ? getContext().getString(R.string.mem_off)
                    : getContext().getString(R.string.mem_size_mb, mValue);
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            final View item = holder.itemView;
            final TextView title = item.findViewById(R.id.memory_slider_title);
            final TextView value = item.findViewById(R.id.memory_slider_value);
            final TextView summary = item.findViewById(R.id.memory_slider_summary);
            final SeekBar seekBar = item.findViewById(R.id.memory_slider_seekbar);

            if (title != null) {
                title.setText(getTitle());
            }
            if (value != null) {
                value.setText(valueText());
                value.setEnabled(isEnabled());
            }
            if (summary != null) {
                final CharSequence text = mSummaryText != null ? mSummaryText : getSummary();
                summary.setText(text);
                summary.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
            }
            if (item != null) {
                item.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN || !isEnabled()) {
                        return false;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        changeValue(-1);
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        changeValue(1);
                        return true;
                    }
                    return false;
                });
            }
            if (seekBar != null) {
                seekBar.setEnabled(isEnabled());
                seekBar.setMax(Math.max(1, (mMax - mMin) / Memory.STEP_MB));
                seekBar.setProgress(Math.max(0, (mValue - mMin) / Memory.STEP_MB));
                seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        mValue = mMin + progress * Memory.STEP_MB;
                        if (value != null) {
                            value.setText(valueText());
                        }
                        if (mListener != null) {
                            mListener.onValueChanged(StepSlider.this, mValue);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                    }
                });
            }
        }

        /** Applies a D-pad step and updates the row without rebinding it. */
        private void changeValue(int steps) {
            final int next = Math.max(mMin, Math.min(mMax, mValue + steps * Memory.STEP_MB));
            if (next == mValue) {
                return;
            }
            mValue = next;
            if (mListener != null) {
                mListener.onValueChanged(this, mValue);
            }
            notifyChanged();
        }
    }
}
