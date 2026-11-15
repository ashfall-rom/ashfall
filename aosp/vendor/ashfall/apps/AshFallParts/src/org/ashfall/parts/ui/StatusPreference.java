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

package org.ashfall.parts.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.ashfall.parts.R;

import java.lang.ref.WeakReference;

/**
 * The live fan status row (docs/phase5.md §7.2): a legend with the exact
 * current values above a scrolling 60 s {@link FanGraphView}, and the
 * last-resort warning below it.
 *
 * <p>The page ticks every 500 ms and calls {@link #update}: the row appends the
 * sample to its own ring buffer and invalidates the bound graph directly.  It
 * never rebinds itself for a value change (no {@code notifyChanged()} per
 * tick); the bound views are reached through weak references, so a recycled or
 * absent row is simply skipped.  The status layout has a single view type, so a
 * recycled holder can only ever be re-bound to this row and a stale write is
 * harmless (the rebind republishes the current values).
 *
 * <p>Not selectable and not focusable: it is a read-out, so the D-pad skips it.
 */
public class StatusPreference extends Preference {

    private final FanGraphView.History mHistory = new FanGraphView.History();

    private int mTemperatureC = -1;
    private int mOutputPct = -1;
    private int mRpm = -1;
    private boolean mSafety;
    private boolean mAvailable;

    private WeakReference<FanGraphView> mGraph = new WeakReference<>(null);
    private WeakReference<View> mBody = new WeakReference<>(null);
    private WeakReference<View> mUnavailable = new WeakReference<>(null);
    private WeakReference<View> mSafetyView = new WeakReference<>(null);
    private WeakReference<TextView> mTemperatureText = new WeakReference<>(null);
    private WeakReference<TextView> mOutputText = new WeakReference<>(null);
    private WeakReference<TextView> mRpmText = new WeakReference<>(null);

    public StatusPreference(Context context) {
        this(context, null);
    }

    public StatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_status);
        setSelectable(false);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    /**
     * Drops the 60 s window: the page calls this from
     * {@code onCreatePreferences}, so a fresh page starts a fresh history.
     */
    public void resetHistory() {
        mHistory.clear();
        final FanGraphView graph = mGraph.get();
        if (graph != null) {
            graph.invalidate();
        }
    }

    /**
     * Appends one 500 ms tick.  Negative values are the daemon's "unknown"
     * ({@code -1}) and become gaps in the plot, never zeros.
     */
    public void update(int temperatureC, int outputPct, int rpm, boolean safety,
            boolean available) {
        mTemperatureC = temperatureC;
        mOutputPct = outputPct;
        mRpm = rpm;
        mSafety = safety;
        mAvailable = available;
        mHistory.append(celsiusOrGap(temperatureC), gapOr(outputPct), gapOr(rpm));
        apply();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View item = holder.itemView;
        // A read-out row: never a focus stop, and its children never steal the
        // D-pad either, so focus moves from the row above to the row below.
        item.setFocusable(false);
        item.setFocusableInTouchMode(false);
        item.setClickable(false);
        item.setLongClickable(false);
        if (item instanceof ViewGroup) {
            ((ViewGroup) item).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }

        final FanGraphView graph = item.findViewById(R.id.status_graph);
        mGraph = new WeakReference<>(graph);
        mBody = new WeakReference<>(item.findViewById(R.id.status_body));
        mUnavailable = new WeakReference<>(item.findViewById(R.id.status_unavailable));
        mSafetyView = new WeakReference<>(item.findViewById(R.id.status_safety));
        mTemperatureText = new WeakReference<>(item.findViewById(R.id.status_legend_temp));
        mOutputText = new WeakReference<>(item.findViewById(R.id.status_legend_pct));
        mRpmText = new WeakReference<>(item.findViewById(R.id.status_legend_rpm));
        if (graph != null) {
            graph.setHistory(mHistory);
        }
        apply();
    }

    /** Pushes the current values into the bound views; safe when unbound. */
    private void apply() {
        final View body = mBody.get();
        if (body != null) {
            body.setVisibility(mAvailable ? View.VISIBLE : View.GONE);
        }
        final View unavailable = mUnavailable.get();
        if (unavailable != null) {
            unavailable.setVisibility(mAvailable ? View.GONE : View.VISIBLE);
        }
        final View safety = mSafetyView.get();
        if (safety != null) {
            safety.setVisibility(mSafety ? View.VISIBLE : View.GONE);
        }
        setText(mTemperatureText.get(), temperatureText());
        setText(mOutputText.get(), outputText());
        setText(mRpmText.get(), rpmText());

        final FanGraphView graph = mGraph.get();
        if (graph != null) {
            graph.invalidate();
        }
    }

    private static void setText(TextView view, CharSequence text) {
        // Only touch the view when the text really changed: the page ticks
        // twice a second and an unchanged setText would re-measure the row.
        if (view != null && !TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private CharSequence temperatureText() {
        return mTemperatureC < 0
                ? getContext().getString(R.string.fan_legend_unknown)
                : getContext().getString(R.string.temperature_value, mTemperatureC);
    }

    private CharSequence outputText() {
        return mOutputPct < 0
                ? getContext().getString(R.string.fan_legend_unknown)
                : getContext().getString(R.string.fan_legend_percent, mOutputPct);
    }

    private CharSequence rpmText() {
        return mRpm < 0
                ? getContext().getString(R.string.fan_legend_unknown)
                : getContext().getString(R.string.rpm_value_grouped, groupDigits(mRpm));
    }

    private static float celsiusOrGap(int temperatureC) {
        return temperatureC < 0 ? Float.NaN : temperatureC;
    }

    private static float gapOr(int value) {
        return value < 0 ? Float.NaN : value;
    }

    /**
     * Groups a non-negative count in threes with a space, as in the contract's
     * {@code 9 800 rpm}: locale-independent, so the legend looks the same in
     * every language.
     */
    private static String groupDigits(int value) {
        final String digits = Integer.toString(Math.abs(value));
        final StringBuilder grouped = new StringBuilder(digits.length() + 2);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(digits.charAt(i));
        }
        return grouped.toString();
    }
}
