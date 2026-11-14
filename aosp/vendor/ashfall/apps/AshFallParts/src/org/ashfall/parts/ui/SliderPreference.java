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
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.ashfall.parts.R;

/**
 * A Settings-style slider row: title and value on the first line, an optional
 * summary and a SeekBar.  The SeekBar is the focus stop, so D-pad left/right
 * changes the value and up/down leaves the row, exactly like the sliders in
 * Settings.
 */
public class SliderPreference extends Preference {

    public interface OnValueChangedListener {
        void onValueChanged(SliderPreference preference, int value);
    }

    private int mMin;
    private int mMax = 100;
    private int mValue;
    private String mValueFormat = "%d";
    private CharSequence mSummaryText;
    private OnValueChangedListener mListener;

    public SliderPreference(Context context) {
        this(context, null);
    }

    public SliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_slider);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    public void setRange(int min, int max) {
        mMin = min;
        mMax = Math.max(min + 1, max);
        notifyChanged();
    }

    public void setValue(int value) {
        final int clamped = Math.max(mMin, Math.min(mMax, value));
        if (mValue != clamped) {
            mValue = clamped;
            notifyChanged();
        }
    }

    public int getValue() {
        return mValue;
    }

    public void setValueFormat(String format) {
        mValueFormat = format;
        notifyChanged();
    }

    public void setValueChangedListener(OnValueChangedListener listener) {
        mListener = listener;
    }

    public void setSummaryText(CharSequence summary) {
        if (TextUtils.equals(mSummaryText, summary)) {
            return;
        }
        mSummaryText = summary;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View item = holder.itemView;
        final TextView title = item.findViewById(R.id.slider_title);
        final TextView value = item.findViewById(R.id.slider_value);
        final TextView summary = item.findViewById(R.id.slider_summary);
        final SeekBar seekBar = item.findViewById(R.id.slider_seekbar);

        if (title != null) {
            title.setText(getTitle());
        }
        if (value != null) {
            value.setText(String.format(java.util.Locale.US, mValueFormat, mValue));
            value.setEnabled(isEnabled());
        }
        if (summary != null) {
            final CharSequence text = mSummaryText != null ? mSummaryText : getSummary();
            summary.setText(text);
            summary.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
        if (item != null) {
            // The preference row keeps the focus (androidx makes the item view
            // focusable), so the D-pad is handled here and forwarded to the
            // value instead of requiring the SeekBar to own the focus.
            item.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN || !isEnabled()) {
                    return false;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    changeValue(-1);
                    return true;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    changeValue(1);
                    return true;
                }
                return false;
            });
        }
        if (seekBar != null) {
            seekBar.setEnabled(isEnabled());
            seekBar.setMax(mMax - mMin);
            seekBar.setProgress(Math.max(0, Math.min(mMax - mMin, mValue - mMin)));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    mValue = progress + mMin;
                    if (value != null) {
                        value.setText(String.format(java.util.Locale.US, mValueFormat, mValue));
                    }
                    if (mListener != null) {
                        mListener.onValueChanged(SliderPreference.this, mValue);
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
    private void changeValue(int delta) {
        final int next = Math.max(mMin, Math.min(mMax, mValue + delta));
        if (next == mValue) {
            return;
        }
        mValue = next;
        if (mListener != null) {
            mListener.onValueChanged(this, mValue);
        }
        // The SeekBar/text are updated through a normal rebind so the caller's
        // listener can also change other rows.
        notifyChanged();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        final SavedState state = new SavedState(super.onSaveInstanceState());
        state.value = mValue;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(@Nullable Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        final SavedState saved = (SavedState) state;
        super.onRestoreInstanceState(saved.getSuperState());
        mValue = saved.value;
        notifyChanged();
    }

    private static final class SavedState extends BaseSavedState {
        int value;

        SavedState(Parcelable superState) {
            super(superState);
        }

        SavedState(Parcel in) {
            super(in);
            value = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(value);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }
}
